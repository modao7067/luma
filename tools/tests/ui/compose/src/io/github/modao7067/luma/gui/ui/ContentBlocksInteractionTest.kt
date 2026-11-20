package io.github.modao7067.luma.gui.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
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
class ContentBlocksInteractionTest {
    @get:Rule val rule = createComposeRule()
    private var copied: AnnotatedString? = null
    private val clipboard = object : ClipboardManager {
        override fun setText(annotatedString: AnnotatedString) { copied = annotatedString }
        override fun getText() = copied
    }

    @Test fun nestedCodeKeepsItsCopyActionAfterSharingTheBlockPalette() {
        val code = "val spacing = 12.dp"
        rule.setContent { MaterialTheme {
            CompositionLocalProvider(LocalClipboardManager provides clipboard, LocalMarkdownNestingDepth provides 1) {
                Box(Modifier.width(350.dp)) { CodeBlock(code, "kotlin") }
            }
        } }
        rule.onNodeWithText(code).assertIsDisplayed()
        rule.onNodeWithContentDescription("复制").assertIsDisplayed().performClick()
        assertEquals(code, copied?.text)
    }

    @Test fun documentExpandsSwitchesModesCopiesAndReturnsToItsSummary() {
        rule.setContent { MaterialTheme {
            CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                Box(Modifier.width(350.dp)) { MarkdownDocumentCard(contentBlockDocumentFixture) }
            }
        } }
        rule.onNodeWithContentDescription("复制").performClick()
        assertEquals(contentBlockDocumentFixture, copied?.text)
        rule.onNodeWithContentDescription("展开").performClick()
        rule.onNodeWithText("预览").assertIsSelected()
        rule.onNodeWithText("源码").performClick().assertIsSelected()
        rule.onNodeWithText(contentBlockDocumentFixture).assertIsDisplayed()
        rule.onNodeWithContentDescription("复制").performClick()
        assertEquals(contentBlockDocumentFixture, copied?.text)
        rule.onNodeWithText("预览").performClick().assertIsSelected()
        rule.onNodeWithContentDescription("收起").performClick()
        rule.onNodeWithContentDescription("展开").assertIsDisplayed()
        rule.onNodeWithTag("markdown-document-card").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已折叠 Markdown 文档卡片"),
        )
    }

    @Test fun narrowLargeTextKeepsDocumentControlsInsideTheCard() {
        rule.setContent { MaterialTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                Box(Modifier.width(284.dp)) { MarkdownDocumentCard(contentBlockDocumentFixture, initiallyExpanded = true) }
            }
        } }
        val card = rule.onNodeWithTag("markdown-document-card").fetchSemanticsNode().boundsInRoot
        val controls = listOf(rule.onNodeWithText("预览"), rule.onNodeWithText("源码"),
            rule.onNodeWithContentDescription("收起"), rule.onNodeWithContentDescription("复制"))
        controls.forEach {
            it.assertIsDisplayed()
            val bounds = it.fetchSemanticsNode().boundsInRoot
            assertTrue("control left stays inside card", bounds.left >= card.left)
            assertTrue("control right stays inside card", bounds.right <= card.right)
        }
        rule.onNodeWithText("源码").performClick().assertIsSelected()
    }

    @Test fun streamingDocumentCopiesOnlyTheVisiblePrefixInEitherState() {
        val source = "# Visible\n\nVisible content.\n\nFUTURE_CONTENT"
        val end = source.indexOf("FUTURE_CONTENT")
        rule.setContent { MaterialTheme {
            CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                Box(Modifier.width(350.dp)) { MarkdownDocumentCard(source, visibleCharacters = end) }
            }
        } }
        rule.onNodeWithContentDescription("复制").performClick()
        assertEquals(source.take(end), copied?.text)
        rule.onNodeWithContentDescription("展开").performClick()
        rule.onNodeWithText("源码").performClick()
        rule.onNodeWithContentDescription("复制").performClick()
        assertEquals(source.take(end), copied?.text)
        rule.onAllNodesWithText("FUTURE_CONTENT", substring = true).assertCountEquals(0)
    }

    @Test fun singleReadKeepsItsFileAndRangeWithoutACopyButton() {
        val item = contentBlockReadFixture("single", "Message.kt", 28)
        val preview = parseProcessReadPreview(item, "OpenCode")
        rule.setContent { MaterialTheme {
            Box(Modifier.width(350.dp)) {
                ProcessGroup(listOf(item), cli = "OpenCode", initiallyExpanded = true, initiallyOpenItem = item.id)
            }
        } }
        rule.onNodeWithText(preview.fileName, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText(preview.rangeLabel, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText(preview.cleanText).assertIsDisplayed()
        rule.onAllNodesWithContentDescription("复制").assertCountEquals(0)
    }

    @Test fun batchReadTabsSwitchContentAndKeepSelectionAfterCollapse() {
        val first = contentBlockReadFixture("batch-a", "Message.kt")
        val second = contentBlockReadFixture("batch-b", "Theme.kt", 32)
        rule.setContent { MaterialTheme {
            Box(Modifier.width(350.dp)) {
                ProcessGroup(listOf(first, second), cli = "OpenCode", initiallyExpanded = true, initiallyOpenItem = first.id)
            }
        } }
        val firstTab = rule.onNode(hasText("Message.kt") and isSelectable())
        val secondTab = rule.onNode(hasText("Theme.kt") and isSelectable())
        firstTab.assertIsSelected()
        secondTab.performClick().assertIsSelected()
        firstTab.assertIsNotSelected()
        rule.onNodeWithText(parseProcessReadPreview(second, "OpenCode").cleanText).assertIsDisplayed()
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "收起批量读取")).performClick()
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "展开批量读取")).performClick()
        secondTab.assertIsSelected()
    }

    @Test fun narrowEditHeaderKeepsTheFilenameAheadOfSecondaryMetadata() {
        var cardWidth by mutableStateOf(248.dp)
        rule.setContent { MaterialTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                Box(Modifier.width(cardWidth)) {
                    ProcessEditCardGroup(contentBlockEditFixture, "OpenCode", rememberSaveableStateHolder(), null, false, null)
                }
            }
        } }
        val narrowLayouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("edit · Message.kt", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(narrowLayouts) }
        assertTrue(narrowLayouts.isNotEmpty())
        assertEquals(1, narrowLayouts.single().lineCount)
        rule.onNodeWithText("2 处", useUnmergedTree = true).assertIsDisplayed()
        rule.onAllNodesWithText("src", useUnmergedTree = true).assertCountEquals(0)

        rule.runOnIdle { cardWidth = 300.dp }
        val widerLayouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("edit · Message.kt", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(widerLayouts) }
        assertTrue(widerLayouts.isNotEmpty())
        assertEquals(1, widerLayouts.single().lineCount)
        assertFalse("filename must not ellipsize when width suffices", widerLayouts.single().isLineEllipsized(0))
        rule.onNodeWithText("2 处", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun readViewportScrollsBothWaysWithoutMovingItsFileHeader() {
        val item = contentBlockReadFixture("long-read", "Message.kt").copy(
            input = """{"path":"/project/src/Message.kt","offset":1,"limit":60}""",
            detail = (1..60).joinToString("\n") { "$it: val message$it = \"${"long-content ".repeat(40)}\"" },
        )
        rule.setContent { MaterialTheme {
            Box(Modifier.width(300.dp)) {
                ProcessGroup(listOf(item), cli = "OpenCode", initiallyExpanded = true, initiallyOpenItem = item.id)
            }
        } }
        val title = rule.onNodeWithText("Message.kt", useUnmergedTree = true)
        val bounds = title.fetchSemanticsNode().boundsInRoot
        assertBodyScrollsBothWays()
        assertEquals(bounds, title.fetchSemanticsNode().boundsInRoot)
        rule.onAllNodesWithContentDescription("复制").assertCountEquals(0)
    }

    @Test fun diffViewportScrollsBothWaysWithoutMovingItsFileHeader() {
        val item = contentBlockEditFixture.first().copy(detail = buildString {
            appendLine("@@ -1,60 +1,60 @@")
            repeat(60) { appendLine(" val message$it = \"${"long-content ".repeat(40)}\"") }
        })
        rule.setContent { MaterialTheme {
            Box(Modifier.width(300.dp)) {
                ProcessEditCardGroup(listOf(item), "OpenCode", rememberSaveableStateHolder(), item.id, false, null)
            }
        } }
        val title = rule.onNodeWithText("edit · Message.kt", useUnmergedTree = true)
        val bounds = title.fetchSemanticsNode().boundsInRoot
        assertBodyScrollsBothWays()
        assertEquals(bounds, title.fetchSemanticsNode().boundsInRoot)
    }

    @Test fun terminalCommandDisplaysAndCopiesTheUnwrappedScript() {
        val command = """/bin/bash -lc 'printf "%s\n" "${'$'}HOME" | head'"""
        val script = """printf "%s\n" "${'$'}HOME" | head"""
        val item = io.github.modao7067.luma.gui.session.ConversationItem(
            "terminal-command", "tool", text = command, input = command, kind = "commandExecution", outcome = "succeeded",
        )
        rule.setContent { MaterialTheme {
            CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                Box(Modifier.width(350.dp)) {
                    ProcessGroup(listOf(item), cli = "Codex", initiallyExpanded = true, initiallyOpenItem = item.id)
                }
            }
        } }
        rule.onNodeWithText("bash").assertIsDisplayed()
        rule.onNodeWithText(script).assertIsDisplayed()
        rule.onNodeWithContentDescription("复制").performClick()
        assertEquals(script, copied?.text)
        assertEquals(command, item.input)
    }

    @Test fun mergedToolCardBalancesDividerAcrossThreeLineSlotsAndCopiesOnlyInput() {
        val inputText = "echo alpha\necho beta"
        val outputText = "alpha\nbeta"
        val item = io.github.modao7067.luma.gui.session.ConversationItem(
            "merged-cmd",
            "tool",
            text = inputText,
            input = inputText,
            detail = outputText,
            kind = "commandExecution",
            outcome = "succeeded",
        )
        rule.setContent { MaterialTheme {
            CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                Box(Modifier.width(350.dp)) {
                    ProcessGroup(listOf(item), cli = "Codex", initiallyExpanded = true, initiallyOpenItem = item.id)
                }
            }
        } }
        rule.onAllNodesWithText("Output").assertCountEquals(0)
        rule.onAllNodesWithText("output").assertCountEquals(0)
        rule.onNodeWithContentDescription("复制").performClick()
        assertEquals(inputText, copied?.text)
        val inputNode = rule.onNodeWithText(inputText, useUnmergedTree = true)
        val outputNode = rule.onNodeWithText(outputText, useUnmergedTree = true)
        val inputBounds = inputNode.fetchSemanticsNode().boundsInRoot
        val outputBounds = outputNode.fetchSemanticsNode().boundsInRoot
        val inputLayouts = mutableListOf<TextLayoutResult>()
        val outputLayouts = mutableListOf<TextLayoutResult>()
        inputNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(inputLayouts) }
        outputNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(outputLayouts) }
        val inputLayout = inputLayouts.single()
        val outputLayout = outputLayouts.single()
        val lineStepPx = inputLayout.getLineBaseline(1) - inputLayout.getLineBaseline(0)
        val inputLastBaseline = inputBounds.top + inputLayout.getLineBaseline(1)
        val outputFirstBaseline = outputBounds.top + outputLayout.getLineBaseline(0)
        assertEquals(2f * lineStepPx, outputFirstBaseline - inputLastBaseline, 1.5f)
    }

    private fun assertBodyScrollsBothWays() {
        val verticalAxis = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
        val vertical = rule.onNode(verticalAxis, useUnmergedTree = true)
        vertical.performTouchInput { swipeUp() }
        assertTrue(vertical.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
        val horizontal = rule.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange) and hasAnyAncestor(verticalAxis),
            useUnmergedTree = true,
        )
        val horizontalRange = horizontal.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertTrue("fixture must overflow horizontally", horizontalRange.maxValue() > 0f)
        horizontal.performTouchInput { swipeLeft() }
        assertTrue(horizontal.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value() > 0f)
    }
}
