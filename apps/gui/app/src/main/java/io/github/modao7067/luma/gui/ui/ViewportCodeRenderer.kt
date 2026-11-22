package io.github.modao7067.luma.gui.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.packInts
import androidx.compose.ui.util.unpackInt1
import androidx.compose.ui.util.unpackInt2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.roundToInt

internal const val CODE_CHUNK_LINES = 16
internal const val CODE_CHUNK_MAX_CHARS = 4_096
internal const val CODE_WINDOW_THRESHOLD_LINES = 32
internal const val CODE_WINDOW_THRESHOLD_CHARS = 3_000
private val EMPTY_CHUNK_RANGE = packInts(-1, -1)

internal class CodeChunk(
    val index: Int,
    val startLine: Int,
    val endLineExclusive: Int,
    val startChar: Int,
    val endChar: Int,
    val visualCols: Int = -1,
) {
    val lineCount: Int get() = endLineExclusive - startLine
}

internal class CodeLineTable(
    val code: String,
    val lineStarts: IntArray,
    val lineEnds: IntArray,
    val lineVisualCols: IntArray,
    val chunks: List<CodeChunk>,
    val widestLineIndex: Int,
    val secondWidestLineIndex: Int,
) {
    val lineCount: Int get() = lineStarts.size
    val isShort: Boolean
        get() = lineCount <= CODE_WINDOW_THRESHOLD_LINES && code.length <= CODE_WINDOW_THRESHOLD_CHARS

    fun lineText(lineIndex: Int): String =
        code.substring(lineStarts[lineIndex], lineEnds[lineIndex])
}

private fun isDoubleWidthBmpCode(code: Int): Boolean =
    code in 0x1100..0x115F ||
        code in 0x2F00..0xA4CF ||
        code in 0xAC00..0xD7A3 ||
        code in 0xF900..0xFAFF ||
        code in 0xFE10..0xFE19 ||
        code in 0xFE30..0xFE6F ||
        code in 0xFF00..0xFF60 ||
        code in 0xFFE0..0xFFE6

internal fun estimateVisualColumns(text: CharSequence, start: Int, end: Int): Int {
    var cols = 0
    var i = start
    while (i < end) {
        val ch = text[i]
        cols += when {
            ch == '\t' -> 4
            ch.code < 0x80 -> 1
            Character.isHighSurrogate(ch) && i + 1 < end && Character.isLowSurrogate(text[i + 1]) -> {
                i++
                2
            }
            isDoubleWidthBmpCode(ch.code) -> 2
            else -> 1
        }
        i++
    }
    return cols
}

private fun findSafeChunkSplit(code: String, start: Int, maxEnd: Int, hardEnd: Int): Int {
    val target = maxEnd.coerceAtMost(hardEnd)
    if (target >= hardEnd) return hardEnd
    val searchMin = (target - 128).coerceAtLeast(start + 1)
    var preferred = -1
    var i = target
    while (i > searchMin) {
        val ch = code[i - 1]
        if (ch == ' ' || ch == '\t' || ch == ',' || ch == ';' || ch == ')' || ch == '}' || ch == ']') {
            preferred = i
            break
        }
        i--
    }
    var split = if (preferred > start) preferred else target
    if (split in (start + 1) until hardEnd &&
        Character.isHighSurrogate(code[split - 1]) &&
        Character.isLowSurrogate(code[split])
    ) {
        split--
    }
    if (split <= start) {
        split = if (start + 1 < hardEnd &&
            Character.isHighSurrogate(code[start]) &&
            Character.isLowSurrogate(code[start + 1])
        ) {
            start + 2
        } else {
            start + 1
        }
    }
    return split
}

internal fun buildCodeLineTable(code: String): CodeLineTable {
    val len = code.length
    var capacity = 32
    var starts = IntArray(capacity)
    var ends = IntArray(capacity)
    var cols = IntArray(capacity)
    var lineCount = 0
    var lineStart = 0
    var widestIdx = 0
    var widestCols = -1
    var secondWidestIdx = -1
    var secondWidestCols = -1

    fun pushLine(s: Int, e: Int) {
        if (lineCount == capacity) {
            capacity *= 2
            starts = starts.copyOf(capacity)
            ends = ends.copyOf(capacity)
            cols = cols.copyOf(capacity)
        }
        val visualCols = estimateVisualColumns(code, s, e)
        starts[lineCount] = s
        ends[lineCount] = e
        cols[lineCount] = visualCols
        if (visualCols > widestCols) {
            secondWidestCols = widestCols
            secondWidestIdx = widestIdx
            widestCols = visualCols
            widestIdx = lineCount
        } else if (visualCols > secondWidestCols) {
            secondWidestCols = visualCols
            secondWidestIdx = lineCount
        }
        lineCount++
    }

    var i = 0
    while (i < len) {
        if (code[i] == '\n') {
            val lineEnd = if (i > lineStart && code[i - 1] == '\r') i - 1 else i
            pushLine(lineStart, lineEnd)
            lineStart = i + 1
        }
        i++
    }
    val tailEnd = if (len > lineStart && code[len - 1] == '\r') len - 1 else len
    pushLine(lineStart, tailEnd)

    val outStarts = starts.copyOf(lineCount)
    val outEnds = ends.copyOf(lineCount)
    val outCols = cols.copyOf(lineCount)
    val chunks = ArrayList<CodeChunk>((lineCount + CODE_CHUNK_LINES - 1) / CODE_CHUNK_LINES)
    var chunkStartLine = 0
    var chunkIndex = 0
    while (chunkStartLine < lineCount) {
        val lineStartChar = outStarts[chunkStartLine]
        val lineEndChar = outEnds[chunkStartLine]
        val firstLineChars = lineEndChar - lineStartChar
        if (firstLineChars > CODE_CHUNK_MAX_CHARS) {
            var sliceStart = lineStartChar
            while (sliceStart < lineEndChar) {
                val sliceEnd = findSafeChunkSplit(code, sliceStart, sliceStart + CODE_CHUNK_MAX_CHARS, lineEndChar)
                val sliceCols = estimateVisualColumns(code, sliceStart, sliceEnd)
                chunks += CodeChunk(
                    index = chunkIndex++,
                    startLine = chunkStartLine,
                    endLineExclusive = chunkStartLine + 1,
                    startChar = sliceStart,
                    endChar = sliceEnd,
                    visualCols = sliceCols,
                )
                sliceStart = sliceEnd
            }
            chunkStartLine++
            continue
        }
        var chunkEndLine = chunkStartLine + 1
        var chunkChars = firstLineChars
        while (chunkEndLine < lineCount && chunkEndLine - chunkStartLine < CODE_CHUNK_LINES) {
            val nextChars = outEnds[chunkEndLine] - outStarts[chunkEndLine] + 1
            if (chunkChars + nextChars > CODE_CHUNK_MAX_CHARS) break
            chunkChars += nextChars
            chunkEndLine++
        }
        chunks += CodeChunk(
            index = chunkIndex++,
            startLine = chunkStartLine,
            endLineExclusive = chunkEndLine,
            startChar = lineStartChar,
            endChar = outEnds[chunkEndLine - 1],
        )
        chunkStartLine = chunkEndLine
    }
    return CodeLineTable(
        code = code,
        lineStarts = outStarts,
        lineEnds = outEnds,
        lineVisualCols = outCols,
        chunks = chunks,
        widestLineIndex = widestIdx,
        secondWidestLineIndex = secondWidestIdx,
    )
}

