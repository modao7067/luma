package io.github.modao7067.luma.gui.ui

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

internal enum class TypesetMode {
    JUSTIFY,
    BALANCED_LEFT,
    STREAMING,
}

internal enum class TypesetSpanKind {
    NORMAL,
    CODE,
    INLINE_OBJECT,
}

internal data class TypesetRunInput(
    val start: Int,
    val end: Int,
    val styleId: Int,
    val kind: TypesetSpanKind = TypesetSpanKind.NORMAL,
    val objectId: String? = null,
    val objectWidthPx: Float = 0f,
    val objectHeightPx: Float = 0f,
    val objectBaselineFraction: Float = 0.78f,
)

internal data class PunctuationTrim(
    val leftTrim: Float = 0f,
    val rightTrim: Float = 0f,
    val hangRight: Float = 0f,
)

internal interface TypesetMeasurer {
    fun measureWidth(text: String, start: Int, end: Int, styleId: Int): Float
    fun measureSpaceWidth(styleId: Int): Float
    fun measureHyphenWidth(styleId: Int): Float
    fun measureEmWidth(styleId: Int): Float
    fun measurePunctuationTrim(ch: Char, styleId: Int): PunctuationTrim
    fun baseLineMetrics(): TypesetLineMetrics
}

internal data class TypesetLineMetrics(
    val lineHeight: Float,
    val baselineOffset: Float,
    val ascent: Float,
    val descent: Float,
)

internal data class TypesetPlacedPiece(
    val charStart: Int,
    val charEnd: Int,
    val styleId: Int,
    val kind: TypesetSpanKind,
    val x: Float,
    val drawX: Float,
    val width: Float,
    val scaleX: Float,
    val objectId: String? = null,
    val objectHeightPx: Float = 0f,
    val objectBaselineFraction: Float = 0.78f,
)

internal data class TypesetLine(
    val lineIndex: Int,
    val charStart: Int,
    val charEnd: Int,
    val visibleEnd: Int,
    val left: Float,
    val right: Float,
    val top: Float,
    val baseline: Float,
    val bottom: Float,
    val pieces: List<TypesetPlacedPiece>,
    val trailingHyphenX: Float = Float.NaN,
    val trailingHyphenStyleId: Int = -1,
    val justified: Boolean = false,
)

