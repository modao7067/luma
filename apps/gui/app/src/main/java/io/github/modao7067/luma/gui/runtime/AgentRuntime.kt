package io.github.modao7067.luma.gui.runtime

import io.github.modao7067.luma.gui.i18n.tr

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import io.github.modao7067.luma.gui.i18n.LumaI18n
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit

data class RuntimeEndpoint(val url: String, val authToken: String?, val environment: String, val externalSocket: String? = null) {
    override fun toString() = "RuntimeEndpoint(environment=$environment, url=$url)"
}
data class RuntimeFile(val name: String, val path: String, val directory: Boolean, val size: Long? = null, val modified: Long? = null)

class AgentRuntime(context: Context) {
    private val app = context.applicationContext
    private val safeNativeId = Regex("[A-Za-z0-9_-]{1,128}")
    suspend fun connect(environment: String): RuntimeEndpoint = withContext(Dispatchers.IO) {
        validateEnvironment(environment)
        locks.getValue(environment).withLock {
            val directory = privateDirectory(environment)
            val tokenFile = File(directory, "capability-token")
            if (!tokenFile.exists()) writePrivate(tokenFile, randomToken())
            val token = tokenFile.readText().trim()
            val url = if (environment == "root" && io.github.modao7067.luma.gui.GuiProduct.isRoot) {
                rootService(environment, "codex", listOf("codex", "app-server", "--listen", "{url}", "--ws-auth", "capability-token", "--ws-token-file", hostPath(tokenFile)), "/readyz")
            } else if (environment == "root") {
                val script = File(directory, "app-server.py")
                writePrivate(script, RuntimeScripts.rootServer)
                for (name in listOf("server.lock", "server.log")) {
                    val file = File(directory, name)
                    if (!file.exists()) writePrivate(file, "")
                }
                JSONObject(rootExecute(listOf("/usr/bin/python3", "-I", hostPath(script), hostPath(directory)))).getString("url")
            } else if (usesRootExecution(environment)) {
                rootService(environment, "codex", listOf("codex", "app-server", "--listen", "{url}", "--ws-auth", "capability-token", "--ws-token-file", runtimePath(environment, "capability-token")), "/readyz")
            } else {
                NoRootRuntime.connect(app, guestPath(environment, "capability-token"))
            }
            verifyAuthenticatedUpgrade(url, token)
            writePrivate(File(directory, "endpoint.json"), JSONObject().put("url", url).toString())
            RuntimeEndpoint(url, token, environment)
        }
    }

    fun externalCodex(environment: String, socket: String): RuntimeEndpoint {
        check(io.github.modao7067.luma.gui.GuiProduct.isRoot && environment == "root") { tr("外部 Codex 接入已不再支持", "External Codex attachment is no longer supported") }
        ExternalCodexPolicy.validateSocket(socket)
        return RuntimeEndpoint("unix://$socket", null, environment, externalSocket = socket)
    }

    suspend fun recoverExternalCodex(environment: String, socket: String) = withContext(Dispatchers.IO) {
        externalCodex(environment, socket)
        locks.getValue(environment).withLock {
            rootExecute(listOf("/usr/bin/python3", "-I", "-c", RuntimeScripts.externalCodexRecovery, socket))
        }
    }

    fun codexTransport(endpoint: RuntimeEndpoint): io.github.modao7067.luma.gui.agent.CodexTransport? = endpoint.externalSocket?.let { socket ->
        check(io.github.modao7067.luma.gui.GuiProduct.isRoot && endpoint.environment == "root") { tr("外部 Codex 接入已不再支持", "External Codex attachment is no longer supported") }
        ExternalCodexPolicy.validateSocket(socket)
        val logFile = File(privateDirectory("root"), "external-codex.log")
        val log = io.github.modao7067.luma.gui.agent.CodexProxyLog(logFile)
        io.github.modao7067.luma.gui.agent.CodexProcessTransport(
            start = { ProcessBuilder(ExternalCodexPolicy.proxyCommand(socket)).start() },
            diagnostic = { event ->
                try { log.append(event); Os.chmod(logFile.absolutePath, 0x180) }
                catch (error: Exception) { android.util.Log.e("LumaExternalCodex", "无法写入外部 Codex 私有诊断", error) }
            })
    }

    fun recordOpenCodeDelivery(environment: String, event: String) {
        val file = File(privateDirectory(environment), "opencode-delivery.log")
        try { io.github.modao7067.luma.gui.agent.CodexProxyLog(file).append(event); Os.chmod(file.absolutePath, 0x180) }
        catch (_: Exception) { android.util.Log.e("LumaOpenCode", "无法写入发送诊断") }
    }

    suspend fun connectOpenCode(environment: String = io.github.modao7067.luma.gui.GuiProduct.environment): RuntimeEndpoint = withContext(Dispatchers.IO) {
        locks.getValue(environment).withLock {
            val password = File(privateDirectory(environment), "opencode-password")
            if (!password.exists()) writePrivate(password, randomToken())
            writePrivate(File(privateDirectory(environment), "opencode-launch.py"), RuntimeScripts.openCodeServer.replace("/opt/luma/agent/skills", skillsPath(environment)))
            val url = if (usesRootExecution(environment)) rootService(environment, "opencode", listOf("/usr/bin/python3", runtimePath(environment, "opencode-launch.py"), "{port}", runtimePath(environment, "opencode-password")), "/api/info")
                else NoRootRuntime.connectOpenCode(app, runtimePath(environment, "opencode-password"), runtimePath(environment, "opencode-launch.py"))
            val endpoint = RuntimeEndpoint(url, password.readText().trim(), environment)
            val client = io.github.modao7067.luma.gui.agent.OpenCodeClient(endpoint.url, endpoint.authToken!!)
            val info = try { client.probe("/api/info", 5_000L) } finally { client.close() }
            check(info.getString("version").removePrefix("v").startsWith("2.")) { tr("当前适配需要 OpenCode 2.x 原生 API", "OpenCode 2.x native API is required") }
            endpoint
        }
    }

