package io.github.modao7067.luma.gui.ui

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.text.TextRunShaper
import android.os.Build
import android.text.TextPaint
import android.util.LruCache
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class TypesetResolvedStyle(
    val typeface: Typeface,
    val textSizePx: Float,
    val letterSpacingEm: Float,
    val colorArgb: Int,
    val backgroundArgb: Int,
    val underline: Boolean,
    val lineThrough: Boolean,
    val fontFeatureSettings: String?,
    val isCode: Boolean,
)

internal data class TypesetPreparedInput(
    val text: String,
    val runs: List<TypesetRunInput>,
    val styles: List<TypesetResolvedStyle>,
    val baseLineMetrics: TypesetLineMetrics,
)

private data class PunctCacheKey(
    val typefaceIdentity: Int,
    val sizeTenthsPx: Int,
    val ch: Char,
)

private val punctuationTrimCache = object : LruCache<PunctCacheKey, PunctuationTrim>(512) {
    override fun sizeOf(key: PunctCacheKey, value: PunctuationTrim): Int = 1
}

@Volatile
private var runShaperSupported: Boolean? = null

@Volatile
private var runAdvanceSupported: Boolean? = null

private fun checkRunShaperSupport(paint: TextPaint): Boolean {
    runShaperSupported?.let { return it }
    val supported = if (Build.VERSION.SDK_INT >= 31) {
        runCatching {
            val glyphs = TextRunShaper.shapeTextRun("，", 0, 1, 0, 1, 0f, 0f, false, paint)
            glyphs.glyphCount() > 0 && glyphs.getAdvance() > 0f
        }.getOrDefault(false)
    } else {
        false
    }
    runShaperSupported = supported
    return supported
}

private fun checkRunAdvanceSupport(paint: TextPaint): Boolean {
    runAdvanceSupported?.let { return it }
    val supported = runCatching {
        paint.getRunAdvance("A", 0, 1, 0, 1, false, 1) > 0f
    }.getOrDefault(false)
    runAdvanceSupported = supported
    return supported
}

internal fun resolveTypesetTextSizePx(
    baseSizePx: Float,
    spanFontSize: TextUnit,
    density: Density,
): Float {
    if (!spanFontSize.isSpecified) return baseSizePx
    return when {
        spanFontSize.isEm -> baseSizePx * spanFontSize.value
        else -> with(density) { spanFontSize.toPx() }
    }.coerceAtLeast(1f)
}

internal fun resolveTypesetLetterSpacingEm(
    letterSpacing: TextUnit,
    textSizePx: Float,
    density: Density,
): Float {
    if (!letterSpacing.isSpecified) return 0f
    return when {
        letterSpacing.isEm -> letterSpacing.value
        else -> with(density) { letterSpacing.toPx() } / textSizePx.coerceAtLeast(1f)
    }
}

