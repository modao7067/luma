package io.github.modao7067.luma.gui.ui

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class TypesetEngineTest {
    private class FixedMetricMeasurer(
        private val cjkAdvance: Float = 16f,
        private val latinAdvance: Float = 8.5f,
        private val codeAdvance: Float = 8.4f,
        private val spaceAdvance: Float = 4.5f,
        private val lineHeight: Float = 24.5f,
    ) : TypesetMeasurer {
        override fun measureWidth(text: String, start: Int, end: Int, styleId: Int): Float {
            var sum = 0f
            var i = start
            while (i < end) {
                val ch = text[i]
                if (Character.isHighSurrogate(ch) && i + 1 < end && Character.isLowSurrogate(text[i + 1])) {
                    sum += cjkAdvance
                    i += 2
                    continue
                }
                sum += when {
                    styleId == 1 -> codeAdvance
                    ch == ' ' -> spaceAdvance
                    TypesetEngine.isCjkIdeograph(ch) ||
                        TypesetEngine.isCjkOpenPunct(ch) ||
                        TypesetEngine.isCjkClosePunct(ch) ||
                        TypesetEngine.isCjkStopPunct(ch) ||
                        TypesetEngine.isCjkMidPunct(ch) ||
                        ch == '—' || ch == '…' -> cjkAdvance
                    else -> latinAdvance
                }
                i++
            }
            return sum
        }

        override fun measureSpaceWidth(styleId: Int): Float = if (styleId == 1) codeAdvance else spaceAdvance
        override fun measureHyphenWidth(styleId: Int): Float = 5.5f
        override fun measureEmWidth(styleId: Int): Float = cjkAdvance
        override fun measurePunctuationTrim(ch: Char, styleId: Int): PunctuationTrim = when {
            TypesetEngine.isCjkOpenPunct(ch) -> PunctuationTrim(leftTrim = cjkAdvance * 0.42f)
            TypesetEngine.isCjkClosePunct(ch) -> PunctuationTrim(rightTrim = cjkAdvance * 0.42f)
            TypesetEngine.isCjkStopPunct(ch) -> PunctuationTrim(rightTrim = cjkAdvance * 0.45f, hangRight = cjkAdvance * 0.44f)
            TypesetEngine.isCjkMidPunct(ch) -> PunctuationTrim(
                rightTrim = cjkAdvance * 0.32f,
                hangRight = if (ch == '：' || ch == '；') cjkAdvance * 0.24f else 0f,
            )
            else -> PunctuationTrim()
        }

        override fun baseLineMetrics(): TypesetLineMetrics = TypesetLineMetrics(
            lineHeight = lineHeight,
            baselineOffset = 18.5f,
            ascent = 18.5f,
            descent = lineHeight - 18.5f,
        )
    }

    private val measurer = FixedMetricMeasurer()

    @Test fun justifiedCjkLatinParagraphAlignsNonFinalLinesFlushToRightEdge() {
        val text = "相比 Telari（macOS 宽屏、静态文章），Luma 是手机窄屏对话客户端，需要在有限宽度里把中英文混排、标点挤压和换行平衡同时做好，才能让段落边缘像书本一样齐整。"
        val width = 320f
        val layout = TypesetEngine.layout(
            text = text,
            runs = listOf(TypesetRunInput(0, text.length, 0, TypesetSpanKind.NORMAL)),
            maxWidthPx = width,
            mode = TypesetMode.JUSTIFY,
            measurer = measurer,
        )
        assertTrue(layout.justifiedParagraph)
        assertTrue(layout.lines.size >= 3)
        for (i in 0 until layout.lines.lastIndex) {
            val line = layout.lines[i]
            assertTrue(line.justified)
            assertEquals(width, line.right, 2.0f)
        }
        assertFalse(layout.lines.last().justified)
        assertTrue(layout.lines.last().right <= width + 0.5f)
    }

    @Test fun balancedHeadingPreventsSingleCharacterOrphanLine() {
        val heading = "三、Luma 场景下最值得先想透的 4 个发散点"
        val naturalWidth = measurer.measureWidth(heading, 0, heading.length, 0)
        val tightWidth = naturalWidth - 10f
        val balanced = TypesetEngine.layout(
            text = heading,
            runs = listOf(TypesetRunInput(0, heading.length, 0, TypesetSpanKind.NORMAL)),
            maxWidthPx = tightWidth,
            mode = TypesetMode.BALANCED_LEFT,
            measurer = measurer,
        )
        assertEquals(2, balanced.lines.size)
        val secondLineChars = balanced.lines[1].visibleEnd - balanced.lines[1].charStart
        assertTrue("Expected balanced second line, got $secondLineChars chars", secondLineChars >= 5)
    }

    @Test fun adjacentCjkPunctuationCompressesInsteadOfLeavingWideGap() {
        val text = "他说：「你好」，然后离开了。"
        val layout = TypesetEngine.layout(
            text = text,
            runs = listOf(TypesetRunInput(0, text.length, 0, TypesetSpanKind.NORMAL)),
            maxWidthPx = 600f,
            mode = TypesetMode.JUSTIFY,
            measurer = measurer,
        )
        assertEquals(1, layout.lines.size)
        val colonIdx = text.indexOf('：')
        val openIdx = text.indexOf('「')
        val closeIdx = text.indexOf('」')
        val commaIdx = text.indexOf('，')
        assertTrue(layout.charLeft[openIdx] - layout.charLeft[colonIdx] < 15.5f)
        assertTrue(layout.charLeft[commaIdx] - layout.charLeft[closeIdx] < 15.5f)
    }

    @Test fun kinsokuPreventsClosingPunctuationAtLineStartAndOpeningAtLineEnd() {
        val text = "第一句话的内容比较长，后面紧接着「关键说明」，再接一段常规正文用来验证标点禁则不会把句号或右括号单独放到行首，也不会把左括号孤零零留在行尾。"
        for (w in listOf(180f, 200f, 224f, 256f, 288f)) {
            val layout = TypesetEngine.layout(
                text = text,
                runs = listOf(TypesetRunInput(0, text.length, 0, TypesetSpanKind.NORMAL)),
                maxWidthPx = w,
                mode = TypesetMode.JUSTIFY,
                measurer = measurer,
            )
            for (line in layout.lines) {
                if (line.visibleEnd > line.charStart) {
                    val firstCh = text[line.charStart]
                    val lastCh = text[line.visibleEnd - 1]
                    assertFalse("Line must not start with closing/stop punct '$firstCh' at width=$w", TypesetEngine.isCjkClosePunct(firstCh) || TypesetEngine.isCjkStopPunct(firstCh))
                    assertFalse("Line must not end with opening punct '$lastCh' at width=$w", TypesetEngine.isCjkOpenPunct(lastCh))
                }
            }
        }
    }

    @Test fun cjkLatinGapIsInsertedAutomaticallyAndAbsorbsManualSpaceHarmoniously() {
        val noSpace = "中文English中文"
        val withSpace = "中文 English 中文"
        val a = TypesetEngine.layout(noSpace, listOf(TypesetRunInput(0, noSpace.length, 0)), 500f, TypesetMode.JUSTIFY, measurer)
        val b = TypesetEngine.layout(withSpace, listOf(TypesetRunInput(0, withSpace.length, 0)), 500f, TypesetMode.JUSTIFY, measurer)
        val eNoSpace = a.charLeft[noSpace.indexOf('E')] - a.charRight[1]
        assertTrue("Expected automatic CJK-Latin gap, got $eNoSpace", eNoSpace in 2.0f..5.0f)
        assertTrue("Manual space at CJK-Latin boundary should harmonize with automatic gap", abs(a.lines[0].right - b.lines[0].right) < 1.5f)
        assertEquals(0, b.charLine[2])
        assertTrue(b.charRight[2] >= b.charLeft[2])
    }

    @Test fun longInlineCodeBreaksAtDelimitersWithoutOverflowAndCodeDenseParagraphFallsBack() {
        val prefix = "入口位于 "
        val code = "apps/gui/app/src/main/java/io/github/modao7067/luma/gui/ui/NativeMarkdown.kt"
        val suffix = " 中。"
        val text = prefix + code + suffix
        val runs = listOf(
            TypesetRunInput(0, prefix.length, 0, TypesetSpanKind.NORMAL),
            TypesetRunInput(prefix.length, prefix.length + code.length, 1, TypesetSpanKind.CODE),
            TypesetRunInput(prefix.length + code.length, text.length, 0, TypesetSpanKind.NORMAL),
        )
        val width = 260f
        val layout = TypesetEngine.layout(text, runs, width, TypesetMode.JUSTIFY, measurer)
        assertFalse("Code-dense paragraph must degrade to balanced flush-left", layout.justifiedParagraph)
        assertTrue(layout.lines.size >= 2)
        layout.lines.forEach { ln ->
            assertTrue("Line ${ln.lineIndex} right=${ln.right} overflowed $width", ln.right <= width + 1.0f)
            assertTrue(ln.trailingHyphenX.isNaN())
        }
    }

    @Test fun englishHyphenationFindsValidSyllableBreaks() {
        val word = "configuration"
        val pts = TypesetEngine.englishHyphenationPoints(word, 0, word.length)
        assertTrue("Expected hyphenation points for $word", pts.isNotEmpty())
        pts.forEach { p ->
            assertTrue(p >= 3)
            assertTrue(p <= word.length - 3)
        }
    }

    @Test fun streamingModeKeepsEarlierLineBreaksStableAsTailGrows() {
        val full = "这是一段正在流式输出的较长中文回复，前面已经排满的文字在后面持续追加新字符时不应该反复跳行，否则阅读起来会非常晃眼。"
        val width = 220f
        val firstMulti = TypesetEngine.layout(full.substring(0, 34), listOf(TypesetRunInput(0, 34, 0)), width, TypesetMode.STREAMING, measurer)
        assertTrue(firstMulti.lines.size >= 2)
        val line0EndInitial = firstMulti.lines[0].visibleEnd
        for (len in 35..full.length) {
            val step = TypesetEngine.layout(full.substring(0, len), listOf(TypesetRunInput(0, len, 0)), width, TypesetMode.STREAMING, measurer)
            assertEquals("Line 0 shifted at len=$len", line0EndInitial, step.lines[0].visibleEnd)
        }
    }

    @Test fun everyUtf16IndexIncludingEmojiAndNewlinesHasValidCoordinates() {
        val text = "第一行 🦋 测试\n第二行 `code` 结尾"
        val codeStart = text.indexOf("code")
        val runs = listOf(
            TypesetRunInput(0, codeStart, 0, TypesetSpanKind.NORMAL),
            TypesetRunInput(codeStart, codeStart + 4, 1, TypesetSpanKind.CODE),
            TypesetRunInput(codeStart + 4, text.length, 0, TypesetSpanKind.NORMAL),
        )
        val layout = TypesetEngine.layout(text, runs, 200f, TypesetMode.JUSTIFY, measurer)
        assertEquals(text.length, layout.charLine.size)
        for (i in text.indices) {
            assertTrue(layout.charLine[i] in layout.lines.indices)
            assertTrue(layout.charRight[i] >= layout.charLeft[i])
        }
    }

    @Test fun nonBmpCjkExtensionsAndConsecutiveEmojiWrapWithoutOverflow() {
        val extB = "\uD840\uDC00".repeat(12)
        assertTrue(TypesetEngine.isCjkIdeographCodePoint(Character.codePointAt(extB, 0)))
        val extLayout = TypesetEngine.layout(extB, listOf(TypesetRunInput(0, extB.length, 0)), 64f, TypesetMode.JUSTIFY, measurer)
        assertTrue("Expected extB to wrap across at least 3 lines, got ${extLayout.lines.size}", extLayout.lines.size >= 3)
        extLayout.lines.forEach { ln ->
            assertTrue("Line ${ln.lineIndex} right=${ln.right} overflowed 64f", ln.right <= 65f)
        }

        val emojis = "🦋".repeat(12)
        val emojiLayout = TypesetEngine.layout(emojis, listOf(TypesetRunInput(0, emojis.length, 0)), 64f, TypesetMode.JUSTIFY, measurer)
        assertTrue("Expected consecutive emojis to wrap across at least 3 lines, got ${emojiLayout.lines.size}", emojiLayout.lines.size >= 3)
        emojiLayout.lines.forEach { ln ->
            assertTrue("Emoji line ${ln.lineIndex} right=${ln.right} overflowed 64f", ln.right <= 65f)
        }

        val flags = "🇨🇳".repeat(8)
        val flagLayout = TypesetEngine.layout(flags, listOf(TypesetRunInput(0, flags.length, 0)), 96f, TypesetMode.JUSTIFY, measurer)
        assertTrue(flagLayout.lines.size >= 3)
        flagLayout.lines.forEach { ln ->
            assertTrue("Flag line ${ln.lineIndex} split inside regional indicator pair", ln.charStart % 4 == 0 && ln.visibleEnd % 4 == 0)
            assertTrue("Flag line ${ln.lineIndex} right=${ln.right} overflowed 96f", ln.right <= 97f)
        }
    }

    @Test fun bidiDetectionSeparatesRtlFromLtrAndCachePolicyExcludesStreamingAndOversized() {
        assertFalse(TypesetEngine.requiresPlatformBidi("中文与 English 123 `code` 混排 🦋 \uD840\uDC00"))
        assertTrue(TypesetEngine.requiresPlatformBidi("مرحبا بالعالم"))
        assertTrue(TypesetEngine.requiresPlatformBidi("שלום עולם"))
        assertTrue(TypesetEngine.requiresPlatformBidi("中文混合 العربية 文本"))

        assertFalse(isTypesetCacheable(TypesetMode.STREAMING, 64))
        assertFalse(isTypesetCacheable(TypesetMode.JUSTIFY, TYPESET_CACHE_MAX_ENTRY_CHARS + 1))
        assertTrue(isTypesetCacheable(TypesetMode.JUSTIFY, 512))
        assertTrue(isTypesetCacheable(TypesetMode.BALANCED_LEFT, TYPESET_CACHE_MAX_ENTRY_CHARS))
    }
}
