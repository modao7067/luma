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
    val baselineShiftMultiplier: Float = 0f,
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
    val fontFeatureSettings: String?,
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
    styleCache: TypesetStyleMeasurementCache? = null,
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
        baselineShift = baseStyle.baselineShift,
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
        val typeface = styleCache?.resolveTypeface(fontFamilyResolver, family, weight, fontStyle, synthesis)
            ?: runCatching {
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
            baselineShiftMultiplier = merged.baselineShift?.multiplier ?: 0f,
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

    val fm = styleCache?.get(styleList[baseStyleId])?.fontMetrics ?: buildTextPaint(styleList[baseStyleId]).fontMetrics
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

private const val CJK_TYPESET_DISABLED_FEATURES = "'chws' 0, 'halt' 0, 'palt' 0"

internal fun resolveTypesetFontFeatureSettings(
    fontFeatureSettings: String?,
    isCode: Boolean,
): String? {
    if (isCode) return fontFeatureSettings
    val raw = fontFeatureSettings?.trim().orEmpty()
    if (raw.isEmpty()) return CJK_TYPESET_DISABLED_FEATURES
    val extras = ArrayList<String>(3)
    if (!raw.contains("chws", ignoreCase = true)) extras.add("'chws' 0")
    if (!raw.contains("halt", ignoreCase = true)) extras.add("'halt' 0")
    if (!raw.contains("palt", ignoreCase = true)) extras.add("'palt' 0")
    return if (extras.isEmpty()) raw else "$raw, ${extras.joinToString(", ")}"
}

internal fun resolveCjkPunctuationWidth(
    ch: Char,
    isolatedAdvance: Float,
    contextRunAdvance: Float,
    contextMeasureAdvance: Float,
    shapedContextAdvance: Float,
    shapedAdjacentAdvance: Float,
    drawnInkLeft: Float,
    drawnInkRight: Float,
    emWidth: Float,
): Float {
    var resolved = isolatedAdvance
    if (contextRunAdvance.isFinite() && contextRunAdvance > resolved) resolved = contextRunAdvance
    if (contextMeasureAdvance.isFinite() && contextMeasureAdvance > resolved) resolved = contextMeasureAdvance
    if (shapedContextAdvance.isFinite() && shapedContextAdvance > resolved) resolved = shapedContextAdvance
    if (shapedAdjacentAdvance.isFinite() && shapedAdjacentAdvance > resolved) resolved = shapedAdjacentAdvance
    if (TypesetEngine.isCjkOpenPunct(ch) &&
        drawnInkLeft.isFinite() &&
        drawnInkRight.isFinite() &&
        emWidth > 0f &&
        drawnInkLeft >= emWidth * 0.30f &&
        drawnInkRight >= emWidth * 0.72f &&
        drawnInkRight > resolved + emWidth * 0.05f
    ) {
        resolved = max(resolved, emWidth)
    }
    return resolved
}

internal fun buildTextPaint(style: TypesetResolvedStyle): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
    typeface = style.typeface
    textSize = style.textSizePx
    letterSpacing = style.letterSpacingEm
    color = style.colorArgb
    fontFeatureSettings = resolveTypesetFontFeatureSettings(style.fontFeatureSettings, style.isCode)
}

internal class TypesetStyleMeasurementCache {
    private data class TypefaceKey(
        val family: FontFamily?,
        val weight: FontWeight,
        val style: FontStyle,
        val synthesis: FontSynthesis,
    )

    internal class Entry(val paint: TextPaint) {
        val fontMetrics: Paint.FontMetrics = paint.fontMetrics
        var spaceWidth: Float = -1f
        var hyphenWidth: Float = -1f
        var emWidth: Float = -1f
        var hanSingleShapedWidth: Float = -1f
        var hanPairShapedWidth: Float = -1f
        var hanPairMeasureWidth: Float = -1f
        val singleCharWidths = HashMap<Char, Float>(64)
        val sliceWidths = HashMap<String, Float>(128)
    }

    private val entries = HashMap<TypesetResolvedStyle, Entry>(8)
    private val typefaces = HashMap<TypefaceKey, Typeface>(8)

