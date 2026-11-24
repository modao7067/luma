@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package io.github.modao7067.luma.gui.ui

import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.modifiers.SelectionController
import androidx.compose.foundation.text.selection.LocalSelectionRegistrar
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.getTextLayoutResult
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.MultiParagraph
import androidx.compose.ui.text.MultiParagraphIntrinsics
import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.ParagraphInfo
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextGranularity
import androidx.compose.ui.text.TextInclusionStrategy
import androidx.compose.ui.text.TextLayoutInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import java.lang.reflect.Field
import java.lang.reflect.Proxy
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal class TypesetParagraphMetrics(
    val layout: TypesetLayout,
    private val styles: List<TypesetResolvedStyle>,
    placeholderRangeStarts: List<Int>,
) {
    val width: Float get() = layout.width
    val height: Float get() = layout.height
    val minIntrinsicWidth: Float get() = layout.minIntrinsicWidth
    val maxIntrinsicWidth: Float get() = layout.maxIntrinsicWidth
    val firstBaseline: Float get() = layout.lines.firstOrNull()?.baseline ?: 0f
    val lastBaseline: Float get() = layout.lines.lastOrNull()?.baseline ?: 0f
    val didExceedMaxLines: Boolean get() = false
    val lineCount: Int get() = layout.lines.size
    val placeholderRects: List<Rect?> = placeholderRangeStarts.map { startIdx ->
        layout.placeholders.firstOrNull { it.charIndex == startIdx }?.let {
            Rect(it.left, it.top, it.right, it.bottom)
        }
    }

    fun getLineLeft(lineIndex: Int): Float = safeLine(lineIndex).left
    fun getLineRight(lineIndex: Int): Float = safeLine(lineIndex).right
    fun getLineTop(lineIndex: Int): Float = safeLine(lineIndex).top
    fun getLineBaseline(lineIndex: Int): Float = safeLine(lineIndex).baseline
    fun getLineBottom(lineIndex: Int): Float = safeLine(lineIndex).bottom
    fun getLineHeight(lineIndex: Int): Float = safeLine(lineIndex).let { it.bottom - it.top }
    fun getLineWidth(lineIndex: Int): Float = safeLine(lineIndex).let { it.right - it.left }
    fun getLineStart(lineIndex: Int): Int = safeLine(lineIndex).charStart
    fun getLineEnd(lineIndex: Int, visibleEnd: Boolean): Int {
        val ln = safeLine(lineIndex)
        return if (visibleEnd) ln.visibleEnd else ln.charEnd
    }

    fun getLineForOffset(offset: Int): Int {
        val len = layout.text.length
        if (len == 0) return 0
        if (offset <= 0) return 0
        if (offset >= len) return layout.lines.lastIndex
        return layout.charLine[offset].coerceIn(0, layout.lines.lastIndex)
    }

    fun getHorizontalPosition(offset: Int): Float {
        val len = layout.text.length
        if (len == 0 || offset <= 0) return layout.lines.firstOrNull()?.left ?: 0f
        if (offset >= len) return layout.lines.lastOrNull()?.right ?: 0f
        val lineIdx = layout.charLine[offset].coerceIn(0, layout.lines.lastIndex)
        val line = layout.lines[lineIdx]
        if (offset >= line.visibleEnd) return line.right
        return layout.charLeft[offset]
    }

    fun getLineForVerticalPosition(vertical: Float): Int {
        val lines = layout.lines
        if (lines.isEmpty() || vertical <= lines.first().top) return 0
        if (vertical >= lines.last().bottom) return lines.lastIndex
        var lo = 0
        var hi = lines.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val ln = lines[mid]
            when {
                vertical < ln.top -> hi = mid - 1
                vertical >= ln.bottom -> lo = mid + 1
                else -> return mid
            }
        }
        return lo.coerceIn(0, lines.lastIndex)
    }

    fun getOffsetForPosition(position: Offset): Int {
        val len = layout.text.length
        if (len == 0) return 0
        val line = safeLine(getLineForVerticalPosition(position.y))
        if (line.visibleEnd <= line.charStart) return line.charStart.coerceIn(0, len)
        if (position.x <= line.left) return line.charStart
        if (position.x >= line.right) return line.visibleEnd.coerceIn(0, len)
        for (c in line.charStart until line.visibleEnd) {
            val mid = (layout.charLeft[c] + layout.charRight[c]) * 0.5f
            if (position.x <= mid) return c
        }
        return line.visibleEnd.coerceIn(0, len)
    }

    fun getBoundingBox(offset: Int): Rect {
        val len = layout.text.length
        if (len == 0) {
            val ln = safeLine(0)
            return Rect(0f, ln.top, 0f, ln.bottom)
        }
        val idx = offset.coerceIn(0, len - 1)
        val ln = safeLine(layout.charLine[idx])
        val l = layout.charLeft[idx]
        val r = max(l, layout.charRight[idx])
        return Rect(l, ln.top, r, ln.bottom)
    }

    fun getCursorRect(offset: Int): Rect {
        val len = layout.text.length
        val clamped = offset.coerceIn(0, len)
        val ln = safeLine(getLineForOffset(clamped))
        val x = getHorizontalPosition(clamped)
        return Rect(x, ln.top, x, ln.bottom)
    }

    fun getPathForRange(start: Int, end: Int): Path {
        val path = Path()
        val len = layout.text.length
        val s = start.coerceIn(0, len)
        val e = end.coerceIn(s, len)
        if (e <= s || len == 0) return path
        val startLine = getLineForOffset(s)
        val endLine = getLineForOffset((e - 1).coerceAtLeast(0))
        for (lineIdx in startLine..endLine) {
            val ln = safeLine(lineIdx)
            val segStart = max(s, ln.charStart)
            val segEnd = min(e, ln.visibleEnd)
            if (segEnd > segStart) {
                val l = layout.charLeft[segStart]
                val r = max(l, layout.charRight[segEnd - 1])
                path.addRect(Rect(l, ln.top, r, ln.bottom))
            } else if (ln.charEnd > ln.charStart && e > ln.charStart && s < ln.charEnd) {
                path.addRect(Rect(ln.left, ln.top, ln.left + 4f, ln.bottom))
            }
        }
        return path
    }

    fun fillBoundingBoxes(range: TextRange, array: FloatArray, arrayStart: Int) {
        val len = layout.text.length
        val s = range.min.coerceIn(0, len)
        val e = range.max.coerceIn(s, len)
        var out = arrayStart
        for (c in s until e) {
            val rect = getBoundingBox(c)
            if (out + 3 >= array.size) break
            array[out] = rect.left
            array[out + 1] = rect.top
            array[out + 2] = rect.right
            array[out + 3] = rect.bottom
            out += 4
        }
    }

    fun getRangeForRect(
        rect: Rect,
        granularity: TextGranularity,
        inclusionStrategy: TextInclusionStrategy,
    ): TextRange {
        val len = layout.text.length
        if (len == 0) return TextRange.Zero
        var first = -1
        var last = -1
        for (c in 0 until len) {
            val box = getBoundingBox(c)
            if (inclusionStrategy.isIncluded(box, rect)) {
                if (first == -1) first = c
                last = c + 1
            }
        }
        if (first == -1) return TextRange.Zero
        return if (granularity == TextGranularity.Word) {
            val ws = getWordBoundary(first)
            val we = getWordBoundary((last - 1).coerceAtLeast(first))
            TextRange(min(ws.min, first), max(we.max, last))
        } else {
            TextRange(first, last)
        }
    }

    fun getWordBoundary(offset: Int): TextRange {
        val text = layout.text
        val len = text.length
        if (len == 0) return TextRange.Zero
        val idx = offset.coerceIn(0, len - 1)
        val ch = text[idx]
        if (isAsciiWordChar(ch) || (ch.isLetterOrDigit() && !TypesetEngine.isCjkIdeograph(ch))) {
            var s = idx
            while (s > 0 && isAsciiWordChar(text[s - 1])) s--
            var e = idx + 1
            while (e < len && isAsciiWordChar(text[e])) e++
            return TextRange(s, e)
        }
        val iterator = BreakIterator.getWordInstance(Locale.getDefault())
        iterator.setText(text)
        val start = if (iterator.isBoundary(idx)) idx else iterator.preceding(idx).coerceAtLeast(0)
        val end = iterator.following(idx).let { if (it == BreakIterator.DONE) len else it }.coerceIn(start, len)
        return if (end > start) TextRange(start, end) else TextRange(idx, (idx + 1).coerceAtMost(len))
    }

    fun paintCanvas(canvas: Canvas, color: Color) {
        val overrideArgb = if (color != Color.Unspecified) color.toArgb() else 0
        drawTypesetLayout(canvas, layout, styles, overrideArgb)
    }

    fun asParagraph(): Paragraph = Proxy.newProxyInstance(
        Paragraph::class.java.classLoader,
        arrayOf(Paragraph::class.java),
    ) { proxy, method, args ->
        val base = method.name.substringBefore('-')
        when (base) {
            "getWidth" -> width
            "getHeight" -> height
            "getMinIntrinsicWidth" -> minIntrinsicWidth
            "getMaxIntrinsicWidth" -> maxIntrinsicWidth
            "getFirstBaseline" -> firstBaseline
            "getLastBaseline" -> lastBaseline
            "getDidExceedMaxLines" -> didExceedMaxLines
            "getLineCount" -> lineCount
            "getPlaceholderRects" -> placeholderRects
            "getPathForRange" -> getPathForRange(args[0] as Int, args[1] as Int)
            "getCursorRect" -> getCursorRect(args[0] as Int)
            "getLineLeft" -> getLineLeft(args[0] as Int)
            "getLineRight" -> getLineRight(args[0] as Int)
            "getLineTop" -> getLineTop(args[0] as Int)
            "getLineBaseline" -> getLineBaseline(args[0] as Int)
            "getLineBottom" -> getLineBottom(args[0] as Int)
            "getLineHeight" -> getLineHeight(args[0] as Int)
            "getLineWidth" -> getLineWidth(args[0] as Int)
            "getLineStart" -> getLineStart(args[0] as Int)
            "getLineEnd" -> getLineEnd(args[0] as Int, args[1] as Boolean)
            "isLineEllipsized" -> false
            "getLineForOffset" -> getLineForOffset(args[0] as Int)
            "getHorizontalPosition" -> getHorizontalPosition(args[0] as Int)
            "getParagraphDirection" -> ResolvedTextDirection.Ltr
            "getBidiRunDirection" -> ResolvedTextDirection.Ltr
            "getLineForVerticalPosition" -> getLineForVerticalPosition(args[0] as Float)
            "getOffsetForPosition" -> getOffsetForPosition(Offset(args[0] as Long))
            "getRangeForRect" -> {
                val rect = args[0] as Rect
                val gran = if ((args[1] as Int) == 1) TextGranularity.Word else TextGranularity.Character
                val strategy = args[2] as TextInclusionStrategy
                getRangeForRect(rect, gran, strategy).packedValue
            }
            "getBoundingBox" -> getBoundingBox(args[0] as Int)
            "fillBoundingBoxes" -> {
                val packed = args[0] as Long
                val range = TextRange((packed ushr 32).toInt(), (packed and 0xFFFFFFFFL).toInt())
                fillBoundingBoxes(range, args[1] as FloatArray, args[2] as Int)
                Unit
            }
            "getWordBoundary" -> getWordBoundary(args[0] as Int).packedValue
            "paint" -> {
                val canvas = args[0] as Canvas
                val color = (args.getOrNull(1) as? Long)?.let { Color(it.toULong()) } ?: Color.Unspecified
                paintCanvas(canvas, color)
                Unit
            }
            "equals" -> proxy === args?.getOrNull(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "TypesetParagraph(lines=$lineCount)"
            else -> null
        }
    } as Paragraph

    private fun safeLine(index: Int): TypesetLine {
        val lines = layout.lines
        return lines[index.coerceIn(0, lines.lastIndex)]
    }

    private fun isAsciiWordChar(ch: Char): Boolean =
        (ch in 'a'..'z') || (ch in 'A'..'Z') || (ch in '0'..'9') || ch == '_'
}

