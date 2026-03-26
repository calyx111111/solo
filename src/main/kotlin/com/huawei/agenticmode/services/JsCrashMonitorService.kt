package com.huawei.agenticmode.services

import com.huawei.agenticmode.SoloModeManager
import com.huawei.agenticmode.vcoder.agent.AgentProcessManager
import com.huawei.agenticmode.vcoder.agent.GlobalBackendService
import com.huawei.agenticmode.vcoder.agent.WebSocketClient
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.BufferedReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@Service(Service.Level.PROJECT)
class JsCrashMonitorService(
    private val project: Project
) : Disposable {

    companion object {
        private val LOG = Logger.getInstance(JsCrashMonitorService::class.java)
        private const val POLL_INTERVAL_SECONDS = 5L
        private const val COMMAND_TIMEOUT_MS = 15_000L
        private const val CONFIG_TIMEOUT_MS = 5_000L
        private const val REMOTE_FAULTLOG_DIR = "/data/log/faultlog/faultlogger"
        private const val EVENT_NAME = "jscrash-detected"
        private const val CONFIG_PATH = "hmos.jscrash_monitor_enabled"
        private const val HDC_COMMAND = "hdc"
        private const val MONITOR_DIR_NAME = "codegenie-jscrash"
        private const val DEFAULT_ENABLED = true
        private const val TOOL_EVENT_NAME = "agentic://tool-event"
        private const val TURN_COMPLETED_EVENT_NAME = "agentic://dialog-turn-completed"
        private const val TURN_FAILED_EVENT_NAME = "agentic://dialog-turn-failed"
        private const val TURN_CANCELLED_EVENT_NAME = "agentic://dialog-turn-cancelled"
    }

    private val gson = Gson()
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "jscrash-monitor-${project.name}").apply { isDaemon = true }
    }
    private val started = AtomicBoolean(false)
    private val suppressionCounter = AtomicInteger(0)
    private val baselineByTarget = ConcurrentHashMap<String, String>()
    private val lastDeliveredByTarget = ConcurrentHashMap<String, String>()
    private val ignoredKeysByTarget = ConcurrentHashMap<String, MutableSet<String>>()
    private val activeSuppressedToolIds = ConcurrentHashMap.newKeySet<String>()
    private val deviceLabelByTarget = ConcurrentHashMap<String, String>()
    private var pollFuture: ScheduledFuture<*>? = null
    private var agentManager: com.huawei.agenticmode.vcoder.agent.AgentProcessManager? = null
    private val toolEventListener = com.huawei.agenticmode.vcoder.agent.WebSocketClient.EventListener { _, payload ->
        handleToolEvent(payload)
    }
    private val turnFinishedEventListener = com.huawei.agenticmode.vcoder.agent.WebSocketClient.EventListener { _, _ ->
        clearSuppressionTools()
    }

    fun start() {
        if (!started.compareAndSet(false, true)) {
            return
        }
        LOG.info("JsCrash monitor started for project: ${project.name}")
        registerBackendListeners()
        pollFuture = executor.scheduleWithFixedDelay(
            ::pollSafely,
            POLL_INTERVAL_SECONDS,
            POLL_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        )
    }

    fun markIgnored(target: String, summaryKey: String) {
        LOG.info("JsCrash marked ignored | target=$target | summaryKey=$summaryKey")
        ignoredKeysByTarget.computeIfAbsent(target) { ConcurrentHashMap.newKeySet() }.add(summaryKey)
        lastDeliveredByTarget[target] = summaryKey
        baselineByTarget[target] = summaryKey
    }

    fun enterSuppression() {
        val current = suppressionCounter.incrementAndGet()
        LOG.info("JsCrash suppression entered | count=$current")
    }

    fun exitSuppression() {
        val current = suppressionCounter.updateAndGet { value -> if (value <= 0) 0 else value - 1 }
        LOG.info("JsCrash suppression exited | count=$current")
    }

    override fun dispose() {
        LOG.info("JsCrash monitor disposed for project: ${project.name}")
        unregisterBackendListeners()
        pollFuture?.cancel(true)
        executor.shutdownNow()
    }

    private fun pollSafely() {
        if (project.isDisposed) {
            return
        }
        try {
            if (!isMonitorEnabled()) {
                LOG.debug("JsCrash monitor disabled by config")
                return
            }
            val targets = resolveTargets()
            if (targets.isEmpty()) {
                LOG.debug("JsCrash poll skipped: no online targets")
                return
            }
            LOG.debug("JsCrash polling targets: ${targets.joinToString(",")}")
            targets.forEach(::pollTarget)
        } catch (t: Throwable) {
            LOG.warn("JsCrash monitor poll failed", t)
        }
    }

    private fun pollTarget(target: String) {
        val summaryKey = resolveLatestSummaryKey(target) ?: return
        val baseline = baselineByTarget[target]

        if (baseline == null) {
            baselineByTarget[target] = summaryKey
            LOG.info("JsCrash baseline initialized | target=$target | summaryKey=$summaryKey")
            return
        }

        if (summaryKey == baseline || summaryKey == lastDeliveredByTarget[target]) {
            return
        }

        if (ignoredKeysByTarget[target]?.contains(summaryKey) == true) {
            baselineByTarget[target] = summaryKey
            lastDeliveredByTarget[target] = summaryKey
            LOG.info("JsCrash skipped ignored key | target=$target | summaryKey=$summaryKey")
            return
        }

        if (suppressionCounter.get() > 0) {
            baselineByTarget[target] = summaryKey
            lastDeliveredByTarget[target] = summaryKey
            LOG.info("JsCrash suppressed | target=$target | summaryKey=$summaryKey | count=${suppressionCounter.get()}")
            return
        }

        LOG.info("JsCrash detected | target=$target | summaryKey=$summaryKey")
        val remoteLogPath = resolveRemoteLogPath(target, summaryKey) ?: return
        val localLogPath = downloadFaultLog(target, remoteLogPath) ?: return
        val deviceLabel = resolveDeviceLabel(target, localLogPath)
        val crashSummary = extractCrashSummary(localLogPath, summaryKey)
        val payload = JsonObject().apply {
            addProperty("target", target)
            addProperty("deviceLabel", deviceLabel)
            addProperty("summaryKey", summaryKey)
            addProperty("crashSummary", crashSummary)
            addProperty("remoteLogPath", remoteLogPath)
            addProperty("localLogPath", localLogPath.toString())
            addProperty("detectedAt", System.currentTimeMillis())
        }

        val delivered = SoloModeManager.getInstance(project).emitEventToWebView(EVENT_NAME, gson.toJson(payload))
        if (!delivered) {
            LOG.warn("JsCrash event dispatch failed | target=$target | summaryKey=$summaryKey")
            return
        }

        LOG.info("JsCrash event dispatched | target=$target | summaryKey=$summaryKey | localLogPath=$localLogPath")
        baselineByTarget[target] = summaryKey
        lastDeliveredByTarget[target] = summaryKey
    }

    private fun resolveTargets(): List<String> {
        val output = runCommand(listOf(HDC_COMMAND, "list", "targets")) ?: return emptyList()
        val targets = output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.startsWith("[") || it.equals("empty", ignoreCase = true) }
            .toList()
        if (targets.isNotEmpty()) {
            LOG.debug("JsCrash online targets resolved: ${targets.joinToString(",")}")
        }
        return targets
    }

    private fun resolveLatestSummaryKey(target: String): String? {
        val output = runCommand(
            listOf(
                HDC_COMMAND,
                "-t",
                target,
                "shell",
                "hidumper",
                "-s",
                "1201",
                "-a",
                "-p Faultlogger LogSuffixWithMs"
            )
        ) ?: return null

        val summaryKey = output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("jscrash-") }
            .maxOrNull()
        if (summaryKey != null) {
            LOG.debug("JsCrash latest summary resolved | target=$target | summaryKey=$summaryKey")
        }
        return summaryKey
    }

    private fun resolveRemoteLogPath(target: String, summaryKey: String): String? {
        val output = runCommand(listOf(HDC_COMMAND, "-t", target, "shell", "ls", REMOTE_FAULTLOG_DIR)) ?: return null
        val summaryPrefix = summaryKey.substringBeforeLast('-', missingDelimiterValue = summaryKey)

        val candidates = output.lineSequence()
            .map { it.trim() }
            .filter { it.endsWith(".log") }
            .filter { it.startsWith(summaryKey) || it.startsWith("$summaryPrefix-") }
            .sortedDescending()
            .toList()

        val fileName = candidates.firstOrNull() ?: return null
        LOG.debug("JsCrash remote log matched | target=$target | summaryKey=$summaryKey | remote=$REMOTE_FAULTLOG_DIR/$fileName")
        return "$REMOTE_FAULTLOG_DIR/$fileName"
    }

    private fun downloadFaultLog(target: String, remoteLogPath: String): Path? {
        return try {
            val tempDir = Paths.get(System.getProperty("java.io.tmpdir"), MONITOR_DIR_NAME)
            Files.createDirectories(tempDir)
            val localDir = tempDir.toAbsolutePath().toString()
            val output = runCommand(listOf(HDC_COMMAND, "-t", target, "file", "recv", remoteLogPath, localDir))
            if (output == null) {
                LOG.warn("JsCrash log download command returned null | target=$target | remote=$remoteLogPath")
                return null
            }
            val fileName = remoteLogPath.substringAfterLast('/')
            val localPath = tempDir.resolve(fileName)
            return if (Files.exists(localPath)) {
                LOG.info("JsCrash log downloaded | target=$target | local=$localPath")
                localPath
            } else {
                LOG.warn("JsCrash local log missing after download | target=$target | local=$localPath")
                null
            }
        } catch (t: Throwable) {
            LOG.warn("Failed to download jscrash log: $remoteLogPath", t)
            null
        }
    }

    private fun resolveDeviceLabel(target: String, localLogPath: Path): String {
        deviceLabelByTarget[target]?.let { cached ->
            return cached
        }

        val previewLines = readLogPreview(localLogPath)
        val modelName = resolveDeviceModel(target)
        val fallbackName = findLogValue(previewLines, "Device info")
            ?: findLogValue(previewLines, "Build info")?.substringBefore('(')?.trim()

        val baseName = when {
            !modelName.isNullOrBlank() -> modelName
            !fallbackName.isNullOrBlank() -> fallbackName
            else -> target
        }

        val label = if (baseName.equals(target, ignoreCase = true)) {
            target
        } else {
            "$baseName ($target)"
        }
        deviceLabelByTarget[target] = label
        return label
    }

    private fun resolveDeviceModel(target: String): String? {
        val commands = listOf(
            listOf(HDC_COMMAND, "-t", target, "shell", "param", "get", "const.product.model"),
            listOf(HDC_COMMAND, "-t", target, "shell", "param", "get", "const.product.name")
        )

        return commands.asSequence()
            .mapNotNull { command -> runCommand(command)?.trim() }
            .map { value -> value.removeSurrounding("\"").trim() }
            .firstOrNull { value ->
                value.isNotBlank() &&
                    !value.equals("null", ignoreCase = true) &&
                    !value.equals("unknown", ignoreCase = true)
            }
    }

    private fun extractCrashSummary(localLogPath: Path, summaryKey: String): String {
        val previewLines = readLogPreview(localLogPath)
        val errorName = findLogValue(previewLines, "Error name")
        val reason = findLogValue(previewLines, "Reason")
        val errorMessage = findLogValue(previewLines, "Error message")
        val stackFrame = extractFirstStackFrame(previewLines)

        val summary = when {
            !errorName.isNullOrBlank() && !errorMessage.isNullOrBlank() -> "$errorName: $errorMessage"
            !reason.isNullOrBlank() && !errorMessage.isNullOrBlank() -> "$reason: $errorMessage"
            !errorName.isNullOrBlank() -> errorName
            !reason.isNullOrBlank() -> reason
            !errorMessage.isNullOrBlank() -> errorMessage
            else -> summaryKey
        }

        return buildString {
            append(truncate(summary, 96))
            if (!stackFrame.isNullOrBlank()) {
                append(" @ ")
                append(stackFrame)
            }
        }
    }

    private fun extractFirstStackFrame(lines: List<String>): String? {
        val stackIndex = lines.indexOfFirst { it.trim() == "Stacktrace:" }
        if (stackIndex < 0) {
            return null
        }

        val frameLine = lines.drop(stackIndex + 1)
            .map { it.trim() }
            .firstOrNull { it.startsWith("at ") }
            ?: return null

        val location = frameLine.substringAfter('(', "")
            .substringBefore(')', "")
            .trim()
        if (location.isBlank()) {
            return frameLine.removePrefix("at ").trim()
        }

        val fileName = location.substringAfterLast('/').substringAfterLast('\\')
        return truncate(fileName, 36)
    }

    private fun findLogValue(lines: List<String>, key: String): String? {
        val prefix = "$key:"
        return lines.asSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith(prefix) }
            ?.substringAfter(prefix)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun readLogPreview(localLogPath: Path, maxLines: Int = 80): List<String> {
        return try {
            Files.newBufferedReader(localLogPath).use { reader: BufferedReader ->
                generateSequence { reader.readLine() }
                    .take(maxLines)
                    .toList()
            }
        } catch (t: Throwable) {
            LOG.debug("Failed to read jscrash log preview: $localLogPath", t)
            emptyList()
        }
    }

    private fun truncate(value: String, maxLength: Int): String {
        if (value.length <= maxLength) {
            return value
        }
        return value.take(maxLength - 3).trimEnd() + "..."
    }

    private fun isMonitorEnabled(): Boolean {
        return try {
            val request = JsonObject().apply {
                addProperty("id", "jscrash-config-${UUID.randomUUID()}")
                addProperty("action", "get_config")
                add("params", JsonObject().apply {
                    addProperty("path", CONFIG_PATH)
                })
            }
            val responseText = com.huawei.agenticmode.vcoder.agent.GlobalBackendService.getInstance()
                .getAgent(project)
                .sendRequest(gson.toJson(request))
                .get(CONFIG_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val response = JsonParser.parseString(responseText).asJsonObject
            val result = response.get("result")
            when {
                result == null || result.isJsonNull -> DEFAULT_ENABLED
                result.isJsonPrimitive && result.asJsonPrimitive.isBoolean -> result.asBoolean
                else -> DEFAULT_ENABLED
            }
        } catch (t: Throwable) {
            LOG.debug("Failed to read jscrash monitor config, fallback to default", t)
            DEFAULT_ENABLED
        }
    }

    private fun registerBackendListeners() {
        try {
            val manager = com.huawei.agenticmode.vcoder.agent.GlobalBackendService.getInstance().getAgent(project)
            agentManager = manager
            manager.addEventListener(TOOL_EVENT_NAME, toolEventListener)
            manager.addEventListener(TURN_COMPLETED_EVENT_NAME, turnFinishedEventListener)
            manager.addEventListener(TURN_FAILED_EVENT_NAME, turnFinishedEventListener)
            manager.addEventListener(TURN_CANCELLED_EVENT_NAME, turnFinishedEventListener)
            LOG.info("JsCrash suppression listeners registered")
        } catch (t: Throwable) {
            LOG.warn("Failed to register jscrash suppression listeners", t)
        }
    }

    private fun unregisterBackendListeners() {
        val manager = agentManager ?: return
        manager.removeEventListener(TOOL_EVENT_NAME, toolEventListener)
        manager.removeEventListener(TURN_COMPLETED_EVENT_NAME, turnFinishedEventListener)
        manager.removeEventListener(TURN_FAILED_EVENT_NAME, turnFinishedEventListener)
        manager.removeEventListener(TURN_CANCELLED_EVENT_NAME, turnFinishedEventListener)
        clearSuppressionTools()
        LOG.info("JsCrash suppression listeners unregistered")
    }

    private fun handleToolEvent(payload: JsonObject) {
        try {
            val toolEvent = payload.getAsJsonObject("toolEvent") ?: return
            val eventType = toolEvent.get("event_type")?.asString ?: return
            val toolName = toolEvent.get("tool_name")?.asString ?: return
            val toolId = toolEvent.get("tool_id")?.asString ?: return

            if (!shouldSuppressForTool(toolName)) {
                return
            }

            when (eventType) {
                "Started" -> {
                    if (activeSuppressedToolIds.add(toolId)) {
                        LOG.info("JsCrash suppression tool started | toolName=$toolName | toolId=$toolId")
                        enterSuppression()
                    }
                }
                "Completed", "Failed", "Cancelled" -> {
                    if (activeSuppressedToolIds.remove(toolId)) {
                        LOG.info("JsCrash suppression tool finished | toolName=$toolName | toolId=$toolId | eventType=$eventType")
                        exitSuppression()
                    }
                }
            }
        } catch (t: Throwable) {
            LOG.debug("Failed to process jscrash suppression tool event", t)
        }
    }

    private fun clearSuppressionTools() {
        if (activeSuppressedToolIds.isNotEmpty() || suppressionCounter.get() > 0) {
            LOG.info("JsCrash suppression cleared | activeTools=${activeSuppressedToolIds.size} | count=${suppressionCounter.get()}")
        }
        activeSuppressedToolIds.clear()
        while (suppressionCounter.get() > 0) {
            exitSuppression()
        }
    }

    private fun shouldSuppressForTool(toolName: String): Boolean {
        if (toolName == "HmosRun" || toolName == "PhoneAgent") {
            return true
        }
        if (!toolName.startsWith("mcp_")) {
            return false
        }
        val normalized = toolName.lowercase()
        return normalized.contains("phone") ||
            normalized.contains("device") ||
            normalized.contains("calibrate") ||
            normalized.contains("hmosrun")
    }

    private fun runCommand(command: List<String>): String? {
        return try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                return null
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            if (process.exitValue() == 0) output else null
        } catch (t: Throwable) {
            LOG.debug("Command failed: ${command.joinToString(" ")}", t)
            null
        }
    }
}
