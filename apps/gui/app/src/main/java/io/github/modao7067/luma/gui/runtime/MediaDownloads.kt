package io.github.modao7067.luma.gui.runtime

import io.github.modao7067.luma.gui.i18n.tr

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
        if (name.isBlank() || name in setOf(".", "..")) name = tr("附件", "attachment")
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
        check(original.isFile) { tr("原始文件已不可读", "Source file is no longer readable") }
        if (original.length() > MAX_BYTES) throw IOException(tr("附件超过 ${MAX_BYTES / (1024 * 1024)} MiB", "Attachment exceeds ${MAX_BYTES / (1024 * 1024)} MiB"))
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
            ?: throw IOException(tr("无法创建下载文件", "Failed to create download file"))
        var committed = false
        var failure: Throwable? = null
        try {
            val descriptor = resolver.openFileDescriptor(uri, "w") ?: throw IOException(tr("无法写入下载文件", "Failed to write download file"))
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                original.inputStream().use { input ->
                    val buffer = ByteArray(65536); var bytes = 0L
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        val count = input.read(buffer); if (count < 0) break
                        bytes += count; if (bytes > MAX_BYTES) throw IOException(tr("附件超过 ${MAX_BYTES / (1024 * 1024)} MiB", "Attachment exceeds ${MAX_BYTES / (1024 * 1024)} MiB"))
                        output.write(buffer, 0, count)
                    }
                }
                output.flush(); output.fd.sync()
            }
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            if (resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) != 1)
                throw IOException(tr("下载文件未能提交", "Failed to finalize download file"))
            val actual = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) throw IOException(tr("无法确认保存位置", "Unable to verify saved file location"))
                val name = cursor.getString(0); val path = cursor.getString(1) ?: throw IOException(tr("无法确认保存位置", "Unable to verify saved file location"))
                if (path.trimEnd('/') != relativePath.trimEnd('/') || name.isNullOrBlank() || name.contains('/') || name.contains('\\')) throw IOException(tr("保存位置与目标不符", "Saved path does not match destination"))
                name
            } ?: throw IOException(tr("无法确认保存位置", "Unable to verify saved file location"))
            committed = true
            return "/sdcard/$relativePath$actual"
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            if (!committed) {
                val cleanupError = try {
                    if (resolver.delete(uri, null, null) != 1) IOException(tr("保存失败，未完成文件清理失败", "Save failed and incomplete file cleanup also failed")) else null
                } catch (cleanup: Exception) { cleanup }
                if (cleanupError != null) {
                    val original = failure
                    if (original != null) original.addSuppressed(cleanupError) else throw cleanupError
                }
            }
        }
    }
}
