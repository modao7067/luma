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

        val emojiAndCjk = "中文与 English 123 🦋 \uD840\uDC00 结尾"
        assertFalse(TypesetEngine.requiresPlatformBidi(emojiAndCjk, 0))
        assertFalse(TypesetEngine.requiresPlatformBidi(emojiAndCjk, emojiAndCjk.indexOf("🦋")))
        val appendedRtl = "$emojiAndCjk مرحبا"
        assertTrue(TypesetEngine.requiresPlatformBidi(appendedRtl, emojiAndCjk.length))
    }

    @Test fun incrementalStreamingLayoutMatchesFreshLayoutAcrossMixedTextAndReducesWork() {
        val sample = "第一段是中文与 English words 混排（带标点「挤压」），还有 inline-code 和 🦋 表情。\n第二段继续流式追加更多文字，验证多行增量复用不会偏离全量贪心排版，即使遇到长单词 configuration 或标点。最后一段收尾。"
        val width = 180f
        var incrementalMeasures = 0
        var freshMeasures = 0
        val countingInc = object : TypesetMeasurer by measurer {
            override fun measureWidth(text: String, start: Int, end: Int, styleId: Int): Float {
                incrementalMeasures++
                return measurer.measureWidth(text, start, end, styleId)
            }
        }
        val countingFresh = object : TypesetMeasurer by measurer {
            override fun measureWidth(text: String, start: Int, end: Int, styleId: Int): Float {
                freshMeasures++
                return measurer.measureWidth(text, start, end, styleId)
            }
        }

        var prevLayout: TypesetLayout? = null
        var prevRuns: List<TypesetRunInput>? = null
        var len = 12
        while (len <= sample.length) {
            val safeLen = markdownVisibleEnd(sample, len)
            val prefix = sample.substring(0, safeLen)
            val runs = listOf(TypesetRunInput(0, prefix.length, 0, TypesetSpanKind.NORMAL))
            val inc = TypesetEngine.layout(
                text = prefix,
                runs = runs,
                maxWidthPx = width,
                mode = TypesetMode.STREAMING,
                measurer = countingInc,
                previousLayout = prevLayout,
                previousRuns = prevRuns,
            )
            val fresh = TypesetEngine.layout(
                text = prefix,
                runs = runs,
                maxWidthPx = width,
                mode = TypesetMode.STREAMING,
                measurer = countingFresh,
            )
            assertEquals("Line count mismatch at len=$safeLen", fresh.lines.size, inc.lines.size)
            assertEquals("Width mismatch at len=$safeLen", fresh.width, inc.width, 0.01f)
            assertEquals("Height mismatch at len=$safeLen", fresh.height, inc.height, 0.01f)
            assertEquals("minIntrinsicWidth mismatch at len=$safeLen", fresh.minIntrinsicWidth, inc.minIntrinsicWidth, 0.01f)
            assertEquals("maxIntrinsicWidth mismatch at len=$safeLen", fresh.maxIntrinsicWidth, inc.maxIntrinsicWidth, 0.01f)
            for (lineIdx in fresh.lines.indices) {
                assertEquals("Line $lineIdx mismatch at len=$safeLen", fresh.lines[lineIdx], inc.lines[lineIdx])
            }
            assertArrayEquals("charLine mismatch at len=$safeLen", fresh.charLine, inc.charLine)
            assertArrayEquals("charLeft mismatch at len=$safeLen", fresh.charLeft, inc.charLeft, 0.01f)
            assertArrayEquals("charRight mismatch at len=$safeLen", fresh.charRight, inc.charRight, 0.01f)
            prevLayout = inc
            prevRuns = runs
            len = if (len == sample.length) sample.length + 1 else minOf(sample.length, len + 7)
        }
        assertTrue(
            "Incremental streaming must measure far fewer segments than fresh: inc=$incrementalMeasures fresh=$freshMeasures",
            incrementalMeasures * 2 < freshMeasures,
        )
    }

    @Test fun incrementalStreamingSoftWrapPreservesExactMaxIntrinsicWidth() {
        val base = "这是一段连续追加的中文文本".repeat(6)
        val full = base + "追加"
        val prevRuns = listOf(TypesetRunInput(0, base.length, 0, TypesetSpanKind.NORMAL))
        val fullRuns = listOf(TypesetRunInput(0, full.length, 0, TypesetSpanKind.NORMAL))
        val prev = TypesetEngine.layout(base, prevRuns, 160f, TypesetMode.STREAMING, measurer)
        val inc = TypesetEngine.layout(full, fullRuns, 160f, TypesetMode.STREAMING, measurer, prev, prevRuns)
        val fresh = TypesetEngine.layout(full, fullRuns, 160f, TypesetMode.STREAMING, measurer)
        assertEquals(fresh.minIntrinsicWidth, inc.minIntrinsicWidth, 0.01f)
        assertEquals(fresh.maxIntrinsicWidth, inc.maxIntrinsicWidth, 0.01f)

        val mixedBase = "前缀中文内容非常长足够折行很多次，接着继续输入中英混排 English words 直到出现软换行"
        val mixedFull = mixedBase + "尾句再追加更多中文和 English。"
        val mPrevRuns = listOf(TypesetRunInput(0, mixedBase.length, 0, TypesetSpanKind.NORMAL))
        val mFullRuns = listOf(TypesetRunInput(0, mixedFull.length, 0, TypesetSpanKind.NORMAL))
        val mPrev = TypesetEngine.layout(mixedBase, mPrevRuns, 140f, TypesetMode.STREAMING, measurer)
        val mInc = TypesetEngine.layout(mixedFull, mFullRuns, 140f, TypesetMode.STREAMING, measurer, mPrev, mPrevRuns)
        val mFresh = TypesetEngine.layout(mixedFull, mFullRuns, 140f, TypesetMode.STREAMING, measurer)
        assertEquals(mFresh.minIntrinsicWidth, mInc.minIntrinsicWidth, 0.01f)
        assertEquals(mFresh.maxIntrinsicWidth, mInc.maxIntrinsicWidth, 0.01f)
    }

    @Test fun streamingRetroactiveStyleNarrowingMatchesFreshLineBreaksAndCoordinates() {
        val base = "甲乙丙丁".repeat(15)
        val full = base + "末"
        val prevRuns = listOf(TypesetRunInput(0, base.length, 0, TypesetSpanKind.NORMAL))
        val nextRuns = listOf(
            TypesetRunInput(0, 49, 0, TypesetSpanKind.NORMAL),
            TypesetRunInput(49, full.length, 1, TypesetSpanKind.NORMAL),
        )
        val prev = TypesetEngine.layout(base, prevRuns, 121f, TypesetMode.STREAMING, measurer)
        val inc = TypesetEngine.layout(full, nextRuns, 121f, TypesetMode.STREAMING, measurer, prev, prevRuns)
        val fresh = TypesetEngine.layout(full, nextRuns, 121f, TypesetMode.STREAMING, measurer)
        assertEquals(50, fresh.lines[6].charEnd)
        assertEquals(50, fresh.lines[7].charStart)
        assertEquals(fresh.lines.size, inc.lines.size)
        assertEquals(fresh.width, inc.width, 0.01f)
        assertEquals(fresh.height, inc.height, 0.01f)
        assertEquals(fresh.minIntrinsicWidth, inc.minIntrinsicWidth, 0.01f)
        assertEquals(fresh.maxIntrinsicWidth, inc.maxIntrinsicWidth, 0.01f)
        for (i in fresh.lines.indices) {
            assertEquals("Line $i mismatch", fresh.lines[i], inc.lines[i])
        }
        assertArrayEquals(fresh.charLine, inc.charLine)
        assertArrayEquals(fresh.charLeft, inc.charLeft, 0.01f)
        assertArrayEquals(fresh.charRight, inc.charRight, 0.01f)
    }

    @Test fun resolveTypesetFontFeatureSettingsDisablesCjkAutoSqueezeForProseOnly() {
        assertEquals("'chws' 0, 'halt' 0, 'palt' 0", resolveTypesetFontFeatureSettings(null, isCode = false))
        assertEquals("'chws' 0, 'halt' 0, 'palt' 0", resolveTypesetFontFeatureSettings("   ", isCode = false))
        assertEquals("'tnum' 1, 'chws' 0, 'halt' 0, 'palt' 0", resolveTypesetFontFeatureSettings("'tnum' 1", isCode = false))
        assertEquals("'chws' 1, 'halt' 0, 'palt' 0", resolveTypesetFontFeatureSettings("'chws' 1", isCode = false))
        assertEquals("'calt' 0, 'liga' 0", resolveTypesetFontFeatureSettings("'calt' 0, 'liga' 0", isCode = true))
        assertNull(resolveTypesetFontFeatureSettings(null, isCode = true))
    }

    @Test fun resolveCjkPunctuationWidthRecoversFullAdvanceWhenIsolatedOpenBracketCollapses() {
        assertEquals(
            36f,
            resolveCjkPunctuationWidth(
                ch = '（',
                isolatedAdvance = 18f,
                contextRunAdvance = 36f,
                contextMeasureAdvance = 36f,
                shapedContextAdvance = 36f,
                shapedAdjacentAdvance = 36f,
                drawnInkLeft = 23f,
                drawnInkRight = 33f,
                emWidth = 36f,
            ),
            0.01f,
        )
        assertEquals(
            36f,
            resolveCjkPunctuationWidth(
                ch = '（',
                isolatedAdvance = 18f,
                contextRunAdvance = 18f,
                contextMeasureAdvance = 18f,
                shapedContextAdvance = 18f,
                shapedAdjacentAdvance = 18f,
                drawnInkLeft = 23f,
                drawnInkRight = 33f,
                emWidth = 36f,
            ),
            0.01f,
        )
        assertEquals(
            18f,
            resolveCjkPunctuationWidth(
                ch = '（',
                isolatedAdvance = 18f,
                contextRunAdvance = 18f,
                contextMeasureAdvance = 18f,
                shapedContextAdvance = 18f,
                shapedAdjacentAdvance = 18f,
                drawnInkLeft = 4.6f,
                drawnInkRight = 14.6f,
                emWidth = 36f,
            ),
            0.01f,
        )
        assertEquals(
            13f,
            resolveCjkPunctuationWidth(
                ch = '“',
                isolatedAdvance = 13f,
                contextRunAdvance = 13f,
                contextMeasureAdvance = 13f,
                shapedContextAdvance = 13f,
                shapedAdjacentAdvance = 13f,
                drawnInkLeft = 4f,
                drawnInkRight = 20f,
                emWidth = 36f,
            ),
            0.01f,
        )
    }

    @Test fun tightFinalLineEndingWithFullStopDoesNotEmitPhantomEmptyLineOrJustify() {
        val text = "非同一控制下合并（购买法）是市场买卖观：花多少钱（对价公允价值）买东西，成本就是多少。"
        val openIdx1 = text.indexOf('（')
        val openIdx2 = text.indexOf('（', openIdx1 + 1)
        for (w in 280..360 step 4) {
            val width = w.toFloat()
            val layout = TypesetEngine.layout(
                text = text,
                runs = listOf(TypesetRunInput(0, text.length, 0, TypesetSpanKind.NORMAL)),
                maxWidthPx = width,
                mode = TypesetMode.JUSTIFY,
                measurer = measurer,
            )
            assertTrue("Expected multi-line layout at width=$width", layout.lines.size >= 2)
            assertTrue("Final paragraph must not emit an empty trailing line at width=$width", layout.lines.all { it.pieces.isNotEmpty() })
            assertFalse("Last line must not be justified at width=$width", layout.lines.last().justified)
            assertTrue("Last line right=${layout.lines.last().right} overflowed $width", layout.lines.last().right <= width + 0.5f)
            for (openIdx in intArrayOf(openIdx1, openIdx2)) {
                val c1 = openIdx + 1
                val c2 = openIdx + 2
                if (layout.charLine[c1] == layout.charLine[c2]) {
                    assertTrue(
                        "Characters inside parentheses overlapped at width=$width",
                        layout.charLeft[c2] - layout.charLeft[c1] >= 14.5f,
                    )
                }
            }
        }
    }
}