internal data class CodeTextMetrics(
    val oneLinePx: Int,
    val lineStepPx: Int,
    val charWidthPx: Float,
)

private data class CodeTextMetricsKey(
    val fontSize: TextUnit,
    val lineHeight: TextUnit,
    val fontFamilyHash: Int,
    val letterSpacing: TextUnit,
    val fontFeatureSettings: String?,
    val platformStyle: PlatformTextStyle?,
    val lineHeightStyle: LineHeightStyle?,
    val density: Float,
    val fontScale: Float,
)

private val codeTextMetricsCache = HashMap<CodeTextMetricsKey, CodeTextMetrics>(16)

@Composable
internal fun rememberCodeTextMetrics(style: TextStyle): CodeTextMetrics {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val key = CodeTextMetricsKey(
        fontSize = style.fontSize,
        lineHeight = style.lineHeight,
        fontFamilyHash = style.fontFamily?.hashCode() ?: 0,
        letterSpacing = style.letterSpacing,
        fontFeatureSettings = style.fontFeatureSettings,
        platformStyle = style.platformStyle,
        lineHeightStyle = style.lineHeightStyle,
        density = density.density,
        fontScale = density.fontScale,
    )
    return remember(key, fontFamilyResolver, layoutDirection) {
        synchronized(codeTextMetricsCache) { codeTextMetricsCache[key] } ?: run {
            val measurer = TextMeasurer(fontFamilyResolver, density, layoutDirection, cacheSize = 4)
            val one = measurer.measure("0123456789", style = style, softWrap = false, maxLines = 1)
            val two = measurer.measure("M\nM", style = style, softWrap = false)
            val oneLinePx = one.size.height.coerceAtLeast(1)
            val lineStepPx = if (two.lineCount >= 2) {
                (two.getLineTop(1) - two.getLineTop(0)).roundToInt().coerceAtLeast(1)
            } else {
                (two.size.height - oneLinePx).coerceAtLeast(1)
            }
            val charWidthPx = (one.size.width / 10f).coerceAtLeast(1f)
            val metrics = CodeTextMetrics(oneLinePx, lineStepPx, charWidthPx)
            synchronized(codeTextMetricsCache) {
                if (codeTextMetricsCache.size >= 32) codeTextMetricsCache.clear()
                codeTextMetricsCache[key] = metrics
            }
            metrics
        }
    }
}

@Composable
internal fun rememberCodeTokenIndex(
    code: String,
    language: String,
    streamingHint: Boolean? = null,
): CodeTokenIndex? {
    val normalized = remember(language) { normalizeCodeLanguage(language) }
    val plain = remember(code, normalized) { isPlainCodeLanguage(code, normalized) }
    if (plain) return null
    var mountedCode by remember(normalized) { mutableStateOf(code) }
    val streaming = streamingHint ?: (code != mountedCode)
    val immediate = remember(code, normalized) {
        cachedCodeTokenIndex(code, normalized)
            ?: if (!streaming && code.length <= IMMEDIATE_HIGHLIGHT_LIMIT) codeTokenIndex(code, normalized) else null
    }
    var readyIndex by remember(normalized) { mutableStateOf(immediate) }
    var lastCompletedAtMs by remember(normalized) { mutableLongStateOf(0L) }
    if (immediate != null && readyIndex !== immediate) {
        readyIndex = immediate
    }
    SideEffect { mountedCode = code }
    LaunchedEffect(code, normalized, streaming) {
        if (readyIndex?.code == code && readyIndex?.language == normalized) return@LaunchedEffect
        val cached = cachedCodeTokenIndex(code, normalized)
        if (cached != null) {
            readyIndex = cached
            return@LaunchedEffect
        }
        if (streaming) {
            val nowMs = System.nanoTime() / 1_000_000L
            if (lastCompletedAtMs != 0L && nowMs - lastCompletedAtMs < 100L) {
                delay(80L)
            }
        }
        val computed = withContext(Dispatchers.Default) {
            val ctx = coroutineContext
            codeTokenIndex(code, normalized) { ctx.ensureActive() }
        }
        lastCompletedAtMs = System.nanoTime() / 1_000_000L
        readyIndex = computed
    }
    val current = readyIndex
    return when {
        current == null -> null
        current.code == code -> current
        code.startsWith(current.code) -> current
        else -> null
    }
}

@Composable
internal fun rememberDiffTokenIndex(
    lines: List<DiffLine>,
    language: String,
): DiffTokenIndex? {
    val normalized = remember(language) { normalizeCodeLanguage(language) }
    val totalChars = remember(lines) { lines.sumOf { it.text.length + 3 } }
    val immediate = remember(lines, normalized) {
        cachedDiffTokenIndex(lines, normalized)
            ?: if (totalChars <= IMMEDIATE_HIGHLIGHT_LIMIT) diffTokenIndex(lines, normalized) else null
    }
    val state = remember(lines, normalized) { mutableStateOf(immediate) }
    LaunchedEffect(state) {
        if (state.value != null) return@LaunchedEffect
        state.value = withContext(Dispatchers.Default) {
            val ctx = coroutineContext
            diffTokenIndex(lines, normalized) { ctx.ensureActive() }
        }
    }
    return state.value
}