internal data class TypesetPlaceholderPlacement(
    val objectId: String,
    val charIndex: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

internal data class TypesetLayout(
    val text: String,
    val maxWidth: Float,
    val width: Float,
    val height: Float,
    val lines: List<TypesetLine>,
    val charLine: IntArray,
    val charLeft: FloatArray,
    val charRight: FloatArray,
    val placeholders: List<TypesetPlaceholderPlacement>,
    val minIntrinsicWidth: Float,
    val maxIntrinsicWidth: Float,
    val justifiedParagraph: Boolean,
)

internal object TypesetEngine {
    private const val INF_PENALTY = 10_000f
    private const val LINE_PENALTY = 10f
    private const val FLAGGED_DEMERIT = 3_000f
    private const val FITNESS_DEMERIT = 3_000f
    private const val MAX_HZ_EXPAND = 1.022f
    private const val MIN_HZ_SHRINK = 0.980f

    private const val TIER_PUNCT = 0
    private const val TIER_CJK_LATIN = 1
    private const val TIER_WORD = 2
    private const val TIER_CJK = 3
    private const val TIER_RIGID = 4

    private enum class AtomClass {
        CJK_IDEOGRAPH,
        CJK_OPEN,
        CJK_CLOSE,
        CJK_STOP,
        CJK_MID,
        INSEPARABLE,
        HALF_OPEN,
        HALF_CLOSE,
        LATIN,
        CODE,
        INLINE_OBJECT,
        SPACE,
        OTHER,
    }

    private sealed interface KpItem {
        val width: Float
    }

    private data class KpBox(
        override val width: Float,
        val charStart: Int,
        val charEnd: Int,
        val styleId: Int,
        val kind: TypesetSpanKind,
        val atomClass: AtomClass,
        val leftTrim: Float = 0f,
        val rightTrim: Float = 0f,
        val hangRight: Float = 0f,
        val objectId: String? = null,
        val objectHeightPx: Float = 0f,
        val objectBaselineFraction: Float = 0.78f,
    ) : KpItem

    private data class KpGlue(
        override val width: Float,
        val stretch: Float,
        val shrink: Float,
        val tier: Int,
        val charStart: Int = -1,
        val charEnd: Int = -1,
    ) : KpItem

    private data class KpPenalty(
        override val width: Float,
        val penalty: Float,
        val flagged: Boolean = false,
        val hyphenStyleId: Int = -1,
        val isHyphen: Boolean = false,
    ) : KpItem

    private class ActiveNode(
        val index: Int,
        val line: Int,
        val fitnessClass: Int,
        val totalDemerits: Double,
        val flagged: Boolean,
        val previous: ActiveNode?,
        val ratio: Float,
    )

    private data class BreakChain(
        val indices: IntArray,
        val maxNonFinalAbsRatio: Float,
        val hadOverfull: Boolean,
    )

    fun layout(
        text: String,
        runs: List<TypesetRunInput>,
        maxWidthPx: Float,
        mode: TypesetMode,
        measurer: TypesetMeasurer,
    ): TypesetLayout {
        val lineMetrics = measurer.baseLineMetrics()
        val safeMaxWidth = if (maxWidthPx.isFinite() && maxWidthPx > 0f) maxWidthPx else Float.POSITIVE_INFINITY
        if (text.isEmpty()) {
            val emptyLine = TypesetLine(
                lineIndex = 0,
                charStart = 0,
                charEnd = 0,
                visibleEnd = 0,
                left = 0f,
                right = 0f,
                top = 0f,
                baseline = lineMetrics.baselineOffset,
                bottom = lineMetrics.lineHeight,
                pieces = emptyList(),
            )
            return TypesetLayout(
                text = text,
                maxWidth = if (safeMaxWidth.isFinite()) safeMaxWidth else 0f,
                width = 0f,
                height = lineMetrics.lineHeight,
                lines = listOf(emptyLine),
                charLine = IntArray(0),
                charLeft = FloatArray(0),
                charRight = FloatArray(0),
                placeholders = emptyList(),
                minIntrinsicWidth = 0f,
                maxIntrinsicWidth = 0f,
                justifiedParagraph = false,
            )
        }

        val normalizedRuns = normalizeRuns(text.length, runs)
        val segments = splitHardParagraphs(text)
        val allLines = ArrayList<TypesetLine>(segments.size * 2)
        val placeholders = ArrayList<TypesetPlaceholderPlacement>()
        val charLine = IntArray(text.length) { -1 }
        val charLeft = FloatArray(text.length)
        val charRight = FloatArray(text.length)
        var currentTop = 0f
        var maxLineRight = 0f
        var minIntrinsic = 0f
        var maxIntrinsic = 0f
        var anyJustified = false

        val emWidth = measurer.measureEmWidth(normalizedRuns.firstOrNull()?.styleId ?: 0).coerceAtLeast(1f)

        for (segIdx in segments.indices) {
            val seg = segments[segIdx]
            val segStart = seg.first
            val segEnd = seg.second
            val hasTrailingNewline = segEnd < text.length && text[segEnd] == '\n'
            val lineEndWithNewline = if (hasTrailingNewline) segEnd + 1 else segEnd

            if (segStart == segEnd) {
                val lineIdx = allLines.size
                val line = TypesetLine(
                    lineIndex = lineIdx,
                    charStart = segStart,
                    charEnd = lineEndWithNewline,
                    visibleEnd = segStart,
                    left = 0f,
                    right = 0f,
                    top = currentTop,
                    baseline = currentTop + lineMetrics.baselineOffset,
                    bottom = currentTop + lineMetrics.lineHeight,
                    pieces = emptyList(),
                )
                allLines.add(line)
                if (hasTrailingNewline) {
                    charLine[segEnd] = lineIdx
                    charLeft[segEnd] = 0f
                    charRight[segEnd] = 0f
                }
                currentTop = line.bottom
                continue
            }

            val built = buildItems(
                text = text,
                segStart = segStart,
                segEnd = segEnd,
                runs = normalizedRuns,
                measurer = measurer,
                maxWidthPx = safeMaxWidth,
                emWidth = emWidth,
                allowHyphenation = mode != TypesetMode.STREAMING,
            )
            val items = built.items
            minIntrinsic = max(minIntrinsic, built.minWordWidth)
            maxIntrinsic = max(maxIntrinsic, built.totalNaturalWidth)

            val unbounded = !safeMaxWidth.isFinite()
            val codeDense = built.visibleChars > 0 && (built.codeChars.toFloat() / built.visibleChars.toFloat()) >= 0.52f
            val tooNarrowForJustify = safeMaxWidth < emWidth * 10.5f

            val effectiveMode = when {
                unbounded -> TypesetMode.STREAMING
                mode == TypesetMode.STREAMING -> TypesetMode.STREAMING
                mode == TypesetMode.BALANCED_LEFT -> TypesetMode.BALANCED_LEFT
                codeDense || tooNarrowForJustify -> TypesetMode.BALANCED_LEFT
                else -> TypesetMode.JUSTIFY
            }

            val breakChain = when (effectiveMode) {
                TypesetMode.STREAMING -> solveGreedy(items, safeMaxWidth)
                TypesetMode.BALANCED_LEFT -> solveBalancedLeft(items, safeMaxWidth, emWidth)
                TypesetMode.JUSTIFY -> solveKnuthPlassMultiPass(items, safeMaxWidth, emWidth)
            }

            val allowJustifyThisSeg = effectiveMode == TypesetMode.JUSTIFY &&
                !breakChain.hadOverfull &&
                breakChain.maxNonFinalAbsRatio <= 2.75f

            val finalBreakChain = if (effectiveMode == TypesetMode.JUSTIFY && !allowJustifyThisSeg) {
                solveBalancedLeft(items, safeMaxWidth, emWidth)
            } else {
                breakChain
            }

            if (allowJustifyThisSeg) anyJustified = true

            val breaks = finalBreakChain.indices
            var prevBreak = 0
            for (bPos in 1 until breaks.size) {
                val endBreak = breaks[bPos]
                val isLastLineOfSeg = bPos == breaks.lastIndex
                val lineIdx = allLines.size
                val builtLine = materializeLine(
                    text = text,
                    items = items,
                    startIdx = prevBreak,
                    endIdx = endBreak,
                    isFirstLine = bPos == 1,
                    isLastLine = isLastLineOfSeg,
                    lineIndex = lineIdx,
                    targetWidth = safeMaxWidth,
                    justify = allowJustifyThisSeg && !isLastLineOfSeg,
                    top = currentTop,
                    baseMetrics = lineMetrics,
                    segStart = segStart,
                    segEnd = segEnd,
                    lineEndWithNewline = if (isLastLineOfSeg) lineEndWithNewline else -1,
                    measurer = measurer,
                    charLine = charLine,
                    charLeft = charLeft,
                    charRight = charRight,
                    placeholders = placeholders,
                )
                allLines.add(builtLine)
                maxLineRight = max(maxLineRight, builtLine.right)
                currentTop = builtLine.bottom
                prevBreak = endBreak
            }
        }

        if (allLines.isEmpty()) {
            allLines.add(
                TypesetLine(
                    lineIndex = 0,
                    charStart = 0,
                    charEnd = text.length,
                    visibleEnd = text.length,
                    left = 0f,
                    right = 0f,
                    top = 0f,
                    baseline = lineMetrics.baselineOffset,
                    bottom = lineMetrics.lineHeight,
                    pieces = emptyList(),
                ),
            )
            currentTop = lineMetrics.lineHeight
        }

        fillUnassignedCharMetrics(text.length, allLines, charLine, charLeft, charRight)

        val resolvedWidth = when {
            safeMaxWidth.isFinite() -> max(
                min(safeMaxWidth, max(maxLineRight, if (anyJustified && allLines.size > 1) safeMaxWidth else maxLineRight)),
                maxLineRight,
            )
            else -> maxLineRight
        }

        return TypesetLayout(
            text = text,
            maxWidth = if (safeMaxWidth.isFinite()) safeMaxWidth else resolvedWidth,
            width = resolvedWidth,
            height = currentTop.coerceAtLeast(lineMetrics.lineHeight),
            lines = allLines,
            charLine = charLine,
            charLeft = charLeft,
            charRight = charRight,
            placeholders = placeholders,
            minIntrinsicWidth = minIntrinsic,
            maxIntrinsicWidth = maxIntrinsic,
            justifiedParagraph = anyJustified,
        )
    }

    private fun normalizeRuns(length: Int, runs: List<TypesetRunInput>): List<TypesetRunInput> {
        if (runs.isEmpty()) return listOf(TypesetRunInput(0, length, 0, TypesetSpanKind.NORMAL))
        val out = ArrayList<TypesetRunInput>(runs.size)
        var cursor = 0
        for (run in runs) {
            val s = run.start.coerceIn(0, length)
            val e = run.end.coerceIn(s, length)
            if (s > cursor) out.add(TypesetRunInput(cursor, s, 0, TypesetSpanKind.NORMAL))
            if (e > s) {
                out.add(run.copy(start = s, end = e))
                cursor = e
            }
        }
        if (cursor < length) out.add(TypesetRunInput(cursor, length, 0, TypesetSpanKind.NORMAL))
        return out
    }

    private fun splitHardParagraphs(text: String): List<Pair<Int, Int>> {
        val result = ArrayList<Pair<Int, Int>>(2)
        var start = 0
        var i = 0
        while (i < text.length) {
            if (text[i] == '\n') {
                result.add(start to i)
                start = i + 1
            }
            i++
        }
        result.add(start to text.length)
        return result
    }

    private data class BuiltParagraph(
        val items: List<KpItem>,
        val minWordWidth: Float,
        val totalNaturalWidth: Float,
        val visibleChars: Int,
        val codeChars: Int,
    )

    private fun buildItems(
        text: String,
        segStart: Int,
        segEnd: Int,
        runs: List<TypesetRunInput>,
        measurer: TypesetMeasurer,
        maxWidthPx: Float,
        emWidth: Float,
        allowHyphenation: Boolean,
    ): BuiltParagraph {
        val items = ArrayList<KpItem>((segEnd - segStart) * 2 + 8)
        var prevAtom: AtomClass? = null
        var prevBox: KpBox? = null
        var pendingSpaceStart = -1
        var pendingSpaceEnd = -1
        var pendingSpaceStyle = 0
        var minWordWidth = 0f
        var totalNaturalWidth = 0f
        var visibleChars = 0
        var codeChars = 0

        fun addBox(box: KpBox) {
            items.add(box)
            totalNaturalWidth += box.width
            minWordWidth = max(minWordWidth, box.width)
            prevBox = box
            prevAtom = box.atomClass
        }

        fun addGlue(glue: KpGlue) {
            items.add(glue)
            totalNaturalWidth += glue.width
        }

        fun emitBoundaryBefore(nextAtom: AtomClass, nextBox: KpBox?, nextStyleId: Int) {
            val prev = prevAtom
            val pBox = prevBox
            if (prev == null || pBox == null) {
                if (pendingSpaceStart >= 0) {
                    val sw = measurer.measureSpaceWidth(pendingSpaceStyle)
                    addGlue(KpGlue(sw, 0f, 0f, TIER_RIGID, pendingSpaceStart, pendingSpaceEnd))
                    pendingSpaceStart = -1
                    pendingSpaceEnd = -1
                }
                return
            }

            val hadSpace = pendingSpaceStart >= 0
            val spStart = pendingSpaceStart
            val spEnd = pendingSpaceEnd
            val spStyle = pendingSpaceStyle
            pendingSpaceStart = -1
            pendingSpaceEnd = -1

            val cjkEm = measurer.measureEmWidth(nextStyleId).coerceAtLeast(1f)
            val prevIsCjkText = prev == AtomClass.CJK_IDEOGRAPH || prev == AtomClass.INSEPARABLE
            val nextIsCjkText = nextAtom == AtomClass.CJK_IDEOGRAPH || nextAtom == AtomClass.INSEPARABLE
            val prevIsLatinLike = prev == AtomClass.LATIN || prev == AtomClass.CODE || prev == AtomClass.INLINE_OBJECT
            val nextIsLatinLike = nextAtom == AtomClass.LATIN || nextAtom == AtomClass.CODE || nextAtom == AtomClass.INLINE_OBJECT

            if (hadSpace) {
                val spaceLen = spEnd - spStart
                if (spaceLen == 1 && (
                        (prevIsCjkText && (nextIsLatinLike || nextAtom == AtomClass.HALF_OPEN)) ||
                        ((prevIsLatinLike || prev == AtomClass.HALF_CLOSE) && nextIsCjkText)
                    )
                ) {
                    val gap = cjkEm * 0.20f
                    val stretch = cjkEm * 0.16f
                    val shrink = cjkEm * 0.085f
                    if (prev == AtomClass.HALF_OPEN || nextAtom == AtomClass.HALF_CLOSE) {
                        items.add(KpPenalty(0f, INF_PENALTY))
                    }
                    addGlue(KpGlue(gap, stretch, shrink, TIER_CJK_LATIN, spStart, spEnd))
                    return
                }
                if (spaceLen == 1 && prevIsCjkText && nextIsCjkText) {
                    val sw = measurer.measureSpaceWidth(spStyle) * 0.75f
                    addGlue(KpGlue(sw, sw * 0.8f, sw * 0.35f, TIER_WORD, spStart, spEnd))
                    return
                }
                val sw = measurer.measureSpaceWidth(spStyle) * spaceLen
                val isCodeSpace = prev == AtomClass.CODE && nextAtom == AtomClass.CODE
                if (isCodeSpace) {
                    addGlue(KpGlue(sw, 0f, 0f, TIER_RIGID, spStart, spEnd))
                } else {
                    addGlue(KpGlue(sw, sw * 0.95f, sw * 0.34f, TIER_WORD, spStart, spEnd))
                }
                return
            }

            if (prev == AtomClass.CJK_OPEN || prev == AtomClass.HALF_OPEN) {
                if (prev == AtomClass.CJK_OPEN && nextAtom == AtomClass.CJK_OPEN && nextBox != null) {
                    val compress = min(nextBox.leftTrim, cjkEm * 0.45f)
                    items.add(KpPenalty(0f, INF_PENALTY))
                    addGlue(KpGlue(-compress, compress * 0.45f, cjkEm * 0.05f, TIER_PUNCT))
                } else {
                    items.add(KpPenalty(0f, INF_PENALTY))
                }
                return
            }

            if (nextAtom == AtomClass.CJK_CLOSE || nextAtom == AtomClass.CJK_STOP || nextAtom == AtomClass.CJK_MID) {
                if (prev == AtomClass.CJK_CLOSE || prev == AtomClass.CJK_STOP || prev == AtomClass.CJK_MID) {
                    val compress = min(pBox.rightTrim, cjkEm * 0.45f)
                    items.add(KpPenalty(0f, INF_PENALTY))
                    addGlue(KpGlue(-compress, compress * 0.35f, cjkEm * 0.05f, TIER_PUNCT))
                } else {
                    items.add(KpPenalty(0f, INF_PENALTY))
                }
                return
            }

            if (nextAtom == AtomClass.HALF_CLOSE) {
                items.add(KpPenalty(0f, INF_PENALTY))
                return
            }

            if ((prev == AtomClass.CJK_CLOSE || prev == AtomClass.CJK_STOP || prev == AtomClass.CJK_MID) && nextAtom == AtomClass.CJK_OPEN && nextBox != null) {
                val compress = min(pBox.rightTrim + nextBox.leftTrim, cjkEm * 0.48f)
                items.add(KpPenalty(0f, 15f))
                addGlue(KpGlue(-compress, compress * 0.65f, cjkEm * 0.06f, TIER_PUNCT))
                return
            }

            if (prev == AtomClass.CJK_STOP || prev == AtomClass.CJK_MID) {
                val shrink = min(pBox.rightTrim, cjkEm * 0.38f)
                val stretch = cjkEm * 0.09f
                items.add(KpPenalty(0f, 8f))
                addGlue(KpGlue(0f, stretch, shrink, TIER_PUNCT))
                return
            }

            if (prev == AtomClass.CJK_CLOSE) {
                val shrink = min(pBox.rightTrim, cjkEm * 0.36f)
                val stretch = cjkEm * 0.10f
                items.add(KpPenalty(0f, 12f))
                addGlue(KpGlue(0f, stretch, shrink, TIER_PUNCT))
                return
            }

            if (nextAtom == AtomClass.CJK_OPEN && nextBox != null) {
                val shrink = min(nextBox.leftTrim, cjkEm * 0.36f)
                val stretch = cjkEm * 0.10f
                items.add(KpPenalty(0f, 15f))
                addGlue(KpGlue(0f, stretch, shrink, TIER_PUNCT))
                return
            }

            if ((prevIsCjkText && (nextIsLatinLike || nextAtom == AtomClass.HALF_OPEN)) ||
                ((prevIsLatinLike || prev == AtomClass.HALF_CLOSE) && nextIsCjkText)
            ) {
                val shouldInsertGap = when {
                    prev == AtomClass.CODE || prev == AtomClass.LATIN -> isAlnumOrIdeographCodePoint(Character.codePointBefore(text, pBox.charEnd))
                    (nextAtom == AtomClass.CODE || nextAtom == AtomClass.LATIN) && nextBox != null -> isAlnumOrIdeographCodePoint(Character.codePointAt(text, nextBox.charStart))
                    else -> true
                }
                if (shouldInsertGap) {
                    val gap = cjkEm * 0.19f
                    val stretch = cjkEm * 0.15f
                    val shrink = cjkEm * 0.08f
                    items.add(KpPenalty(0f, 14f))
                    addGlue(KpGlue(gap, stretch, shrink, TIER_CJK_LATIN))
                } else {
                    items.add(KpPenalty(0f, 18f))
                    addGlue(KpGlue(0f, cjkEm * 0.08f, 0f, TIER_CJK_LATIN))
                }
                return
            }

            if (prevIsCjkText && nextIsCjkText) {
                addGlue(KpGlue(0f, cjkEm * 0.085f, cjkEm * 0.022f, TIER_CJK))
                return
            }

            if (prev == AtomClass.CODE && nextAtom == AtomClass.LATIN || prev == AtomClass.LATIN && nextAtom == AtomClass.CODE) {
                items.add(KpPenalty(0f, 40f))
                addGlue(KpGlue(0f, cjkEm * 0.08f, 0f, TIER_CJK_LATIN))
                return
            }

            if (prevIsCjkText || nextIsCjkText) {
                addGlue(KpGlue(0f, cjkEm * 0.08f, cjkEm * 0.02f, TIER_CJK))
                return
            }

            if ((prev == AtomClass.OTHER && nextAtom == AtomClass.OTHER) ||
                (prev == AtomClass.INLINE_OBJECT && nextAtom == AtomClass.INLINE_OBJECT)
            ) {
                addGlue(KpGlue(0f, cjkEm * 0.06f, 0f, TIER_CJK))
                return
            }

            if (prev == AtomClass.OTHER || nextAtom == AtomClass.OTHER ||
                prev == AtomClass.INLINE_OBJECT || nextAtom == AtomClass.INLINE_OBJECT
            ) {
                items.add(KpPenalty(0f, 60f))
                addGlue(KpGlue(0f, cjkEm * 0.04f, 0f, TIER_CJK))
            }
        }

        for (run in runs) {
            val rStart = max(run.start, segStart)
            val rEnd = min(run.end, segEnd)
            if (rStart >= rEnd) continue

            if (run.kind == TypesetSpanKind.INLINE_OBJECT) {
                val box = KpBox(
                    width = run.objectWidthPx,
                    charStart = rStart,
                    charEnd = rEnd,
                    styleId = run.styleId,
                    kind = TypesetSpanKind.INLINE_OBJECT,
                    atomClass = AtomClass.INLINE_OBJECT,
                    objectId = run.objectId,
                    objectHeightPx = run.objectHeightPx,
                    objectBaselineFraction = run.objectBaselineFraction,
                )
                emitBoundaryBefore(AtomClass.INLINE_OBJECT, box, run.styleId)
                addBox(box)
                visibleChars += (rEnd - rStart)
                continue
            }

            if (run.kind == TypesetSpanKind.CODE) {
                var i = rStart
                while (i < rEnd) {
                    if (text[i] == ' ' || text[i] == '\t') {
                        val spStart = i
                        while (i < rEnd && (text[i] == ' ' || text[i] == '\t')) i++
                        if (pendingSpaceStart < 0) {
                            pendingSpaceStart = spStart
                            pendingSpaceEnd = i
                            pendingSpaceStyle = run.styleId
                        } else {
                            pendingSpaceEnd = i
                        }
                        continue
                    }
                    val tokStart = i
                    while (i < rEnd && text[i] != ' ' && text[i] != '\t') i++
                    val tokEnd = i
                    codeChars += (tokEnd - tokStart)
                    visibleChars += (tokEnd - tokStart)
                    val pieces = splitCodeToken(text, tokStart, tokEnd, run.styleId, measurer, maxWidthPx)
                    for (pIdx in pieces.indices) {
                        val segPiece = pieces[pIdx]
                        val w = measurer.measureWidth(text, segPiece.start, segPiece.end, run.styleId)
                        val box = KpBox(
                            width = w,
                            charStart = segPiece.start,
                            charEnd = segPiece.end,
                            styleId = run.styleId,
                            kind = TypesetSpanKind.CODE,
                            atomClass = AtomClass.CODE,
                        )
                        if (pIdx == 0) {
                            emitBoundaryBefore(AtomClass.CODE, box, run.styleId)
                        } else {
                            items.add(KpPenalty(0f, segPiece.breakPenaltyBefore))
                        }
                        addBox(box)
                    }
                }
                continue
            }

            var i = rStart
            while (i < rEnd) {
                val ch = text[i]
                if (ch == ' ' || ch == '\t') {
                    val spStart = i
                    while (i < rEnd && (text[i] == ' ' || text[i] == '\t')) i++
                    if (pendingSpaceStart < 0) {
                        pendingSpaceStart = spStart
                        pendingSpaceEnd = i
                        pendingSpaceStyle = run.styleId
                    } else {
                        pendingSpaceEnd = i
                    }
                    continue
                }

                if ((ch == '—' || ch == '…') && i + 1 < rEnd && text[i + 1] == ch) {
                    val pairEnd = i + 2
                    val w = measurer.measureWidth(text, i, pairEnd, run.styleId)
                    val box = KpBox(
                        width = w,
                        charStart = i,
                        charEnd = pairEnd,
                        styleId = run.styleId,
                        kind = TypesetSpanKind.NORMAL,
                        atomClass = AtomClass.INSEPARABLE,
                    )
                    emitBoundaryBefore(AtomClass.INSEPARABLE, box, run.styleId)
                    addBox(box)
                    visibleChars += 2
                    i = pairEnd
                    continue
                }

                val cp = Character.codePointAt(text, i)
                val cls = classifyCodePoint(cp)
                if (cls == AtomClass.LATIN) {
                    val wStart = i
                    while (i < rEnd && isLatinWordContinuation(text, i, rEnd)) {
                        i = nextGraphemeEnd(text, i, rEnd)
                    }
                    val wEnd = i
                    visibleChars += (wEnd - wStart)
                    emitLatinToken(
                        text = text,
                        start = wStart,
                        end = wEnd,
                        styleId = run.styleId,
                        measurer = measurer,
                        maxWidthPx = maxWidthPx,
                        allowHyphenation = allowHyphenation,
                        emitLeadingBoundary = { firstBox -> emitBoundaryBefore(AtomClass.LATIN, firstBox, run.styleId) },
                        addBox = ::addBox,
                        addPenalty = { items.add(it) },
                    )
                    continue
                }

                val gEnd = nextGraphemeEnd(text, i, rEnd)
                val w = measurer.measureWidth(text, i, gEnd, run.styleId)
                val trim = if (cls == AtomClass.CJK_OPEN || cls == AtomClass.CJK_CLOSE || cls == AtomClass.CJK_STOP || cls == AtomClass.CJK_MID) {
                    measurer.measurePunctuationTrim(ch, run.styleId)
                } else {
                    PunctuationTrim()
                }
                val box = KpBox(
                    width = w,
                    charStart = i,
                    charEnd = gEnd,
                    styleId = run.styleId,
                    kind = TypesetSpanKind.NORMAL,
                    atomClass = cls,
                    leftTrim = trim.leftTrim,
                    rightTrim = trim.rightTrim,
                    hangRight = trim.hangRight,
                )
                emitBoundaryBefore(cls, box, run.styleId)
                addBox(box)
                visibleChars += (gEnd - i)
                i = gEnd
            }
        }

        if (pendingSpaceStart >= 0) {
            val sw = measurer.measureSpaceWidth(pendingSpaceStyle) * (pendingSpaceEnd - pendingSpaceStart)
            addGlue(KpGlue(sw, 0f, 0f, TIER_RIGID, pendingSpaceStart, pendingSpaceEnd))
        }

        items.add(KpGlue(0f, 1_000_000f, 0f, TIER_RIGID))
        items.add(KpPenalty(0f, -INF_PENALTY))

        return BuiltParagraph(
            items = items,
            minWordWidth = minWordWidth,
            totalNaturalWidth = totalNaturalWidth,
            visibleChars = visibleChars,
            codeChars = codeChars,
        )
    }

    private data class CodeSlice(val start: Int, val end: Int, val breakPenaltyBefore: Float)

    private fun splitCodeToken(
        text: String,
        start: Int,
        end: Int,
        styleId: Int,
        measurer: TypesetMeasurer,
        maxWidthPx: Float,
    ): List<CodeSlice> {
        val rawSlices = ArrayList<CodeSlice>(4)
        var sliceStart = start
        var pendingPenalty = 0f
        var i = start
        while (i < end) {
            val c = text[i]
            val nextIdx = nextGraphemeEnd(text, i, end)
            val isSoftDelim = c == '/' || c == '.' || c == '_' || c == '-' || c == ':' ||
                c == ',' || c == ';' || c == '=' || c == '?' || c == '&' || c == '|'
            if (isSoftDelim && nextIdx < end && i > sliceStart) {
                rawSlices.add(CodeSlice(sliceStart, nextIdx, pendingPenalty))
                sliceStart = nextIdx
                pendingPenalty = if (c == '/' || c == '-' || c == ',') 28f else 42f
                i = nextIdx
                continue
            }
            if (nextIdx < end && (end - start) >= 8 && i - sliceStart >= 2) {
                val nextCh = text[nextIdx]
                if ((c.isLowerCase() || c.isDigit()) && nextCh.isUpperCase()) {
                    rawSlices.add(CodeSlice(sliceStart, nextIdx, pendingPenalty))
                    sliceStart = nextIdx
                    pendingPenalty = 95f
                    i = nextIdx
                    continue
                }
            }
            i = nextIdx
        }
        if (sliceStart < end) {
            rawSlices.add(CodeSlice(sliceStart, end, pendingPenalty))
        }

        if (!maxWidthPx.isFinite()) return rawSlices
        val emergencyLimit = maxWidthPx * 0.88f
        val out = ArrayList<CodeSlice>(rawSlices.size)
        for (slice in rawSlices) {
            val w = measurer.measureWidth(text, slice.start, slice.end, styleId)
            if (w <= emergencyLimit || slice.end - slice.start <= 1) {
                out.add(slice)
            } else {
                var cur = slice.start
                var first = true
                while (cur < slice.end) {
                    var nxt = nextGraphemeEnd(text, cur, slice.end)
                    while (nxt < slice.end) {
                        val candidate = nextGraphemeEnd(text, nxt, slice.end)
                        if (measurer.measureWidth(text, cur, candidate, styleId) > emergencyLimit) break
                        nxt = candidate
                    }
                    out.add(CodeSlice(cur, nxt, if (first) slice.breakPenaltyBefore else 240f))
                    first = false
                    cur = nxt
                }
            }
        }
        return out
    }

    private fun emitLatinToken(
        text: String,
        start: Int,
        end: Int,
        styleId: Int,
        measurer: TypesetMeasurer,
        maxWidthPx: Float,
        allowHyphenation: Boolean,
        emitLeadingBoundary: (KpBox) -> Unit,
        addBox: (KpBox) -> Unit,
        addPenalty: (KpPenalty) -> Unit,
    ) {
        val segments = ArrayList<Pair<Int, Int>>(4)
        var s = start
        var i = start
        while (i < end) {
            val c = text[i]
            val next = nextGraphemeEnd(text, i, end)
            if ((c == '-' || c == '–' || c == '—' || c == '/') && next < end && i > s) {
                segments.add(s to next)
                s = next
            }
            i = next
        }
        if (s < end) segments.add(s to end)

        var emittedFirst = false
        for (segIndex in segments.indices) {
            val (segS, segE) = segments[segIndex]
            val segWidth = measurer.measureWidth(text, segS, segE, styleId)
            val hyphenPoints = if (allowHyphenation && isPureAsciiAlpha(text, segS, segE) && segE - segS >= 7) {
                englishHyphenationPoints(text, segS, segE)
            } else {
                IntArray(0)
            }

            if (hyphenPoints.isNotEmpty()) {
                val hyphenW = measurer.measureHyphenWidth(styleId)
                var partStart = segS
                for (hp in hyphenPoints) {
                    val pw = measurer.measureWidth(text, partStart, hp, styleId)
                    val box = KpBox(pw, partStart, hp, styleId, TypesetSpanKind.NORMAL, AtomClass.LATIN)
                    if (!emittedFirst) {
                        emitLeadingBoundary(box)
                        emittedFirst = true
                    } else if (partStart == segS) {
                        addPenalty(KpPenalty(0f, 25f))
                    }
                    addBox(box)
                    addPenalty(KpPenalty(hyphenW, 55f, flagged = true, hyphenStyleId = styleId, isHyphen = true))
                    partStart = hp
                }
                val lastW = measurer.measureWidth(text, partStart, segE, styleId)
                val lastBox = KpBox(lastW, partStart, segE, styleId, TypesetSpanKind.NORMAL, AtomClass.LATIN)
                addBox(lastBox)
            } else if (maxWidthPx.isFinite() && segWidth > maxWidthPx * 0.90f && segE - segS > 1) {
                val limit = maxWidthPx * 0.88f
                var cur = segS
                while (cur < segE) {
                    var nxt = nextGraphemeEnd(text, cur, segE)
                    while (nxt < segE) {
                        val candidate = nextGraphemeEnd(text, nxt, segE)
                        if (measurer.measureWidth(text, cur, candidate, styleId) > limit) break
                        nxt = candidate
                    }
                    val cw = measurer.measureWidth(text, cur, nxt, styleId)
                    val box = KpBox(cw, cur, nxt, styleId, TypesetSpanKind.NORMAL, AtomClass.LATIN)
                    if (!emittedFirst) {
                        emitLeadingBoundary(box)
                        emittedFirst = true
                    } else if (cur == segS) {
                        addPenalty(KpPenalty(0f, 25f))
                    } else {
                        addPenalty(KpPenalty(0f, 240f))
                    }
                    addBox(box)
                    cur = nxt
                }
            } else {
                val box = KpBox(segWidth, segS, segE, styleId, TypesetSpanKind.NORMAL, AtomClass.LATIN)
                if (!emittedFirst) {
                    emitLeadingBoundary(box)
                    emittedFirst = true
                } else {
                    addPenalty(KpPenalty(0f, 25f))
                }
                addBox(box)
            }
        }
    }

    private fun solveKnuthPlassMultiPass(
        items: List<KpItem>,
        targetWidth: Float,
        emWidth: Float,
    ): BreakChain {
        val pass1 = solveKnuthPlass(items, targetWidth, emWidth, tolerance = 1.65f, allowHyphens = false, emergencyStretch = 0f)
        if (pass1 != null && !pass1.hadOverfull) return pass1
        val pass2 = solveKnuthPlass(items, targetWidth, emWidth, tolerance = 3.25f, allowHyphens = true, emergencyStretch = 0f)
        if (pass2 != null && !pass2.hadOverfull) return pass2
        val pass3 = solveKnuthPlass(items, targetWidth, emWidth, tolerance = 6.5f, allowHyphens = true, emergencyStretch = emWidth * 2.5f)
        return pass3 ?: solveGreedy(items, targetWidth)
    }

    private fun solveKnuthPlass(
        items: List<KpItem>,
        targetWidth: Float,
        emWidth: Float,
        tolerance: Float,
        allowHyphens: Boolean,
        emergencyStretch: Float,
    ): BreakChain? {
        val n = items.size
        if (n == 0) return BreakChain(intArrayOf(0), 0f, false)

        val sumW = FloatArray(n + 1)
        val sumStretch = FloatArray(n + 1)
        val sumShrink = FloatArray(n + 1)
        for (i in 0 until n) {
            val it = items[i]
            sumW[i + 1] = sumW[i] + if (it is KpPenalty) 0f else it.width
            if (it is KpGlue) {
                sumStretch[i + 1] = sumStretch[i] + it.stretch
                sumShrink[i + 1] = sumShrink[i] + it.shrink
            } else {
                sumStretch[i + 1] = sumStretch[i]
                sumShrink[i + 1] = sumShrink[i]
            }
        }

        val firstBoxAfter = IntArray(n + 1) { n }
        var nextBox = n
        for (i in n - 1 downTo 0) {
            if (items[i] is KpBox) nextBox = i
            firstBoxAfter[i] = nextBox
        }

        val lastBoxBefore = IntArray(n + 1) { -1 }
        var prevBoxIdx = -1
        for (i in 0..n) {
            lastBoxBefore[i] = prevBoxIdx
            if (i < n && items[i] is KpBox) prevBoxIdx = i
        }

        val active = ArrayList<ActiveNode>(16)
        active.add(ActiveNode(index = 0, line = 0, fitnessClass = 1, totalDemerits = 0.0, flagged = false, previous = null, ratio = 0f))

        val orphanThreshold = min(targetWidth * 0.24f, emWidth * 3.8f)
        var hadOverfull = false

        for (i in 0 until n) {
            val item = items[i]
            val isLegalBreak = when (item) {
                is KpGlue -> i > 0 && items[i - 1] is KpBox
                is KpPenalty -> item.penalty < INF_PENALTY && (allowHyphens || !item.isHyphen)
                is KpBox -> false
            }
            if (!isLegalBreak) continue

            val isFinal = i == n - 1
            val isForced = item is KpPenalty && item.penalty <= -INF_PENALTY
            val penaltyVal = if (item is KpPenalty) item.penalty else 0f
            val penaltyWidth = if (item is KpPenalty) item.width else 0f
            val flagged = item is KpPenalty && item.flagged

            val bestByFitness = arrayOfNulls<ActiveNode>(4)
            var fallbackOverfull: ActiveNode? = null
            var fallbackOverflow = Float.POSITIVE_INFINITY

            var aIdx = 0
            while (aIdx < active.size) {
                val a = active[aIdx]
                val contentStart = if (a.index == 0) firstBoxAfter[0] else firstBoxAfter[a.index + 1]
                val effectiveStart = min(contentStart, i)

                var actualW = (sumW[i] - sumW[effectiveStart]) + penaltyWidth
                val actualStretch = (sumStretch[i] - sumStretch[effectiveStart]) + emergencyStretch
                val actualShrink = sumShrink[i] - sumShrink[effectiveStart]

                if (effectiveStart < i) {
                    val startBox = items[effectiveStart] as? KpBox
                    if (startBox != null && startBox.atomClass == AtomClass.CJK_OPEN && startBox.leftTrim > 0f) {
                        actualW -= startBox.leftTrim * 0.72f
                    }
                    if (!isFinal) {
                        val endBoxIdx = lastBoxBefore[i]
                        if (endBoxIdx >= effectiveStart) {
                            val endBox = items[endBoxIdx] as? KpBox
                            if (endBox != null && endBox.hangRight > 0f) {
                                actualW -= endBox.hangRight
                            }
                        }
                    }
                }

                val ratio = when {
                    isFinal && actualW <= targetWidth -> 0f
                    actualW < targetWidth -> if (actualStretch > 0.001f) (targetWidth - actualW) / actualStretch else Float.POSITIVE_INFINITY
                    actualW > targetWidth -> if (actualShrink > 0.001f) (targetWidth - actualW) / actualShrink else Float.NEGATIVE_INFINITY
                    else -> 0f
                }

                if (ratio < -1f || isForced) {
                    if (ratio < -1f) {
                        val overflow = actualW - targetWidth
                        if (overflow < fallbackOverflow) {
                            fallbackOverflow = overflow
                            fallbackOverfull = ActiveNode(
                                index = i,
                                line = a.line + 1,
                                fitnessClass = 0,
                                totalDemerits = a.totalDemerits + 100_000_000.0 + overflow.toDouble() * 1000.0,
                                flagged = flagged,
                                previous = a,
                                ratio = -1f,
                            )
                        }
                    }
                    active.removeAt(aIdx)
                } else {
                    aIdx++
                }

                if (ratio >= -1f && (ratio <= tolerance || isForced)) {
                    val clampedRatio = ratio.coerceIn(-1f, tolerance)
                    val badness = min(10_000f, 100f * abs(clampedRatio) * abs(clampedRatio) * abs(clampedRatio))
                    var demerits = if (penaltyVal >= 0f) {
                        val base = LINE_PENALTY + badness + penaltyVal
                        base.toDouble() * base.toDouble()
                    } else if (penaltyVal > -INF_PENALTY) {
                        val base = LINE_PENALTY + badness
                        base.toDouble() * base.toDouble() - penaltyVal.toDouble() * penaltyVal.toDouble()
                    } else {
                        val base = LINE_PENALTY + badness
                        base.toDouble() * base.toDouble()
                    }
                    if (flagged && a.flagged) demerits += FLAGGED_DEMERIT
                    val fitness = when {
                        clampedRatio < -0.5f -> 0
                        clampedRatio <= 0.5f -> 1
                        clampedRatio <= 1.0f -> 2
                        else -> 3
                    }
                    if (abs(fitness - a.fitnessClass) > 1) demerits += FITNESS_DEMERIT
                    if (isFinal && a.line >= 1 && actualW > 0f && actualW < orphanThreshold) {
                        val deficit = 1f - (actualW / orphanThreshold)
                        demerits += deficit.toDouble() * deficit.toDouble() * 48_000.0
                    }

                    val totalD = a.totalDemerits + demerits
                    val existing = bestByFitness[fitness]
                    if (existing == null || totalD < existing.totalDemerits) {
                        bestByFitness[fitness] = ActiveNode(
                            index = i,
                            line = a.line + 1,
                            fitnessClass = fitness,
                            totalDemerits = totalD,
                            flagged = flagged,
                            previous = a,
                            ratio = clampedRatio,
                        )
                    }
                }
            }

            var addedAny = false
            for (f in 0..3) {
                val node = bestByFitness[f]
                if (node != null) {
                    active.add(node)
                    addedAny = true
                }
            }
            if (!addedAny && active.isEmpty() && fallbackOverfull != null) {
                hadOverfull = true
                active.add(fallbackOverfull)
            }
        }

        if (active.isEmpty()) return null
        val winner = active.minBy { it.totalDemerits }
        val chain = ArrayList<Int>(winner.line + 1)
        var maxNonFinalRatio = 0f
        var cur: ActiveNode? = winner
        var isLast = true
        while (cur != null) {
            chain.add(cur.index)
            if (!isLast && cur.previous != null) {
                maxNonFinalRatio = max(maxNonFinalRatio, abs(cur.ratio))
            }
            isLast = false
            cur = cur.previous
        }
        chain.reverse()
        return BreakChain(chain.toIntArray(), maxNonFinalRatio, hadOverfull)
    }

    private fun solveBalancedLeft(
        items: List<KpItem>,
        targetWidth: Float,
        emWidth: Float,
    ): BreakChain {
        val n = items.size
        if (n == 0) return BreakChain(intArrayOf(0), 0f, false)

        val sumW = FloatArray(n + 1)
        for (i in 0 until n) {
            val it = items[i]
            sumW[i + 1] = sumW[i] + if (it is KpPenalty) 0f else it.width
        }

        val firstBoxAfter = IntArray(n + 1) { n }
        var nextBox = n
        for (i in n - 1 downTo 0) {
            if (items[i] is KpBox) nextBox = i
            firstBoxAfter[i] = nextBox
        }

        val totalWidth = sumW[n]
        if (!targetWidth.isFinite() || totalWidth <= targetWidth) {
            return BreakChain(intArrayOf(0, n - 1), 0f, false)
        }

        val estimatedLines = ceil(totalWidth / (targetWidth * 0.96f)).toInt().coerceAtLeast(2)
        val idealWidth = (totalWidth / estimatedLines.toFloat()).coerceIn(targetWidth * 0.50f, targetWidth * 0.96f)
        val orphanThreshold = min(idealWidth * 0.48f, emWidth * 3.5f)

        val candidates = ArrayList<Int>(n / 2 + 2)
        candidates.add(0)
        for (i in 0 until n) {
            val it = items[i]
            val legal = when (it) {
                is KpGlue -> i > 0 && items[i - 1] is KpBox
                is KpPenalty -> it.penalty < INF_PENALTY && !it.isHyphen
                is KpBox -> false
            }
            if (legal) candidates.add(i)
        }
        if (candidates.last() != n - 1) candidates.add(n - 1)

        val m = candidates.size
        val dp = DoubleArray(m) { Double.POSITIVE_INFINITY }
        val prev = IntArray(m) { -1 }
        val lineCountAt = IntArray(m)
        dp[0] = 0.0

        for (bPos in 1 until m) {
            val b = candidates[bPos]
            val isFinal = b == n - 1
            val bItem = items[b]
            val breakPenalty = if (bItem is KpPenalty && bItem.penalty > 0f) bItem.penalty else 0f

            var bestCost = Double.POSITIVE_INFINITY
            var bestPrev = -1
            var bestLines = 0

            for (aPos in bPos - 1 downTo 0) {
                if (!dp[aPos].isFinite()) continue
                val a = candidates[aPos]
                val contentStart = if (a == 0) firstBoxAfter[0] else firstBoxAfter[a + 1]
                val effectiveStart = min(contentStart, b)
                var w = sumW[b] - sumW[effectiveStart]
                if (effectiveStart < b) {
                    val startBox = items[effectiveStart] as? KpBox
                    if (startBox != null && startBox.atomClass == AtomClass.CJK_OPEN && startBox.leftTrim > 0f) {
                        w -= startBox.leftTrim * 0.72f
                    }
                }
                if (w > targetWidth) {
                    if (aPos == bPos - 1 && bestPrev == -1) {
                        val overflowCost = dp[aPos] + 1_000_000.0 + (w - targetWidth).toDouble() * 1000.0
                        bestCost = overflowCost
                        bestPrev = aPos
                        bestLines = lineCountAt[aPos] + 1
                    }
                    break
                }
                val diff = if (isFinal) {
                    val under = max(0f, idealWidth * 0.85f - w)
                    under / targetWidth
                } else {
                    abs(idealWidth - w) / targetWidth
                }
                var lineCost = (diff * 100.0) * (diff * 100.0) + breakPenalty.toDouble() * breakPenalty.toDouble() * 0.25 + 140.0
                if (isFinal && lineCountAt[aPos] >= 1 && w > 0f && w < orphanThreshold) {
                    val deficit = 1.0 - (w / orphanThreshold).toDouble()
                    lineCost += deficit * deficit * 24_000.0
                }
                val total = dp[aPos] + lineCost
                if (total < bestCost) {
                    bestCost = total
                    bestPrev = aPos
                    bestLines = lineCountAt[aPos] + 1
                }
            }

            if (bestPrev == -1) {
                bestPrev = bPos - 1
                bestCost = dp[bestPrev] + 10_000_000.0
                bestLines = lineCountAt[bestPrev] + 1
            }
            dp[bPos] = bestCost
            prev[bPos] = bestPrev
            lineCountAt[bPos] = bestLines
        }

        val out = ArrayList<Int>(lineCountAt[m - 1] + 1)
        var cur = m - 1
        while (cur >= 0) {
            out.add(candidates[cur])
            cur = prev[cur]
        }
        out.reverse()
        return BreakChain(out.toIntArray(), 0f, false)
    }

    private fun solveGreedy(items: List<KpItem>, targetWidth: Float): BreakChain {
        val n = items.size
        if (n == 0) return BreakChain(intArrayOf(0), 0f, false)
        if (!targetWidth.isFinite()) return BreakChain(intArrayOf(0, n - 1), 0f, false)

        val breaks = ArrayList<Int>(8)
        breaks.add(0)
        var lineWidth = 0f
        var lastLegalBreak = -1
        var hasContentOnLine = false

        for (i in 0 until n) {
            val item = items[i]
            val isLegal = when (item) {
                is KpGlue -> i > 0 && items[i - 1] is KpBox
                is KpPenalty -> item.penalty < INF_PENALTY && !item.isHyphen
                is KpBox -> false
            }
            if (isLegal && hasContentOnLine) {
                lastLegalBreak = i
            }
            if (item is KpPenalty && item.penalty <= -INF_PENALTY) {
                breaks.add(i)
                break
            }
            if (item is KpBox) {
                var boxW = item.width
                if (!hasContentOnLine && item.atomClass == AtomClass.CJK_OPEN && item.leftTrim > 0f) {
                    boxW -= item.leftTrim * 0.72f
                }
                if (hasContentOnLine && lineWidth + boxW > targetWidth && lastLegalBreak > breaks.last()) {
                    breaks.add(lastLegalBreak)
                    lineWidth = 0f
                    hasContentOnLine = false
                    var k = lastLegalBreak + 1
                    while (k <= i) {
                        val itk = items[k]
                        if (itk is KpBox) {
                            var kw = itk.width
                            if (!hasContentOnLine && itk.atomClass == AtomClass.CJK_OPEN && itk.leftTrim > 0f) {
                                kw -= itk.leftTrim * 0.72f
                            }
                            lineWidth += kw
                            hasContentOnLine = true
                        } else if (itk is KpGlue && hasContentOnLine) {
                            lineWidth += itk.width
                        }
                        k++
                    }
                    lastLegalBreak = -1
                } else {
                    lineWidth += boxW
                    hasContentOnLine = true
                }
            } else if (item is KpGlue && hasContentOnLine) {
                lineWidth += item.width
            }
        }
        if (breaks.last() != n - 1) breaks.add(n - 1)
        return BreakChain(breaks.toIntArray(), 0f, false)
    }

    private fun materializeLine(
        text: String,
        items: List<KpItem>,
        startIdx: Int,
        endIdx: Int,
        isFirstLine: Boolean,
        isLastLine: Boolean,
        lineIndex: Int,
        targetWidth: Float,
        justify: Boolean,
        top: Float,
        baseMetrics: TypesetLineMetrics,
        segStart: Int,
        segEnd: Int,
        lineEndWithNewline: Int,
        measurer: TypesetMeasurer,
        charLine: IntArray,
        charLeft: FloatArray,
        charRight: FloatArray,
        placeholders: MutableList<TypesetPlaceholderPlacement>,
    ): TypesetLine {
        var contentStart = if (isFirstLine) 0 else startIdx + 1
        while (contentStart < endIdx && items[contentStart] !is KpBox) contentStart++
        var contentEnd = endIdx
        while (contentEnd > contentStart && items[contentEnd - 1] !is KpBox) contentEnd--

        val endPenalty = items.getOrNull(endIdx) as? KpPenalty
        val hasTrailingHyphen = endPenalty != null && endPenalty.isHyphen
        val hyphenW = if (hasTrailingHyphen) endPenalty.width else 0f

        var naturalWidth = hyphenW
        var printableTextWidth = 0f
        var primaryStretch = 0f
        var secondaryStretch = 0f
        var primaryShrink = 0f
        var secondaryShrink = 0f
        var startTrim = 0f
        var endHang = 0f

        if (contentStart < contentEnd) {
            val firstBox = items[contentStart] as KpBox
            if (firstBox.atomClass == AtomClass.CJK_OPEN && firstBox.leftTrim > 0f) {
                startTrim = firstBox.leftTrim * 0.72f
            }
            if (justify) {
                val lastBox = items[contentEnd - 1] as KpBox
                if (lastBox.hangRight > 0f) {
                    endHang = lastBox.hangRight
                }
            }
        }

        for (i in contentStart until contentEnd) {
            val it = items[i]
            when (it) {
                is KpBox -> {
                    naturalWidth += it.width
                    if (it.kind != TypesetSpanKind.INLINE_OBJECT) printableTextWidth += it.width
                }
                is KpGlue -> {
                    naturalWidth += it.width
                    if (it.tier == TIER_PUNCT || it.tier == TIER_CJK_LATIN || it.tier == TIER_WORD) {
                        primaryStretch += it.stretch
                        primaryShrink += it.shrink
                    } else if (it.tier == TIER_CJK) {
                        secondaryStretch += it.stretch
                        secondaryShrink += it.shrink
                    }
                }
                is KpPenalty -> Unit
            }
        }
        naturalWidth -= startTrim

        var lineScaleX = 1f
        var primaryDeltaRatio = 0f
        var secondaryDeltaRatio = 0f

        if (justify && targetWidth.isFinite() && contentStart < contentEnd) {
            val effectiveTarget = targetWidth + endHang
            var delta = effectiveTarget - naturalWidth
            if (delta > 0.25f) {
                if (printableTextWidth > 0f && delta > primaryStretch * 0.55f) {
                    val maxHzGain = printableTextWidth * (MAX_HZ_EXPAND - 1f)
                    val hzGain = min(maxHzGain, delta * 0.42f)
                    lineScaleX = 1f + hzGain / printableTextWidth
                    delta -= hzGain
                }
                if (primaryStretch <= 0.001f && secondaryStretch > 0.001f) {
                    secondaryDeltaRatio = (delta / secondaryStretch).coerceIn(0f, 2.2f)
                } else {
                    val weightedCap = primaryStretch + secondaryStretch * 0.48f
                    if (weightedCap > 0.001f) {
                        val r = (delta / weightedCap).coerceIn(0f, 3.2f)
                        primaryDeltaRatio = r
                        secondaryDeltaRatio = r * 0.48f
                    }
                }
            } else if (delta < -0.25f) {
                if (printableTextWidth > 0f && -delta > primaryShrink * 0.55f) {
                    val maxHzSave = printableTextWidth * (1f - MIN_HZ_SHRINK)
                    val hzSave = min(maxHzSave, -delta * 0.42f)
                    lineScaleX = 1f - hzSave / printableTextWidth
                    delta += hzSave
                }
                val weightedCap = primaryShrink + secondaryShrink * 0.42f
                if (weightedCap > 0.001f) {
                    val r = (delta / weightedCap).coerceIn(-1.15f, 0f)
                    primaryDeltaRatio = r
                    secondaryDeltaRatio = r * 0.42f
                }
            }
        }

        var lineAscent = baseMetrics.ascent
        var lineDescent = baseMetrics.descent
        for (i in contentStart until contentEnd) {
            val box = items[i] as? KpBox ?: continue
            if (box.kind == TypesetSpanKind.INLINE_OBJECT && box.objectHeightPx > 0f) {
                val objAbove = box.objectHeightPx * box.objectBaselineFraction + 2f
                val objBelow = box.objectHeightPx * (1f - box.objectBaselineFraction) + 2f
                lineAscent = max(lineAscent, objAbove)
                lineDescent = max(lineDescent, objBelow)
            }
        }
        val extraAbove = max(0f, lineAscent - baseMetrics.ascent)
        val extraBelow = max(0f, lineDescent - baseMetrics.descent)
        val baseline = top + baseMetrics.baselineOffset + extraAbove
        val bottom = top + baseMetrics.lineHeight + extraAbove + extraBelow

        val pieces = ArrayList<TypesetPlacedPiece>((contentEnd - contentStart) / 2 + 4)
        var x = 0f
        var lineCharStart = Int.MAX_VALUE
        var lineVisibleEnd = -1

        for (i in contentStart until contentEnd) {
            val it = items[i]
            when (it) {
                is KpBox -> {
                    val isTrimmedStart = i == contentStart && startTrim > 0f
                    val boxScale = if (it.kind == TypesetSpanKind.INLINE_OBJECT) 1f else lineScaleX
                    val scaledW = it.width * boxScale
                    val drawX = if (isTrimmedStart) x - startTrim * boxScale else x
                    val advanceW = if (isTrimmedStart) scaledW - startTrim * boxScale else scaledW
                    pieces.add(
                        TypesetPlacedPiece(
                            charStart = it.charStart,
                            charEnd = it.charEnd,
                            styleId = it.styleId,
                            kind = it.kind,
                            x = x,
                            drawX = drawX,
                            width = advanceW,
                            scaleX = boxScale,
                            objectId = it.objectId,
                            objectHeightPx = it.objectHeightPx,
                            objectBaselineFraction = it.objectBaselineFraction,
                        ),
                    )
                    if (it.charStart < lineCharStart) lineCharStart = it.charStart
                    if (it.charEnd > lineVisibleEnd) lineVisibleEnd = it.charEnd

                    assignPieceCharMetrics(
                        text = text,
                        pieceStart = it.charStart,
                        pieceEnd = it.charEnd,
                        styleId = it.styleId,
                        startX = x,
                        totalWidth = advanceW,
                        kind = it.kind,
                        lineIndex = lineIndex,
                        measurer = measurer,
                        charLine = charLine,
                        charLeft = charLeft,
                        charRight = charRight,
                    )

                    if (it.kind == TypesetSpanKind.INLINE_OBJECT && it.objectId != null) {
                        val objTop = baseline - it.objectHeightPx * it.objectBaselineFraction
                        placeholders.add(
                            TypesetPlaceholderPlacement(
                                objectId = it.objectId,
                                charIndex = it.charStart,
                                left = x,
                                top = objTop,
                                right = x + advanceW,
                                bottom = objTop + it.objectHeightPx,
                            ),
                        )
                    }
                    x += advanceW
                }
                is KpGlue -> {
                    val adj = when {
                        !justify -> 0f
                        it.tier == TIER_PUNCT || it.tier == TIER_CJK_LATIN || it.tier == TIER_WORD ->
                            if (primaryDeltaRatio >= 0f) it.stretch * primaryDeltaRatio else it.shrink * primaryDeltaRatio
                        it.tier == TIER_CJK ->
                            if (secondaryDeltaRatio >= 0f) it.stretch * secondaryDeltaRatio else it.shrink * secondaryDeltaRatio
                        else -> 0f
                    }
                    val actualGlueW = it.width + adj
                    if (it.charStart >= 0 && it.charEnd > it.charStart) {
                        val count = it.charEnd - it.charStart
                        val step = actualGlueW / count.toFloat()
                        for (c in it.charStart until it.charEnd) {
                            charLine[c] = lineIndex
                            charLeft[c] = x + (c - it.charStart) * step
                            charRight[c] = x + (c - it.charStart + 1) * step
                        }
                        if (it.charStart < lineCharStart) lineCharStart = it.charStart
                    }
                    x += actualGlueW
                }
                is KpPenalty -> Unit
            }
        }

        val trailingHyphenX = if (hasTrailingHyphen) x else Float.NaN
        if (hasTrailingHyphen) x += hyphenW

        val trailingGlue = items.getOrNull(endIdx) as? KpGlue
        var lineCharEnd = if (lineVisibleEnd >= 0) lineVisibleEnd else segStart
        if (trailingGlue != null && trailingGlue.charStart >= 0 && trailingGlue.charEnd > trailingGlue.charStart) {
            for (c in trailingGlue.charStart until trailingGlue.charEnd) {
                charLine[c] = lineIndex
                charLeft[c] = x
                charRight[c] = x
            }
            if (trailingGlue.charEnd > lineCharEnd) lineCharEnd = trailingGlue.charEnd
            if (trailingGlue.charStart < lineCharStart) lineCharStart = trailingGlue.charStart
        }

        if (isFirstLine && lineCharStart > segStart) {
            for (c in segStart until min(lineCharStart, segEnd)) {
                charLine[c] = lineIndex
                charLeft[c] = 0f
                charRight[c] = 0f
            }
            lineCharStart = segStart
        }
        if (lineCharStart == Int.MAX_VALUE) lineCharStart = segStart
        if (lineVisibleEnd < lineCharStart) lineVisibleEnd = lineCharStart
        if (isLastLine && lineEndWithNewline >= 0) {
            for (c in lineCharEnd until lineEndWithNewline) {
                charLine[c] = lineIndex
                charLeft[c] = x
                charRight[c] = x
            }
            lineCharEnd = lineEndWithNewline
        }

        val visualRight = if (justify && endHang > 0f && targetWidth.isFinite()) {
            min(x, targetWidth)
        } else {
            x
        }

        return TypesetLine(
            lineIndex = lineIndex,
            charStart = lineCharStart,
            charEnd = lineCharEnd,
            visibleEnd = lineVisibleEnd,
            left = 0f,
            right = visualRight,
            top = top,
            baseline = baseline,
            bottom = bottom,
            pieces = pieces,
            trailingHyphenX = trailingHyphenX,
            trailingHyphenStyleId = if (hasTrailingHyphen) endPenalty.hyphenStyleId else -1,
            justified = justify,
        )
    }

    private fun assignPieceCharMetrics(
        text: String,
        pieceStart: Int,
        pieceEnd: Int,
        styleId: Int,
        startX: Float,
        totalWidth: Float,
        kind: TypesetSpanKind,
        lineIndex: Int,
        measurer: TypesetMeasurer,
        charLine: IntArray,
        charLeft: FloatArray,
        charRight: FloatArray,
    ) {
        val len = pieceEnd - pieceStart
        if (len <= 0) return
        if (len == 1 || kind == TypesetSpanKind.INLINE_OBJECT) {
            for (c in pieceStart until pieceEnd) {
                charLine[c] = lineIndex
                charLeft[c] = startX
                charRight[c] = startX + totalWidth
            }
            return
        }
        val rawTotal = measurer.measureWidth(text, pieceStart, pieceEnd, styleId)
        val scale = if (rawTotal > 0.001f) totalWidth / rawTotal else 1f
        var prevEnd = 0f
        var i = pieceStart
        while (i < pieceEnd) {
            val gEnd = nextGraphemeEnd(text, i, pieceEnd)
            val prefixW = measurer.measureWidth(text, pieceStart, gEnd, styleId) * scale
            val cLeft = startX + prevEnd
            val cRight = startX + prefixW
            for (c in i until gEnd) {
                charLine[c] = lineIndex
                charLeft[c] = cLeft
                charRight[c] = cRight
            }
            prevEnd = prefixW
            i = gEnd
        }
    }

    private fun fillUnassignedCharMetrics(
        length: Int,
        lines: List<TypesetLine>,
        charLine: IntArray,
        charLeft: FloatArray,
        charRight: FloatArray,
    ) {
        var lastLine = 0
        var lastX = 0f
        for (i in 0 until length) {
            if (charLine[i] >= 0) {
                lastLine = charLine[i]
                lastX = charRight[i]
            } else {
                val ln = lines.getOrElse(lastLine) { lines.last() }
                charLine[i] = ln.lineIndex
                charLeft[i] = lastX
                charRight[i] = lastX
            }
        }
    }

    private fun nextGraphemeEnd(text: String, index: Int, limit: Int): Int {
        if (index >= limit) return limit
        val firstCp = Character.codePointAt(text, index)
        var end = index + Character.charCount(firstCp)
        if (firstCp in 0x1F1E6..0x1F1FF && end < limit) {
            val secondCp = Character.codePointAt(text, end)
            if (secondCp in 0x1F1E6..0x1F1FF) {
                end += Character.charCount(secondCp)
            }
        }
        while (end < limit) {
            val ch = text[end]
            if (ch == '\u200D') {
                end++
                if (end < limit) {
                    val nextCp = Character.codePointAt(text, end)
                    end += Character.charCount(nextCp)
                }
                continue
            }
            val type = Character.getType(ch)
            if (type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.ENCLOSING_MARK.toInt() ||
                ch in '\uFE00'..'\uFE0F'
            ) {
                end++
                continue
            }
            if (Character.isHighSurrogate(ch) && end + 1 < limit) {
                val cp = Character.toCodePoint(ch, text[end + 1])
                if (cp in 0x1F3FB..0x1F3FF || cp in 0xE0100..0xE01EF) {
                    end += 2
                    continue
                }
            }
            break
        }
        return end
    }

    private fun classifyCodePoint(cp: Int): AtomClass {
        if (isCjkIdeographCodePoint(cp)) return AtomClass.CJK_IDEOGRAPH
        if (cp > 0xFFFF) return AtomClass.OTHER
        val ch = cp.toChar()
        return when {
            isCjkOpenPunct(ch) -> AtomClass.CJK_OPEN
            isCjkClosePunct(ch) -> AtomClass.CJK_CLOSE
            isCjkStopPunct(ch) -> AtomClass.CJK_STOP
            isCjkMidPunct(ch) -> AtomClass.CJK_MID
            ch == '(' || ch == '[' || ch == '{' -> AtomClass.HALF_OPEN
            ch == ')' || ch == ']' || ch == '}' || ch == ',' || ch == '.' || ch == ';' || ch == ':' || ch == '!' || ch == '?' || ch == '%' -> AtomClass.HALF_CLOSE
            ch == ' ' || ch == '\t' -> AtomClass.SPACE
            isLatinWordStart(ch) -> AtomClass.LATIN
            else -> AtomClass.OTHER
        }
    }

    internal fun isCjkIdeograph(ch: Char): Boolean = isCjkIdeographCodePoint(ch.code)

    internal fun isCjkIdeographCodePoint(cp: Int): Boolean =
        cp in 0x4E00..0x9FFF ||
            cp in 0x3400..0x4DBF ||
            cp in 0xF900..0xFAFF ||
            cp in 0x3040..0x30FF ||
            cp in 0xAC00..0xD7AF ||
            cp == 0x3005 || cp == 0x3007 || cp == 0x303B ||
            cp in 0x20000..0x2A6DF ||
            cp in 0x2A700..0x2EBEF ||
            cp in 0x2F800..0x2FA1F ||
            cp in 0x30000..0x323AF

    internal fun requiresPlatformBidi(text: String): Boolean {
        var hasPotentialBidi = false
        var i = 0
        val n = text.length
        while (i < n) {
            val ch = text[i]
            val c = ch.code
            if (c in 0x0590..0x08FF ||
                c == 0x200F ||
                c in 0x202A..0x202E ||
                c in 0x2066..0x2069 ||
                c in 0xFB1D..0xFDFF ||
                c in 0xFE70..0xFEFF ||
                Character.isHighSurrogate(ch)
            ) {
                hasPotentialBidi = true
                break
            }
            i++
        }
        if (!hasPotentialBidi) return false
        val chars = text.toCharArray()
        return java.text.Bidi.requiresBidi(chars, 0, chars.size)
    }

    internal fun isCjkOpenPunct(ch: Char): Boolean =
        ch == '（' || ch == '「' || ch == '『' || ch == '《' || ch == '〈' ||
            ch == '【' || ch == '〔' || ch == '〖' || ch == '“' || ch == '‘'

    internal fun isCjkClosePunct(ch: Char): Boolean =
        ch == '）' || ch == '」' || ch == '』' || ch == '》' || ch == '〉' ||
            ch == '】' || ch == '〕' || ch == '〗' || ch == '”' || ch == '’'

    internal fun isCjkStopPunct(ch: Char): Boolean =
        ch == '，' || ch == '。' || ch == '、'

    internal fun isCjkMidPunct(ch: Char): Boolean =
        ch == '：' || ch == '；' || ch == '！' || ch == '？'

    private fun isAlnumOrIdeographCodePoint(cp: Int): Boolean =
        Character.isLetterOrDigit(cp) || isCjkIdeographCodePoint(cp)

    private fun isLatinWordStart(ch: Char): Boolean =
        ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' ||
            ch in '\u00C0'..'\u024F' || ch == '#' || ch == '$' || ch == '¥' || ch == '€' || ch == '£' ||
            ch == '@' || ch == '+' || ch == '~'

    private fun isLatinWordContinuation(text: String, index: Int, limit: Int): Boolean {
        val ch = text[index]
        if (ch == ' ' || ch == '\t' || ch == '\n') return false
        if (isCjkIdeograph(ch) || isCjkOpenPunct(ch) || isCjkClosePunct(ch) || isCjkStopPunct(ch) || isCjkMidPunct(ch)) return false
        if (ch == '(' || ch == '[' || ch == '{' || ch == ')' || ch == ']' || ch == '}') return false
        if (ch == '—' || ch == '…') return false
        if (ch.code >= 0x2000 && ch != '–' && ch != '\'' && ch != '’' && ch != '×' && ch != '±' && ch != '°' && ch != '‰') return false
        return true
    }

    private fun isPureAsciiAlpha(text: String, start: Int, end: Int): Boolean {
        var upperCount = 0
        for (i in start until end) {
            val c = text[i]
            if (c !in 'A'..'Z' && c !in 'a'..'z') return false
            if (c in 'A'..'Z') upperCount++
        }
        return upperCount < (end - start)
    }

    private val hyphenPrefixes = arrayOf(
        "counter", "pseudo", "super", "trans", "inter", "micro", "multi", "photo",
        "proto", "under", "ultra", "anti", "auto", "over", "post", "semi", "comp",
        "conf", "cons", "cont", "para", "poly", "tele", "pre", "pro", "sub", "non", "dis", "mis", "com", "con",
    )

    private val hyphenSuffixes = arrayOf(
        "ization", "ational", "fulness", "ousness", "iveness", "ability", "ibility",
        "ically", "ations", "action", "tation", "ment", "ness", "tion", "sion",
        "able", "ible", "ical", "ally", "less", "ious", "eous", "ance", "ence",
        "ship", "ward", "wise", "like", "ling", "ing", "ive", "ize", "ise", "ous", "ity", "ful", "ery", "ory",
    )

    internal fun englishHyphenationPoints(text: String, start: Int, end: Int): IntArray {
        val len = end - start
        if (len < 7) return IntArray(0)
        val lower = text.substring(start, end).lowercase()
        val marks = BooleanArray(len)

        for (prefix in hyphenPrefixes) {
            if (lower.startsWith(prefix) && prefix.length >= 3 && len - prefix.length >= 3) {
                marks[prefix.length] = true
                break
            }
        }
        for (suffix in hyphenSuffixes) {
            if (lower.endsWith(suffix) && suffix.length >= 3 && len - suffix.length >= 3) {
                val cut = len - suffix.length
                if (hasVowel(lower, 0, cut)) marks[cut] = true
                break
            }
        }

        var i = 3
        while (i <= len - 3) {
            if (!marks[i] && isVowel(lower[i - 1])) {
                if (!isVowel(lower[i]) && !isVowel(lower[i + 1])) {
                    val c1 = lower[i]
                    val c2 = lower[i + 1]
                    if (isConsonantOnsetPair(c1, c2)) {
                        if (i >= 3 && len - i >= 3) marks[i] = true
                    } else if (i + 1 <= len - 3) {
                        marks[i + 1] = true
                    }
                }
            }
            i++
        }

        val points = ArrayList<Int>(4)
        var lastCut = 0
        for (pos in 3..(len - 3)) {
            if (marks[pos] && pos - lastCut >= 2 && hasVowel(lower, lastCut, pos) && hasVowel(lower, pos, len)) {
                points.add(start + pos)
                lastCut = pos
            }
        }
        return points.toIntArray()
    }

    private fun isVowel(c: Char): Boolean = c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u' || c == 'y'

    private fun hasVowel(s: String, start: Int, end: Int): Boolean {
        for (i in start until end) if (isVowel(s[i])) return true
        return false
    }

    private fun isConsonantOnsetPair(a: Char, b: Char): Boolean = when {
        b == 'h' && (a == 'c' || a == 's' || a == 't' || a == 'p' || a == 'w' || a == 'g') -> true
        b == 'r' && (a == 'b' || a == 'c' || a == 'd' || a == 'f' || a == 'g' || a == 'p' || a == 't') -> true
        b == 'l' && (a == 'b' || a == 'c' || a == 'f' || a == 'g' || a == 'p' || a == 's') -> true
        a == 's' && (b == 'p' || b == 't' || b == 'k' || b == 'm' || b == 'n' || b == 'w') -> true
        a == 'q' && b == 'u' -> true
        a == 'c' && b == 'k' -> true
        else -> false
    }
}
