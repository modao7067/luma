package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.i18n.tr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.util.LruCache
import androidx.compose.ui.unit.IntSize
import com.caverock.androidsvg.SVG
import io.github.modao7067.luma.gui.runtime.SessionMediaResolver
import io.github.modao7067.luma.gui.session.ResolvedMedia
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class MediaFrame(
    val bitmap: Bitmap,
    val pageCount: Int = 1,
    val intrinsicWidth: Int = bitmap.width,
    val intrinsicHeight: Int = bitmap.height,
)

internal data class MediaFrameKey(
    val owner: String,
    val sessionId: String,
    val cwd: String,
    val source: String,
    val resolvedFileStamp: String,
    val maxDimension: Int,
    val page: Int,
)

internal fun resolvedFileStamp(media: ResolvedMedia): String {
    val uri = Uri.parse(media.uri)
    val file = uri.path?.takeIf { uri.scheme == "file" }?.let(::File)
    return if (file != null && file.isFile) {
        "${media.uri}|${file.length()}|${file.lastModified()}"
    } else {
        "${media.uri}|${media.sizeBytes}"
    }
}

internal class BoundedMediaFrameCache(
    thumbnailBudgetBytes: Int = THUMBNAIL_BUDGET_BYTES,
    fullscreenBudgetBytes: Int = FULLSCREEN_BUDGET_BYTES,
) {
    private fun createCache(budgetBytes: Int): LruCache<MediaFrameKey, MediaFrame> =
        object : LruCache<MediaFrameKey, MediaFrame>(budgetBytes.coerceAtLeast(1)) {
            override fun sizeOf(key: MediaFrameKey, value: MediaFrame): Int =
                value.bitmap.allocationByteCount.coerceAtLeast(1)
        }

    private val thumbnailPool = createCache(thumbnailBudgetBytes)
    private val fullscreenPool = createCache(fullscreenBudgetBytes)

    private fun poolFor(maxDimension: Int): LruCache<MediaFrameKey, MediaFrame> =
        if (maxDimension >= 2048) fullscreenPool else thumbnailPool

    fun get(key: MediaFrameKey): MediaFrame? =
        poolFor(key.maxDimension).get(key)?.takeUnless { it.bitmap.isRecycled }

    fun put(key: MediaFrameKey, frame: MediaFrame) {
        if (frame.bitmap.isRecycled) return
        poolFor(key.maxDimension).put(key, frame)
    }

    fun findPreviewFrame(
        owner: String,
        sessionId: String,
        cwd: String,
        source: String,
        page: Int,
    ): MediaFrame? {
        val entries = thumbnailPool.snapshot().entries
        var best: MediaFrame? = null
        var bestDim = -1
        for ((key, frame) in entries) {
            if (frame.bitmap.isRecycled) continue
            if (key.owner == owner &&
                key.sessionId == sessionId &&
                key.cwd == cwd &&
                key.source == source &&
                key.page == page &&
                key.maxDimension < 2048
            ) {
                if (key.maxDimension >= bestDim) {
                    best = frame
                    bestDim = key.maxDimension
                }
            }
        }
        return best
    }

    fun findExactFrame(
        owner: String,
        sessionId: String,
        cwd: String,
        source: String,
        maxDimension: Int,
        page: Int,
    ): MediaFrame? {
        val entries = poolFor(maxDimension).snapshot().entries
        var match: MediaFrame? = null
        for ((key, frame) in entries) {
            if (frame.bitmap.isRecycled) continue
            if (key.owner == owner &&
                key.sessionId == sessionId &&
                key.cwd == cwd &&
                key.source == source &&
                key.maxDimension == maxDimension &&
                key.page == page
            ) {
                match = frame
            }
        }
        return match
    }

    fun clear() {
        thumbnailPool.evictAll()
        fullscreenPool.evictAll()
    }

    companion object {
        internal const val THUMBNAIL_BUDGET_BYTES = 24 * 1024 * 1024
        internal const val FULLSCREEN_BUDGET_BYTES = 64 * 1024 * 1024
    }
}

