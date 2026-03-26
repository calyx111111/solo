package com.huawei.agenticmode.vcoder.webview;

import com.huawei.agenticmode.services.JsCrashMonitorService;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.DynamicBundle;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.huawei.agenticmode.vcoder.agent.AgentProcessManager;
import com.huawei.agenticmode.vcoder.integration.ProjectContextProvider;
import org.jetbrains.annotations.NotNull;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class JsBridgeHandler {
    private static final Logger LOG = Logger.getInstance(JsBridgeHandler.class);
    private final Project project;
    private final AgentProcessManager agentManager;
    private final ProjectContextProvider contextProvider;
    private final Consumer<String> focusRequester;
    private final Gson gson = new Gson();

    public JsBridgeHandler(@NotNull Project project, @NotNull AgentProcessManager agentManager) {
        this(project, agentManager, null);
    }

    public JsBridgeHandler(@NotNull Project project, @NotNull AgentProcessManager agentManager, Consumer<String> focusRequester) {
        this.project = project;
        this.agentManager = agentManager;
        this.contextProvider = new ProjectContextProvider(project);
        this.focusRequester = focusRequester;
    }

    public String handleRequest(String request) {
        try {
            JsonObject json = JsonParser.parseString(request).getAsJsonObject();
            String id = json.has("id") ? json.get("id").getAsString() : "";
            String action = json.has("action") ? json.get("action").getAsString() : "";
            JsonObject params = json.has("params") ? json.getAsJsonObject("params") : new JsonObject();

            if (isRemovedAction(action)) {
                return createErrorResponse(id, -32601, "Command not supported in plugin environment");
            }

            return switch (action) {
                case "initialize_global_state" -> handleInitializeGlobalState(id);
                case "get_current_workspace" -> handleGetCurrentWorkspace(id);
                case "get_recent_workspaces" -> handleGetRecentWorkspaces(id);
                case "open_workspace" -> handleOpenWorkspace(id, params);
                case "close_workspace" -> handleCloseWorkspace(id);
                case "scan_workspace_info" -> handleScanWorkspaceInfo(id, params);
                case "get_project_info" -> handleGetProjectInfo(id);
                case "get_workspace_path" -> handleGetWorkspacePath(id);
                case "get_open_files" -> handleGetOpenFiles(id);
                case "open_file" -> handleOpenFile(id, params);
                case "get_selection" -> handleGetSelection(id);
                case "focus_webview" -> handleFocusWebview(id, params);
                case "switch_backend" -> handleSwitchBackend(id, params);
                case "jscrash_ignore" -> handleJsCrashIgnore(id, params);
                case "i18n_get_current_language" -> getCurrentLanguage(id);
                case "open_link" -> handleOpenLink(id, params);
                default -> forwardToAgent(request);
            };
        } catch (Exception e) {
            return createErrorResponse("", -1, "Request processing failed: " + e.getMessage());
        }
    }

    private String handleInitializeGlobalState(String id) {
        JsonObject result = new JsonObject();
        result.addProperty("status", "initialized");
        result.add("workspace", WorkspaceResponseBuilder.buildWorkspace(project));
        return createSuccessResponse(id, result);
    }

    private String handleGetCurrentWorkspace(String id) {
        JsonElement workspace = WorkspaceResponseBuilder.buildWorkspace(project);
        return createSuccessResponse(id, workspace);
    }

    private String handleGetRecentWorkspaces(String id) {
        JsonElement recent = WorkspaceResponseBuilder.buildRecentWorkspaces(project);
        return createSuccessResponse(id, recent);
    }

    private String handleOpenWorkspace(String id, JsonObject params) {
        JsonElement workspace = WorkspaceResponseBuilder.buildWorkspace(project);
        return createSuccessResponse(id, workspace);
    }

    private String handleCloseWorkspace(String id) {
        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        return createSuccessResponse(id, result);
    }

    private String handleScanWorkspaceInfo(String id, JsonObject params) {
        JsonElement workspace = WorkspaceResponseBuilder.buildWorkspace(project);
        return createSuccessResponse(id, workspace);
    }

    private String handleGetProjectInfo(String id) {
        JsonObject result = new JsonObject();
        result.addProperty("name", project.getName());
        result.addProperty("basePath", project.getBasePath());
        result.addProperty("isHarmonyOS", contextProvider.isHarmonyOSProject());
        return createSuccessResponse(id, result);
    }

    private String handleGetWorkspacePath(String id) {
        JsonObject result = new JsonObject();
        result.addProperty("path", project.getBasePath());
        return createSuccessResponse(id, result);
    }

    private String handleGetOpenFiles(String id) {
        return ReadAction.compute(() -> {
            JsonObject result = new JsonObject();
            result.add("files", gson.toJsonTree(contextProvider.getOpenFiles()));
            return createSuccessResponse(id, result);
        });
    }

    private String handleOpenFile(String id, JsonObject params) {
        String filePath = params.has("path") ? params.get("path").getAsString() : "";
        int line = params.has("line") ? params.get("line").getAsInt() : 0;
        ApplicationManager.getApplication().invokeLater(() -> contextProvider.openFileInEditor(filePath, line));
        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        return createSuccessResponse(id, result);
    }

    private String handleGetSelection(String id) {
        return ReadAction.compute(() -> {
            JsonObject result = new JsonObject();
            var selection = contextProvider.getCurrentSelection();
            result.addProperty("text", selection.text());
            result.addProperty("filePath", selection.filePath());
            result.addProperty("startLine", selection.startLine());
            result.addProperty("endLine", selection.endLine());
            return createSuccessResponse(id, result);
        });
    }

    private String handleFocusWebview(String id, JsonObject params) {
        String reason = params.has("reason") ? params.get("reason").getAsString() : "unknown";
        if (focusRequester != null) {
            ApplicationManager.getApplication().invokeLater(() -> {
                try {
                    focusRequester.accept(reason);
                } catch (Exception e) {
                    LOG.error("Failed to focus webview: " + e.getMessage(), e);
                }
            });
        }
        JsonObject result = new JsonObject();
        result.addProperty("success", focusRequester != null);
        result.addProperty("reason", reason);
        return createSuccessResponse(id, result);
    }

    private String handleSwitchBackend(String id, JsonObject params) {
        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        result.addProperty("backendType", "typescript");
        result.addProperty("message", "Only TypeScript backend is available");
        result.addProperty("currentBackend", "typescript");
        return createSuccessResponse(id, result);
    }

    private String handleJsCrashIgnore(String id, JsonObject params) {
        String target = params.has("target") ? params.get("target").getAsString() : "";
        String summaryKey = params.has("summaryKey") ? params.get("summaryKey").getAsString() : "";
        JsonObject result = new JsonObject();
        if (target.isEmpty() || summaryKey.isEmpty()) {
            result.addProperty("success", false);
            return createSuccessResponse(id, result);
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                JsCrashMonitorService service = project.getService(JsCrashMonitorService.class);
                if (service != null) {
                    service.markIgnored(target, summaryKey);
                }
            } catch (Exception e) {
                LOG.error("Failed to mark JS crash as ignored for target=" + target + ", summaryKey=" + summaryKey + ": " + e.getMessage(), e);
            }
        });
        result.addProperty("success", true);
        return createSuccessResponse(id, result);
    }

    private String handleOpenLink(String id, JsonObject params) {
        JsonObject result = new JsonObject();
        String url = params.has("url") ? params.get("url").getAsString() : "";
        
        LOG.info("Received open_link request with URL: " + (url.isEmpty() ? "[empty]" : url));
        
        if (url.isEmpty()) {
            LOG.warn("open_link request failed: URL is empty");
            result.addProperty("success", false);
            result.addProperty("error", "URL parameter is required");
            return createErrorResponse(id, -1, "URL parameter is required");
        }
        
        if (!isValidUrl(url)) {
            LOG.warn("open_link request failed: Invalid URL format - " + url);
            result.addProperty("success", false);
            result.addProperty("error", "Invalid URL format");
            return createErrorResponse(id, -1, "Invalid URL format");
        }
        
        if (!isUrlSafe(url)) {
            LOG.warn("open_link request failed: Unsafe URL detected - " + url);
            result.addProperty("success", false);
            result.addProperty("error", "Unsafe URL detected");
            return createErrorResponse(id, -1, "Unsafe URL detected");
        }
        
        // 修复：在异步操作之前设置成功状态，避免竞态条件
        result.addProperty("success", true);
        result.addProperty("url", url);
        
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(new URI(url));
                    LOG.info("Successfully opened URL in default browser: " + url);
                } else {
                    LOG.error("Desktop browsing is not supported on this system");
                }
            } catch (URISyntaxException e) {
                LOG.error("open_link failed: Invalid URI syntax for URL - " + url, e);
            } catch (IOException e) {
                LOG.error("open_link failed: IO error while opening URL - " + url, e);
            } catch (Exception e) {
                LOG.error("open_link failed: Unexpected error while opening URL - " + url, e);
            }
        });
        
        return createSuccessResponse(id, result);
    }
    
    private boolean isValidUrl(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            
            if (scheme == null) {
                return false;
            }
            
            return true;
        } catch (URISyntaxException e) {
            return false;
        }
    }
    
    private boolean isUrlSafe(String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            
            if (scheme == null) {
                return false;
            }
            
            String lowerScheme = scheme.toLowerCase(Locale.ROOT);
            return "http".equals(lowerScheme) || "https".equals(lowerScheme);
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private boolean isRemovedAction(String action) {
        return switch (action) {
            case "get_prompt_template_config", "save_prompt_template_config", "export_prompt_templates",
                 "import_prompt_templates", "reset_prompt_templates" -> true;
            case "transcribe_audio_stream", "test_speech_recognition_config", "start_audio_session",
                 "send_audio_chunk", "stop_audio_session" -> true;
            case "get_document_statuses", "toggle_document_enabled", "create_context_document",
                 "generate_context_document", "cancel_context_document_generation", "get_project_context_config",
                 "save_project_context_config", "create_project_category", "delete_project_category",
                 "get_all_categories", "import_project_document", "delete_imported_document",
                 "toggle_imported_document_enabled", "delete_context_document" -> true;
            case "get_file_tree", "get_directory_children", "get_directory_children_paginated" -> true;
            default -> false;
        };
    }

    private static final int MAX_RETRIES_FOR_INIT = 3;
    private static final long RETRY_DELAY_MS = 2000;

    private String forwardToAgent(String request) {
        String lastResponse = null;
        Exception lastException = null;
        for (int attempt = 0; attempt <= MAX_RETRIES_FOR_INIT; attempt++) {
            try {
                CompletableFuture<String> future = agentManager.sendRequest(request);
                lastResponse = future.get();
                if (lastResponse == null) {
                    continue;
                }
                if (!isRetryableError(lastResponse)) {
                    return lastResponse;
                }
                if (attempt < MAX_RETRIES_FOR_INIT) {
                    try { Thread.sleep(RETRY_DELAY_MS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            } catch (Exception e) {
                lastException = e;
                if (attempt < MAX_RETRIES_FOR_INIT) {
                    try { Thread.sleep(RETRY_DELAY_MS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            }
        }
        if (lastResponse != null) {
            return lastResponse;
        }
        JsonObject json = JsonParser.parseString(request).getAsJsonObject();
        String id = json.has("id") ? json.get("id").getAsString() : "";
        return createErrorResponse(id, -1, "Agent request failed: " + (lastException != null ? lastException.getMessage() : "unknown"));
    }

    private boolean isRetryableError(String response) {
        if (response == null) {
            return false;
        }
        return response.contains("未初始化") || response.contains("not initialized") || response.contains("AI未初始化");
    }

    private String createSuccessResponse(String id, JsonElement result) {
        JsonObject response = new JsonObject();
        response.addProperty("id", id);
        response.add("result", result);
        return gson.toJson(response);
    }

    private String createErrorResponse(String id, int code, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("id", id);
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        response.add("error", error);
        return gson.toJson(response);
    }

    private String getCurrentLanguage(String id) {

        JsonObject response = new JsonObject();
        response.addProperty("id", id);

        Locale locale = DynamicBundle.getLocale();
        String language = locale.getLanguage();
        if (language != null && !language.isEmpty()) {
            response.addProperty("result", language);
        } else {
            response.addProperty("result", Locale.getDefault().getLanguage());
        }
        return gson.toJson(response);
    }
}