internal fun prepareTypesetInput(
    annotated: AnnotatedString,
    baseStyle: TextStyle,
    defaultColor: Color,
    codeFontFamily: FontFamily?,
    inlineFormulas: Map<String, TypesetInlineObjectSpec>,
    density: Density,
    fontFamilyResolver: FontFamily.Resolver,
): TypesetPreparedInput {
    val text = annotated.text
    val baseSizePx = with(density) {
        (if (baseStyle.fontSize.isSpecified) baseStyle.fontSize else 16.sp).toPx()
    }.coerceAtLeast(1f)
    val baseColor = when {
        defaultColor != Color.Unspecified -> defaultColor
        baseStyle.color != Color.Unspecified -> baseStyle.color
        else -> Color.Black
    }

    val baseSpan = SpanStyle(
        color = baseColor,
        fontSize = with(density) { baseSizePx.toSp() },
        fontWeight = baseStyle.fontWeight ?: FontWeight.Normal,
        fontStyle = baseStyle.fontStyle ?: FontStyle.Normal,
        fontSynthesis = baseStyle.fontSynthesis ?: FontSynthesis.All,
        fontFamily = baseStyle.fontFamily,
        fontFeatureSettings = baseStyle.fontFeatureSettings,
        letterSpacing = baseStyle.letterSpacing,
        background = baseStyle.background,
        textDecoration = baseStyle.textDecoration,
    )

    val boundaries = HashSet<Int>(annotated.spanStyles.size * 2 + 8)
    boundaries.add(0)
    boundaries.add(text.length)

    val styledRanges = ArrayList<AnnotatedString.Range<SpanStyle>>(annotated.spanStyles.size + 4)
    annotated.spanStyles.forEach { r ->
        val s = r.start.coerceIn(0, text.length)
        val e = r.end.coerceIn(s, text.length)
        if (e > s) {
            styledRanges.add(AnnotatedString.Range(r.item, s, e))
            boundaries.add(s)
            boundaries.add(e)
        }
    }
    annotated.getLinkAnnotations(0, text.length).forEach { r ->
        val linkSpan = r.item.styles?.style ?: return@forEach
        val s = r.start.coerceIn(0, text.length)
        val e = r.end.coerceIn(s, text.length)
        if (e > s) {
            styledRanges.add(AnnotatedString.Range(linkSpan, s, e))
            boundaries.add(s)
            boundaries.add(e)
        }
    }

    val inlineRanges = annotated.getStringAnnotations(INLINE_CONTENT_TAG, 0, text.length)
        .filter { it.end > it.start && it.item in inlineFormulas }
    inlineRanges.forEach { r ->
        boundaries.add(r.start.coerceIn(0, text.length))
        boundaries.add(r.end.coerceIn(0, text.length))
    }

    val sortedBounds = boundaries.toIntArray()
    sortedBounds.sort()

    val styleList = ArrayList<TypesetResolvedStyle>(8)
    val styleIndexMap = HashMap<TypesetResolvedStyle, Int>(8)

    fun resolveStyleId(merged: SpanStyle): Int {
        val family = merged.fontFamily ?: baseStyle.fontFamily
        val weight = merged.fontWeight ?: baseStyle.fontWeight ?: FontWeight.Normal
        val fontStyle = merged.fontStyle ?: baseStyle.fontStyle ?: FontStyle.Normal
        val synthesis = merged.fontSynthesis ?: baseStyle.fontSynthesis ?: FontSynthesis.All
        val typeface = runCatching {
            fontFamilyResolver.resolve(family, weight, fontStyle, synthesis).value as? Typeface
        }.getOrNull() ?: Typeface.DEFAULT
        val sizePx = resolveTypesetTextSizePx(baseSizePx, merged.fontSize, density)
        val letterSp = resolveTypesetLetterSpacingEm(merged.letterSpacing, sizePx, density)
        val fg = if (merged.color != Color.Unspecified) merged.color else baseColor
        val bg = if (merged.background != Color.Unspecified && merged.background != Color.Transparent) {
            merged.background.toArgb()
        } else {
            0
        }
        val dec = merged.textDecoration
        val isCode = (codeFontFamily != null && family == codeFontFamily) || family == FontFamily.Monospace
        val resolved = TypesetResolvedStyle(
            typeface = typeface,
            textSizePx = sizePx,
            letterSpacingEm = letterSp,
            colorArgb = fg.toArgb(),
            backgroundArgb = bg,
            underline = dec != null && TextDecoration.Underline in dec,
            lineThrough = dec != null && TextDecoration.LineThrough in dec,
            fontFeatureSettings = merged.fontFeatureSettings,
            isCode = isCode,
        )
        return styleIndexMap.getOrPut(resolved) {
            val idx = styleList.size
            styleList.add(resolved)
            idx
        }
    }

    val baseStyleId = resolveStyleId(baseSpan)
    val runs = ArrayList<TypesetRunInput>(sortedBounds.size)

    for (b in 0 until sortedBounds.size - 1) {
        val segStart = sortedBounds[b]
        val segEnd = sortedBounds[b + 1]
        if (segEnd <= segStart) continue

        val inlineAnn = inlineRanges.firstOrNull { segStart >= it.start && segEnd <= it.end }
        if (inlineAnn != null) {
            val spec = inlineFormulas[inlineAnn.item]
            if (spec != null) {
                runs.add(
                    TypesetRunInput(
                        start = segStart,
                        end = segEnd,
                        styleId = baseStyleId,
                        kind = TypesetSpanKind.INLINE_OBJECT,
                        objectId = inlineAnn.item,
                        objectWidthPx = spec.widthPx,
                        objectHeightPx = spec.heightPx,
                        objectBaselineFraction = spec.baselineFraction,
                    ),
                )
                continue
            }
        }

        var merged = baseSpan
        for (sr in styledRanges) {
            if (sr.start <= segStart && sr.end >= segEnd) {
                merged = merged.merge(sr.item)
            }
        }
        val styleId = resolveStyleId(merged)
        val kind = if (styleList[styleId].isCode) TypesetSpanKind.CODE else TypesetSpanKind.NORMAL
        runs.add(TypesetRunInput(segStart, segEnd, styleId, kind))
    }

    val basePaint = buildTextPaint(styleList[baseStyleId])
    val fm = basePaint.fontMetrics
    val naturalAscent = -fm.ascent
    val naturalDescent = fm.descent
    val naturalHeight = (naturalAscent + naturalDescent).coerceAtLeast(baseSizePx)
    val requestedLineHeight = when {
        baseStyle.lineHeight.isSpecified && baseStyle.lineHeight.isEm -> baseSizePx * baseStyle.lineHeight.value
        baseStyle.lineHeight.isSpecified -> with(density) { baseStyle.lineHeight.toPx() }
        else -> naturalHeight * 1.25f
    }.coerceAtLeast(naturalHeight)
    val extraLeading = (requestedLineHeight - naturalHeight).coerceAtLeast(0f)
    val baselineOffset = extraLeading * 0.45f + naturalAscent

    return TypesetPreparedInput(
        text = text,
        runs = runs,
        styles = styleList,
        baseLineMetrics = TypesetLineMetrics(
            lineHeight = requestedLineHeight,
            baselineOffset = baselineOffset,
            ascent = baselineOffset,
            descent = requestedLineHeight - baselineOffset,
        ),
    )
}