internal class WrappedChunkLayoutState(
    val lineTable: CodeLineTable,
    val colsPerLine: Int,
    val lineStepPx: Int,
    previous: WrappedChunkLayoutState?,
) {
    val chunkCount: Int = lineTable.chunks.size
    var layoutVersion by mutableIntStateOf(0)
        private set
    private val estimatedHeights = IntArray(chunkCount)
    private val measuredHeights = IntArray(chunkCount) { -1 }
    private val prefixTops = IntArray(chunkCount + 1)
    private var partialTailIndex: Int = -1
    private var partialTailHeight: Int = -1

    init {
        val reusePrev = previous != null &&
            previous.colsPerLine == colsPerLine &&
            previous.lineStepPx == lineStepPx &&
            lineTable.code.startsWith(previous.lineTable.code)
        val reusableCount = if (reusePrev) {
            minOf((previous!!.chunkCount - 1).coerceAtLeast(0), chunkCount)
        } else {
            0
        }
        for (c in 0 until chunkCount) {
            val chunk = lineTable.chunks[c]
            var visualLines = 0
            if (chunk.visualCols >= 0) {
                visualLines = if (chunk.visualCols <= colsPerLine) {
                    1
                } else {
                    ((chunk.visualCols - 1) / colsPerLine) + 1
                }
            } else {
                for (line in chunk.startLine until chunk.endLineExclusive) {
                    val visualCols = lineTable.lineVisualCols[line]
                    visualLines += if (visualCols <= colsPerLine) {
                        1
                    } else {
                        ((visualCols - 1) / colsPerLine) + 1
                    }
                }
            }
            estimatedHeights[c] = (visualLines.coerceAtLeast(1) * lineStepPx).coerceAtLeast(lineStepPx)
            if (c < reusableCount &&
                previous!!.lineTable.chunks[c].startChar == chunk.startChar &&
                previous.lineTable.chunks[c].endChar == chunk.endChar
            ) {
                measuredHeights[c] = previous.measuredHeights[c]
            }
        }
        rebuildPrefixTops(0)
    }

    private fun heightAt(chunkIdx: Int): Int {
        val measured = measuredHeights[chunkIdx]
        return if (measured >= 0) measured else estimatedHeights[chunkIdx]
    }

    private fun rebuildPrefixTops(fromChunk: Int) {
        var acc = prefixTops[fromChunk]
        for (i in fromChunk until chunkCount) {
            acc += heightAt(i)
            prefixTops[i + 1] = acc
        }
    }

    fun chunkTopPx(chunkIdx: Int): Int = prefixTops[chunkIdx]

    fun recordMeasured(chunkIdx: Int, heightPx: Int, fullChunk: Boolean) {
        val clamped = heightPx.coerceAtLeast(lineStepPx)
        if (fullChunk) {
            var changed = false
            if (partialTailIndex == chunkIdx) {
                partialTailIndex = -1
                partialTailHeight = -1
                changed = true
            }
            val oldHeight = heightAt(chunkIdx)
            if (measuredHeights[chunkIdx] != clamped) {
                measuredHeights[chunkIdx] = clamped
                if (clamped != oldHeight) {
                    rebuildPrefixTops(chunkIdx)
                    changed = true
                }
            }
            if (changed) {
                layoutVersion++
            }
        } else {
            if (partialTailIndex != chunkIdx || partialTailHeight != clamped) {
                partialTailIndex = chunkIdx
                partialTailHeight = clamped
                layoutVersion++
            }
        }
    }

    fun maxRevealedChunk(visibleEnd: Int): Int {
        if (visibleEnd <= 0 || chunkCount == 0) return -1
        if (visibleEnd >= lineTable.code.length) return chunkCount - 1
        var lo = 0
        var hi = chunkCount - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lineTable.chunks[mid].startChar < visibleEnd) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    fun totalRevealedHeightPx(visibleEnd: Int): Int {
        val maxChunk = maxRevealedChunk(visibleEnd)
        if (maxChunk < 0) return lineStepPx
        val lastChunk = lineTable.chunks[maxChunk]
        return if (visibleEnd < lastChunk.endChar && partialTailIndex == maxChunk && partialTailHeight > 0) {
            prefixTops[maxChunk] + partialTailHeight
        } else {
            prefixTops[maxChunk + 1]
        }
    }

    fun visibleRange(visTop: Float, visBottom: Float, visibleEnd: Int): Long {
        layoutVersion
        val maxChunk = maxRevealedChunk(visibleEnd)
        if (maxChunk < 0) return EMPTY_CHUNK_RANGE
        val totalH = totalRevealedHeightPx(visibleEnd).toFloat()
        if (visBottom <= 0f || visTop >= totalH) return EMPTY_CHUNK_RANGE
        val clampedTop = visTop.coerceAtLeast(0f)
        val clampedBottom = visBottom.coerceAtMost(totalH)
        var lo = 0
        var hi = maxChunk
        var first = maxChunk
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val chunkBottom = if (mid == maxChunk && partialTailIndex == maxChunk && partialTailHeight > 0 && visibleEnd < lineTable.chunks[mid].endChar) {
                prefixTops[mid] + partialTailHeight
            } else {
                prefixTops[mid + 1]
            }
            if (chunkBottom > clampedTop) {
                first = mid
                hi = mid - 1
            } else {
                lo = mid + 1
            }
        }
        lo = first
        hi = maxChunk
        var last = first
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (prefixTops[mid] < clampedBottom) {
                last = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return packInts(first, last)
    }
}