    suspend fun stopIdleOpenCode(environment: String = io.github.modao7067.luma.gui.GuiProduct.environment, lastEndpoint: RuntimeEndpoint? = null) = withContext(Dispatchers.IO) {
        validateEnvironment(environment)
        locks.getValue(environment).withLock {
            if (usesRootExecution(environment)) {
                val directory = privateDirectory(environment)
                if (!File(directory, "opencode.json").isFile) return@withLock
                val script = File(directory, "opencode-idle-stop.py")
                writePrivate(script, RuntimeScripts.openCodeIdleStop)
                runCatching { rootExecute(listOf("/usr/bin/python3", "-I", runtimePath(environment, "opencode-idle-stop.py"), runtimeBasePath(environment))) }
            } else {
                val passwordFile = File(privateDirectory(environment), "opencode-password")
                val token = lastEndpoint?.authToken ?: runCatching { passwordFile.readText().trim() }.getOrNull()
                if (lastEndpoint != null && !token.isNullOrEmpty()) {
                    val active = runCatching {
                        val client = io.github.modao7067.luma.gui.agent.OpenCodeClient(lastEndpoint.url, token)
                        try { client.probe("/api/session/active", 2_500L).optJSONObject("data")?.length() ?: -1 }
                        finally { client.close() }
                    }.getOrDefault(-1)
                    if (active != 0) return@withLock
                }
                runCatching { NoRootRuntime.stopOpenCodeIfIdle(app) }
            }
        }
    }

    suspend fun stopIdleCodex(environment: String = io.github.modao7067.luma.gui.GuiProduct.environment) = withContext(Dispatchers.IO) {
        validateEnvironment(environment)
        locks.getValue(environment).withLock {
            if (usesRootExecution(environment)) {
                val directory = privateDirectory(environment)
                if (!File(directory, "codex.json").isFile && !File(directory, "server.json").isFile) return@withLock
                val script = File(directory, "codex-idle-stop.py")
                writePrivate(script, RuntimeScripts.codexIdleStop)
                runCatching { rootExecute(listOf("/usr/bin/python3", "-I", runtimePath(environment, "codex-idle-stop.py"), runtimeBasePath(environment))) }
            } else {
                runCatching { NoRootRuntime.stopCodexIfIdle(app) }
            }
        }
    }

    private fun resolveExistingBridgeEndpoint(cli: String, environment: String, known: RuntimeEndpoint?): RuntimeEndpoint? {
        if (known?.authToken != null) return known
        if (!usesRootExecution(environment)) return null
        val directory = privateDirectory(environment)
        val token = runCatching { File(directory, "cli-bridge-token").readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        val stateFile = File(directory, "${NativeCliRuntimePolicy.task(cli)}.json")
        val url = runCatching { JSONObject(stateFile.readText()).optString("url") }.getOrNull()?.takeIf { it.startsWith("http://127.0.0.1:") } ?: return null
        return RuntimeEndpoint(url, token, environment)
    }

    suspend fun releaseBridgedSessionIfRunning(
        cli: String,
        environment: String,
        sessionId: String,
        nativeId: String?,
        nativeProtocol: String?,
        known: RuntimeEndpoint?,
    ) = withContext(Dispatchers.IO) {
        if (nativeId.isNullOrBlank()) return@withContext
        val target = resolveExistingBridgeEndpoint(cli, environment, known) ?: return@withContext
        val client = io.github.modao7067.luma.gui.agent.NativeCliClient(target.url, target.authToken!!)
        try {
            val payload = JSONObject().put("nativeId", nativeId)
            if (nativeProtocol != null) payload.put("nativeProtocol", nativeProtocol)
            runCatching { client.call("/sessions/$sessionId/release", payload) }
        } finally {
            client.close()
        }
    }

    suspend fun destroyBridgedSessionIfRunning(
        cli: String,
        environment: String,
        sessionId: String,
        known: RuntimeEndpoint?,
        deleteState: Boolean = true,
    ) = withContext(Dispatchers.IO) {
        val target = resolveExistingBridgeEndpoint(cli, environment, known) ?: return@withContext
        val client = io.github.modao7067.luma.gui.agent.NativeCliClient(target.url, target.authToken!!)
        try {
            runCatching { client.call("/sessions/$sessionId/destroy", JSONObject().put("deleteState", deleteState)) }
        } finally {
            client.close()
        }
    }

    suspend fun connectNativeCli(cli: String = "pi", environment: String = io.github.modao7067.luma.gui.GuiProduct.environment): RuntimeEndpoint = withContext(Dispatchers.IO) {
        locks.getValue(environment).withLock { connectNativeCliLocked(cli, environment) }
    }

    suspend fun restartNativeCliBridge(cli: String, environment: String, confirmed: Boolean): RuntimeEndpoint {
        NativeCliRuntimePolicy.requireRestartConsent(cli, confirmed)
        validateEnvironment(environment)
        return withContext(Dispatchers.IO) {
            locks.getValue(environment).withLock {
                prepareNativeCliAssets(environment)
                val task = NativeCliRuntimePolicy.task(cli)
                if (usesRootExecution(environment)) {
                    val directory = privateDirectory(environment)
                    val script = File(directory, "cli-bridge-stop.py")
                    writePrivate(script, RuntimeScripts.cliBridgeStop)
                    rootExecute(listOf("/usr/bin/python3", "-I", runtimePath(environment, "cli-bridge-stop.py"), runtimeBasePath(environment), task, "confirmed"))
                } else NoRootRuntime.stopNativeCli(app, task)
                connectNativeCliLocked(cli, environment)
            }
        }
    }

    private fun prepareNativeCliAssets(environment: String) {
        val directory = privateDirectory(environment)
        val token = File(directory, "cli-bridge-token")
        if (!token.exists()) writePrivate(token, randomToken())
        val revision = NativeCliRuntimePolicy.revision
        val code = File(directory, revision)
        if (!code.isDirectory) {
            val staging = File(directory, "$revision-${UUID.randomUUID()}").apply { mkdirs(); Os.chmod(absolutePath, 0x1C0) }
            fun copyAssets(asset: String, target: File) {
                val children = app.assets.list(asset).orEmpty()
                if (children.isEmpty()) {
                    app.assets.open(asset).use { input -> target.outputStream().use { output -> input.copyTo(output) } }
                    Os.chmod(target.absolutePath, 0x180)
                } else {
                    target.mkdirs(); Os.chmod(target.absolutePath, 0x1C0)
                    children.filterNot { it == "__pycache__" }.forEach { copyAssets("$asset/$it", File(target, it)) }
                }
            }
            copyAssets("cli_bridge", staging)
            check(staging.renameTo(code)) { tr("无法发布 CLI 运行文件", "Failed to publish CLI runtime files") }
        }
        pruneOldBridgeRevisions(directory, revision)
    }

    private fun pruneOldBridgeRevisions(directory: File, currentRevision: String) {
        val activeRevisions = mutableSetOf(currentRevision)
        directory.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }?.forEach { file ->
            runCatching {
                val text = file.readText()
                Regex("cli-bridge-v[0-9]+").findAll(text).forEach { activeRevisions.add(it.value) }
            }
        }
        File(app.cacheDir, "services/tasks").listFiles()?.filter { it.isFile && it.name.endsWith(".json") }?.forEach { file ->
            runCatching {
                val json = JSONObject(file.readText())
                if (json.optString("phase") != "exited") {
                    Regex("cli-bridge-v[0-9]+").findAll(json.toString()).forEach { activeRevisions.add(it.value) }
                }
            }
        }
        directory.listFiles()?.filter {
            it.isDirectory && it.name.startsWith("cli-bridge-v") && it.name !in activeRevisions
        }?.forEach { it.deleteRecursively() }
    }

