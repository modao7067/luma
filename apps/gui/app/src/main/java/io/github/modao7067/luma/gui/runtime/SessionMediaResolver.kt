package io.github.modao7067.luma.gui.runtime

import io.github.modao7067.luma.gui.i18n.tr

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Process
import android.provider.OpenableColumns
import android.system.Os
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.github.modao7067.luma.gui.session.MediaResource
import io.github.modao7067.luma.gui.session.ResolvedMedia
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class SessionMediaResolver(
    private val context: Context,
    private val runtime: AgentRuntime,
    private val clockMs: () -> Long,
) {
    constructor(context: Context, runtime: AgentRuntime) : this(context, runtime, System::currentTimeMillis)

    private data class CachedEntry(
        val media: ResolvedMedia,
        val file: File,
        val cachedAtMs: Long,
        val sourceLength: Long?,
        val sourceLastModified: Long?,
        val generation: Long,
    )

    private class InFlightTask(
        val generation: Long,
        val forceRefresh: Boolean,
        val deferred: CompletableDeferred<ResolvedMedia> = CompletableDeferred(),
        var waiters: Int = 1,
        @Volatile var workerJob: Job? = null,
    )

    private val stateLock = Any()
    private val openLock = Mutex()
    private val resolverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cached = linkedMapOf<List<String>, CachedEntry>()
    private val inFlight = hashMapOf<List<String>, InFlightTask>()
    private val writingFiles = hashSetOf<String>()
    private var nextGeneration = 0L
    private val directory get() = File(context.cacheDir, "session-media").apply { mkdirs() }

    init {
        cleanOpenCache(maxAgeMs = FOREGROUND_CLEAN_GRACE_MS)
    }

    fun cleanOpenCache(maxAgeMs: Long = 0L) {
        val now = System.currentTimeMillis()
        File(context.cacheDir, "terminal-open").listFiles().orEmpty()
            .filter { it.name.startsWith("open-media-") && (maxAgeMs <= 0L || now - it.lastModified() >= maxAgeMs) }
            .forEach { it.delete() }
        File(context.cacheDir, "session-export").listFiles().orEmpty()
            .filter { it.name.startsWith("export-") && (maxAgeMs <= 0L || now - it.lastModified() >= maxAgeMs) }
            .forEach { it.delete() }
    }

    fun scheduleOpenCacheCleanup(delayMs: Long = AUTO_CLEAN_DELAY_MS) {
        val grace = minOf(delayMs, FOREGROUND_CLEAN_GRACE_MS)
        cleanupExecutor.schedule({ runCatching { cleanOpenCache(maxAgeMs = grace) } }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun verifyExternalContentUri(uri: Uri) {
        val authority = uri.authority?.trim().orEmpty()
        if (authority.isEmpty() || authority == context.packageName || authority.startsWith("${context.packageName}.")) {
            throw IOException(tr("不允许读取内部附件地址", "Reading internal attachment URI is not allowed"))
        }
        if (context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            throw IOException(tr("未获得该文件的读取授权，请重新选择", "Read permission not granted for this file; please select it again"))
        }
    }

    private fun readOriginal(environment: String, resource: MediaResource, file: File, limit: Long): ResolvedMedia {
        var name = resource.name.ifBlank { MediaSourcePolicy.name(resource.source) }
        var mime = resource.mimeType.substringBefore(';').trim().lowercase(java.util.Locale.ROOT).takeIf { it.isNotBlank() && !it.endsWith("/*") } ?: MediaSourcePolicy.mime(name)
        if (resource.source.startsWith("content:")) {
            val uri = Uri.parse(resource.source)
            verifyExternalContentUri(uri)
            context.contentResolver.getType(uri)?.takeIf { it.isNotBlank() && it != "application/octet-stream" && !it.endsWith("/*") }?.let { mime = it }
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    if (resource.name.isBlank() && !cursor.isNull(0)) name = cursor.getString(0)
                    if (!cursor.isNull(1) && cursor.getLong(1) > limit) throw IOException(tr("附件超过 ${limit / (1024 * 1024)} MiB", "Attachment exceeds ${limit / (1024 * 1024)} MiB"))
                }
            }
            if (mime == "application/octet-stream") mime = MediaSourcePolicy.mime(name)
            (context.contentResolver.openInputStream(uri) ?: throw IOException(tr("无法读取附件，请重新选择", "Unable to read attachment; please select it again"))).use { MediaByteReader.copy(it, file, limit) }
        } else mime = MediaByteReader.read(resource.source, resource.cwd, file, mime,
            imported = { runtime.importedImageFile(environment, resource.source) },
            guest = { path, target, maximum -> runtime.copyMediaFile(environment, path, target, maximum) },
            allowHttp = { host -> android.security.NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host) }, limit = limit,
            managedRootProtected = { runtime.managedRootProtectedPath(environment, resource.source) })
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val header = file.inputStream().use { input -> ByteArray(4096).let { bytes -> val count = input.read(bytes); bytes.copyOf(count.coerceAtLeast(0)) } }
        mime = MediaSourcePolicy.sniff(header, mime)
        Os.chmod(file.absolutePath, 0x180)
        return ResolvedMedia(Uri.fromFile(file).toString(), mime, name, file.length())
    }

    private fun isCacheEntryFresh(environment: String, resource: MediaResource, entry: CachedEntry): Boolean {
        if (!entry.file.isFile) return false
        if (resource.source.startsWith("data:")) return true
        val imported = runtime.importedImageFile(environment, resource.source)
        if (imported != null) {
            return imported.isFile &&
                entry.sourceLength == imported.length() &&
                entry.sourceLastModified == imported.lastModified()
        }
        return clockMs() - entry.cachedAtMs <= SOFT_TTL_MS
    }

    suspend fun resolve(environment: String, resource: MediaResource, forceRefresh: Boolean = false): ResolvedMedia {
        val key = listOf(environment, resource.sessionId.orEmpty(), resource.cwd.orEmpty(), resource.source)
        var taskToStart: Job? = null
        val task: InFlightTask
        synchronized(stateLock) {
            if (!forceRefresh) {
                val entry = cached[key]
                if (entry != null) {
                    if (isCacheEntryFresh(environment, resource, entry)) {
                        cached.remove(key)
                        cached[key] = entry
                        runCatching { entry.file.setLastModified(clockMs()) }
                        return entry.media
                    }
                    cached.remove(key)
                }
            }
            val existing = inFlight[key]
            if (existing != null && (!forceRefresh || existing.forceRefresh)) {
                existing.waiters++
                task = existing
            } else {
                val created = InFlightTask(generation = ++nextGeneration, forceRefresh = forceRefresh)
                inFlight[key] = created
                val job = resolverScope.launch(start = CoroutineStart.LAZY) {
                    val file = File(directory, "media-${UUID.randomUUID()}")
                    val filePath = canonicalOrAbsolute(file)
                    synchronized(stateLock) { writingFiles.add(filePath) }
                    try {
                        val resolvedTuple = runInterruptible(Dispatchers.IO) {
                            val importedBefore = runtime.importedImageFile(environment, resource.source)
                            val sourceLength = importedBefore?.length()
                            val sourceLastModified = importedBefore?.lastModified()
                            val result = readOriginal(environment, resource, file, MediaSourcePolicy.MAX_BYTES)
                            if (file.length() == 0L) throw IOException(tr("附件为空", "Attachment is empty"))
                            val finalMedia = if (result.mimeType.startsWith("image/") && result.mimeType != "image/svg+xml") {
                                val header = file.inputStream().use { input -> ByteArray(4096).let { bytes -> val count = input.read(bytes); bytes.copyOf(count.coerceAtLeast(0)) } }
                                if (result.mimeType in setOf("image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp") &&
                                    MediaSourcePolicy.sniff(header, "application/octet-stream") == "application/octet-stream") throw IOException(tr("图片数据损坏", "Corrupted image data"))
                                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeFile(file.absolutePath, options)
                                if (options.outWidth <= 0 || options.outHeight <= 0) throw IOException(tr("图片损坏或系统不支持此格式", "Corrupted image or unsupported format"))
                                if (options.outWidth.toLong() * options.outHeight > 64_000_000L) throw IOException(tr("图片像素过大，请缩小后查看", "Image dimensions are too large to preview"))
                                result.copy(mimeType = options.outMimeType ?: result.mimeType)
                            } else result
                            val now = clockMs()
                            runCatching { file.setLastModified(now) }
                            Triple(finalMedia, sourceLength to sourceLastModified, now)
                        }
                        val (finalMedia, stamp, now) = resolvedTuple
                        synchronized(stateLock) {
                            val current = cached[key]
                            if (current == null || current.generation <= created.generation) {
                                cached.remove(key)
                                cached[key] = CachedEntry(finalMedia, file, now, stamp.first, stamp.second, created.generation)
                                while (cached.size > 128 || cached.keys.sumOf { it.last().length.toLong() } > 4L * 1024 * 1024) {
                                    cached.remove(cached.keys.first())
                                }
                            }
                            if (inFlight[key] === created) inFlight.remove(key)
                        }
                        trim(file)
                        created.deferred.complete(finalMedia)
                    } catch (error: Throwable) {
                        file.delete()
                        synchronized(stateLock) {
                            if (inFlight[key] === created) inFlight.remove(key)
                        }
                        created.deferred.completeExceptionally(error)
                    } finally {
                        synchronized(stateLock) { writingFiles.remove(filePath) }
                    }
                }
                created.workerJob = job
                taskToStart = job
                task = created
            }
        }
        taskToStart?.start()
        return try {
            task.deferred.await()
        } finally {
            var jobToCancel: Job? = null
            synchronized(stateLock) {
                task.waiters--
                if (task.waiters <= 0 && !task.deferred.isCompleted) {
                    if (inFlight[key] === task) inFlight.remove(key)
                    jobToCancel = task.workerJob
                }
            }
            jobToCancel?.cancel()
        }
    }
    suspend fun save(environment: String, resource: MediaResource): String = runInterruptible(Dispatchers.IO) {
        val exports = File(context.cacheDir, "session-export").apply { mkdirs() }
        val available = exports.usableSpace.takeIf { it > 0L } ?: MediaDownloads.MAX_BYTES
        val budget = minOf(MediaDownloads.MAX_BYTES, available)
        val file = File(exports, "export-${UUID.randomUUID()}")
        try {
            val original = try {
                readOriginal(environment, resource, file, budget)
            } catch (error: IOException) {
                if (error.message in setOf("附件超过 ${budget / (1024 * 1024)} MiB", "Attachment exceeds ${budget / (1024 * 1024)} MiB", "文件超过预览大小限制", "File exceeds preview size limit", "文件过大", "File is too large")) {
                    val msg = if (available < MediaDownloads.MAX_BYTES) tr("可用缓存空间不足，无法保存文件", "Insufficient cache space to save file")
                    else tr("附件超过 ${MediaDownloads.MAX_BYTES / (1024 * 1024)} MiB", "Attachment exceeds ${MediaDownloads.MAX_BYTES / (1024 * 1024)} MiB")
                    throw IOException(msg, error)
                }
                throw error
            }
            MediaDownloads.save(context.contentResolver, file, original.name, original.mimeType)
        } finally { file.delete() }
    }
    suspend fun open(environment: String, resource: MediaResource, chooseApp: Boolean = false) {
        var staged: File? = null
        var launched = false
        try {
            val resolved = openLock.withLock {
                runInterruptible(Dispatchers.IO) {
                    val directory = File(context.cacheDir, "terminal-open").apply { mkdirs() }
                    if (!directory.isDirectory) throw IOException(tr("无法创建外部打开缓存目录", "Failed to create external-open cache directory"))
                    directory.listFiles().orEmpty().filter { it.name.startsWith("open-media-") }.forEach { it.delete() }
                    val freeBudget = (directory.usableSpace - MIN_FREE_RESERVE_BYTES).coerceAtLeast(0L)
                    if (freeBudget <= 0L) throw IOException(tr("可用缓存空间不足", "Insufficient cache space"))
                    val budget = minOf(OPEN_MAX_BYTES, freeBudget)
                    val destination = File(directory, "open-media-${UUID.randomUUID()}")
                    staged = destination
                    try {
                        readOriginal(environment, resource, destination, budget)
                    } catch (error: IOException) {
                        if (error.message in setOf("附件超过 ${budget / (1024 * 1024)} MiB", "Attachment exceeds ${budget / (1024 * 1024)} MiB", "文件超过预览大小限制", "File exceeds preview size limit", "文件过大", "File is too large")) {
                            val msg = if (freeBudget < OPEN_MAX_BYTES) tr("可用缓存空间不足", "Insufficient cache space") else tr("外部打开文件超过 512 MiB，请先保存到下载处理", "File exceeds 512 MiB for external open; save to Downloads first")
                            throw IOException(msg, error)
                        }
                        throw error
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            val file = requireNotNull(staged)
            val originalName = MediaDownloads.name(resolved.name, resolved.mimeType)
            val types = android.webkit.MimeTypeMap.getSingleton()
            val mime = if (originalName.endsWith(".apk", true) || MediaSourcePolicy.name(resource.source).endsWith(".apk", true)) "application/vnd.android.package-archive" else resolved.mimeType
            val extension = if (mime == "application/vnd.android.package-archive") "apk" else types.getExtensionFromMimeType(mime)
            val existingType = types.getMimeTypeFromExtension(originalName.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT))
            val name = if (extension != null && existingType != mime) "$originalName.$extension" else originalName
            val uri = Uri.Builder().scheme("content").authority(context.packageName + ".terminal-files").appendPath(file.name).appendPath(name).build()
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.clipData = ClipData.newRawUri(name, uri)
            val launchIntent = if (!chooseApp) intent else Intent.createChooser(intent, null).apply {
                clipData = intent.clipData
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try { context.startActivity(launchIntent) }
            catch (error: ActivityNotFoundException) { throw IOException(tr("没有可打开此文件的应用", "No app available to open this file"), error) }
            catch (error: SecurityException) { throw IOException(tr("系统未允许打开此文件：${error.message ?: "访问被拒绝"}", "System blocked opening this file: ${error.message ?: "Access denied"}"), error) }
            launched = true
            scheduleOpenCacheCleanup(AUTO_CLEAN_DELAY_MS)
        } finally {
            if (!launched) staged?.delete()
        }
    }
    internal fun trim(keep: File? = null) {
        val keepPath = keep?.let(::canonicalOrAbsolute)
        val activeWrites = synchronized(stateLock) { writingFiles.toSet() }
        val allFiles = directory.listFiles().orEmpty().sortedBy { it.lastModified() }
        var bytes = allFiles.sumOf { it.length() }
        val now = clockMs()
        val deletedPaths = hashSetOf<String>()
        for (file in allFiles) {
            val filePath = canonicalOrAbsolute(file)
            if (filePath == keepPath || filePath in activeWrites || isPinned(file)) continue
            if (bytes > MediaSourcePolicy.CACHE_BYTES || now - file.lastModified() > 24 * 60 * 60_000L) {
                val size = file.length()
                if (file.delete()) {
                    bytes -= size
                    deletedPaths.add(filePath)
                }
            }
        }
        if (deletedPaths.isNotEmpty()) {
            synchronized(stateLock) {
                val iterator = cached.entries.iterator()
                while (iterator.hasNext()) {
                    val entryPath = canonicalOrAbsolute(iterator.next().value.file)
                    if (entryPath in deletedPaths) iterator.remove()
                }
            }
        }
    }

    companion object {
        internal const val SOFT_TTL_MS = 20_000L
        internal const val OPEN_MAX_BYTES = 512L * 1024 * 1024
        internal const val MIN_FREE_RESERVE_BYTES = 256L * 1024 * 1024
        internal const val FOREGROUND_CLEAN_GRACE_MS = 10_000L
        internal const val AUTO_CLEAN_DELAY_MS = 3 * 60_000L
        private val cleanupExecutor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "LumaOpenCacheCleaner").apply { isDaemon = true }
        }
        private val activePinLock = Any()
        private val activePins = hashMapOf<String, Int>()

        private fun canonicalOrAbsolute(file: File): String =
            runCatching { file.canonicalPath }.getOrElse { file.absolutePath }

        internal fun pinUri(uri: String): String? {
            val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return null
            if (parsed.scheme != "file") return null
            val path = parsed.path?.takeIf { it.isNotBlank() } ?: return null
            return pinFile(File(path))
        }

        internal fun pinFile(file: File): String {
            val key = canonicalOrAbsolute(file)
            synchronized(activePinLock) {
                activePins[key] = (activePins[key] ?: 0) + 1
            }
            return key
        }

        internal fun unpinPath(key: String?) {
            if (key == null) return
            synchronized(activePinLock) {
                val count = activePins[key] ?: return
                if (count <= 1) activePins.remove(key) else activePins[key] = count - 1
            }
        }

        internal fun isPinned(file: File): Boolean {
            val key = canonicalOrAbsolute(file)
            return synchronized(activePinLock) { (activePins[key] ?: 0) > 0 }
        }
    }
}
