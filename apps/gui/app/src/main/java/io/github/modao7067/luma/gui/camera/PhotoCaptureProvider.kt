package io.github.modao7067.luma.gui.camera

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.FileProvider
import io.github.modao7067.luma.gui.i18n.tr
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID

class PhotoCaptureProvider : FileProvider() {
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val store = PhotoCaptureStore(requireNotNull(context))
        store.fileForUri(uri) ?: throw FileNotFoundException("Invalid photo capture URI")
        return super.openFile(uri, mode) ?: throw FileNotFoundException("Photo capture unavailable")
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val store = PhotoCaptureStore(requireNotNull(context))
        store.fileForUri(uri) ?: throw SecurityException("Invalid photo capture URI")
        return super.query(uri, projection, selection, selectionArgs, sortOrder)
    }

    override fun getType(uri: Uri): String? {
        val store = PhotoCaptureStore(requireNotNull(context))
        store.fileForUri(uri) ?: return null
        return super.getType(uri)
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val store = PhotoCaptureStore(requireNotNull(context))
        store.fileForUri(uri) ?: throw SecurityException("Invalid photo capture URI")
        return 0
    }
}

internal class PhotoCaptureStore(private val context: Context) {
    private val directory = File(context.cacheDir, "photo-capture")
    private val authority = context.packageName + ".capture-files"

    fun create(): String {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException(tr("无法准备拍照文件", "Failed to prepare photo file"))
        prune()
        val name = "capture-${UUID.randomUUID()}.jpg"
        val file = file(name) ?: throw IOException(tr("无法准备拍照文件", "Failed to prepare photo file"))
        if (!file.createNewFile()) throw IOException(tr("无法准备拍照文件", "Failed to prepare photo file"))
        return name
    }

    fun uri(name: String): Uri = FileProvider.getUriForFile(context, authority,
        file(name) ?: throw IOException(tr("拍照文件无效", "Invalid photo file")))

    fun file(name: String): File? {
        if (!NAME.matches(name)) return null
        val candidate = File(directory, name)
        return candidate.takeIf { it.canonicalFile.parentFile == directory.canonicalFile }
    }

    fun fileForUri(uri: Uri): File? = if (uri.scheme == "content" && uri.authority == authority &&
        uri.pathSegments.size == 2 && uri.pathSegments[0] == "photo" && uri.query == null && uri.fragment == null)
        file(uri.pathSegments[1]) else null

    fun ready(name: String): Uri {
        val file = file(name)?.takeIf { it.isFile && it.length() > 0 } ?: throw IOException(tr("未取得照片，请重试", "No photo captured; please try again"))
        val marker = File(directory, "$name.ready")
        if (!marker.createNewFile()) throw IOException(tr("无法准备照片导入", "Failed to prepare photo import"))
        return uri(file.name)
    }

    fun isReady(name: String): Boolean = file(name) != null && File(directory, "$name.ready").isFile

    fun importFinished(uri: Uri) {
        val name = fileForUri(uri)?.name ?: return
        if (isReady(name)) discard(name)
    }

    fun discard(name: String) {
        val file = file(name) ?: return
        for (entry in listOf(file, File(directory, "$name.ready"))) {
            if (entry.exists() && !entry.delete()) Log.w("PhotoCapture", "Capture cache cleanup failed")
        }
    }

    private fun prune() {
        pruneDirectory(directory)
    }

    companion object {
        private val NAME = Regex("capture-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg")
        private val READY_NAME = Regex("capture-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg\\.ready")

        internal fun pruneDirectory(
            directory: File,
            nowMs: Long = System.currentTimeMillis(),
            onCleanupFailure: () -> Unit = { Log.w("PhotoCapture", "Capture cache cleanup failed") },
        ) {
            val before = nowMs - 24 * 60 * 60 * 1000L
            for (entry in directory.listFiles().orEmpty()) {
                if (NAME.matches(entry.name) && entry.lastModified() < before) {
                    for (target in listOf(entry, File(directory, "${entry.name}.ready"))) {
                        if (target.exists() && !target.delete()) onCleanupFailure()
                    }
                } else if (READY_NAME.matches(entry.name)) {
                    val jpg = File(directory, entry.name.removeSuffix(".ready"))
                    if (!jpg.isFile || entry.lastModified() < before) {
                        if (entry.exists() && !entry.delete()) onCleanupFailure()
                    }
                }
            }
        }
    }
}
