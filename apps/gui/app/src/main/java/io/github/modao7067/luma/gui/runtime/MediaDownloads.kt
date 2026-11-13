package io.github.modao7067.luma.gui.runtime

import android.content.ContentResolver
import android.content.ContentValues
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException
import java.util.Locale

internal object MediaDownloads {
    const val MAX_BYTES = 4096L * 1024 * 1024
    val relativePath = Environment.DIRECTORY_DOWNLOADS + "/Luma/"

    fun name(original: String, mime: String): String {
        var name = original.replace('\\', '/').substringAfterLast('/').trim()
            .map { if (it.code < 32 || it.code == 127 || it in ":*?\"<>|") '_' else it }.joinToString("")
        if (name.isBlank() || name in setOf(".", "..")) name = "附件"
        if (!name.contains('.') || name.endsWith('.')) {
            val extension = when (mime) {
                "application/vnd.android.package-archive" -> "apk"
                "image/svg+xml" -> "svg"
                "text/markdown" -> "md"
                "text/x-diff" -> "patch"
                else -> MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            }
            if (extension != null) name = name.trimEnd('.') + ".$extension"
        }
        if (name.toByteArray(Charsets.UTF_8).size > 220) {
            val dot = name.lastIndexOf('.'); val suffix = if (dot > 0 && name.length - dot <= 20) name.substring(dot) else ""
            val stem = name.removeSuffix(suffix); val budget = 220 - suffix.toByteArray(Charsets.UTF_8).size
            val kept = StringBuilder(); var bytes = 0; var index = 0
            while (index < stem.length) {
                val end = index + Character.charCount(stem.codePointAt(index)); val next = stem.substring(index, end)
                if (bytes + next.toByteArray(Charsets.UTF_8).size > budget) break
                kept.append(next); bytes += next.toByteArray(Charsets.UTF_8).size; index = end
            }
            name = kept.toString() + suffix
        }
        return name
    }
    fun save(resolver: ContentResolver, original: File, originalName: String, sourceMime: String): String {
        check(original.isFile) { "原始文件已不可读" }
        if (original.length() > MAX_BYTES) throw IOException("附件超过 ${MAX_BYTES / (1024 * 1024)} MiB")
        var mime = sourceMime.substringBefore(';').trim().lowercase(Locale.ROOT)
            .takeIf { Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+").matches(it) } ?: "application/octet-stream"
        if (mime == "text/plain") MimeTypeMap.getSingleton().getMimeTypeFromExtension(originalName.substringAfterLast('.', "").lowercase(Locale.ROOT))
            ?.takeIf(MediaSourcePolicy::isText)?.let { mime = it }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name(originalName, mime))
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("无法创建下载文件")
        var committed = false
        var failure: Throwable? = null
        try {
            val descriptor = resolver.openFileDescriptor(uri, "w") ?: throw IOException("无法写入下载文件")
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                original.inputStream().use { input ->
                    val buffer = ByteArray(65536); var bytes = 0L
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        val count = input.read(buffer); if (count < 0) break
                        bytes += count; if (bytes > MAX_BYTES) throw IOException("附件超过 ${MAX_BYTES / (1024 * 1024)} MiB")
                        output.write(buffer, 0, count)
                    }
                }
                output.flush(); output.fd.sync()
            }
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            if (resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) != 1)
                throw IOException("下载文件未能提交")
            val actual = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) throw IOException("无法确认保存位置")
                val name = cursor.getString(0); val path = cursor.getString(1) ?: throw IOException("无法确认保存位置")
                if (path.trimEnd('/') != relativePath.trimEnd('/') || name.isNullOrBlank() || name.contains('/') || name.contains('\\')) throw IOException("保存位置与目标不符")
                name
            } ?: throw IOException("无法确认保存位置")
            committed = true
            return "/sdcard/$relativePath$actual"
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            if (!committed) try {
                if (resolver.delete(uri, null, null) != 1) throw IOException("保存失败，未完成文件清理失败")
            } catch (cleanup: Exception) {
                val error = IOException("保存失败，未完成文件清理失败", failure ?: cleanup)
                if (failure != null) error.addSuppressed(cleanup)
                throw error
            }
        }
    }
}