internal data class TypesetInlineObjectSpec(
    val widthPx: Float,
    val heightPx: Float,
    val baselineFraction: Float,
)

private const val INLINE_CONTENT_TAG = "androidx.compose.foundation.text.inlineContent"

internal fun buildTextPaint(style: TypesetResolvedStyle): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
    typeface = style.typeface
    textSize = style.textSizePx
    letterSpacing = style.letterSpacingEm
    color = style.colorArgb
    fontFeatureSettings = style.fontFeatureSettings
}

internal class AndroidTypesetMeasurer(
    private val prepared: TypesetPreparedInput,
) : TypesetMeasurer {
    private val paints = Array(prepared.styles.size) { buildTextPaint(prepared.styles[it]) }
    private val spaceWidths = FloatArray(prepared.styles.size) { -1f }
    private val hyphenWidths = FloatArray(prepared.styles.size) { -1f }
    private val emWidths = FloatArray(prepared.styles.size) { -1f }
    private val singleCharWidthCache = Array(prepared.styles.size) { HashMap<Char, Float>(32) }

    override fun baseLineMetrics(): TypesetLineMetrics = prepared.baseLineMetrics

    override fun measureWidth(text: String, start: Int, end: Int, styleId: Int): Float {
        if (end <= start) return 0f
        val safeId = styleId.coerceIn(0, paints.lastIndex)
        val paint = paints[safeId]
        if (end - start == 1) {
            val ch = text[start]
            return singleCharWidthCache[safeId].getOrPut(ch) { measureSlice(paint, text, start, end) }
        }
        return measureSlice(paint, text, start, end)
    }

    override fun measureSpaceWidth(styleId: Int): Float {
        val safeId = styleId.coerceIn(0, paints.lastIndex)
        val cached = spaceWidths[safeId]
        if (cached >= 0f) return cached
        val measured = measureSlice(paints[safeId], " ", 0, 1).let { if (it > 0.5f) it else paints[safeId].textSize * 0.28f }
        spaceWidths[safeId] = measured
        return measured
    }

    override fun measureHyphenWidth(styleId: Int): Float {
        val safeId = styleId.coerceIn(0, paints.lastIndex)
        val cached = hyphenWidths[safeId]
        if (cached >= 0f) return cached
        val measured = measureSlice(paints[safeId], "-", 0, 1).let { if (it > 0.5f) it else paints[safeId].textSize * 0.34f }
        hyphenWidths[safeId] = measured
        return measured
    }

    override fun measureEmWidth(styleId: Int): Float {
        val safeId = styleId.coerceIn(0, paints.lastIndex)
        val cached = emWidths[safeId]
        if (cached >= 0f) return cached
        val paint = paints[safeId]
        val measured = measureSlice(paint, "汉", 0, 1).let { if (it > 0.5f) it else paint.textSize }
        emWidths[safeId] = measured
        return measured
    }

    override fun measurePunctuationTrim(ch: Char, styleId: Int): PunctuationTrim {
        val safeId = styleId.coerceIn(0, paints.lastIndex)
        val paint = paints[safeId]
        val style = prepared.styles[safeId]
        val key = PunctCacheKey(
            typefaceIdentity = System.identityHashCode(style.typeface),
            sizeTenthsPx = (style.textSizePx * 10f).roundToInt(),
            ch = ch,
        )
        synchronized(punctuationTrimCache) {
            punctuationTrimCache.get(key)?.let { return it }
        }
        val computed = computePunctuationTrim(ch, paint)
        synchronized(punctuationTrimCache) {
            punctuationTrimCache.put(key, computed)
        }
        return computed
    }

    private fun measureSlice(paint: TextPaint, text: String, start: Int, end: Int): Float {
        if (checkRunAdvanceSupport(paint)) {
            val adv = runCatching { paint.getRunAdvance(text, start, end, start, end, false, end) }.getOrDefault(0f)
            if (adv > 0f) return adv
        }
        val measured = paint.measureText(text, start, end)
        if (measured > 0f) return measured
        return (end - start) * paint.textSize * 0.6f
    }

    private fun computePunctuationTrim(ch: Char, paint: TextPaint): PunctuationTrim {
        val advance = measureSlice(paint, ch.toString(), 0, 1).coerceAtLeast(paint.textSize * 0.5f)
        if (Build.VERSION.SDK_INT >= 31 && checkRunShaperSupport(paint)) {
            val optical = runCatching {
                val glyphs = TextRunShaper.shapeTextRun(ch.toString(), 0, 1, 0, 1, 0f, 0f, false, paint)
                if (glyphs.glyphCount() > 0) {
                    val rect = RectF()
                    val glyphAdvance = glyphs.getAdvance().coerceAtLeast(advance)
                    glyphs.getFont(0).getGlyphBounds(glyphs.getGlyphId(0), paint, rect)
                    val inkLeft = rect.left.coerceIn(0f, glyphAdvance)
                    val inkRight = rect.right.coerceIn(inkLeft, glyphAdvance)
                    val leftSpace = max(0f, inkLeft - glyphAdvance * 0.06f)
                    val rightSpace = max(0f, glyphAdvance - inkRight - glyphAdvance * 0.06f)
                    when {
                        TypesetEngine.isCjkOpenPunct(ch) -> PunctuationTrim(
                            leftTrim = min(leftSpace, glyphAdvance * 0.48f),
                        )
                        TypesetEngine.isCjkClosePunct(ch) -> PunctuationTrim(
                            rightTrim = min(rightSpace, glyphAdvance * 0.48f),
                        )
                        TypesetEngine.isCjkStopPunct(ch) -> PunctuationTrim(
                            rightTrim = min(rightSpace, glyphAdvance * 0.48f),
                            hangRight = min(rightSpace + glyphAdvance * 0.07f, glyphAdvance * 0.46f),
                        )
                        TypesetEngine.isCjkMidPunct(ch) -> PunctuationTrim(
                            rightTrim = min(rightSpace, glyphAdvance * 0.40f),
                            hangRight = if (ch == '：' || ch == '；') min(rightSpace + glyphAdvance * 0.04f, glyphAdvance * 0.28f) else 0f,
                        )
                        else -> PunctuationTrim()
                    }
                } else null
            }.getOrNull()
            if (optical != null) return optical
        }
        return when {
            TypesetEngine.isCjkOpenPunct(ch) -> PunctuationTrim(leftTrim = advance * 0.40f)
            TypesetEngine.isCjkClosePunct(ch) -> PunctuationTrim(rightTrim = advance * 0.40f)
            TypesetEngine.isCjkStopPunct(ch) -> PunctuationTrim(rightTrim = advance * 0.42f, hangRight = advance * 0.42f)
            TypesetEngine.isCjkMidPunct(ch) -> PunctuationTrim(
                rightTrim = advance * 0.30f,
                hangRight = if (ch == '：' || ch == '；') advance * 0.22f else 0f,
            )
            else -> PunctuationTrim()
        }
    }
}