private object MultiParagraphBinder {
    private val emptyAnnotated = AnnotatedString("")
    private val fields: Fields? = runCatching {
        val cls = MultiParagraph::class.java
        Fields(
            intrinsics = cls.getDeclaredField("intrinsics").apply { isAccessible = true },
            width = cls.getDeclaredField("width").apply { isAccessible = true },
            height = cls.getDeclaredField("height").apply { isAccessible = true },
            lineCount = cls.getDeclaredField("lineCount").apply { isAccessible = true },
            placeholderRects = cls.getDeclaredField("placeholderRects").apply { isAccessible = true },
            paragraphInfoList = cls.getDeclaredField("paragraphInfoList").apply { isAccessible = true },
        )
    }.getOrNull()

    private class Fields(
        val intrinsics: Field,
        val width: Field,
        val height: Field,
        val lineCount: Field,
        val placeholderRects: Field,
        val paragraphInfoList: Field,
    )

    fun buildTextLayoutResult(
        annotated: AnnotatedString,
        style: TextStyle,
        placeholders: List<AnnotatedString.Range<Placeholder>>,
        constraints: Constraints,
        density: Density,
        layoutDirection: LayoutDirection,
        fontFamilyResolver: FontFamily.Resolver,
        metrics: TypesetParagraphMetrics,
    ): TextLayoutResult {
        val layoutInput = TextLayoutInput(
            text = annotated,
            style = style,
            placeholders = placeholders,
            maxLines = Int.MAX_VALUE,
            softWrap = true,
            overflow = TextOverflow.Clip,
            density = density,
            layoutDirection = layoutDirection,
            fontFamilyResolver = fontFamilyResolver,
            constraints = constraints,
        )
        val widthPx = ceil(metrics.width).toInt().coerceIn(constraints.minWidth, constraints.maxWidth)
        val heightPx = ceil(metrics.height).toInt().coerceIn(constraints.minHeight, constraints.maxHeight)
        val boundFields = fields
        if (boundFields != null) {
            val wrapped = runCatching {
                val realIntrinsics = MultiParagraphIntrinsics(
                    annotatedString = annotated,
                    style = style,
                    placeholders = placeholders,
                    density = density,
                    fontFamilyResolver = fontFamilyResolver,
                    softWrap = true,
                )
                val shell = MultiParagraph(
                    intrinsics = MultiParagraphIntrinsics(
                        annotatedString = emptyAnnotated,
                        style = style,
                        placeholders = emptyList(),
                        density = density,
                        fontFamilyResolver = fontFamilyResolver,
                        softWrap = true,
                    ),
                    constraints = Constraints(maxWidth = 1),
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
                val info = ParagraphInfo(
                    paragraph = metrics.asParagraph(),
                    startIndex = 0,
                    endIndex = annotated.length,
                    startLineIndex = 0,
                    endLineIndex = metrics.lineCount,
                    top = 0f,
                    bottom = metrics.height,
                )
                boundFields.intrinsics.set(shell, realIntrinsics)
                boundFields.width.setFloat(shell, metrics.width)
                boundFields.height.setFloat(shell, metrics.height)
                boundFields.lineCount.setInt(shell, metrics.lineCount)
                boundFields.placeholderRects.set(shell, metrics.placeholderRects)
                boundFields.paragraphInfoList.set(shell, listOf(info))
                TextLayoutResult(layoutInput, shell, IntSize(widthPx, heightPx))
            }.getOrNull()
            if (wrapped != null) return wrapped
        }
        val fallback = MultiParagraph(
            annotatedString = annotated,
            style = style,
            placeholders = placeholders,
            constraints = Constraints(maxWidth = constraints.maxWidth),
            density = density,
            fontFamilyResolver = fontFamilyResolver,
            maxLines = Int.MAX_VALUE,
            overflow = TextOverflow.Clip,
        )
        return TextLayoutResult(
            layoutInput,
            fallback,
            IntSize(
                ceil(fallback.width).toInt().coerceIn(constraints.minWidth, constraints.maxWidth),
                ceil(fallback.height).toInt().coerceIn(constraints.minHeight, constraints.maxHeight),
            ),
        )
    }
}

private data class TypesetCacheKey(
    val text: String,
    val spanSignature: Int,
    val styleSignature: Int,
    val widthPx: Int,
    val mode: TypesetMode,
    val densityKey: Int,
    val inlineSignature: Int,
)

private data class TypesetCachedEntry(
    val prepared: TypesetPreparedInput,
    val layout: TypesetLayout,
)

internal const val TYPESET_CACHE_MAX_CHARS = 256 * 1024
internal const val TYPESET_CACHE_MAX_ENTRY_CHARS = 32 * 1024

internal fun isTypesetCacheable(mode: TypesetMode, textLength: Int): Boolean =
    mode != TypesetMode.STREAMING && textLength <= TYPESET_CACHE_MAX_ENTRY_CHARS

private val typesetLayoutCache = object : LruCache<TypesetCacheKey, TypesetCachedEntry>(TYPESET_CACHE_MAX_CHARS) {
    override fun sizeOf(key: TypesetCacheKey, value: TypesetCachedEntry): Int =
        key.text.length.coerceAtLeast(1)
}

internal fun typesetCacheSizeChars(): Int = synchronized(typesetLayoutCache) { typesetLayoutCache.size() }

internal fun clearTypesetCache() {
    synchronized(typesetLayoutCache) { typesetLayoutCache.evictAll() }
}

private const val INLINE_ANNOTATION_TAG = "androidx.compose.foundation.text.inlineContent"
private const val STREAMING_SELECTION_SYNC_INTERVAL_NANOS = 250_000_000L

@Composable
internal fun TypesetText(
    text: AnnotatedString,
    style: TextStyle,
    color: Color,
    mode: TypesetMode,
    codeFontFamily: FontFamily?,
    inlineFormulas: Map<String, RenderedFormula> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val stateHolder = remember { TypesetRenderState() }
    val usePlatformBidi = layoutDirection == LayoutDirection.Rtl ||
        remember(text.text, stateHolder) {
            val raw = text.text
            val prev = stateHolder.lastBidiText
            val result = if (prev.isNotEmpty() && raw.length >= prev.length && raw.startsWith(prev)) {
                stateHolder.lastBidiRequired || TypesetEngine.requiresPlatformBidi(raw, prev.length)
            } else {
                TypesetEngine.requiresPlatformBidi(raw)
            }
            stateHolder.lastBidiText = raw
            stateHolder.lastBidiRequired = result
            result
        }
    if (usePlatformBidi) {
        val fallbackInlineContent = remember(inlineFormulas, density) {
            if (inlineFormulas.isEmpty()) emptyMap()
            else inlineFormulas.mapValues { (_, formula) ->
                InlineTextContent(
                    Placeholder(
                        width = with(density) { formula.widthPx.toSp() },
                        height = with(density) { formula.heightPx.toSp() },
                        placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                    ),
                ) {
                    Image(
                        bitmap = formula.bitmap,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        Text(
            text = text,
            modifier = modifier,
            color = color,
            style = style,
            inlineContent = fallbackInlineContent,
        )
        return
    }
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val uriHandler = LocalUriHandler.current
    val selectionRegistrar = LocalSelectionRegistrar.current
    val selectionColors = LocalTextSelectionColors.current
    val pinnableContainer = LocalPinnableContainer.current

    val inlineSpecs = remember(inlineFormulas) {
        if (inlineFormulas.isEmpty()) emptyMap()
        else inlineFormulas.mapValues { (_, f) ->
            TypesetInlineObjectSpec(
                widthPx = f.widthPx.toFloat(),
                heightPx = f.heightPx.toFloat(),
                baselineFraction = f.baselineFraction,
            )
        }
    }

    val placeholderRanges = remember(text, inlineFormulas, density) {
        if (inlineFormulas.isEmpty()) emptyList()
        else text.getStringAnnotations(INLINE_ANNOTATION_TAG, 0, text.length).mapNotNull { ann ->
            val formula = inlineFormulas[ann.item] ?: return@mapNotNull null
            AnnotatedString.Range(
                item = Placeholder(
                    width = with(density) { formula.widthPx.toSp() },
                    height = with(density) { formula.heightPx.toSp() },
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                ),
                start = ann.start,
                end = ann.end,
            )
        }
    }

    val placeholderRangeStarts = remember(placeholderRanges) {
        placeholderRanges.map { it.start }
    }

    val selectionController = remember(selectionRegistrar, selectionColors.backgroundColor) {
        selectionRegistrar?.let { registrar ->
            SelectionController(
                selectableId = registrar.nextSelectableId(),
                selectionRegistrar = registrar,
                backgroundSelectionColor = selectionColors.backgroundColor,
            )
        }
    }

    if (selectionController != null) {
        SideEffect {
            selectionController.updatePinnableContainer(pinnableContainer)
        }
        DisposableEffect(selectionController) {
            selectionController.onPlaced()
            onDispose { selectionController.onUnplaced() }
        }
        LaunchedEffect(selectionController, stateHolder) {
            for (ignored in stateHolder.selectionSyncTrigger) {
                val elapsedNanos = System.nanoTime() - stateHolder.lastSelectionSyncNanos
                val remainingMs = ((STREAMING_SELECTION_SYNC_INTERVAL_NANOS - elapsedNanos) / 1_000_000L)
                    .coerceIn(1L, STREAMING_SELECTION_SYNC_INTERVAL_NANOS / 1_000_000L)
                delay(remainingMs)
                if (stateHolder.selectionLayoutDirty) {
                    if (!stateHolder.syncSelectionLayout(selectionController, System.nanoTime())) {
                        stateHolder.selectionLayoutDirty = false
                    }
                }
            }
        }
    }

    val latestText by rememberUpdatedState(text)
    val latestUriHandler by rememberUpdatedState(uriHandler)
    val textMeta = remember(text) {
        val links = text.getLinkAnnotations(0, text.length)
        var h = text.spanStyles.hashCode()
        h = 31 * h + links.hashCode()
        Pair(links.isNotEmpty(), h)
    }
    val hasLinks = textMeta.first
    val spanSignature = textMeta.second

    val styleSignature = remember(style, color, codeFontFamily) {
        var h = style.hashCode()
        h = 31 * h + color.hashCode()
        h = 31 * h + (codeFontFamily?.hashCode() ?: 0)
        h
    }
    val inlineSignature = remember(text, inlineSpecs) {
        var h = inlineSpecs.hashCode()
        if (inlineSpecs.isNotEmpty()) {
            h = 31 * h + text.getStringAnnotations(INLINE_ANNOTATION_TAG, 0, text.length).hashCode()
        }
        h
    }
    val densityKey = remember(density) {
        (density.density * 100f).roundToInt() * 31 + (density.fontScale * 100f).roundToInt()
    }
    val reusableMeasurer = remember(styleSignature, densityKey, fontFamilyResolver) {
        AndroidTypesetMeasurer()
    }

    val linkTapModifier = if (hasLinks) {
        Modifier.pointerInput(stateHolder) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                var tap = true
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    if (event.changes.size != 1) tap = false
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                        tap = false
                    }
                    if (change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) {
                        tap = false
                    }
                    if (change.changedToUpIgnoreConsumed()) {
                        if (tap && !change.isConsumed) {
                            val currentResult = stateHolder.resolveTextLayoutResult()
                            if (currentResult != null) {
                                val lineIdx = currentResult.getLineForVerticalPosition(change.position.y)
                                val withinLineX = change.position.x >= currentResult.getLineLeft(lineIdx) - 8f &&
                                    change.position.x <= currentResult.getLineRight(lineIdx) + 8f
                                if (withinLineX) {
                                    val offset = currentResult.getOffsetForPosition(change.position)
                                    val hit = latestText.getLinkAnnotations(offset, offset).firstOrNull()
                                        ?: if (offset > 0) latestText.getLinkAnnotations(offset - 1, offset).firstOrNull() else null
                                    if (hit != null) {
                                        change.consume()
                                        when (val ann = hit.item) {
                                            is LinkAnnotation.Url -> runCatching { latestUriHandler.openUri(ann.url) }
                                            is LinkAnnotation.Clickable -> ann.linkInteractionListener?.onClick(ann)
                                        }
                                    }
                                }
                            }
                        }
                        break
                    }
                }
            }
        }
    } else {
        Modifier
    }

    val composedModifier = modifier
        .then(selectionController?.modifier ?: Modifier)
        .then(linkTapModifier)
        .then(
            if (selectionController != null) {
                Modifier.onGloballyPositioned { coords ->
                    selectionController.updateLayoutCoordinates(coords)
                }
            } else {
                Modifier
            },
        )
        .semantics {
            this.text = text
            getTextLayoutResult { results ->
                val result = stateHolder.resolveTextLayoutResult()
                if (result != null) {
                    results.add(result)
                    true
                } else {
                    false
                }
            }
        }
        .drawBehind {
            stateHolder.drawRevision
            selectionController?.draw(this)
            val layout = stateHolder.layout
            val styles = stateHolder.styles
            if (layout != null && styles != null) {
                drawTypesetLayout(drawContext.canvas, layout, styles, drawCache = stateHolder.drawCache)
            }
        }

    val orderedInlineEntries = remember(text, inlineFormulas) {
        if (inlineFormulas.isEmpty()) emptyList()
        else text.getStringAnnotations(INLINE_ANNOTATION_TAG, 0, text.length).mapNotNull { ann ->
            val formula = inlineFormulas[ann.item] ?: return@mapNotNull null
            Triple(ann.start, ann.item, formula)
        }
    }

    Layout(
        modifier = composedModifier,
        content = {
            orderedInlineEntries.forEach { (_, _, formula) ->
                Box {
                    Image(
                        bitmap = formula.bitmap,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val maxWidthPx = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        val maxWidthFloat = if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else Float.POSITIVE_INFINITY
        val cacheKey = TypesetCacheKey(
            text = text.text,
            spanSignature = spanSignature,
            styleSignature = styleSignature,
            widthPx = maxWidthPx,
            mode = mode,
            densityKey = densityKey,
            inlineSignature = inlineSignature,
        )
        val cacheable = isTypesetCacheable(mode, text.text.length)
        val entry = if (stateHolder.lastCacheKey == cacheKey) {
            stateHolder.lastEntry
        } else {
            null
        } ?: (if (cacheable) synchronized(typesetLayoutCache) { typesetLayoutCache.get(cacheKey) } else null)
            ?: run {
                val prepared = prepareTypesetInput(
                    annotated = text,
                    baseStyle = style,
                    defaultColor = color,
                    codeFontFamily = codeFontFamily,
                    inlineFormulas = inlineSpecs,
                    density = density,
                    fontFamilyResolver = fontFamilyResolver,
                    styleCache = reusableMeasurer.cache,
                )
                val measurer = reusableMeasurer.bind(prepared)
                val prevStreaming = if (mode == TypesetMode.STREAMING) stateHolder.lastStreamingEntry else null
                val canReuseStreaming = prevStreaming != null &&
                    stateHolder.lastStreamingMaxWidthPx == maxWidthFloat &&
                    prevStreaming.prepared.baseLineMetrics == prepared.baseLineMetrics &&
                    stylesMatchPrefix(prevStreaming.prepared.styles, prepared.styles)
                val computed = TypesetEngine.layout(
                    text = prepared.text,
                    runs = prepared.runs,
                    maxWidthPx = maxWidthFloat,
                    mode = mode,
                    measurer = measurer,
                    previousLayout = if (canReuseStreaming) prevStreaming.layout else null,
                    previousRuns = if (canReuseStreaming) prevStreaming.prepared.runs else null,
                )
                TypesetCachedEntry(prepared, computed).also {
                    if (cacheable) {
                        synchronized(typesetLayoutCache) { typesetLayoutCache.put(cacheKey, it) }
                    }
                }
            }
        stateHolder.lastCacheKey = cacheKey
        stateHolder.lastEntry = entry
        if (mode == TypesetMode.STREAMING) {
            stateHolder.lastStreamingEntry = entry
            stateHolder.lastStreamingMaxWidthPx = maxWidthFloat
        } else {
            stateHolder.lastStreamingEntry = null
            stateHolder.lastStreamingMaxWidthPx = Float.NaN
        }

        stateHolder.updatePendingLayoutInputs(
            annotated = text,
            style = style,
            placeholders = placeholderRanges,
            placeholderStarts = placeholderRangeStarts,
            constraints = constraints,
            density = density,
            layoutDirection = layoutDirection,
            fontFamilyResolver = fontFamilyResolver,
            cacheKey = cacheKey,
            entry = entry,
        )

        if (stateHolder.layout !== entry.layout || stateHolder.styles !== entry.prepared.styles) {
            stateHolder.layout = entry.layout
            stateHolder.styles = entry.prepared.styles
            stateHolder.drawRevision++
        }
        if (selectionController != null && !stateHolder.isSelectionLayoutSynced(selectionController, cacheKey, constraints)) {
            val now = System.nanoTime()
            val hasActiveSelection = selectionRegistrar?.subselections?.isNotEmpty() == true
            val needsSync = mode != TypesetMode.STREAMING ||
                !stateHolder.hasSyncedSelectionLayout ||
                stateHolder.lastSyncedController !== selectionController ||
                hasActiveSelection ||
                now - stateHolder.lastSelectionSyncNanos >= STREAMING_SELECTION_SYNC_INTERVAL_NANOS
            if (needsSync) {
                stateHolder.syncSelectionLayout(selectionController, now)
            } else {
                stateHolder.selectionLayoutDirty = true
                stateHolder.selectionSyncTrigger.trySend(Unit)
            }
        }

        val placeables = measurables.mapIndexed { idx, measurable ->
            val formula = orderedInlineEntries.getOrNull(idx)?.third
            val w = formula?.widthPx?.coerceAtLeast(1) ?: 1
            val h = formula?.heightPx?.coerceAtLeast(1) ?: 1
            measurable.measure(Constraints.fixed(w, h))
        }

        val width = ceil(entry.layout.width).toInt().coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = ceil(entry.layout.height).toInt().coerceIn(constraints.minHeight, constraints.maxHeight)
        val firstBaseline = (entry.layout.lines.firstOrNull()?.baseline ?: 0f).roundToInt()
        val lastBaseline = (entry.layout.lines.lastOrNull()?.baseline ?: 0f).roundToInt()

        layout(
            width = width,
            height = height,
            alignmentLines = mapOf(
                FirstBaseline to firstBaseline,
                LastBaseline to lastBaseline,
            ),
        ) {
            placeables.forEachIndexed { idx, placeable ->
                val charStart = orderedInlineEntries.getOrNull(idx)?.first ?: return@forEachIndexed
                val placement = entry.layout.placeholders.firstOrNull { it.charIndex == charStart }
                    ?: return@forEachIndexed
                placeable.placeRelative(placement.left.roundToInt(), placement.top.roundToInt())
            }
        }
    }
}

private fun stylesMatchPrefix(
    previous: List<TypesetResolvedStyle>,
    current: List<TypesetResolvedStyle>,
): Boolean {
    if (current.size < previous.size) return false
    for (i in previous.indices) {
        if (current[i] != previous[i]) return false
    }
    return true
}

private class TypesetRenderState {
    var lastCacheKey: TypesetCacheKey? = null
    var lastEntry: TypesetCachedEntry? = null
    var lastStreamingEntry: TypesetCachedEntry? = null
    var lastStreamingMaxWidthPx: Float = Float.NaN
    var lastBidiText: String = ""
    var lastBidiRequired: Boolean = false
    var layout: TypesetLayout? = null
    var styles: List<TypesetResolvedStyle>? = null
    var drawRevision by mutableIntStateOf(0)
    val drawCache = TypesetDrawCache()

    private var pendingAnnotated: AnnotatedString? = null
    private var pendingStyle: TextStyle? = null
    private var pendingPlaceholders: List<AnnotatedString.Range<Placeholder>> = emptyList()
    private var pendingPlaceholderStarts: List<Int> = emptyList()
    private var pendingConstraints: Constraints = Constraints()
    private var pendingDensity: Density? = null
    private var pendingLayoutDirection: LayoutDirection = LayoutDirection.Ltr
    private var pendingFontFamilyResolver: FontFamily.Resolver? = null
    private var pendingCacheKey: TypesetCacheKey? = null
    private var pendingEntry: TypesetCachedEntry? = null

    private var cachedResultKey: TypesetCacheKey? = null
    private var cachedResultConstraints: Constraints = Constraints()
    private var cachedResult: TextLayoutResult? = null
    var lastSelectionSyncNanos: Long = 0L
    var hasSyncedSelectionLayout: Boolean = false
    var selectionLayoutDirty: Boolean = false
    var lastSyncedController: SelectionController? = null
    private var lastSyncedCacheKey: TypesetCacheKey? = null
    private var lastSyncedConstraints: Constraints = Constraints()
    val selectionSyncTrigger = Channel<Unit>(Channel.CONFLATED)

    fun isSelectionLayoutSynced(
        controller: SelectionController,
        cacheKey: TypesetCacheKey,
        constraints: Constraints,
    ): Boolean = hasSyncedSelectionLayout &&
        lastSyncedController === controller &&
        lastSyncedCacheKey == cacheKey &&
        lastSyncedConstraints == constraints

    fun syncSelectionLayout(controller: SelectionController, nowNanos: Long): Boolean {
        val result = resolveTextLayoutResult() ?: return false
        controller.updateTextLayout(result)
        hasSyncedSelectionLayout = true
        lastSelectionSyncNanos = nowNanos
        lastSyncedController = controller
        lastSyncedCacheKey = pendingCacheKey
        lastSyncedConstraints = pendingConstraints
        selectionLayoutDirty = false
        return true
    }

    fun updatePendingLayoutInputs(
        annotated: AnnotatedString,
        style: TextStyle,
        placeholders: List<AnnotatedString.Range<Placeholder>>,
        placeholderStarts: List<Int>,
        constraints: Constraints,
        density: Density,
        layoutDirection: LayoutDirection,
        fontFamilyResolver: FontFamily.Resolver,
        cacheKey: TypesetCacheKey,
        entry: TypesetCachedEntry,
    ) {
        pendingAnnotated = annotated
        pendingStyle = style
        pendingPlaceholders = placeholders
        pendingPlaceholderStarts = placeholderStarts
        pendingConstraints = constraints
        pendingDensity = density
        pendingLayoutDirection = layoutDirection
        pendingFontFamilyResolver = fontFamilyResolver
        pendingCacheKey = cacheKey
        pendingEntry = entry
    }

    fun resolveTextLayoutResult(): TextLayoutResult? {
        val key = pendingCacheKey ?: return cachedResult
        val existing = cachedResult
        if (existing != null && cachedResultKey == key && cachedResultConstraints == pendingConstraints) {
            return existing
        }
        val annotated = pendingAnnotated ?: return existing
        val style = pendingStyle ?: return existing
        val density = pendingDensity ?: return existing
        val resolver = pendingFontFamilyResolver ?: return existing
        val entry = pendingEntry ?: return existing
        val metrics = TypesetParagraphMetrics(entry.layout, entry.prepared.styles, pendingPlaceholderStarts)
        val built = MultiParagraphBinder.buildTextLayoutResult(
            annotated = annotated,
            style = style,
            placeholders = pendingPlaceholders,
            constraints = pendingConstraints,
            density = density,
            layoutDirection = pendingLayoutDirection,
            fontFamilyResolver = resolver,
            metrics = metrics,
        )
        cachedResultKey = key
        cachedResultConstraints = pendingConstraints
        cachedResult = built
        return built
    }
}
