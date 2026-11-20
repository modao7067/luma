package io.github.modao7067.luma.gui.ui

import android.app.Application
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import io.github.modao7067.luma.gui.session.ConversationItem
import kotlinx.coroutines.runBlocking
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
class CodeBlockLongOutputTest {
    @get:Rule val rule = createComposeRule()
    private var code by mutableStateOf("val x = \"text\"; return 123 // comment\n".repeat(5000) + "最后一行🙂 " + "wide-output ".repeat(30))
    private var copied: AnnotatedString? = null
    private val clipboard = object : ClipboardManager {
        override fun setText(annotatedString: AnnotatedString) { copied = annotatedString }
        override fun getText() = copied
    }
    @Test fun longOutputStaysCompleteCopyableAndFixedWhileStreamingAtAnInnerScrollPosition() {
        lateinit var vertical: ScrollState
        lateinit var horizontal: ScrollState
        rule.setContent { MaterialTheme {
            vertical = rememberScrollState(); horizontal = rememberScrollState()
            CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                Box(Modifier.width(360.dp).testTag("shell")) { CodeBlock(code, "output", vertical, horizontal) }
            }
        } }
        rule.waitForIdle()
        val height = rule.onNodeWithTag("shell").fetchSemanticsNode().boundsInRoot.height
        rule.onNodeWithContentDescription("复制").performClick()
        assertEquals(code, copied?.text)
        rule.onNodeWithTag("shell").performTouchInput {
            swipe(Offset(center.x, bottom - 24f), Offset(center.x, top + 100f), 180)
        }
        rule.runOnIdle { assertTrue("actual body drag scrolls", vertical.value > 0) }
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange), useUnmergedTree = true)
            .assertCountEquals(0)
        rule.runOnIdle { assertEquals(0, horizontal.value) }
        rule.runOnIdle { runBlocking { vertical.scrollTo(3000) } }
        val anchor = rule.runOnIdle { vertical.value }
        repeat(5) { step -> rule.runOnIdle { code += "\nstream $step val 42" }; rule.waitForIdle() }
        rule.runOnIdle { assertEquals(anchor, vertical.value); assertTrue(vertical.maxValue > 3000) }
        assertEquals(height, rule.onNodeWithTag("shell").fetchSemanticsNode().boundsInRoot.height)
        rule.onNodeWithContentDescription("复制").assertIsDisplayed().performClick()
        assertEquals(code, copied?.text)
        rule.runOnIdle { runBlocking { vertical.scrollTo(vertical.maxValue) } }
        rule.onNodeWithText(code).assertExists()
        println("PASS full ${code.length} chars, fixed shell, inner reading anchor, five streaming updates, exact copy including tail")
    }
    @Test fun adaptiveSoftWrapRespondsToViewportWidthAcrossCodeBlockAndToolCard() {
        val codeBlockText = "val first = \"short\"\nval longPath = \"" + "segment/".repeat(60) + "example.kt\""
        val toolCommand = "python3 tools/verify.py --offline " + "--include=/workspace/app/module ".repeat(16)
        val toolOutput = "short-head\n" + "segment-".repeat(64)
        val item = ConversationItem(
            "wrap-cmd",
            "tool",
            text = toolCommand,
            input = toolCommand,
            detail = toolOutput,
            kind = "commandExecution",
            outcome = "succeeded",
        )
        var viewportWidth by mutableStateOf(160.dp)
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                    Column(Modifier.width(viewportWidth)) {
                        CodeBlock(codeBlockText, "kotlin")
                        ProcessGroup(
                            listOf(item),
                            cli = "Codex",
                            initiallyExpanded = true,
                            initiallyOpenItem = item.id,
                            previewDetailFraction = 1f,
                            previewGroupFraction = 1f,
                        )
                    }
                }
            }
        }
        rule.waitForIdle()

        fun layoutOf(text: String): TextLayoutResult {
            val layouts = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(text, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single()
        }

        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange), useUnmergedTree = true)
            .assertCountEquals(0)
        val narrowCodeLayout = layoutOf(codeBlockText)
        val narrowCmdLayout = layoutOf(toolCommand)
        val narrowOutLayout = layoutOf(toolOutput)
        for (layout in listOf(narrowCodeLayout, narrowCmdLayout, narrowOutLayout)) {
            assertTrue(layout.layoutInput.softWrap)
            assertEquals(LineBreak.Simple, layout.layoutInput.style.lineBreak)
            assertEquals(Hyphens.None, layout.layoutInput.style.hyphens)
        }
        assertEquals(narrowCmdLayout.layoutInput.constraints.maxWidth, narrowOutLayout.layoutInput.constraints.maxWidth)
        assertTrue(narrowCmdLayout.layoutInput.constraints.maxWidth > 0)
        val newlineLine = narrowOutLayout.getLineForOffset("short-head\n".length)
        assertFalse(isSoftWrappedVisualLine(narrowOutLayout, newlineLine))
        rule.onAllNodesWithContentDescription("复制")[0].performClick()
        assertEquals(codeBlockText, copied?.text)
        rule.onAllNodesWithContentDescription("复制")[1].performClick()
        assertEquals(toolCommand, copied?.text)

        rule.runOnIdle { viewportWidth = 390.dp }
        rule.waitForIdle()
        val wideCodeLayout = layoutOf(codeBlockText)
        val wideCmdLayout = layoutOf(toolCommand)
        val wideOutLayout = layoutOf(toolOutput)
        val codeWidthDelta = wideCodeLayout.layoutInput.constraints.maxWidth - narrowCodeLayout.layoutInput.constraints.maxWidth
        val cmdWidthDelta = wideCmdLayout.layoutInput.constraints.maxWidth - narrowCmdLayout.layoutInput.constraints.maxWidth
        val outWidthDelta = wideOutLayout.layoutInput.constraints.maxWidth - narrowOutLayout.layoutInput.constraints.maxWidth
        assertEquals(codeWidthDelta, cmdWidthDelta)
        assertEquals(cmdWidthDelta, outWidthDelta)
        assertTrue(wideCodeLayout.layoutInput.constraints.maxWidth > narrowCodeLayout.layoutInput.constraints.maxWidth * 2)
        assertTrue(wideCmdLayout.layoutInput.constraints.maxWidth > narrowCmdLayout.layoutInput.constraints.maxWidth * 2)
        assertTrue(wideOutLayout.layoutInput.constraints.maxWidth > narrowOutLayout.layoutInput.constraints.maxWidth * 2)
    }

    @Test fun backgroundHighlightNeverPublishesAnotherInputsText() {
        var rendered = AnnotatedString("")
        rule.setContent { MaterialTheme { rendered = rememberHighlightedCode(code, "kotlin", MaterialTheme.colorScheme) } }
        repeat(8) { step ->
            rule.runOnIdle { code = "val current = $step\n" + "x ".repeat(100000) }
            rule.waitForIdle()
            rule.runOnIdle { assertEquals(code, rendered.text) }
        }
        rule.mainClock.advanceTimeBy(100)
        rule.waitUntil(10000) { rendered.spanStyles.isNotEmpty() }
        rule.runOnIdle { assertEquals(code, rendered.text) }
        println("PASS immediate full text and latest-only async coloring through eight replacements")
    }
}