internal fun drawTypesetLayout(
    canvas: Canvas,
    layout: TypesetLayout,
    styles: List<TypesetResolvedStyle>,
    overrideColorArgb: Int = 0,
) {
    val nativeCanvas = canvas.nativeCanvas
    val text = layout.text
    if (text.isEmpty() || styles.isEmpty()) return

    val paints = Array(styles.size) { idx ->
        buildTextPaint(styles[idx]).apply {
            if (overrideColorArgb != 0 && styles[idx].colorArgb == styles[0].colorArgb) {
                color = overrideColorArgb
            }
        }
    }
    val decoPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val useRunDraw = checkRunAdvanceSupport(paints[0])

    for (line in layout.lines) {
        val pieces = line.pieces
        var pIdx = 0
        while (pIdx < pieces.size) {
            val first = pieces[pIdx]
            if (first.kind == TypesetSpanKind.INLINE_OBJECT) {
                pIdx++
                continue
            }
            val styleId = first.styleId.coerceIn(0, styles.lastIndex)
            val style = styles[styleId]
            val paint = paints[styleId]

            var runEndPiece = pIdx
            var runCharEnd = first.charEnd
            var runWidth = first.width
            var prevRight = first.drawX + first.width
            while (runEndPiece + 1 < pieces.size) {
                val next = pieces[runEndPiece + 1]
                if (next.kind == first.kind &&
                    next.styleId == first.styleId &&
                    next.charStart == runCharEnd &&
                    abs(next.scaleX - first.scaleX) < 0.0005f &&
                    abs(next.drawX - prevRight) < 0.35f
                ) {
                    runEndPiece++
                    runCharEnd = next.charEnd
                    runWidth += next.width
                    prevRight = next.drawX + next.width
                } else {
                    break
                }
            }

            if (style.backgroundArgb != 0) {
                decoPaint.color = style.backgroundArgb
                decoPaint.style = Paint.Style.FILL
                nativeCanvas.drawRect(first.x, line.top, first.x + runWidth, line.bottom, decoPaint)
            }

            paint.textScaleX = first.scaleX
            if (useRunDraw) {
                nativeCanvas.drawTextRun(
                    text,
                    first.charStart,
                    runCharEnd,
                    first.charStart,
                    runCharEnd,
                    first.drawX,
                    line.baseline,
                    false,
                    paint,
                )
            } else {
                nativeCanvas.drawText(text, first.charStart, runCharEnd, first.drawX, line.baseline, paint)
            }

            if (style.underline) {
                decoPaint.color = paint.color
                decoPaint.style = Paint.Style.STROKE
                decoPaint.strokeWidth = max(1f, style.textSizePx * 0.068f)
                val underlineY = min(line.bottom - decoPaint.strokeWidth * 0.5f, line.baseline + style.textSizePx * 0.16f)
                nativeCanvas.drawLine(first.x, underlineY, first.x + runWidth, underlineY, decoPaint)
            }

            if (style.lineThrough) {
                decoPaint.color = paint.color
                decoPaint.style = Paint.Style.STROKE
                decoPaint.strokeWidth = max(1f, style.textSizePx * 0.068f)
                val strikeY = line.baseline - style.textSizePx * 0.30f
                nativeCanvas.drawLine(first.x, strikeY, first.x + runWidth, strikeY, decoPaint)
            }

            pIdx = runEndPiece + 1
        }

        if (line.trailingHyphenX.isFinite() && line.trailingHyphenStyleId >= 0) {
            val hPaint = paints[line.trailingHyphenStyleId.coerceIn(0, paints.lastIndex)]
            hPaint.textScaleX = 1f
            nativeCanvas.drawText("-", line.trailingHyphenX, line.baseline, hPaint)
        }
    }
}
