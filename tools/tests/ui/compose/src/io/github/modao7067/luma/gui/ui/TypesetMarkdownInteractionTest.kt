@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package io.github.modao7067.luma.gui.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.LocalSelectionRegistrar
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.SelectionRegistrar
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp", shadows = [SelectionMagnifierShadow::class])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class TypesetMarkdownInteractionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun singleTildeRangeStaysPlainWhileDoubleTildeStrikesThrough() {
        val text = "手机每行 20~24 字，进入嵌套列表后只有 17~19 字，而 ~~旧算法~~ 才画删除线。"
        val doc = MarkdownDocumentParser().parse(text, complete = true)
        rule.setContent {
            MaterialTheme {
                ConversationTypography {
                    Box(Modifier.width(340.dp)) {
                        NativeMarkdown(text, presentation = MarkdownPresentation(doc, Int.MAX_VALUE))
                    }
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("20~24", substring = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val rendered = layouts.single().layoutInput.text
        assertTrue(rendered.text.contains("20~24"))
        assertTrue(rendered.text.contains("17~19"))
        val strikeSpans = rendered.spanStyles.filter { it.item.textDecoration == TextDecoration.LineThrough }
        assertEquals(1, strikeSpans.size)
        assertEquals("旧算法", rendered.text.substring(strikeSpans.single().start, strikeSpans.single().end))
    }

    @Test fun justifiedParagraphAlignsNonFinalLinesFlushToContainerEdge() {
        val base = "相比 Telari（macOS 宽屏、静态文章），Luma 是手机窄屏对话客户端，需要在有限宽度里把中英文混排、标点挤压和换行平衡同时做好，才能让段落边缘像书本一样齐整。"
        val paragraph = base + base + base
        val doc = MarkdownDocumentParser().parse(paragraph, complete = true)
        rule.setContent {
            MaterialTheme {
                ConversationTypography {
                    Box(Modifier.width(80.dp)) {
                        NativeMarkdown(paragraph, presentation = MarkdownPresentation(doc, Int.MAX_VALUE))
                    }
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(paragraph)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertTrue("Expected multi-line paragraph, got ${layout.lineCount}", layout.lineCount >= 3)
        val targetWidth = layout.size.width.toFloat()
        for (i in 0 until layout.lineCount - 1) {
            assertEquals("Line $i right edge must sit flush at $targetWidth", targetWidth, layout.getLineRight(i), 3.5f)
        }
        assertTrue(layout.getLineRight(layout.lineCount - 1) <= targetWidth + 1f)
    }

    @Test fun fileLinkTapInvokesCallbackInsideTypesetParagraph() {
        val clicked = mutableListOf<String>()
        val source = "请查看 [核心文件](NativeMarkdown.kt) 说明。"
        val doc = MarkdownDocumentParser().parse(source, complete = true)
        rule.setContent {
            MaterialTheme {
                ConversationTypography {
                    Box(Modifier.width(340.dp)) {
                        NativeMarkdown(source, onFileLink = { clicked += it }, presentation = MarkdownPresentation(doc, Int.MAX_VALUE))
                    }
                }
            }
        }
        val node = rule.onNodeWithText("请查看 核心文件 说明。")
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val linkIndex = layouts.single().layoutInput.text.text.indexOf("核心文件") + 1
        val pos = layouts.single().getBoundingBox(linkIndex).center
        node.performTouchInput { click(pos) }
        rule.runOnIdle {
            assertEquals(listOf("NativeMarkdown.kt"), clicked)
        }
    }

    @Test fun streamingTailAvoidsJitterAndJustifiesOnceDocumentCompletes() {
        val unit = "这是一段正在流式输出的较长中文回复，前面已经排满的文字在后面持续追加新字符时不应该反复跳行，等整段输出完成后再自动进入全局两端对齐。"
        val full = unit + unit + unit
        var doc by mutableStateOf(MarkdownDocumentParser().parse(full.substring(0, 90), complete = false))
        rule.setContent {
            MaterialTheme {
                ConversationTypography {
                    Column(Modifier.width(52.dp)) {
                        NativeMarkdown(doc.source, presentation = MarkdownPresentation(doc, Int.MAX_VALUE))
                    }
                }
            }
        }
        fun fetchLayout(): TextLayoutResult {
            val out = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText("这是一段正在流式输出", substring = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(out) }
            return out.single()
        }
        val initial = fetchLayout()
        assertTrue("Expected multi-line streaming prefix, got ${initial.lineCount}", initial.lineCount >= 2)
        val firstLineEnd = initial.getLineEnd(0, visibleEnd = true)

        rule.runOnIdle { doc = MarkdownDocumentParser().parse(full, complete = false) }
        val stillStreaming = fetchLayout()
        assertEquals("Line 0 must not jump while streaming", firstLineEnd, stillStreaming.getLineEnd(0, visibleEnd = true))

        rule.runOnIdle { doc = MarkdownDocumentParser().parse(full, complete = true) }
        val completed = fetchLayout()
        assertTrue(completed.lineCount >= 3)
        val targetWidth = completed.size.width.toFloat()
        for (i in 0 until completed.lineCount - 1) {
            assertEquals(targetWidth, completed.getLineRight(i), 3.5f)
        }
    }

    @Test fun streamingPrefixesDoNotPolluteGlobalTypesetCache() {
        clearTypesetCache()
        val unit = "这是一段正在流式输出的较长中文回复，每个流式前缀都不应该写入全局排版缓存，只有最终稳定段落才会被缓存。"
        var doc by mutableStateOf(MarkdownDocumentParser().parse(unit.substring(0, 20), complete = false))
        rule.setContent {
            MaterialTheme {
                ConversationTypography {
                    Column(Modifier.width(240.dp)) {
                        NativeMarkdown(doc.source, presentation = MarkdownPresentation(doc, Int.MAX_VALUE))
                    }
                }
            }
        }
        rule.waitForIdle()
        assertEquals("Streaming prefix must not enter global cache", 0, typesetCacheSizeChars())

        for (len in listOf(30, 42, unit.length)) {
            rule.runOnIdle { doc = MarkdownDocumentParser().parse(unit.substring(0, len), complete = false) }
            rule.waitForIdle()
            assertEquals("Streaming prefix len=$len must not enter global cache", 0, typesetCacheSizeChars())
        }

        rule.runOnIdle { doc = MarkdownDocumentParser().parse(unit, complete = true) }
        rule.waitForIdle()
        assertEquals(unit.length, typesetCacheSizeChars())
    }

    @Test fun rtlMarkdownParagraphDelegatesCleanlyToPlatformText() {
        val arabic = "مرحبا بالعالم في اختبار النص العربي مع `code` داخل الفقرة."
        val hebrew = "שלום עולם בבדיקת כיווניות מלאה."
        val source = "$arabic\n\n$hebrew"
        val doc = MarkdownDocumentParser().parse(source, complete = true)
        rule.setContent {
            MaterialTheme {
                ConversationTypography {
                    Column(Modifier.width(300.dp)) {
                        NativeMarkdown(source, presentation = MarkdownPresentation(doc, Int.MAX_VALUE))
                    }
                }
            }
        }
        rule.onNodeWithText("مرحبا بالعالم", substring = true).assertIsDisplayed()
        rule.onNodeWithText("שלום עולם", substring = true).assertIsDisplayed()
    }

    @Test fun pausedStreamingFlushesThrottledSelectionLayoutAfterInterval() {
        val initial = "流式首句。"
        val expanded = "流式首句。后面紧跟大量最新输出的流式正文内容，用于验证流式暂停后依然能够精确选中末尾的新增文字。"
        var text by mutableStateOf(AnnotatedString(initial))
        var registrar: SelectionRegistrar? = null
        rule.setContent {
            MaterialTheme {
                ConversationTypography {
                    SelectionContainer {
                        registrar = LocalSelectionRegistrar.current
                        Box(Modifier.width(180.dp)) {
                            TypesetText(
                                text = text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                mode = TypesetMode.STREAMING,
                                codeFontFamily = null,
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { text = AnnotatedString(expanded) }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(320L)
        rule.waitForIdle()

        val node = rule.onNodeWithText("新增文字", substring = true)
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val targetIdx = layouts.single().layoutInput.text.text.indexOf("新增文字") + 1
        val pos = layouts.single().getBoundingBox(targetIdx).center
        node.performTouchInput { longClick(pos) }
        rule.waitForIdle()

        var coversTarget = false
        var observedRange = -1..-1
        registrar?.subselections?.forEachValue { sel ->
            val lo = minOf(sel.start.offset, sel.end.offset)
            val hi = maxOf(sel.start.offset, sel.end.offset)
            observedRange = lo..hi
            if (targetIdx in lo..hi && lo >= initial.length) {
                coversTarget = true
            }
        }
        assertTrue(
            "Expected selection to cover targetIdx=$targetIdx in appended tail (observed=$observedRange)",
            coversTarget,
        )
    }

    @Test fun chineseFootnoteRendersSuperscriptReferenceAndFootnoteBody() {
        val source = buildString {
            appendLine("正文说明这里有一处脚注[^1]。")
            appendLine()
            append("[^1]: 这是没有空格的中文脚注正文，含[参考链接](NativeMarkdown.kt)。")
        }
        val clicked = mutableListOf<String>()
        val doc = MarkdownDocumentParser().parse(source, complete = true)
        rule.setContent {
            MaterialTheme {
                ConversationTypography {
                    Box(Modifier.width(340.dp)) {
                        NativeMarkdown(source, onFileLink = { clicked += it }, presentation = MarkdownPresentation(doc, Int.MAX_VALUE))
                    }
                }
            }
        }
        val bodyLayouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("正文说明这里有一处脚注[1]。")
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(bodyLayouts) }
        val bodyText = bodyLayouts.single().layoutInput.text
        val supSpan = bodyText.spanStyles.firstOrNull {
            bodyText.text.substring(it.start, it.end) == "[1]" && (it.item.baselineShift?.multiplier ?: 0f) > 0f
        }
        assertNotNull("Expected superscript [1] span in body", supSpan)

        rule.onNodeWithText("1.").assertIsDisplayed()
        val noteNode = rule.onNodeWithText("这是没有空格的中文脚注正文", substring = true)
        noteNode.assertIsDisplayed()
        val noteLayouts = mutableListOf<TextLayoutResult>()
        noteNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(noteLayouts) }
        val linkIdx = noteLayouts.single().layoutInput.text.text.indexOf("参考链接") + 1
        val pos = noteLayouts.single().getBoundingBox(linkIdx).center
        noteNode.performTouchInput { click(pos) }
        rule.runOnIdle {
            assertEquals(listOf("NativeMarkdown.kt"), clicked)
        }
    }

    @Test fun justifiedInlineCodeSelectionMatchesGlyphBoundsAndWordBoundaryIncludesUnderscore() {
        val source = "新增 `MAX_LIVE_WEBVIEWS = 4` LRU 回收，超过上限时销毁最久未使用的闲置后台 `WebView` 并保留 URL/标题元数据；"
        val doc = MarkdownDocumentParser().parse(source, complete = true)
        rule.setContent {
            MaterialTheme {
                ConversationTypography {
                    Box(Modifier.width(280.dp)) {
                        NativeMarkdown(source, presentation = MarkdownPresentation(doc, Int.MAX_VALUE))
                    }
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("MAX_LIVE_WEBVIEWS", substring = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val raw = layout.layoutInput.text.text
        val ident = "MAX_LIVE_WEBVIEWS"
        val start = raw.indexOf(ident)
        val end = start + ident.length
        assertTrue(start > 0)

        val underscoreIdx = raw.indexOf('_', start)
        val boundary = layout.getWordBoundary(underscoreIdx)
        assertEquals(start, boundary.start)
        assertEquals(end, boundary.end)

        val pathBounds = layout.getPathForRange(start, end).getBounds()
        val firstBox = layout.getBoundingBox(start)
        val lastBox = layout.getBoundingBox(end - 1)
        assertEquals(firstBox.left, pathBounds.left, 0.5f)
        assertEquals(lastBox.right, pathBounds.right, 0.5f)
        assertEquals(lastBox.right, layout.getHorizontalPosition(end, usePrimaryDirection = true), 0.5f)

        val measurer = object : TypesetMeasurer {
            override fun measureWidth(text: String, start: Int, end: Int, styleId: Int): Float =
                (end - start) * (if (styleId == 1) 12f else 20f)
            override fun measureSpaceWidth(styleId: Int): Float = if (styleId == 1) 12f else 6f
            override fun measureHyphenWidth(styleId: Int): Float = 8f
            override fun measureEmWidth(styleId: Int): Float = 20f
            override fun measurePunctuationTrim(ch: Char, styleId: Int): PunctuationTrim = PunctuationTrim()
            override fun baseLineMetrics(): TypesetLineMetrics = TypesetLineMetrics(24f, 18f, 18f, 6f)
        }
        val plain = "新增 MAX_LIVE_WEBVIEWS = 4 LRU 回收，超过上限时销毁最久未使用的闲置后台 WebView 并保留元数据。"
        val codeStart = plain.indexOf("MAX_LIVE_WEBVIEWS = 4")
        val codeEnd = codeStart + "MAX_LIVE_WEBVIEWS = 4".length
        val runs = listOf(
            TypesetRunInput(0, codeStart, 0, TypesetSpanKind.NORMAL),
            TypesetRunInput(codeStart, codeEnd, 1, TypesetSpanKind.CODE),
            TypesetRunInput(codeEnd, plain.length, 0, TypesetSpanKind.NORMAL),
        )
        val engineLayout = TypesetEngine.layout(plain, runs, maxWidthPx = 395f, mode = TypesetMode.JUSTIFY, measurer = measurer)
        val codePieces = engineLayout.lines.flatMap { it.pieces }.filter { it.kind == TypesetSpanKind.CODE }
        assertTrue(codePieces.isNotEmpty())
        assertTrue("CODE pieces must stay unscaled (scaleX=1f) even on justified lines", codePieces.all { it.scaleX == 1f })
    }
}