    fun resolveTypeface(
        resolver: FontFamily.Resolver,
        family: FontFamily?,
        weight: FontWeight,
        style: FontStyle,
        synthesis: FontSynthesis,
    ): Typeface {
        val key = TypefaceKey(family, weight, style, synthesis)
        typefaces[key]?.let { return it }
        if (typefaces.size >= MAX_STYLE_ENTRIES) {
            typefaces.clear()
        }
        val resolved = runCatching {
            resolver.resolve(family, weight, style, synthesis).value as? Typeface
        }.getOrNull() ?: Typeface.DEFAULT
        typefaces[key] = resolved
        return resolved
    }

    fun get(style: TypesetResolvedStyle): Entry {
        if (entries.size >= MAX_STYLE_ENTRIES && style !in entries) {
            entries.clear()
        }
        return entries.getOrPut(style) { Entry(buildTextPaint(style)) }
    }

    companion object {
        private const val MAX_STYLE_ENTRIES = 32
        internal const val MAX_SLICE_ENTRIES = 512
        internal const val MAX_CACHED_SLICE_LEN = 48
    }
}

internal class TypesetDrawCache {
    private var cachedStyles: List<TypesetResolvedStyle>? = null
    private var cachedOverrideArgb: Int = 0
    private var cachedPaints: Array<TextPaint> = emptyArray()
    val decoPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun paintsFor(styles: List<TypesetResolvedStyle>, overrideColorArgb: Int): Array<TextPaint> {
        if (cachedOverrideArgb == overrideColorArgb && cachedPaints.size == styles.size) {
            if (cachedStyles === styles || cachedStyles == styles) {
                cachedStyles = styles
                return cachedPaints
            }
        }
        val built = Array(styles.size) { idx ->
            buildTextPaint(styles[idx]).apply {
                if (overrideColorArgb != 0 && styles[idx].colorArgb == styles[0].colorArgb) {
                    color = overrideColorArgb
                }
            }
        }
        cachedStyles = styles
        cachedOverrideArgb = overrideColorArgb
        cachedPaints = built
        return built
    }
}