@Composable
internal fun ViewportWrappedCode(
    code: String,
    visibleEnd: Int,
    language: String,
    colors: ColorScheme,
    style: TextStyle,
    streamingHint: Boolean?,
    viewportTopPx: () -> Float,
    viewportHeightPx: () -> Float,
    showSoftWrapIndicators: Boolean,
    wrapIndicatorColor: Color,
    contentPaddingStart: Dp,
    contentPaddingEnd: Dp,
    modifier: Modifier = Modifier,
) {
    val dark = codePaletteDark(colors)
    val palette = codePalette(dark)
    val lineTable = remember(code) { buildCodeLineTable(code) }
    val tokenIndex = rememberCodeTokenIndex(code, language, streamingHint)

    if (lineTable.isShort) {
        val annotated = remember(code, visibleEnd, tokenIndex, dark) {
            if (tokenIndex != null) {
                tokenIndex.slice(code, 0, visibleEnd, palette)
            } else {
                val safeEnd = visibleEnd.coerceIn(0, code.length)
                AnnotatedString(if (safeEnd == code.length) code else code.substring(0, safeEnd))
            }
        }
        val wrapPath = remember { Path() }
        var layoutResult by remember(code) { mutableStateOf<TextLayoutResult?>(null) }
        Box(
            modifier
                .fillMaxWidth()
                .then(
                    if (showSoftWrapIndicators) {
                        Modifier.drawBehind {
                            drawSoftWrapIndicators(layoutResult, wrapIndicatorColor, wrapPath, 0f, size.height)
                        }
                    } else {
                        Modifier
                    },
                )
                .padding(start = contentPaddingStart, end = contentPaddingEnd),
        ) {
            Text(
                text = annotated,
                modifier = Modifier.fillMaxWidth(),
                fontFamily = style.fontFamily,
                style = style,
                softWrap = true,
                onTextLayout = { layoutResult = it },
            )
        }
        return
    }

    val metrics = rememberCodeTextMetrics(style)
    val density = LocalDensity.current
    val padStartPx = with(density) { contentPaddingStart.roundToPx() }
    val padEndPx = with(density) { contentPaddingEnd.roundToPx() }
    val previousStateRef = remember { arrayOfNulls<WrappedChunkLayoutState>(1) }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val maxWidthPx = if (constraints.hasBoundedWidth) {
            constraints.maxWidth
        } else {
            with(density) { 360.dp.roundToPx() }
        }
        val textWidthPx = (maxWidthPx - padStartPx - padEndPx).coerceAtLeast(1)
        val colsPerLine = (textWidthPx / metrics.charWidthPx).toInt().coerceAtLeast(8)
        val layoutState = remember(lineTable, colsPerLine, metrics.lineStepPx) {
            WrappedChunkLayoutState(
                lineTable = lineTable,
                colsPerLine = colsPerLine,
                lineStepPx = metrics.lineStepPx,
                previous = previousStateRef[0],
            ).also { previousStateRef[0] = it }
        }
        val activeRange by remember(layoutState, visibleEnd) {
            derivedStateOf {
                val top = viewportTopPx()
                val viewH = viewportHeightPx().coerceAtLeast(metrics.lineStepPx * 4f)
                val overscan = maxOf(viewH * 0.75f, metrics.lineStepPx * 10f)
                layoutState.visibleRange(top - overscan, top + viewH + overscan, visibleEnd)
            }
        }
        val firstChunk = unpackInt1(activeRange)
        val lastChunk = unpackInt2(activeRange)

        Layout(
            content = {
                if (firstChunk >= 0 && lastChunk >= firstChunk) {
                    for (chunkIdx in firstChunk..lastChunk) {
                        val chunk = lineTable.chunks[chunkIdx]
                        val chunkEnd = minOf(chunk.endChar, visibleEnd)
                        key(chunkIdx) {
                            ViewportWrappedCodeChunk(
                                code = code,
                                startChar = chunk.startChar,
                                endChar = chunkEnd,
                                tokenIndex = tokenIndex,
                                dark = dark,
                                style = style,
                                showSoftWrapIndicators = showSoftWrapIndicators,
                                wrapIndicatorColor = wrapIndicatorColor,
                                contentPaddingStart = contentPaddingStart,
                                contentPaddingEnd = contentPaddingEnd,
                            )
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { measurables, childOuterConstraints ->
            val targetWidth = if (childOuterConstraints.hasBoundedWidth) childOuterConstraints.maxWidth else maxWidthPx
            val childConstraints = Constraints(
                minWidth = targetWidth,
                maxWidth = targetWidth,
                minHeight = 0,
                maxHeight = Constraints.Infinity,
            )
            val placeables = if (measurables.isEmpty()) {
                emptyList()
            } else {
                measurables.mapIndexed { offset, measurable ->
                    val chunkIdx = firstChunk + offset
                    val placeable = measurable.measure(childConstraints)
                    val fullChunk = visibleEnd >= lineTable.chunks[chunkIdx].endChar
                    layoutState.recordMeasured(chunkIdx, placeable.height, fullChunk)
                    placeable
                }
            }
            val totalHeight = layoutState.totalRevealedHeightPx(visibleEnd)
            layout(targetWidth, totalHeight) {
                for (i in placeables.indices) {
                    placeables[i].placeRelative(0, layoutState.chunkTopPx(firstChunk + i))
                }
            }
        }
    }
}

@Composable
private fun ViewportWrappedCodeChunk(
    code: String,
    startChar: Int,
    endChar: Int,
    tokenIndex: CodeTokenIndex?,
    dark: Boolean,
    style: TextStyle,
    showSoftWrapIndicators: Boolean,
    wrapIndicatorColor: Color,
    contentPaddingStart: Dp,
    contentPaddingEnd: Dp,
) {
    val palette = codePalette(dark)
    val chunkText = remember(code, startChar, endChar) {
        if (startChar >= endChar) "" else code.substring(startChar, endChar)
    }
    val annotated = remember(chunkText, startChar, endChar, tokenIndex, dark) {
        if (tokenIndex != null) {
            tokenIndex.slice(code, startChar, endChar, palette)
        } else {
            AnnotatedString(chunkText)
        }
    }
    val wrapPath = remember { Path() }
    var layoutResult by remember(chunkText) { mutableStateOf<TextLayoutResult?>(null) }
    Box(
        Modifier
            .fillMaxWidth()
            .then(
                if (showSoftWrapIndicators) {
                    Modifier.drawBehind {
                        drawSoftWrapIndicators(layoutResult, wrapIndicatorColor, wrapPath, 0f, size.height)
                    }
                } else {
                    Modifier
                },
            )
            .padding(start = contentPaddingStart, end = contentPaddingEnd),
    ) {
        Text(
            text = annotated,
            modifier = Modifier.fillMaxWidth(),
            fontFamily = style.fontFamily,
            style = style,
            softWrap = true,
            onTextLayout = { layoutResult = it },
        )
    }
}

@Composable
internal fun rememberWidestLineWidthPx(
    lineTable: CodeLineTable,
    style: TextStyle,
): Int {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val fontFamilyResolver = LocalFontFamilyResolver.current
    return remember(lineTable, style, density, layoutDirection, fontFamilyResolver) {
        val measurer = TextMeasurer(fontFamilyResolver, density, layoutDirection, cacheSize = 4)
        var maxWidth = 0
        val idx1 = lineTable.widestLineIndex
        if (idx1 in 0 until lineTable.lineCount) {
            maxWidth = maxOf(
                maxWidth,
                measurer.measure(lineTable.lineText(idx1), style = style, softWrap = false, maxLines = 1).size.width,
            )
        }
        val idx2 = lineTable.secondWidestLineIndex
        if (idx2 in 0 until lineTable.lineCount && idx2 != idx1) {
            maxWidth = maxOf(
                maxWidth,
                measurer.measure(lineTable.lineText(idx2), style = style, softWrap = false, maxLines = 1).size.width,
            )
        }
        maxWidth
    }
}

internal fun computeUniformVisibleChunkRange(
    totalLines: Int,
    chunkCount: Int,
    lineStepPx: Int,
    oneLinePx: Int = lineStepPx,
    padTopPx: Float,
    visTop: Float,
    visBottom: Float,
): Long {
    if (totalLines <= 0 || chunkCount <= 0) return EMPTY_CHUNK_RANGE
    val totalHeightPx = padTopPx + (totalLines - 1) * lineStepPx.toFloat() + oneLinePx.toFloat()
    if (visBottom <= 0f || visTop >= totalHeightPx) return EMPTY_CHUNK_RANGE
    val firstLine = ((visTop - padTopPx) / lineStepPx.toFloat()).toInt().coerceIn(0, totalLines - 1)
    val lastLine = ((visBottom - padTopPx) / lineStepPx.toFloat()).toInt().coerceIn(firstLine, totalLines - 1)
    val firstChunk = (firstLine / CODE_CHUNK_LINES).coerceIn(0, chunkCount - 1)
    val lastChunk = (lastLine / CODE_CHUNK_LINES).coerceIn(firstChunk, chunkCount - 1)
    return packInts(firstChunk, lastChunk)
}

@Composable
internal fun ViewportNoWrapCodeBlock(
    code: String,
    visibleEnd: Int = code.length,
    language: String,
    colors: ColorScheme,
    style: TextStyle,
    streamingHint: Boolean?,
    hScroll: ScrollState,
    viewportTopPx: () -> Float,
    viewportHeightPx: () -> Float,
    gutterWidth: Dp = 0.dp,
    gutterBg: Color = Color.Transparent,
    gutterDividerColor: Color = Color.Transparent,
    gutterTextStyle: TextStyle = style,
    gutterPadEnd: Dp = 6.dp,
    lineNumberAt: ((Int) -> String)? = null,
    contentPaddingHorizontal: Dp = 12.dp,
    contentPaddingVertical: Dp = 8.dp,
    modifier: Modifier = Modifier,
) {
    val dark = codePaletteDark(colors)
    val palette = codePalette(dark)
    val effectiveCode = remember(code, visibleEnd) {
        val safeEnd = visibleEnd.coerceIn(0, code.length)
        if (safeEnd == code.length) code else code.substring(0, safeEnd)
    }
    val lineTable = remember(effectiveCode) { buildCodeLineTable(effectiveCode) }
    val tokenIndex = rememberCodeTokenIndex(code, language, streamingHint)
    val hasGutter = lineNumberAt != null && gutterWidth > 0.dp
    val showDivider = hasGutter && gutterDividerColor.alpha > 0f
    val gutterMeasurer = rememberTextMeasurer(cacheSize = 256)

    if (lineTable.isShort) {
        val annotated = remember(effectiveCode, tokenIndex, dark) {
            if (tokenIndex != null) {
                tokenIndex.slice(effectiveCode, 0, effectiveCode.length, palette)
            } else {
                AnnotatedString(effectiveCode)
            }
        }
        var codeLayout by remember(effectiveCode) { mutableStateOf<TextLayoutResult?>(null) }
        val metrics = rememberCodeTextMetrics(style)
        Row(modifier.fillMaxWidth()) {
            if (hasGutter) {
                val dividerDp = if (showDivider) 1.dp else 0.dp
                val density = LocalDensity.current
                val totalHeightDp = remember(codeLayout, lineTable.lineCount, metrics.lineStepPx, contentPaddingVertical, density) {
                    val textH = codeLayout?.size?.height ?: (lineTable.lineCount * metrics.lineStepPx)
                    with(density) { textH.toDp() } + contentPaddingVertical * 2
                }
                Spacer(
                    Modifier
                        .width(gutterWidth + dividerDp)
                        .height(totalHeightDp)
                        .drawBehind {
                            val gutterW = gutterWidth.toPx()
                            val padV = contentPaddingVertical.toPx()
                            val padH = gutterPadEnd.toPx()
                            val fallbackH = metrics.lineStepPx.toFloat()
                            if (gutterBg.alpha > 0f) {
                                drawRect(gutterBg, Offset.Zero, Size(gutterW, size.height))
                            }
                            val layout = codeLayout
                            for (idx in 0 until lineTable.lineCount) {
                                val label = lineNumberAt!!(idx)
                                if (label.isEmpty()) continue
                                val m = gutterMeasurer.measure(label, style = gutterTextStyle, softWrap = false, maxLines = 1)
                                val x = (gutterW - padH - m.size.width).coerceAtLeast(2f)
                                val y = if (layout != null && idx < layout.lineCount) {
                                    padV + layout.getLineBaseline(idx) - m.firstBaseline
                                } else {
                                    padV + idx * fallbackH + (fallbackH - m.size.height) / 2f
                                }
                                drawText(m, topLeft = Offset(x, y))
                            }
                            if (showDivider) {
                                drawRect(gutterDividerColor, Offset(gutterW, 0f), Size(1.dp.toPx(), size.height))
                            }
                        },
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .padding(horizontal = contentPaddingHorizontal, vertical = contentPaddingVertical),
            ) {
                LumaSelectionContainer {
                    Text(
                        text = annotated,
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(hScroll),
                        fontFamily = style.fontFamily,
                        style = style,
                        softWrap = false,
                        onTextLayout = { codeLayout = it },
                    )
                }
            }
        }
        return
    }

    val metrics = rememberCodeTextMetrics(style)
    val density = LocalDensity.current
    val padHPx = with(density) { contentPaddingHorizontal.roundToPx() }
    val padVPx = with(density) { contentPaddingVertical.roundToPx() }
    val gutterWidthPx = with(density) { gutterWidth.roundToPx() }
    val dividerWidthPx = if (showDivider) with(density) { 1.dp.roundToPx().coerceAtLeast(1) } else 0
    val lineStepPx = metrics.lineStepPx
    val oneLinePx = metrics.oneLinePx
    val totalLines = lineTable.lineCount
    val totalHeightPx = padVPx * 2 + if (totalLines <= 0) 0 else (totalLines - 1) * lineStepPx + oneLinePx
    val widestLineWidthPx = rememberWidestLineWidthPx(lineTable, style)
    val chunkLayouts = remember(lineTable) { mutableStateMapOf<Int, TextLayoutResult>() }
    val uniformChunks = remember(lineTable) {
        val count = (lineTable.lineCount + CODE_CHUNK_LINES - 1) / CODE_CHUNK_LINES
        List(count) { c ->
            val startLine = c * CODE_CHUNK_LINES
            val endLine = minOf(startLine + CODE_CHUNK_LINES, lineTable.lineCount)
            CodeChunk(
                index = c,
                startLine = startLine,
                endLineExclusive = endLine,
                startChar = lineTable.lineStarts[startLine],
                endChar = lineTable.lineEnds[endLine - 1],
            )
        }
    }
    val activeRange by remember(totalLines, uniformChunks.size, lineStepPx, oneLinePx, padVPx) {
        derivedStateOf {
            val top = viewportTopPx()
            val viewH = viewportHeightPx().coerceAtLeast(lineStepPx * 4f)
            val overscan = maxOf(viewH * 0.75f, lineStepPx * 8f)
            computeUniformVisibleChunkRange(
                totalLines = totalLines,
                chunkCount = uniformChunks.size,
                lineStepPx = lineStepPx,
                oneLinePx = oneLinePx,
                padTopPx = padVPx.toFloat(),
                visTop = top - overscan,
                visBottom = top + viewH + overscan,
            )
        }
    }
    val firstChunk = unpackInt1(activeRange)
    val lastChunk = unpackInt2(activeRange)

    Row(modifier.fillMaxWidth()) {
        if (hasGutter) {
            Layout(
                content = {
                    if (firstChunk >= 0 && lastChunk >= firstChunk) {
                        for (chunkIdx in firstChunk..lastChunk) {
                            val chunk = uniformChunks[chunkIdx]
                            key(chunkIdx) {
                                val chunkLayout = chunkLayouts[chunkIdx]
                                val chunkHeightPx = (chunk.lineCount - 1).coerceAtLeast(0) * lineStepPx + oneLinePx
                                Spacer(
                                    Modifier
                                        .width(gutterWidth)
                                        .height(with(density) { chunkHeightPx.toDp() })
                                        .drawBehind {
                                            val gutterW = gutterWidth.toPx()
                                            val padH = gutterPadEnd.toPx()
                                            val fallbackH = lineStepPx.toFloat()
                                            for (localIdx in 0 until chunk.lineCount) {
                                                val globalIdx = chunk.startLine + localIdx
                                                val label = lineNumberAt!!(globalIdx)
                                                if (label.isEmpty()) continue
                                                val m = gutterMeasurer.measure(label, style = gutterTextStyle, softWrap = false, maxLines = 1)
                                                val x = (gutterW - padH - m.size.width).coerceAtLeast(2f)
                                                val y = if (chunkLayout != null && localIdx < chunkLayout.lineCount) {
                                                    chunkLayout.getLineBaseline(localIdx) - m.firstBaseline
                                                } else {
                                                    localIdx * fallbackH + (fallbackH - m.size.height) / 2f
                                                }
                                                drawText(m, topLeft = Offset(x, y))
                                            }
                                        },
                                )
                            }
                        }
                    }
                },
                modifier = Modifier.drawBehind {
                    if (gutterBg.alpha > 0f) {
                        drawRect(gutterBg, Offset.Zero, Size(gutterWidthPx.toFloat(), size.height))
                    }
                    if (showDivider) {
                        drawRect(gutterDividerColor, Offset(gutterWidthPx.toFloat(), 0f), Size(dividerWidthPx.toFloat(), size.height))
                    }
                },
            ) { measurables, _ ->
                val placeables = measurables.map { it.measure(Constraints(maxWidth = gutterWidthPx)) }
                layout(gutterWidthPx + dividerWidthPx, totalHeightPx) {
                    for (i in placeables.indices) {
                        val chunk = uniformChunks[firstChunk + i]
                        placeables[i].placeRelative(0, padVPx + chunk.startLine * lineStepPx)
                    }
                }
            }
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            val viewportCodeWidthPx = if (constraints.hasBoundedWidth) constraints.maxWidth else with(density) { 300.dp.roundToPx() }
            val minInnerTextWidthPx = maxOf((viewportCodeWidthPx - padHPx * 2).coerceAtLeast(1), widestLineWidthPx)
            val contentWidthPx = minInnerTextWidthPx + padHPx * 2
            Box(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(hScroll),
            ) {
                LumaSelectionContainer {
                    Layout(
                        content = {
                            if (firstChunk >= 0 && lastChunk >= firstChunk) {
                                for (chunkIdx in firstChunk..lastChunk) {
                                    val chunk = uniformChunks[chunkIdx]
                                    key(chunkIdx) {
                                        DisposableEffect(chunkIdx) {
                                            onDispose { chunkLayouts.remove(chunkIdx) }
                                        }
                                        val chunkText = remember(effectiveCode, chunk.startChar, chunk.endChar) {
                                            effectiveCode.substring(chunk.startChar, chunk.endChar)
                                        }
                                        val annotated = remember(chunkText, chunk.startChar, chunk.endChar, tokenIndex, dark) {
                                            if (tokenIndex != null) {
                                                tokenIndex.slice(effectiveCode, chunk.startChar, chunk.endChar, palette)
                                            } else {
                                                AnnotatedString(chunkText)
                                            }
                                        }
                                        Text(
                                            text = annotated,
                                            fontFamily = style.fontFamily,
                                            style = style,
                                            softWrap = false,
                                            onTextLayout = { chunkLayouts[chunkIdx] = it },
                                        )
                                    }
                                }
                            }
                        },
                    ) { measurables, _ ->
                        val childConstraints = Constraints(
                            minWidth = minInnerTextWidthPx,
                            maxWidth = Constraints.Infinity,
                            minHeight = 0,
                            maxHeight = Constraints.Infinity,
                        )
                        var widestMeasured = minInnerTextWidthPx
                        val placeables = measurables.map { measurable ->
                            val p = measurable.measure(childConstraints)
                            if (p.width > widestMeasured) widestMeasured = p.width
                            p
                        }
                        val finalWidth = maxOf(contentWidthPx, widestMeasured + padHPx * 2)
                        layout(finalWidth, totalHeightPx) {
                            for (i in placeables.indices) {
                                val chunk = uniformChunks[firstChunk + i]
                                placeables[i].placeRelative(padHPx, padVPx + chunk.startLine * lineStepPx)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun rememberDiffWidestLineWidthPx(
    hunks: List<ProcessEditHunk>,
    style: TextStyle,
): Int {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val fontFamilyResolver = LocalFontFamilyResolver.current
    return remember(hunks, style, density, layoutDirection, fontFamilyResolver) {
        var bestLine: String? = null
        var bestCols = -1
        var secondLine: String? = null
        var secondCols = -1
        for (hunk in hunks) {
            for (dLine in hunk.diffLines) {
                val cols = estimateVisualColumns(dLine.text, 0, dLine.text.length) + 2
                if (cols > bestCols) {
                    secondCols = bestCols
                    secondLine = bestLine
                    bestCols = cols
                    bestLine = "+ " + dLine.text
                } else if (cols > secondCols) {
                    secondCols = cols
                    secondLine = "+ " + dLine.text
                }
            }
        }
        val measurer = TextMeasurer(fontFamilyResolver, density, layoutDirection, cacheSize = 4)
        var maxWidth = 0
        bestLine?.let {
            maxWidth = maxOf(maxWidth, measurer.measure(it, style = style, softWrap = false, maxLines = 1).size.width)
        }
        secondLine?.let {
            maxWidth = maxOf(maxWidth, measurer.measure(it, style = style, softWrap = false, maxLines = 1).size.width)
        }
        maxWidth
    }
}

@Composable
internal fun ViewportDiffHunkView(
    diffLines: List<DiffLine>,
    language: String,
    colors: ColorScheme,
    codeStyle: TextStyle,
    gutterNumStyle: TextStyle,
    gutterWidthDp: Dp,
    gutterDefaultBg: Color,
    borderColor: Color,
    addGutterBg: Color,
    addRowBg: Color,
    addBarColor: Color,
    delGutterBg: Color,
    delRowBg: Color,
    delBarColor: Color,
    vPad: Dp,
    hScroll: ScrollState,
    minCodeContentWidthPx: Int,
    viewportTopPx: () -> Float,
    viewportHeightPx: () -> Float,
    gutterMeasurer: TextMeasurer,
    modifier: Modifier = Modifier,
) {
    val dark = codePaletteDark(colors)
    val palette = codePalette(dark)
    val metrics = rememberCodeTextMetrics(codeStyle)
    val density = LocalDensity.current
    val lineStepPx = metrics.lineStepPx
    val totalLines = diffLines.size
    val totalChars = remember(diffLines) { diffLines.sumOf { it.text.length + 3 } }
    val diffIndex = rememberDiffTokenIndex(diffLines, language)
    val padVPx = with(density) { vPad.roundToPx() }
    val padHPx = with(density) { 8.dp.roundToPx() }
    val gutterWidthPx = with(density) { gutterWidthDp.roundToPx() }
    val dividerWidthPx = with(density) { 1.dp.roundToPx().coerceAtLeast(1) }
    val isShort = totalLines <= CODE_WINDOW_THRESHOLD_LINES && totalChars <= CODE_WINDOW_THRESHOLD_CHARS

    if (isShort) {
        val annotated = remember(diffLines, diffIndex, dark) {
            diffIndex?.sliceLines(0, diffLines.size, palette) ?: plainDiffAnnotated(diffLines, 0, diffLines.size)
        }
        var codeLayout by remember(diffLines) { mutableStateOf<TextLayoutResult?>(null) }
        val totalHeightDp = remember(codeLayout, totalLines, lineStepPx, vPad, density) {
            val textH = codeLayout?.size?.height ?: (totalLines * lineStepPx)
            with(density) { textH.toDp() } + vPad * 2
        }
        Row(modifier.fillMaxWidth()) {
            Spacer(
                Modifier
                    .width(gutterWidthDp + 1.dp)
                    .height(totalHeightDp)
                    .drawBehind {
                        val padPx = vPad.toPx()
                        val barW = 2.dp.toPx()
                        val gutterW = gutterWidthDp.toPx()
                        val dividerW = 1.dp.toPx()
                        val colPad = 4.dp.toPx()
                        val colGap = 4.dp.toPx()
                        val singleColW = ((gutterW - colPad * 2f - colGap) / 2f).coerceAtLeast(8f)
                        val fallbackH = lineStepPx.toFloat()
                        val layout = codeLayout
                        drawRect(gutterDefaultBg, Offset.Zero, Size(gutterW, size.height))
                        for (idx in diffLines.indices) {
                            val dLine = diffLines[idx]
                            val top = if (layout != null && idx < layout.lineCount) padPx + layout.getLineTop(idx) else padPx + idx * fallbackH
                            val bottom = if (layout != null && idx < layout.lineCount) padPx + layout.getLineBottom(idx) else top + fallbackH
                            val rowH = (bottom - top).coerceAtLeast(1f)
                            val baselineY = if (layout != null && idx < layout.lineCount) padPx + layout.getLineBaseline(idx) else null
                            when (dLine.kind) {
                                DiffLineKind.ADDITION -> {
                                    drawRect(addGutterBg, Offset(0f, top), Size(gutterW, rowH))
                                    drawRect(addBarColor, Offset(0f, top), Size(barW, rowH))
                                }
                                DiffLineKind.DELETION -> {
                                    drawRect(delGutterBg, Offset(0f, top), Size(gutterW, rowH))
                                    drawRect(delBarColor, Offset(0f, top), Size(barW, rowH))
                                }
                                DiffLineKind.CONTEXT -> Unit
                            }
                            dLine.oldLine?.let { oldNum ->
                                val m = gutterMeasurer.measure(oldNum.toString(), style = gutterNumStyle, softWrap = false, maxLines = 1)
                                val x = (colPad + singleColW - m.size.width).coerceAtLeast(1f)
                                val y = if (baselineY != null) baselineY - m.firstBaseline else top + (rowH - m.size.height) / 2f
                                drawText(m, topLeft = Offset(x, y))
                            }
                            dLine.newLine?.let { newNum ->
                                val m = gutterMeasurer.measure(newNum.toString(), style = gutterNumStyle, softWrap = false, maxLines = 1)
                                val x = (gutterW - colPad - m.size.width).coerceAtLeast(colPad + singleColW + colGap)
                                val y = if (baselineY != null) baselineY - m.firstBaseline else top + (rowH - m.size.height) / 2f
                                drawText(m, topLeft = Offset(x, y))
                            }
                        }
                        drawRect(borderColor, Offset(gutterW, 0f), Size(dividerW, size.height))
                    },
            )
            BoxWithConstraints(Modifier.weight(1f)) {
                val viewportCodeWidthPx = if (constraints.hasBoundedWidth) constraints.maxWidth else with(density) { 260.dp.roundToPx() }
                val minWidthDp = with(density) { maxOf(viewportCodeWidthPx, minCodeContentWidthPx + padHPx * 2).toDp() }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(hScroll),
                ) {
                    LumaSelectionContainer {
                        Box(
                            Modifier
                                .widthIn(min = minWidthDp)
                                .drawBehind {
                                    val padPx = vPad.toPx()
                                    val fallbackH = lineStepPx.toFloat()
                                    val layout = codeLayout
                                    for (idx in diffLines.indices) {
                                        val rowBg = when (diffLines[idx].kind) {
                                            DiffLineKind.ADDITION -> addRowBg
                                            DiffLineKind.DELETION -> delRowBg
                                            DiffLineKind.CONTEXT -> continue
                                        }
                                        val top = if (layout != null && idx < layout.lineCount) padPx + layout.getLineTop(idx) else padPx + idx * fallbackH
                                        val bottom = if (layout != null && idx < layout.lineCount) padPx + layout.getLineBottom(idx) else top + fallbackH
                                        drawRect(rowBg, Offset(0f, top), Size(size.width, (bottom - top).coerceAtLeast(1f)))
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = vPad),
                        ) {
                            Text(
                                text = annotated,
                                fontFamily = codeStyle.fontFamily,
                                style = codeStyle,
                                softWrap = false,
                                onTextLayout = { codeLayout = it },
                            )
                        }
                    }
                }
            }
        }
        return
    }

    val oneLinePx = metrics.oneLinePx
    val totalHeightPx = padVPx * 2 + if (totalLines <= 0) 0 else (totalLines - 1) * lineStepPx + oneLinePx
    val chunkCount = (totalLines + CODE_CHUNK_LINES - 1) / CODE_CHUNK_LINES
    val chunkLayouts = remember(diffLines) { mutableStateMapOf<Int, TextLayoutResult>() }
    val activeRange by remember(totalLines, chunkCount, lineStepPx, oneLinePx, padVPx) {
        derivedStateOf {
            val top = viewportTopPx()
            val viewH = viewportHeightPx().coerceAtLeast(lineStepPx * 4f)
            val overscan = maxOf(viewH * 0.75f, lineStepPx * 8f)
            computeUniformVisibleChunkRange(
                totalLines = totalLines,
                chunkCount = chunkCount,
                lineStepPx = lineStepPx,
                oneLinePx = oneLinePx,
                padTopPx = padVPx.toFloat(),
                visTop = top - overscan,
                visBottom = top + viewH + overscan,
            )
        }
    }
    val firstChunk = unpackInt1(activeRange)
    val lastChunk = unpackInt2(activeRange)

    Row(modifier.fillMaxWidth()) {
        Layout(
            content = {
                if (firstChunk >= 0 && lastChunk >= firstChunk) {
                    for (chunkIdx in firstChunk..lastChunk) {
                        val startLine = chunkIdx * CODE_CHUNK_LINES
                        val endLine = minOf(startLine + CODE_CHUNK_LINES, totalLines)
                        val chunkLinesCount = endLine - startLine
                        val chunkHeightPx = (chunkLinesCount - 1).coerceAtLeast(0) * lineStepPx + oneLinePx
                        key(chunkIdx) {
                            val chunkLayout = chunkLayouts[chunkIdx]
                            Spacer(
                                Modifier
                                    .width(gutterWidthDp)
                                    .height(with(density) { chunkHeightPx.toDp() })
                                    .drawBehind {
                                        val barW = 2.dp.toPx()
                                        val gutterW = gutterWidthDp.toPx()
                                        val colPad = 4.dp.toPx()
                                        val colGap = 4.dp.toPx()
                                        val singleColW = ((gutterW - colPad * 2f - colGap) / 2f).coerceAtLeast(8f)
                                        val fallbackH = lineStepPx.toFloat()
                                        for (localIdx in 0 until chunkLinesCount) {
                                            val dLine = diffLines[startLine + localIdx]
                                            val top = if (chunkLayout != null && localIdx < chunkLayout.lineCount) chunkLayout.getLineTop(localIdx) else localIdx * fallbackH
                                            val bottom = if (chunkLayout != null && localIdx < chunkLayout.lineCount) chunkLayout.getLineBottom(localIdx) else top + fallbackH
                                            val rowH = (bottom - top).coerceAtLeast(1f)
                                            val baselineY = if (chunkLayout != null && localIdx < chunkLayout.lineCount) chunkLayout.getLineBaseline(localIdx) else null
                                            when (dLine.kind) {
                                                DiffLineKind.ADDITION -> {
                                                    drawRect(addGutterBg, Offset(0f, top), Size(gutterW, rowH))
                                                    drawRect(addBarColor, Offset(0f, top), Size(barW, rowH))
                                                }
                                                DiffLineKind.DELETION -> {
                                                    drawRect(delGutterBg, Offset(0f, top), Size(gutterW, rowH))
                                                    drawRect(delBarColor, Offset(0f, top), Size(barW, rowH))
                                                }
                                                DiffLineKind.CONTEXT -> Unit
                                            }
                                            dLine.oldLine?.let { oldNum ->
                                                val m = gutterMeasurer.measure(oldNum.toString(), style = gutterNumStyle, softWrap = false, maxLines = 1)
                                                val x = (colPad + singleColW - m.size.width).coerceAtLeast(1f)
                                                val y = if (baselineY != null) baselineY - m.firstBaseline else top + (rowH - m.size.height) / 2f
                                                drawText(m, topLeft = Offset(x, y))
                                            }
                                            dLine.newLine?.let { newNum ->
                                                val m = gutterMeasurer.measure(newNum.toString(), style = gutterNumStyle, softWrap = false, maxLines = 1)
                                                val x = (gutterW - colPad - m.size.width).coerceAtLeast(colPad + singleColW + colGap)
                                                val y = if (baselineY != null) baselineY - m.firstBaseline else top + (rowH - m.size.height) / 2f
                                                drawText(m, topLeft = Offset(x, y))
                                            }
                                        }
                                    },
                            )
                        }
                    }
                }
            },
            modifier = Modifier.drawBehind {
                drawRect(gutterDefaultBg, Offset.Zero, Size(gutterWidthPx.toFloat(), size.height))
                drawRect(borderColor, Offset(gutterWidthPx.toFloat(), 0f), Size(dividerWidthPx.toFloat(), size.height))
            },
        ) { measurables, _ ->
            val placeables = measurables.map { it.measure(Constraints(maxWidth = gutterWidthPx)) }
            layout(gutterWidthPx + dividerWidthPx, totalHeightPx) {
                for (i in placeables.indices) {
                    val startLine = (firstChunk + i) * CODE_CHUNK_LINES
                    placeables[i].placeRelative(0, padVPx + startLine * lineStepPx)
                }
            }
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            val viewportCodeWidthPx = if (constraints.hasBoundedWidth) constraints.maxWidth else with(density) { 260.dp.roundToPx() }
            val minContentWidthPx = maxOf(viewportCodeWidthPx, minCodeContentWidthPx + padHPx * 2)
            Box(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(hScroll),
            ) {
                LumaSelectionContainer {
                    Layout(
                        content = {
                            if (firstChunk >= 0 && lastChunk >= firstChunk) {
                                for (chunkIdx in firstChunk..lastChunk) {
                                    val startLine = chunkIdx * CODE_CHUNK_LINES
                                    val endLine = minOf(startLine + CODE_CHUNK_LINES, totalLines)
                                    val chunkLinesCount = endLine - startLine
                                    key(chunkIdx) {
                                        DisposableEffect(chunkIdx) {
                                            onDispose { chunkLayouts.remove(chunkIdx) }
                                        }
                                        val chunkLayout = chunkLayouts[chunkIdx]
                                        val annotated = remember(diffLines, startLine, endLine, diffIndex, dark) {
                                            diffIndex?.sliceLines(startLine, endLine, palette)
                                                ?: plainDiffAnnotated(diffLines, startLine, endLine)
                                        }
                                        Box(
                                            Modifier
                                                .drawBehind {
                                                    val fallbackH = lineStepPx.toFloat()
                                                    for (localIdx in 0 until chunkLinesCount) {
                                                        val rowBg = when (diffLines[startLine + localIdx].kind) {
                                                            DiffLineKind.ADDITION -> addRowBg
                                                            DiffLineKind.DELETION -> delRowBg
                                                            DiffLineKind.CONTEXT -> continue
                                                        }
                                                        val top = if (chunkLayout != null && localIdx < chunkLayout.lineCount) chunkLayout.getLineTop(localIdx) else localIdx * fallbackH
                                                        val bottom = if (chunkLayout != null && localIdx < chunkLayout.lineCount) chunkLayout.getLineBottom(localIdx) else top + fallbackH
                                                        drawRect(rowBg, Offset(0f, top), Size(size.width, (bottom - top).coerceAtLeast(1f)))
                                                    }
                                                }
                                                .padding(horizontal = 8.dp),
                                        ) {
                                            Text(
                                                text = annotated,
                                                fontFamily = codeStyle.fontFamily,
                                                style = codeStyle,
                                                softWrap = false,
                                                onTextLayout = { chunkLayouts[chunkIdx] = it },
                                            )
                                        }
                                    }
                                }
                            }
                        },
                    ) { measurables, _ ->
                        val childConstraints = Constraints(
                            minWidth = minContentWidthPx,
                            maxWidth = Constraints.Infinity,
                            minHeight = 0,
                            maxHeight = Constraints.Infinity,
                        )
                        var widestMeasured = minContentWidthPx
                        val placeables = measurables.map { measurable ->
                            val p = measurable.measure(childConstraints)
                            if (p.width > widestMeasured) widestMeasured = p.width
                            p
                        }
                        val finalWidth = maxOf(minContentWidthPx, widestMeasured)
                        layout(finalWidth, totalHeightPx) {
                            for (i in placeables.indices) {
                                val startLine = (firstChunk + i) * CODE_CHUNK_LINES
                                placeables[i].placeRelative(0, padVPx + startLine * lineStepPx)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun plainDiffAnnotated(lines: List<DiffLine>, startLine: Int, endLine: Int): AnnotatedString {
    val sb = StringBuilder()
    for (idx in startLine until endLine) {
        if (idx > startLine) sb.append('\n')
        val line = lines[idx]
        val prefix = when (line.kind) {
            DiffLineKind.ADDITION -> "+ "
            DiffLineKind.DELETION -> "- "
            DiffLineKind.CONTEXT -> "  "
        }
        sb.append(prefix).append(line.text)
    }
    return AnnotatedString(sb.toString())
}

internal fun chunkLineCountOf(totalLines: Int): Int =
    if (totalLines <= 0) 0 else ceil(totalLines.toDouble() / CODE_CHUNK_LINES).toInt()
