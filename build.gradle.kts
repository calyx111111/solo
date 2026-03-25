import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.gradle.api.GradleException
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.24"
    id("org.jetbrains.intellij") version "1.17.3"
}

group = "com.example"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.java-websocket:Java-WebSocket:1.5.7")
}

// 前端构建 & 复制产物到 plugin resources
val frontendDir = File(project.projectDir.parentFile, "smart_frontend")
val frontendDistDir = File(frontendDir, "dist")
val backendDir = File(project.projectDir.parentFile, "spec_vcoder")
val backendDistDir = File(backendDir, "dist")
val backendNodeModulesDir = File(backendDir, "node_modules")
val backendPackageJson = File(backendDir, "package.json")

tasks.register<Exec>("buildFrontend") {
    group = "build"
    description = "Build the frontend (npm run build in smart_frontend)"
    workingDir = frontendDir

    if (System.getProperty("os.name").lowercase().contains("windows")) {
        commandLine("C:/Windows/System32/cmd.exe", "/c", "npm install && npm run build")
    } else {
        commandLine("sh", "-c", "npm install && npm run build")
    }

    onlyIf {
        frontendDir.resolve("package.json").exists()
    }
}

tasks.register<Exec>("buildBackend") {
    group = "build"
    description = "Build the backend (npm run build in spec_vcoder)"
    workingDir = backendDir

    if (System.getProperty("os.name").lowercase().contains("windows")) {
        commandLine("C:/Windows/System32/cmd.exe", "/c", "npm install && npm run build")
    } else {
        commandLine("sh", "-c", "npm install && npm run build")
    }

    onlyIf {
        backendDir.resolve("package.json").exists()
    }
}

tasks.register<Copy>("copyVcoderResources") {
    group = "build"
    description = "Copy frontend build output to resources"
    dependsOn("buildFrontend")

    doFirst {
        delete(file("src/main/resources/webview"))
    }

    from(frontendDistDir)
    into("src/main/resources/webview")

    onlyIf {
        frontendDistDir.exists()
    }
}

tasks.register<Copy>("copyBackendResources") {
    group = "build"
    description = "Copy backend build output to resources"
    dependsOn("buildBackend")

    doFirst {
        delete(file("src/main/resources/ts-backend"))
    }

    from(backendDistDir) {
        into("dist")
    }
    from(backendNodeModulesDir) {
        into("node_modules")
    }
    from(backendPackageJson)
    into("src/main/resources/ts-backend")

    onlyIf {
        backendDistDir.exists()
    }
}

/**
 * 生成 ts-backend/.backend.hash
 *
 * 默认假设你的 backend 资源在:
 *   src/main/resources/ts-backend
 *
 * 如果你的 ts-backend 实际不在这里，把下面 tsBackendSourceDir 改成真实目录即可。
 */
val tsBackendSourceDir = layout.projectDirectory.dir("src/main/resources/ts-backend")
val generatedBackendHashResources = layout.buildDirectory.dir("generated/backendHashResources")

abstract class GenerateBackendHashTask : DefaultTask() {

    @get:org.gradle.api.tasks.Internal
    abstract val backendDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val backendRoot = backendDir.get().asFile.toPath()
        val outRoot = outputDir.get().asFile.toPath()
        val outTsBackendDir = outRoot.resolve("ts-backend")
        val hashFile = outTsBackendDir.resolve(".backend.hash")

        if (!Files.isDirectory(backendRoot)) {
            logger.lifecycle("ts-backend directory not found, skipping hash generation: $backendRoot")
            return
        }

        Files.createDirectories(outTsBackendDir)

        val digest = MessageDigest.getInstance("SHA-256")

        Files.walk(backendRoot).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString() != ".backend.hash" } // 避免把自己算进去
                .sorted { a, b ->
                    backendRoot.relativize(a).toString().replace('\\', '/')
                        .compareTo(backendRoot.relativize(b).toString().replace('\\', '/'))
                }
                .forEach { file ->
                    val relative = backendRoot.relativize(file).toString().replace('\\', '/')
                    digest.update(relative.toByteArray(Charsets.UTF_8))
                    digest.update(0)
                    digest.update(Files.readAllBytes(file))
                    digest.update(0)
                }
        }

        val hash = digest.digest().joinToString("") { "%02x".format(it) }

        Files.writeString(
            hashFile,
            hash + System.lineSeparator(),
            Charsets.UTF_8
        )

        logger.lifecycle("Generated ts-backend hash: $hash -> $hashFile")
    }
}

val generateBackendHash = tasks.register<GenerateBackendHashTask>("generateBackendHash") {
    group = "build"
    description = "Generate ts-backend/.backend.hash for plugin resources"
    dependsOn("copyBackendResources")
    backendDir.set(tsBackendSourceDir)
    outputDir.set(generatedBackendHashResources)
}

// 把 build/generated/backendHashResources 当成额外资源目录
sourceSets {
    named("main") {
        resources.srcDir(generatedBackendHashResources)
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn("copyVcoderResources")
    dependsOn("copyBackendResources")
    dependsOn(generateBackendHash)
}

tasks.named("patchPluginXml") {
    dependsOn("copyVcoderResources")
    dependsOn("copyBackendResources")

}

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
intellij {
    version.set("2024.1.7")
    type.set("IC") // Target IDE Platform

    plugins.set(listOf(/* Plugin Dependencies */))

    // 禁用字节码插桩，避免在 Windows 上因 JDK 路径解析错误导致 instrumentCode 失败
    // 错误示例: C:\Users\xxx\.jdks\ms-21.0.10\Packages does not exist
    instrumentCode.set(false)
}

tasks {
    // 禁用 buildSearchableOptions，避免 GradleJvmSupportMatrix 在解析 Java 25 时抛出 IllegalArgumentException
    // 原因：IntelliJ 2024.1.7 的 JavaVersion.parse() 尚不支持 Java 25，且本插件无自定义 Settings 页
    named("buildSearchableOptions") {
        enabled = false
    }

    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }

    patchPluginXml {
        sinceBuild.set("241")
        untilBuild.set("243.*")
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
}

// 备用：直接打包 JAR 为可安装的 ZIP（当 buildPlugin 因 prepareSandbox 文件锁定失败时使用）
tasks.register<Zip>("packagePlugin") {
    group = "build"
    description = "Package plugin JAR into installable ZIP (use when buildPlugin fails due to file lock)"
    dependsOn("jar")
    archiveBaseName.set("solo")
    archiveVersion.set(version.toString())
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(tasks.jar.get().outputs.files) {
        into("solo/lib")
    }
    doLast {
        println("Plugin ZIP: ${archiveFile.get().asFile.absolutePath}")
    }
}