internal class AndroidTypesetMeasurer(
    initialPrepared: TypesetPreparedInput? = null,
    private val styleCache: TypesetStyleMeasurementCache = TypesetStyleMeasurementCache(),
) : TypesetMeasurer {
    internal val cache: TypesetStyleMeasurementCache get() = styleCache
    private var prepared: TypesetPreparedInput? = null
    private var boundStyles: List<TypesetResolvedStyle> = emptyList()
    private var boundEntries: Array<TypesetStyleMeasurementCache.Entry> = emptyArray()
    private var scratchChars = CharArray(64)

    init {
        initialPrepared?.let(::bind)
    }

    fun bind(next: TypesetPreparedInput): AndroidTypesetMeasurer {
        prepared = next
        boundStyles = next.styles
        boundEntries = Array(next.styles.size) { styleCache.get(next.styles[it]) }
        return this
    }

    override fun baseLineMetrics(): TypesetLineMetrics = requireNotNull(prepared).baseLineMetrics

    override fun measureWidth(text: String, start: Int, end: Int, styleId: Int): Float {
        if (end <= start) return 0f
        val safeId = styleId.coerceIn(0, boundEntries.lastIndex)
        val entry = boundEntries[safeId]
        val paint = entry.paint
        val len = end - start
        if (len == 1) {
            val ch = text[start]
            return entry.singleCharWidths.getOrPut(ch) {
                if (TypesetEngine.isCjkPunctuation(ch)) {
                    measureCjkPunctuationWidth(entry, safeId, ch, text, start, end)
                } else {
                    measureSlice(paint, text, start, end)
                }
            }
        }
        if (len <= TypesetStyleMeasurementCache.MAX_CACHED_SLICE_LEN) {
            val key = text.substring(start, end)
            entry.sliceWidths[key]?.let { return it }
            val measured = measureSlice(paint, text, start, end)
            if (entry.sliceWidths.size >= TypesetStyleMeasurementCache.MAX_SLICE_ENTRIES) {
                entry.sliceWidths.clear()
            }
            entry.sliceWidths[key] = measured
            return measured
        }
        return measureSlice(paint, text, start, end)
    }

    override fun measureCharAdvances(text: String, start: Int, end: Int, styleId: Int, outAdvances: FloatArray) {
        val len = end - start
        if (len <= 0) return
        val entry = boundEntries[styleId.coerceIn(0, boundEntries.lastIndex)]
        val paint = entry.paint
        if (Build.VERSION.SDK_INT >= 29) {
            if (scratchChars.size < len) {
                scratchChars = CharArray(max(len, scratchChars.size * 2))
            }
            text.toCharArray(scratchChars, 0, start, end)
            val total = runCatching {
                paint.getTextRunAdvances(scratchChars, 0, len, 0, len, false, outAdvances, 0)
            }.getOrDefault(0f)
            if (total > 0f && total.isFinite()) return
        }
        val count = runCatching { paint.getTextWidths(text, start, end, outAdvances) }.getOrDefault(0)
        if (count == len) {
            var sum = 0f
            for (k in 0 until len) {
                val v = outAdvances[k]
                if (v > 0f && v.isFinite()) sum += v
            }
            if (sum > 0f) return
        }
        super.measureCharAdvances(text, start, end, styleId, outAdvances)
    }

    override fun measureSpaceWidth(styleId: Int): Float {
        val entry = boundEntries[styleId.coerceIn(0, boundEntries.lastIndex)]
        if (entry.spaceWidth >= 0f) return entry.spaceWidth
        val measured = measureSlice(entry.paint, " ", 0, 1).let { if (it > 0.5f) it else entry.paint.textSize * 0.28f }
        entry.spaceWidth = measured
        return measured
    }

    override fun measureHyphenWidth(styleId: Int): Float {
        val entry = boundEntries[styleId.coerceIn(0, boundEntries.lastIndex)]
        if (entry.hyphenWidth >= 0f) return entry.hyphenWidth
        val measured = measureSlice(entry.paint, "-", 0, 1).let { if (it > 0.5f) it else entry.paint.textSize * 0.34f }
        entry.hyphenWidth = measured
        return measured
    }

    override fun measureEmWidth(styleId: Int): Float {
        val entry = boundEntries[styleId.coerceIn(0, boundEntries.lastIndex)]
        if (entry.emWidth >= 0f) return entry.emWidth
        val paint = entry.paint
        val measured = measureSlice(paint, "汉", 0, 1).let { if (it > 0.5f) it else paint.textSize }
        entry.emWidth = measured
        return measured
    }

    override fun measurePunctuationTrim(ch: Char, styleId: Int): PunctuationTrim {
        val safeId = styleId.coerceIn(0, boundEntries.lastIndex)
        val entry = boundEntries[safeId]
        val paint = entry.paint
        val style = boundStyles[safeId]
        val key = PunctCacheKey(
            typefaceIdentity = System.identityHashCode(style.typeface),
            sizeTenthsPx = (style.textSizePx * 10f).roundToInt(),
            ch = ch,
            fontFeatureSettings = paint.fontFeatureSettings,
        )
        synchronized(punctuationTrimCache) {
            punctuationTrimCache.get(key)?.let { return it }
        }
        val chStr = ch.toString()
        val measuredAdvance = measureWidth(chStr, 0, 1, safeId)
        val computed = computePunctuationTrim(ch, entry, measuredAdvance)
        synchronized(punctuationTrimCache) {
            punctuationTrimCache.put(key, computed)
        }
        return computed
    }

    private fun hanSingleShapedWidth(entry: TypesetStyleMeasurementCache.Entry): Float {
        if (entry.hanSingleShapedWidth >= 0f) return entry.hanSingleShapedWidth
        val measured = if (Build.VERSION.SDK_INT >= 31 && checkRunShaperSupport(entry.paint)) {
            runCatching {
                TextRunShaper.shapeTextRun("汉", 0, 1, 0, 1, 0f, 0f, false, entry.paint).getAdvance()
            }.getOrDefault(0f)
        } else {
            0f
        }
        entry.hanSingleShapedWidth = if (measured > 0f && measured.isFinite()) measured else 0f
        return entry.hanSingleShapedWidth
    }

    private fun hanPairShapedWidth(entry: TypesetStyleMeasurementCache.Entry): Float {
        if (entry.hanPairShapedWidth >= 0f) return entry.hanPairShapedWidth
        val measured = if (Build.VERSION.SDK_INT >= 31 && checkRunShaperSupport(entry.paint)) {
            runCatching {
                TextRunShaper.shapeTextRun("汉汉", 0, 2, 0, 2, 0f, 0f, false, entry.paint).getAdvance()
            }.getOrDefault(0f)
        } else {
            0f
        }
        entry.hanPairShapedWidth = if (measured > 0f && measured.isFinite()) measured else 0f
        return entry.hanPairShapedWidth
    }

    private fun hanPairMeasureWidth(entry: TypesetStyleMeasurementCache.Entry): Float {
        if (entry.hanPairMeasureWidth >= 0f) return entry.hanPairMeasureWidth
        val measured = runCatching { entry.paint.measureText("汉汉") }.getOrDefault(0f)
        entry.hanPairMeasureWidth = if (measured > 0f && measured.isFinite()) measured else 0f
        return entry.hanPairMeasureWidth
    }

    private fun measureCjkPunctuationWidth(
        entry: TypesetStyleMeasurementCache.Entry,
        safeId: Int,
        ch: Char,
        text: String,
        start: Int,
        end: Int,
    ): Float {
        val paint = entry.paint
        val isoAdv = measureSlice(paint, text, start, end)
        val em = measureEmWidth(safeId)
        val midProbe = "汉${ch}汉"
        val adjacentProbe = if (TypesetEngine.isCjkOpenPunct(ch)) "${ch}汉" else "汉${ch}"

        val ctxRunAdv = if (checkRunAdvanceSupport(paint)) {
            runCatching { paint.getRunAdvance(midProbe, 1, 2, 0, 3, false, 2) }.getOrDefault(0f)
        } else {
            0f
        }
        val hanPairMeas = hanPairMeasureWidth(entry)
        val ctxMeasAdv = if (hanPairMeas > 0f) {
            runCatching { paint.measureText(midProbe) - hanPairMeas }.getOrDefault(0f)
        } else {
            0f
        }

        var shapedContextAdv = 0f
        var shapedAdjacentAdv = 0f
        var drawnInkLeft = Float.NaN
        var drawnInkRight = Float.NaN
        if (Build.VERSION.SDK_INT >= 31 && checkRunShaperSupport(paint)) {
            val hanPairShaped = hanPairShapedWidth(entry)
            if (hanPairShaped > 0f) {
                shapedContextAdv = runCatching {
                    TextRunShaper.shapeTextRun(midProbe, 0, 3, 0, 3, 0f, 0f, false, paint).getAdvance() - hanPairShaped
                }.getOrDefault(0f)
            }
            val hanSingleShaped = hanSingleShapedWidth(entry)
            if (hanSingleShaped > 0f) {
                runCatching {
                    val glyphs = TextRunShaper.shapeTextRun(
                        adjacentProbe,
                        0,
                        adjacentProbe.length,
                        0,
                        adjacentProbe.length,
                        0f,
                        0f,
                        false,
                        paint,
                    )
                    val adv = glyphs.getAdvance() - hanSingleShaped
                    if (adv > 0f && adv.isFinite()) {
                        shapedAdjacentAdv = adv
                    }
                    if (TypesetEngine.isCjkOpenPunct(ch) && glyphs.glyphCount() >= 2) {
                        val rect = RectF()
                        glyphs.getFont(0).getGlyphBounds(glyphs.getGlyphId(0), paint, rect)
                        val glyphX0 = glyphs.getGlyphX(0)
                        drawnInkLeft = glyphX0 + rect.left
                        drawnInkRight = glyphX0 + rect.right
                    }
                }
            }
        }

        return resolveCjkPunctuationWidth(
            ch = ch,
            isolatedAdvance = isoAdv,
            contextRunAdvance = ctxRunAdv,
            contextMeasureAdvance = ctxMeasAdv,
            shapedContextAdvance = shapedContextAdv,
            shapedAdjacentAdvance = shapedAdjacentAdv,
            drawnInkLeft = drawnInkLeft,
            drawnInkRight = drawnInkRight,
            emWidth = em,
        )
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

    private fun computePunctuationTrim(
        ch: Char,
        entry: TypesetStyleMeasurementCache.Entry,
        measuredAdvance: Float,
    ): PunctuationTrim {
        val paint = entry.paint
        val advance = measuredAdvance.coerceAtLeast(paint.textSize * 0.5f)
        if (Build.VERSION.SDK_INT >= 31 && checkRunShaperSupport(paint)) {
            val optical = runCatching {
                val isOpen = TypesetEngine.isCjkOpenPunct(ch)
                val probe = if (isOpen) "${ch}汉" else "汉${ch}"
                val hanAdv = hanSingleShapedWidth(entry)
                val glyphs = TextRunShaper.shapeTextRun(probe, 0, probe.length, 0, probe.length, 0f, 0f, false, paint)
                val usePaired = hanAdv > 0f && glyphs.glyphCount() >= 2
                val targetGlyphs = if (usePaired) {
                    glyphs
                } else {
                    TextRunShaper.shapeTextRun(ch.toString(), 0, 1, 0, 1, 0f, 0f, false, paint)
                }
                if (targetGlyphs.glyphCount() > 0) {
                    val targetIdx = if (usePaired && !isOpen) targetGlyphs.glyphCount() - 1 else 0
                    val rect = RectF()
                    val shapedAdv = if (usePaired) {
                        (targetGlyphs.getAdvance() - hanAdv).takeIf { it > 0f && it.isFinite() } ?: advance
                    } else {
                        targetGlyphs.getAdvance()
                    }
                    val glyphAdvance = max(shapedAdv, advance)
                    val glyphOffsetX = if (usePaired && !isOpen) {
                        targetGlyphs.getGlyphX(targetIdx) - hanAdv
                    } else {
                        targetGlyphs.getGlyphX(targetIdx)
                    }
                    targetGlyphs.getFont(targetIdx).getGlyphBounds(targetGlyphs.getGlyphId(targetIdx), paint, rect)
                    val inkLeft = (glyphOffsetX + rect.left).coerceIn(0f, glyphAdvance)
                    val inkRight = (glyphOffsetX + rect.right).coerceIn(inkLeft, glyphAdvance)
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
    drawCache: TypesetDrawCache? = null,
) {
    val nativeCanvas = canvas.nativeCanvas
    val text = layout.text
    if (text.isEmpty() || styles.isEmpty()) return

    val paints = drawCache?.paintsFor(styles, overrideColorArgb) ?: Array(styles.size) { idx ->
        buildTextPaint(styles[idx]).apply {
            if (overrideColorArgb != 0 && styles[idx].colorArgb == styles[0].colorArgb) {
                color = overrideColorArgb
            }
        }
    }
    val decoPaint = drawCache?.decoPaint ?: Paint(Paint.ANTI_ALIAS_FLAG)
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
            var expectedNextX = first.x + first.width
            while (runEndPiece + 1 < pieces.size) {
                val next = pieces[runEndPiece + 1]
                if (next.kind == first.kind &&
                    next.styleId == first.styleId &&
                    next.charStart == runCharEnd &&
                    abs(next.scaleX - first.scaleX) < 0.0005f &&
                    abs(next.drawX - expectedNextX) < 0.05f
                ) {
                    runEndPiece++
                    runCharEnd = next.charEnd
                    runWidth += next.width
                    expectedNextX += next.width
                } else {
                    break
                }
            }

            if (style.backgroundArgb != 0) {
                decoPaint.color = style.backgroundArgb
                decoPaint.style = Paint.Style.FILL
                nativeCanvas.drawRect(first.x, line.top, first.x + runWidth, line.bottom, decoPaint)
            }

            paint.textScaleX = 1f
            val drawBaseline = if (style.baselineShiftMultiplier != 0f) {
                line.baseline + paint.ascent() * style.baselineShiftMultiplier
            } else {
                line.baseline
            }
            val scaled = abs(first.scaleX - 1f) > 0.0005f
            if (scaled) {
                nativeCanvas.save()
                nativeCanvas.translate(first.drawX, drawBaseline)
                nativeCanvas.scale(first.scaleX, 1f)
            }
            val textX = if (scaled) 0f else first.drawX
            val textY = if (scaled) 0f else drawBaseline
            if (useRunDraw) {
                nativeCanvas.drawTextRun(
                    text,
                    first.charStart,
                    runCharEnd,
                    first.charStart,
                    runCharEnd,
                    textX,
                    textY,
                    false,
                    paint,
                )
            } else {
                nativeCanvas.drawText(text, first.charStart, runCharEnd, textX, textY, paint)
            }
            if (scaled) {
                nativeCanvas.restore()
            }

            if (style.underline) {
                decoPaint.color = paint.color
                decoPaint.style = Paint.Style.STROKE
                decoPaint.strokeWidth = max(1f, style.textSizePx * 0.068f)
                val underlineY = min(line.bottom - decoPaint.strokeWidth * 0.5f, drawBaseline + style.textSizePx * 0.16f)
                nativeCanvas.drawLine(first.x, underlineY, first.x + runWidth, underlineY, decoPaint)
            }

            if (style.lineThrough) {
                decoPaint.color = paint.color
                decoPaint.style = Paint.Style.STROKE
                decoPaint.strokeWidth = max(1f, style.textSizePx * 0.068f)
                val strikeY = drawBaseline - style.textSizePx * 0.30f
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