internal object MediaFrameCache {
    private val delegate = BoundedMediaFrameCache()
    fun get(key: MediaFrameKey): MediaFrame? = delegate.get(key)
    fun put(key: MediaFrameKey, frame: MediaFrame) = delegate.put(key, frame)
    fun findExactFrame(owner: String, sessionId: String, cwd: String, source: String, maxDimension: Int, page: Int): MediaFrame? =
        delegate.findExactFrame(owner, sessionId, cwd, source, maxDimension, page)
    fun findPreviewFrame(owner: String, sessionId: String, cwd: String, source: String, page: Int): MediaFrame? =
        delegate.findPreviewFrame(owner, sessionId, cwd, source, page)
    fun clear() = delegate.clear()
}

internal fun decodeMediaFrame(context: Context, media: ResolvedMedia, maxDimension: Int, page: Int): MediaFrame {
    val pin = SessionMediaResolver.pinUri(media.uri)
    try {
        val uri = Uri.parse(media.uri)
        val limit = maxDimension.coerceIn(64, 2048)
        if (media.mimeType == "application/pdf") {
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: error(tr("无法读取 PDF", "Unable to read PDF"))
            descriptor.use { fd -> PdfRenderer(fd).use { pdf ->
                require(pdf.pageCount > 0) { tr("PDF 没有页面", "PDF has no pages") }
                pdf.openPage(page.coerceIn(0, pdf.pageCount - 1)).use { sheet ->
                    val size = decodedVectorSize(sheet.width, sheet.height, limit)
                    val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    sheet.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return MediaFrame(bitmap, pdf.pageCount)
                }
            } }
        }
        if (media.mimeType == "image/svg+xml") {
            SVG.setInternalEntitiesEnabled(false)
            val svg = context.contentResolver.openInputStream(uri)?.use { SVG.getFromInputStream(it) } ?: error(tr("无法读取 SVG", "Unable to read SVG"))
            val box = svg.documentViewBox
            val width = svg.documentWidth.takeIf { it > 0f } ?: box?.width() ?: 512f
            val height = svg.documentHeight.takeIf { it > 0f } ?: box?.height() ?: 512f
            val intrinsicW = width.roundToInt().coerceAtLeast(1)
            val intrinsicH = height.roundToInt().coerceAtLeast(1)
            val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
            val size = decodedVectorSize(intrinsicW, intrinsicH, limit, density)
            val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
            if (box == null) svg.setDocumentViewBox(0f, 0f, width, height)
            svg.setDocumentWidth(size.width.toFloat()); svg.setDocumentHeight(size.height.toFloat())
            svg.renderToCanvas(android.graphics.Canvas(bitmap))
            return MediaFrame(bitmap, intrinsicWidth = intrinsicW, intrinsicHeight = intrinsicH)
        }
        var rawW = 0
        var rawH = 0
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            rawW = info.size.width
            rawH = info.size.height
            val size = decodedMediaSize(info.size.width, info.size.height, limit)
            decoder.setTargetSize(size.width, size.height)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
        }
        return MediaFrame(
            bitmap,
            intrinsicWidth = if (rawW > 0) rawW else bitmap.width,
            intrinsicHeight = if (rawH > 0) rawH else bitmap.height,
        )
    } finally {
        SessionMediaResolver.unpinPath(pin)
    }
}

internal fun boundedMediaSize(width: Int, height: Int, limit: Int): IntSize {
    require(width > 0 && height > 0)
    val scale = min(1f, limit.toFloat() / max(width, height))
    return IntSize(max(1, (width * scale).roundToInt()), max(1, (height * scale).roundToInt()))
}

internal fun decodedMediaSize(width: Int, height: Int, requestedEdge: Int): IntSize {
    if (requestedEdge < 2048) return boundedMediaSize(width, height, requestedEdge)
    require(width > 0 && height > 0)
    val factor = minOf(1.0, sqrt(8_388_608.0 / (width.toDouble() * height)), 16_384.0 / max(width, height))
    return IntSize(max(1, (width * factor).toInt()), max(1, (height * factor).toInt()))
}
internal fun decodedVectorSize(width: Int, height: Int, requestedEdge: Int, density: Float = 1f): IntSize {
    if (requestedEdge < 2048) {
        val factor = density.coerceAtLeast(1f)
        val scaledW = (width * factor).roundToInt().coerceAtLeast(1)
        val scaledH = (height * factor).roundToInt().coerceAtLeast(1)
        return boundedMediaSize(scaledW, scaledH, requestedEdge)
    }
    val up = max(1.0, requestedEdge.toDouble() / max(width, height))
    return decodedMediaSize((width * up).toInt(), (height * up).toInt(), requestedEdge)
}

