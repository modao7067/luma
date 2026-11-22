package io.github.modao7067.luma.gui.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import io.github.modao7067.luma.gui.session.ConversationItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ViewportCodeWindowInteractionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun codeBlockSoftWrapOnlyMountsVisibleWindowAndUpdatesOnScroll() {
        val code = (0 until 320).joinToString("\n") { idx -> "val item$idx = \"marker_$idx\"" }
        lateinit var vScroll: androidx.compose.foundation.ScrollState
        rule.setContent {
            MaterialTheme {
                vScroll = rememberScrollState()
                Box(Modifier.width(360.dp)) {
                    CodeBlock(code = code, language = "kotlin", bodyVerticalScrollState = vScroll, softWrap = true)
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("marker_0", substring = true).assertExists()
        rule.onAllNodesWithText("marker_319", substring = true).assertCountEquals(0)

        rule.runOnIdle { runBlocking { vScroll.scrollTo(vScroll.maxValue) } }
        rule.waitForIdle()
        rule.onNodeWithText("marker_319", substring = true).assertExists()
        rule.onAllNodesWithText("marker_0", substring = true).assertCountEquals(0)
    }

    @Test fun codeBlockNoWrapOnlyMountsVisibleWindowAndSupportsHorizontalScroll() {
        val code = (0 until 240).joinToString("\n") { idx ->
            val tag = idx.toString().padStart(3, '0')
            "val wide$tag = \"nowrap_marker_$tag\" + \"${"segment_".repeat(70)}\""
        }
        lateinit var vScroll: androidx.compose.foundation.ScrollState
        lateinit var hScroll: androidx.compose.foundation.ScrollState
        rule.setContent {
            MaterialTheme {
                vScroll = rememberScrollState()
                hScroll = rememberScrollState()
                Box(Modifier.width(320.dp)) {
                    CodeBlock(code = code, language = "kotlin", bodyVerticalScrollState = vScroll, bodyHorizontalScrollState = hScroll, softWrap = false)
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("nowrap_marker_000", substring = true).assertExists()
        rule.onAllNodesWithText("nowrap_marker_239", substring = true).assertCountEquals(0)
        rule.runOnIdle { assertTrue("wide no-wrap code must allow horizontal scroll", hScroll.maxValue > 0) }

        rule.runOnIdle { runBlocking { vScroll.scrollTo(vScroll.maxValue) } }
        rule.waitForIdle()
        rule.onNodeWithText("nowrap_marker_239", substring = true).assertExists()
        rule.onAllNodesWithText("nowrap_marker_000", substring = true).assertCountEquals(0)
    }

    @Test fun processMergedToolCardCullsOffscreenOutputWhenTopInputIsVisible() {
        val input = (0 until 180).joinToString("\n") { idx -> "echo input_marker_${idx.toString().padStart(3, '0')}" }
        val output = (0 until 180).joinToString("\n") { idx -> "output_marker_${idx.toString().padStart(3, '0')}" }
        val item = ConversationItem(
            id = "long-tool",
            role = "tool",
            text = input,
            input = input,
            detail = output,
            kind = "commandExecution",
            outcome = "succeeded",
        )
        rule.setContent {
            MaterialTheme {
                Box(Modifier.width(360.dp)) {
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
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("input_marker_000", substring = true).fetchSemanticsNodes().isNotEmpty())
        rule.onAllNodesWithText("output_marker_179", substring = true).assertCountEquals(0)
    }

    @Test fun readAndDiffCardsCullOffscreenCodeChunks() {
        val readItem = contentBlockReadFixture("long-read-window", "Engine.kt").copy(
            input = """{"path":"/project/src/Engine.kt","offset":1,"limit":260}""",
            detail = (1..260).joinToString("\n") { idx ->
                val tag = idx.toString().padStart(3, '0')
                "$idx: val readLine$tag = \"read_marker_$tag\""
            },
        )
        val diffItem = contentBlockEditFixture.first().copy(
            detail = buildString {
                appendLine("@@ -1,220 +1,220 @@")
                repeat(220) { idx ->
                    val tag = idx.toString().padStart(3, '0')
                    appendLine("+val diffLine$tag = \"diff_marker_$tag\"")
                }
            },
        )
        rule.setContent {
            MaterialTheme {
                Column(Modifier.width(360.dp)) {
                    ProcessGroup(
                        listOf(readItem),
                        cli = "OpenCode",
                        initiallyExpanded = true,
                        initiallyOpenItem = readItem.id,
                        previewDetailFraction = 1f,
                        previewGroupFraction = 1f,
                    )
                    ProcessGroup(
                        listOf(diffItem),
                        cli = "OpenCode",
                        initiallyExpanded = true,
                        initiallyOpenItem = diffItem.id,
                        previewDetailFraction = 1f,
                        previewGroupFraction = 1f,
                    )
                }
            }
        }
        rule.waitForIdle()
        val readChunk0 = rule.onNodeWithText("read_marker_001", substring = true).fetchSemanticsNode().boundsInRoot
        val readChunk1 = rule.onNodeWithText("read_marker_017", substring = true).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "read chunks must stay contiguous (chunk0Bottom=${readChunk0.bottom}, chunk1Top=${readChunk1.top})",
            readChunk1.top <= readChunk0.bottom + 2f,
        )
        rule.onAllNodesWithText("read_marker_260", substring = true).assertCountEquals(0)
        val diffChunk0 = rule.onNodeWithText("diff_marker_000", substring = true).fetchSemanticsNode().boundsInRoot
        val diffChunk1 = rule.onNodeWithText("diff_marker_016", substring = true).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "diff chunks must stay contiguous (chunk0Bottom=${diffChunk0.bottom}, chunk1Top=${diffChunk1.top})",
            diffChunk1.top <= diffChunk0.bottom + 2f,
        )
        rule.onAllNodesWithText("diff_marker_219", substring = true).assertCountEquals(0)
    }

    @Test fun codeFileViewerOnlyMountsVisibleWindowInsideScrollablePreview() {
        val code = (1..360).joinToString("\n") { idx ->
            val tag = idx.toString().padStart(3, '0')
            "fun line$tag() = \"file_marker_$tag\""
        }
        lateinit var scroll: androidx.compose.foundation.ScrollState
        rule.setContent {
            MaterialTheme {
                scroll = rememberScrollState()
                var viewportH by remember { mutableFloatStateOf(0f) }
                Box(Modifier.width(360.dp).height(300.dp).onSizeChanged { viewportH = it.height.toFloat() }) {
                    Column(Modifier.verticalScroll(scroll)) {
                        CodeFileViewer(
                            code = code,
                            language = "kotlin",
                            verticalScrollState = scroll,
                            viewportHeightPx = viewportH,
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        val firstChunkBounds = rule.onNodeWithText("file_marker_001", substring = true).fetchSemanticsNode().boundsInRoot
        val secondChunkBounds = rule.onNodeWithText("file_marker_017", substring = true).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "adjacent chunks must not leave a blank vertical gap (firstBottom=${firstChunkBounds.bottom}, secondTop=${secondChunkBounds.top})",
            secondChunkBounds.top <= firstChunkBounds.bottom + 2f,
        )
        rule.onAllNodesWithText("file_marker_360", substring = true).assertCountEquals(0)

        rule.runOnIdle { runBlocking { scroll.scrollTo(scroll.maxValue) } }
        rule.waitForIdle()
        rule.onNodeWithText("file_marker_360", substring = true).assertExists()
        rule.onAllNodesWithText("file_marker_001", substring = true).assertCountEquals(0)
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .let { nodes ->
                assertTrue("windowed file viewer should keep mounted text nodes bounded", nodes.size <= 12)
            }
    }

    @Test fun markdownDocumentCardSourceModeCullsOffscreenLinesInScrollContainer() {
        val markdownSource = buildString {
            appendLine("# Heading")
            repeat(280) { idx ->
                appendLine("Paragraph line md_marker_$idx with `code_$idx`.")
            }
        }
        lateinit var scroll: androidx.compose.foundation.ScrollState
        rule.setContent {
            MaterialTheme {
                scroll = rememberScrollState()
                Box(Modifier.width(360.dp).height(320.dp)) {
                    Column(Modifier.verticalScroll(scroll)) {
                        MarkdownDocumentCard(
                            code = markdownSource,
                            language = "markdown",
                            initiallyExpanded = true,
                            initiallyShowSource = true,
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("md_marker_0", substring = true).assertExists()
        rule.onAllNodesWithText("md_marker_279", substring = true).assertCountEquals(0)

        rule.runOnIdle { runBlocking { scroll.scrollTo(scroll.maxValue) } }
        rule.waitForIdle()
        rule.onNodeWithText("md_marker_279", substring = true).assertExists()
    }
}