    private suspend fun connectNativeCliLocked(cli: String, environment: String): RuntimeEndpoint {
        prepareNativeCliAssets(environment)
        val directory = privateDirectory(environment)
        val token = File(directory, "cli-bridge-token")
        val revision = NativeCliRuntimePolicy.revision
        val task = NativeCliRuntimePolicy.task(cli)
        val launcher = runtimePath(environment, "$revision/main.py")
        val secret = runtimePath(environment, "cli-bridge-token")
        val sessions = runtimePath(environment, "cli-sessions")
        val url = if (usesRootExecution(environment)) rootService(environment, task, listOf("/usr/bin/python3", "-I", "-B", launcher, "{port}", secret, sessions), "/health")
            else NoRootRuntime.connectNativeCli(app, launcher, secret, sessions, task)
        val endpoint = RuntimeEndpoint(url, token.readText().trim(), environment)
        val client = io.github.modao7067.luma.gui.agent.NativeCliClient(endpoint.url, endpoint.authToken!!)
        try {
            NativeCliRuntimePolicy.acceptHealth(cli, client.call("/health"))
        } finally { client.close() }
        return endpoint
    }

    suspend fun importImage(environment: String, source: Uri): String = withContext(Dispatchers.IO) {
        try {
            validateEnvironment(environment)
            val mime = app.contentResolver.getType(source) ?: throw IOException(tr("无法识别图片类型", "Unrecognized image type"))
            if (!mime.startsWith("image/")) throw IOException(tr("附件不是图片", "Attachment is not an image"))
            val extension = when (mime) {
                "image/png" -> "png"; "image/jpeg" -> "jpg"; "image/webp" -> "webp"
                "image/gif" -> "gif"; else -> throw IOException(tr("请选择 PNG、JPEG、WebP 或 GIF 图片", "Please choose a PNG, JPEG, WebP, or GIF image"))
            }
            val folder = File(privateDirectory(environment), "attachments").apply { mkdirs(); Os.chmod(absolutePath, 0x1C0) }
            val file = File(folder, "${UUID.randomUUID()}.$extension")
            try {
                app.contentResolver.openInputStream(source)?.use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(8192); var bytes = 0L
                        while (true) {
                            val n = input.read(buffer); if (n < 0) break
                            bytes += n
                            if (bytes > 20L * 1024 * 1024) throw IOException(tr("图片超过 20 MiB，请缩小后再发送", "Image exceeds 20 MiB; resize it before sending"))
                            output.write(buffer, 0, n)
                        }
                        if (bytes == 0L) throw IOException(tr("图片为空", "Image is empty"))
                    }
                } ?: throw IOException(tr("无法读取所选图片，请重新选择", "Unable to read selected image; please select it again"))
                Os.chmod(file.absolutePath, 0x180)
                if (environment == "root") hostPath(file) else guestPath(environment, "attachments/${file.name}")
            } catch (error: Exception) { file.delete(); throw error }
        } finally {
            io.github.modao7067.luma.gui.camera.PhotoCaptureStore(app).importFinished(source)
        }
    }

    fun imagePreviewUri(environment: String, cliPath: String): String? {
        if (environment != "root" && environment != "noroot") return null
        val directory = File(app.filesDir, "${io.github.modao7067.luma.gui.GuiProduct.agentRuntimePath(environment)}/attachments")
        val prefix = if (environment == "root") hostPath(directory) + "/" else guestPath(environment, "attachments/")
        if (!cliPath.startsWith(prefix)) return null
        val name = cliPath.removePrefix(prefix)
        if (!name.matches(Regex("[a-fA-F0-9-]{36}\\.(png|jpg|webp|gif)"))) return null
        val file = File(directory, name)
        return if (file.isFile && file.canonicalFile.parentFile == directory.canonicalFile) Uri.fromFile(file).toString() else null
    }

    internal fun importedImageFile(environment: String, source: String): File? =
        resolveManagedRuntimeFile(app.filesDir, environment, source)

    internal fun managedSkillEntry(environment: String, path: String): Pair<File, String>? {
        if (environment != "root" && environment != "noroot") return null
        val skillsDir = File(app.filesDir, "${io.github.modao7067.luma.gui.GuiProduct.agentRuntimePath(environment)}/skills")
        val prefix = if (environment == "root") hostPath(skillsDir) else guestPath(environment, "skills")
        if (path != prefix && !path.startsWith("$prefix/")) return null
        val rel = path.removePrefix(prefix).trimStart('/')
        val target = if (rel.isEmpty()) skillsDir else File(skillsDir, rel)
        val canonicalBase = skillsDir.canonicalFile
        val canonicalTarget = target.canonicalFile
        val basePath = canonicalBase.path
        val targetPath = canonicalTarget.path
        if (targetPath != basePath && !targetPath.startsWith("$basePath/")) return null
        val logicalPath = if (rel.isEmpty()) prefix else "$prefix/" + targetPath.removePrefix(basePath).trimStart('/')
        return canonicalTarget to logicalPath
    }

    internal fun managedRootProtectedPath(environment: String, source: String): String? =
        resolveManagedRootProtectedPath(app.filesDir, environment, source)

    internal fun copyMediaFile(environment: String, path: String, destination: File, limit: Long) {
        validateEnvironment(environment); validatePath(path)
        importedImageFile(environment, path)?.let { local ->
            local.inputStream().use { MediaByteReader.copy(it, destination, limit) }
            return
        }
        resolveManagedRootProtectedPath(app.filesDir, environment, path)?.let { protectedHostPath ->
            io.github.modao7067.luma.root.GuiRuntimeBridge.copyFile(app, protectedHostPath, destination, limit)
            return
        }
        val normalized = File(path).toPath().normalize().toString()
        if (MediaSourcePolicy.isForbiddenGuestPath(normalized)) throw IOException(tr("不允许读取系统伪文件系统路径", "Reading system pseudo-filesystem paths is not allowed"))
        if (usesRootExecution(environment)) io.github.modao7067.luma.root.GuiRuntimeBridge.copyFile(app, normalized, destination, limit)
        else NoRootRuntime.copyFile(app, normalized, destination, limit)
    }
    suspend fun mediaMetadata(environment: String, source: String, cwd: String): Pair<Long, String> = withContext(Dispatchers.IO) {
        val imported = importedImageFile(environment, source)
        if (imported != null) return@withContext imported.length() to MediaSourcePolicy.mime(imported.name)
        val protectedPath = resolveManagedRootProtectedPath(app.filesDir, environment, source)
        val path = protectedPath ?: MediaSourcePolicy.guestPath(source, cwd)
        val result = JSONObject(execute(environment, listOf("/usr/bin/python3", "-I", "-c", RuntimeScripts.mediaMetadata, path)))
        result.getLong("size") to result.optString("mime")
    }
    suspend fun inspectMedia(environment: String, resource: io.github.modao7067.luma.gui.session.MediaResource): io.github.modao7067.luma.gui.session.MediaResource = withContext(Dispatchers.IO) {
        if (resource.sizeBytes != null && resource.mimeType.isNotBlank() && resource.mimeType != "application/octet-stream") return@withContext resource
        if (resource.source.startsWith("content:")) {
            val uri = Uri.parse(resource.source)
            val authority = uri.authority?.trim().orEmpty()
            if (authority.isEmpty() || authority == app.packageName || authority.startsWith("${app.packageName}.") ||
                app.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED
            ) {
                return@withContext resource
            }
            var result = resource.copy(mimeType = app.contentResolver.getType(uri) ?: resource.mimeType)
            app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) result = result.copy(name = resource.name.ifBlank { if (cursor.isNull(0)) "" else cursor.getString(0) },
                    sizeBytes = if (cursor.isNull(1)) null else cursor.getLong(1).takeIf { it >= 0 })
            }
            return@withContext result
        }
        if (MediaSourcePolicy.relativeHttp(resource.source, resource.cwd) != resource.source) return@withContext resource
        if (resource.source.startsWith("http:") || resource.source.startsWith("https:") || resource.source.startsWith("data:") || resource.source.startsWith("codex-file:") || resource.source.startsWith("codex-mcp:") || resource.source.startsWith("luma-error:")) return@withContext resource
        val (size, mime) = mediaMetadata(environment, resource.source, resource.cwd ?: throw IOException(tr("资源缺少所属工作目录", "Resource is missing working directory")))
        resource.copy(sizeBytes = size, mimeType = resource.mimeType.takeUnless { it.isBlank() || it.endsWith("/*") } ?: mime)
    }
    private val mediaResolver by lazy { SessionMediaResolver(app, this) }
    suspend fun resolveMedia(environment: String, resource: io.github.modao7067.luma.gui.session.MediaResource, forceRefresh: Boolean = false) = mediaResolver.resolve(environment, resource, forceRefresh)
    suspend fun openMediaExternally(environment: String, resource: io.github.modao7067.luma.gui.session.MediaResource, chooseApp: Boolean = false) = mediaResolver.open(environment, resource, chooseApp)
    suspend fun saveMedia(environment: String, resource: io.github.modao7067.luma.gui.session.MediaResource): String = mediaResolver.save(environment, resource)
    fun onAppForegrounded() {
        mediaResolver.cleanOpenCache(maxAgeMs = SessionMediaResolver.FOREGROUND_CLEAN_GRACE_MS)
        mediaResolver.scheduleOpenCacheCleanup(SessionMediaResolver.FOREGROUND_CLEAN_GRACE_MS)
    }

    suspend fun listFiles(environment: String, path: String): List<RuntimeFile> = withContext(Dispatchers.IO) {
        validateEnvironment(environment)
        validatePath(path)
        managedSkillEntry(environment, path)?.let { (dir, logicalBase) ->
            if (!dir.isDirectory) throw IOException(tr("目录不存在：$path", "Directory does not exist: $path"))
            val children = dir.listFiles().orEmpty()
            if (children.size > 5000) throw IOException(tr("目录超过 5000 项，请输入更具体的路径", "Directory has over 5,000 entries; enter a more specific path"))
            return@withContext children
                .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
                .map { child ->
                    RuntimeFile(
                        name = child.name,
                        path = "${logicalBase.trimEnd('/')}/${child.name}",
                        directory = child.isDirectory,
                        size = if (child.isFile) child.length() else null,
                        modified = child.lastModified() / 1000L,
                    )
                }
        }
        val normalized = File(path).toPath().normalize().toString()
        if (MediaSourcePolicy.isForbiddenGuestPath(normalized)) throw IOException(tr("不允许访问系统伪文件系统路径", "Accessing system pseudo-filesystem paths is not allowed"))
        val entries = JSONArray(execute(environment, listOf("/usr/bin/python3", "-I", "-c", RuntimeScripts.listFiles, normalized)))
        List(entries.length()) { index -> entries.getJSONObject(index).let {
            RuntimeFile(it.getString("name"), it.getString("path"), it.getBoolean("directory"),
                if (it.isNull("size")) null else it.optLong("size"), if (it.isNull("mtime")) null else it.optLong("mtime"))
        } }
    }
    suspend fun readFile(environment: String, path: String): String = withContext(Dispatchers.IO) {
        validateEnvironment(environment)
        validatePath(path)
        val localManaged = managedSkillEntry(environment, path)?.first ?: importedImageFile(environment, path)
        if (localManaged != null) {
            if (!localManaged.isFile) throw IOException(tr("找不到文件：$path", "File not found: $path"))
            val data = localManaged.inputStream().use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > 524288) throw IOException(tr("文件超过 512 KiB，请在终端查看", "File exceeds 512 KiB; view it in the terminal"))
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            return@withContext try {
                decoder.decode(java.nio.ByteBuffer.wrap(data)).toString()
            } catch (_: java.nio.charset.CharacterCodingException) {
                throw IOException(tr("该文件不是 UTF-8 文本，请在终端使用原生文件预览", "Not a UTF-8 text file; preview it in the terminal"))
            }
        }
        val normalized = File(path).toPath().normalize().toString()
        if (MediaSourcePolicy.isForbiddenGuestPath(normalized)) throw IOException(tr("不允许读取系统伪文件系统路径", "Reading system pseudo-filesystem paths is not allowed"))
        execute(environment, listOf("/usr/bin/python3", "-I", "-c", RuntimeScripts.readFile, normalized))
    }

    suspend fun readCodexRolloutTokenUsage(environment: String, path: String): JSONObject? = withContext(Dispatchers.IO) {
        validateEnvironment(environment)
        validatePath(path)
        runCatching {
            val raw = execute(environment, listOf("/usr/bin/python3", "-I", "-c", RuntimeScripts.codexRolloutTokenUsage, path)).trim()
            if (raw.isEmpty()) null else JSONObject(raw).takeIf { it.length() > 0 }
        }.getOrNull()
    }

    suspend fun readCodexSessionPricing(environment: String, rolloutPath: String? = null): JSONObject? = withContext(Dispatchers.IO) {
        validateEnvironment(environment)
        val cleanPath = rolloutPath?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanPath != null) validatePath(cleanPath)
        runCatching {
            val script = stageGlobalUsageScript(environment)
            val cmd = buildList {
                add("/usr/bin/python3")
                add("-I")
                add(script)
                add("codex-session")
                if (cleanPath != null) add(cleanPath)
            }
            val raw = execute(environment, cmd).trim()
            if (raw.isEmpty()) null else JSONObject(raw).takeIf { it.length() > 0 }
        }.getOrNull()
    }

    suspend fun readAntigravitySessionMetrics(
        environment: String,
        nativeId: String,
        model: String,
    ): List<io.github.modao7067.luma.gui.session.Metric> = withContext(Dispatchers.IO) {
        validateEnvironment(environment)
        if (!safeNativeId.matches(nativeId)) return@withContext emptyList()
        runCatching {
            locks.getValue(environment).withLock { prepareNativeCliAssets(environment) }
            val bridgeDir = runtimePath(environment, NativeCliRuntimePolicy.revision)
            val raw = execute(
                environment,
                listOf("/usr/bin/python3", "-I", "-B", "-c", RuntimeScripts.antigravitySessionMetrics, bridgeDir, nativeId, model),
            ).trim()
            val arr = JSONArray(raw)
            List(arr.length()) { index ->
                val obj = arr.getJSONObject(index)
                io.github.modao7067.luma.gui.session.Metric(obj.getString("label"), obj.getString("value"))
            }
        }.getOrDefault(emptyList())
    }

    private fun stageGlobalUsageScript(environment: String): String = synchronized(globalUsageStageLock) {
        val dir = privateDirectory(environment)
        val target = File(dir, "global-usage.py")
        val content = app.assets.open("cli_bridge/luma_cli/global_usage.py").bufferedReader().use { it.readText() }
        if (!target.isFile || runCatching { target.readText() }.getOrNull() != content) {
            val tmp = File(dir, "global-usage.py.${UUID.randomUUID()}.tmp")
            try {
                writePrivate(tmp, content)
                if (!tmp.renameTo(target)) writePrivate(target, content)
            } finally {
                if (tmp.exists()) tmp.delete()
            }
        }
        return runtimePath(environment, "global-usage.py")
    }

    suspend fun readGlobalUsageSummary(
        environment: String,
        days: Int = 30,
        timeZone: String,
        refresh: Boolean = false,
        period: String? = null,
    ): JSONObject = withContext(Dispatchers.IO) {
        validateEnvironment(environment)
        val script = stageGlobalUsageScript(environment)
        val windowArg = period?.trim()?.lowercase()?.takeIf {
            it == "today" || it == "week" || it == "month" || it == "all"
        } ?: days.coerceIn(1, 365).toString()
        val cmd = buildList {
            add("/usr/bin/python3")
            add("-I")
            add(script)
            add("summary")
            add(windowArg)
            add(timeZone.ifBlank { "UTC" })
            if (refresh) add("--refresh")
        }
        JSONObject(execute(environment, cmd))
    }

    suspend fun readGlobalUsageLimits(
        environment: String,
        fresh: Boolean = false,
    ): JSONObject = withContext(Dispatchers.IO) {
        validateEnvironment(environment)
        val script = stageGlobalUsageScript(environment)
        val cmd = buildList {
            add("/usr/bin/python3")
            add("-I")
            add(script)
            add("limits")
            if (fresh) add("--fresh")
        }
        JSONObject(execute(environment, cmd))
    }

    fun openTerminal(context: Context, environment: String, endpoint: RuntimeEndpoint?, threadId: String?, cwd: String, shell: Boolean, cli: String = "Codex", nativeTerminal: JSONObject? = null, sessionId: String? = null) {
        validateEnvironment(environment); validatePath(cwd)
        val rootTerminal = usesRootExecution(environment)
        val needsSetup = !rootTerminal && environment == "noroot" && !NoRootRuntime.isReady(app)
        if (needsSetup && !shell) throw IOException(tr("Debian 尚未准备完成，请先打开 NoRoot 终端", "Debian is not ready yet; open the NoRoot terminal first"))
        val key: String
        val command: String
        if (shell) {
            key = UUID.randomUUID().toString().replace("-", "")
            command = "cd ${quote(cwd)} && exec /bin/bash --login"
        } else if (nativeTerminal != null) {
            require(cli in setOf("pi", "Grok Build")) { tr("未知原生终端协议", "Unknown native terminal protocol") }
            require(nativeTerminal.getString("nativeId") == threadId.orEmpty()) { tr("原生会话身份不符", "Native session identity mismatch") }
            val instance = nativeTerminal.getString("instanceId")
            require(instance.matches(Regex("[a-f0-9]{32}"))) { tr("原生宿主身份无效", "Invalid native host identity") }
            val argv = nativeTerminal.getJSONArray("argv")
            require(argv.length() in 1..32) { tr("原生终端命令无效", "Invalid native terminal command") }
            val arguments = (0 until argv.length()).map { argv.getString(it).also { arg -> require('\u0000' !in arg) } }
            key = TerminalLaunchPolicy.sessionWindowId(environment, cli, instance)
            command = "cd ${quote(cwd)} && exec " + arguments.joinToString(" ", transform = ::quote)
        } else if (cli == "OpenCode") {
            val server = endpoint ?: throw IOException(tr("请先连接 OpenCode 会话", "Connect the OpenCode session first"))
            require(server.environment == environment)
            require(threadId != null && threadId.matches(Regex("ses[a-zA-Z0-9_-]{1,128}")))
            require(server.url.matches(Regex("http://127\\.0\\.0\\.1:[0-9]{1,5}")))
            val password = runtimePath(environment, "opencode-password")
            key = TerminalLaunchPolicy.sessionWindowId(environment, cli, threadId)
            command = "cd ${quote(cwd)} && if ! command -v opencode >/dev/null 2>&1; then export PATH=\"\$PATH:\$HOME/.opencode/bin\"; fi && export OPENCODE_PASSWORD=\"\$(cat ${quote(password)})\" && exec opencode --server ${quote(server.url)} --session ${quote(threadId)}"
        } else {
            require(cli == "Codex") { tr("此 CLI 不支持同一执行进程的实时 TUI 接管，请使用普通终端", "Live TUI takeover is not supported for this CLI; use a regular terminal") }
            val server = endpoint ?: throw IOException(tr("请先连接会话，再打开官方 TUI", "Connect the session before opening the official TUI"))
            require(server.environment == environment) { tr("会话运行环境不匹配", "Session environment mismatch") }
            require(threadId != null && threadId.matches(Regex("[a-zA-Z0-9_-]{1,128}"))) { tr("原生会话标识无效", "Invalid native session ID") }
            if (server.externalSocket == null) require(server.url.matches(Regex("ws://127\\.0\\.0\\.1:[0-9]{1,5}"))) { tr("会话服务地址无效", "Invalid session service URL") }
            val token = if (server.externalSocket != null) null else if (environment == "root") hostPath(File(privateDirectory(environment), "capability-token")) else guestPath(environment, "capability-token")
            key = if (server.externalSocket != null) TerminalLaunchPolicy.sessionWindowId(environment, "$cli:unix:${server.externalSocket}", threadId)
                else if (environment == "noroot" || io.github.modao7067.luma.gui.GuiProduct.isRoot) TerminalLaunchPolicy.sessionWindowId(environment, cli, threadId)
                else UUID.nameUUIDFromBytes((server.url + ":" + threadId).toByteArray()).toString().replace("-", "")
            command = server.externalSocket?.let { ExternalCodexPolicy.remoteCommand(it, threadId, cwd) }
                ?: "cd ${quote(cwd)} && export LUMA_CODEX_REMOTE_TOKEN=\"\$(cat ${quote(checkNotNull(token))})\" && exec codex resume ${quote(threadId)} --remote ${quote(server.url)} --remote-auth-token-env LUMA_CODEX_REMOTE_TOKEN"
        }
        val ticketId = randomToken()
        val directory = File(app.filesDir, "agent-terminal-requests").apply { mkdirs(); Os.chmod(absolutePath, 0x1C0) }
        val now = System.currentTimeMillis()
        directory.listFiles()?.filter { it.isFile && now - it.lastModified() > 3_600_000L }?.forEach { it.delete() }
        val ticketEnv = if (rootTerminal) "root" else environment
        val ticket = JSONObject().put("environment", ticketEnv).put("windowId", key).put("cwd", cwd)
            .put("title", if (shell) "Shell · ${File(cwd).name.ifEmpty { "/" }}" else "$cli · ${threadId?.takeLast(8) ?: tr("启动", "launch")}")
            .put("script", "/bin/bash --login -c ${quote(command)}")
        if (!shell && !sessionId.isNullOrBlank()) ticket.put("sessionId", sessionId)
        if (!shell && nativeTerminal != null) nativeTerminal.optString("instanceId").takeIf { it.isNotBlank() }?.let { ticket.put("instanceId", it) }
        TerminalLaunchPolicy.mode(ticketEnv, shell, io.github.modao7067.luma.gui.GuiProduct.isRoot || rootTerminal)?.let { ticket.put("terminalMode", it) }
        writePrivate(File(directory, "$ticketId.json"), ticket.toString())
        val activity = if (rootTerminal) "io.github.modao7067.luma.root.MainActivity" else if (needsSetup) "io.github.modao7067.luma.SetupActivity" else "io.github.modao7067.luma.MainActivity"
        context.startActivity(Intent().setClassName(app.packageName, activity)
            .putExtra("io.github.modao7067.luma.gui.TERMINAL_TICKET", ticketId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun usesRootExecution(environment: String) = environment == "root" || DebianRootMode.isEnabled(app)
    fun skillsPath(environment: String) = runtimePath(environment, "skills")
    private fun runtimeBasePath(environment: String) =
        if (environment == "root") hostPath(privateDirectory(environment)) else "/opt/luma/${io.github.modao7067.luma.gui.GuiProduct.agentRelative(environment)}"
    private fun runtimePath(environment: String, name: String) =
        if (environment == "root") hostPath(File(privateDirectory(environment), name)) else guestPath(environment, name)

    private fun rootService(environment: String, name: String, command: List<String>, probe: String): String {
        val directory = privateDirectory(environment)
        val script = File(directory, "service-launch.py")
        writePrivate(script, RuntimeScripts.rootService)
        for (suffix in listOf("lock", "json", "log")) {
            val file = File(directory, "$name.$suffix")
            if (!file.exists()) writePrivate(file, "")
        }
        return JSONObject(rootExecute(listOf("/usr/bin/python3", "-I", runtimePath(environment, "service-launch.py"), runtimeBasePath(environment), name, probe,
            JSONArray(command).toString()))).getString("url")
    }

    private fun withLumaLang(command: List<String>): List<String> =
        if (command.firstOrNull() == "/usr/bin/env" && command.any { it.startsWith("LUMA_LANG=") }) command
        else listOf("/usr/bin/env", "LUMA_LANG=${LumaI18n.langCode()}") + command

    private fun execute(environment: String, command: List<String>): String {
        validateEnvironment(environment)
        val envCmd = withLumaLang(command)
        return if (usesRootExecution(environment)) rootExecute(envCmd) else NoRootRuntime.execute(app, envCmd)
    }
    private fun rootExecute(command: List<String>): String {
        val builtinChroot = DebianRootMode.isEnabled(app)
        if (builtinChroot) runCatching { NoRootRuntime.syncGuestDns(app) }
        val launcher = DebianRootMode.rootLauncher(app)
        val lang = LumaI18n.langCode()
        val line = listOf(launcher).plus(withLumaLang(command)).joinToString(" ", transform = ::quote)
        val process = try {
            ProcessBuilder(DebianRootMode.suExecutable(), "-c", "export LUMA_LANG=${quote(lang)}; exec $line").redirectErrorStream(true).start()
        } catch (error: IOException) {
            if (builtinChroot) throw IOException(tr("没拿到 Root 权限。用临时 Root 的话，重新激活后再试", "Root access unavailable. If using temporary root, re-activate it and try again"), error)
            throw error
        }
        runCatching { process.outputStream.close() }
        val output = ByteArrayOutputStream()
        val reader = Thread({
            try {
                process.inputStream.use { input ->
                    val block = ByteArray(8192)
                    while (true) {
                        val n = input.read(block)
                        if (n < 0) break
                        if (output.size() + n > 2 * 1024 * 1024) {
                            process.destroy()
                            break
                        }
                        output.write(block, 0, n)
                    }
                }
            } catch (_: IOException) {
            }
        }, "LumaRootAgentIO").apply {
            isDaemon = true
            start()
        }
        if (!process.waitFor(35, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            runCatching { reader.join(300) }
            throw IOException(if (builtinChroot) tr("Root 操作超时，请确认 Root 授权可用", "Root operation timed out; verify root access") else tr("Root 操作超时，请确认 Root 授权与 Ubuntu 入口可用", "Root operation timed out; verify root access and Ubuntu launcher"))
        }
        reader.join(2000)
        val text = output.toString("UTF-8")
        if (process.exitValue() != 0) {
            val fallback = if (builtinChroot) tr("Root 模式下执行失败。用临时 Root 的话，确认 Root 还在后再试", "Execution failed in Root mode. If using temporary root, make sure root is still active")
                else tr("Root 操作失败，请确认授权和 Ubuntu 环境", "Root operation failed; check root grant and Ubuntu environment")
            throw IOException(text.trim().ifEmpty { fallback })
        }
        return text
    }
    private fun verifyAuthenticatedUpgrade(url: String, token: String) {
        val uri = Uri.parse(url)
        Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress("127.0.0.1", uri.port), 3000); socket.soTimeout = 3000
            val request = "GET / HTTP/1.1\r\nHost: 127.0.0.1:${uri.port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\nAuthorization: Bearer $token\r\n\r\n"
            socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII)); socket.getOutputStream().flush()
            val status = socket.getInputStream().bufferedReader(Charsets.US_ASCII).readLine().orEmpty()
            if (!status.contains(" 101 ")) throw IOException(tr("Codex 服务认证失败，请重连；不会重新发送上一条输入", "Codex service authentication failed; reconnect (last input will not be resent)"))
        }
    }
    private fun privateDirectory(environment: String): File = File(app.filesDir, io.github.modao7067.luma.gui.GuiProduct.agentRuntimePath(environment)).apply {
        if (!isDirectory && !mkdirs()) throw IOException(tr("无法创建会话运行目录", "Failed to create session runtime directory"))
        Os.chmod(absolutePath, 0x1C0)
    }
    private fun guestPath(environment: String, name: String) = "/opt/luma/${io.github.modao7067.luma.gui.GuiProduct.agentRelative(environment)}/$name"
    private fun hostPath(file: File) = "/proc/1/root" + file.absolutePath
    private fun validatePath(path: String) { require(path.startsWith("/") && '\u0000' !in path) { tr("请输入绝对路径", "Enter an absolute path") } }
    private fun validateEnvironment(environment: String) { require(environment == "root" || environment == "noroot") { tr("未知运行环境", "Unknown runtime environment") } }
    companion object {
        private val locks = mapOf("root" to Mutex(), "noroot" to Mutex())
        private val globalUsageStageLock = Any()
        private data class ManagedMediaDir(val subPath: String, val directChildOnly: Boolean, val forbidJson: Boolean)
        private val managedMediaDirs = listOf(
            ManagedMediaDir("attachments", directChildOnly = true, forbidJson = true),
            ManagedMediaDir("cli-sessions/media-cache", directChildOnly = true, forbidJson = true),
            ManagedMediaDir("browser", directChildOnly = true, forbidJson = true),
            ManagedMediaDir("skills", directChildOnly = false, forbidJson = false),
        )
        internal fun resolveManagedRuntimeFile(filesDir: File, environment: String, source: String): File? {
            if (environment != "root" && environment != "noroot") return null
            if (source.isBlank() || '\u0000' in source) return null
            val rawPath = when {
                source.startsWith("file:") || source.startsWith("sandbox:") ->
                    runCatching { URI(source.replace(" ", "%20")).path }.getOrNull()
                else -> source
            } ?: return null
            if (!rawPath.startsWith("/")) return null
            val normalized = File(rawPath).toPath().normalize().toString()
            val agentRoot = File(filesDir, io.github.modao7067.luma.gui.GuiProduct.agentRuntimePath(environment))
            for (entry in managedMediaDirs) {
                val baseDir = File(agentRoot, entry.subPath)
                val prefixes = buildList {
                    add(baseDir.absolutePath)
                    if (environment == "root") add("/proc/1/root" + baseDir.absolutePath)
                    if (environment == "noroot") add("/opt/luma/agent/${entry.subPath}")
                }
                val matchedPrefix = prefixes.firstOrNull { normalized.startsWith("$it/") } ?: continue
                val rel = normalized.removePrefix("$matchedPrefix/")
                if (rel.isEmpty() || (entry.directChildOnly && '/' in rel)) continue
                val target = File(baseDir, rel)
                if (!target.isFile) continue
                val canonicalBase = runCatching { baseDir.canonicalFile }.getOrNull() ?: continue
                val canonicalTarget = runCatching { target.canonicalFile }.getOrNull() ?: continue
                val basePath = canonicalBase.path
                val targetPath = canonicalTarget.path
                if (entry.directChildOnly) {
                    if (canonicalTarget.parentFile != canonicalBase) continue
                } else if (targetPath != basePath && !targetPath.startsWith(basePath + File.separator)) {
                    continue
                }
                if (entry.forbidJson && canonicalTarget.name.endsWith(".json", ignoreCase = true)) continue
                return canonicalTarget
            }
            return null
        }
        internal fun resolveManagedRootProtectedPath(filesDir: File, environment: String, source: String): String? {
            if (environment != "root") return null
            if (source.isBlank() || '\u0000' in source) return null
            val rawPath = when {
                source.startsWith("file:") || source.startsWith("sandbox:") ->
                    runCatching { URI(source.replace(" ", "%20")).path }.getOrNull()
                else -> source
            } ?: return null
            if (!rawPath.startsWith("/")) return null
            val normalized = File(rawPath).toPath().normalize().toString()
            val candidateRoots = buildSet {
                add(filesDir.absolutePath.trimEnd('/'))
                runCatching { filesDir.canonicalPath.trimEnd('/') }.getOrNull()?.let(::add)
            }
            for (rootPath in candidateRoots) {
                val agentRootPath = "$rootPath/runtime/agent/root"
                for (entry in managedMediaDirs) {
                    val basePath = "$agentRootPath/${entry.subPath}"
                    val prefixes = listOf(basePath, "/proc/1/root$basePath")
                    val matchedPrefix = prefixes.firstOrNull { normalized.startsWith("$it/") } ?: continue
                    val rel = normalized.removePrefix("$matchedPrefix/")
                    if (rel.isEmpty()) continue
                    val segments = rel.split('/')
                    if (segments.any { it.isEmpty() || it == "." || it == ".." }) continue
                    if (entry.directChildOnly && segments.size != 1) continue
                    if (entry.forbidJson && segments.last().endsWith(".json", ignoreCase = true)) continue
                    return "/proc/1/root$basePath/$rel"
                }
            }
            return null
        }
        private fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
        private fun randomToken(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun writePrivate(file: File, text: String) {
            file.writeText(text); Os.chmod(file.absolutePath, 0x180)
        }
    }
}
