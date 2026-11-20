package io.github.modao7067.luma.gui.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp")
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
}
