package io.github.modao7067.luma.gui.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.modao7067.luma.gui.agent.CodexProjection
import io.github.modao7067.luma.gui.agent.obj
import org.json.JSONArray
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
class CodexCommandBlocksInteractionTest {
    @get:Rule val rule = createComposeRule()

    private fun read(id: String, file: String, body: String) = CodexProjection.item(obj(
        "id" to id, "type" to "commandExecution", "status" to "completed", "exitCode" to 0,
        "command" to "sed -n '28,29p' src/$file", "cwd" to "/project", "aggregatedOutput" to body,
        "commandActions" to JSONArray().put(obj("type" to "read", "command" to "sed -n '28,29p' src/$file", "path" to "src/$file", "name" to file)),
    ))

    @Test fun nativeCommandReadUsesTheSingleFileCardWithoutCopyOrOutputWrappers() {
        val item = read("read-one", "Message.kt", "val title = \"hello\"\nval body = \"world\"\n")
        rule.setContent { MaterialTheme {
            Box(Modifier.width(350.dp)) {
                ProcessGroup(listOf(item), cli = "Codex", initiallyExpanded = true, initiallyOpenItem = item.id)
            }
        } }
        rule.onNodeWithText("Message.kt", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("L28-29", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("val title = \"hello\"\nval body = \"world\"").assertIsDisplayed()
        rule.onAllNodesWithContentDescription("复制").assertCountEquals(0)
        rule.onAllNodesWithText("Parameters").assertCountEquals(0)
    }

    @Test fun consecutiveNativeCommandReadsUseTheExistingBatchTabs() {
        val first = read("read-first", "Message.kt", "val title = \"hello\"\nval body = \"world\"\n")
        val second = read("read-second", "Theme.kt", "val spacing = 12.dp\nval radius = 12.dp\n")
        assertTrue(groupProcessRunSlices(listOf(first, second), "Codex", first.id).single() is ProcessRunSlice.ReadBatch)
        rule.setContent { MaterialTheme {
            Box(Modifier.width(350.dp)) {
                ProcessGroup(listOf(first, second), cli = "Codex", initiallyExpanded = true, initiallyOpenItem = first.id)
            }
        } }
        rule.onNode(hasText("Message.kt") and isSelectable()).assertIsSelected()
        rule.onNode(hasText("Theme.kt") and isSelectable()).performClick().assertIsSelected()
        rule.onNodeWithText("val spacing = 12.dp\nval radius = 12.dp").assertIsDisplayed()
    }

    @Test fun wrappedCatThroughHeadUsesTheFileReadCard() {
        val base = read("head-read", "CodexContractTest.kt", "package sample\nclass CodexContractTest\n")
        val item = base.copy(input = "/bin/bash -lc \"cat src/CodexContractTest.kt | head -n 135\"", text = "read")
        rule.setContent { MaterialTheme {
            Box(Modifier.width(350.dp)) {
                ProcessGroup(listOf(item), cli = "Codex", initiallyExpanded = true, initiallyOpenItem = item.id)
            }
        } }
        rule.onNodeWithText("CodexContractTest.kt", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("L1-2", useUnmergedTree = true).assertIsDisplayed()
        rule.onAllNodesWithText("Parameters").assertCountEquals(0)
        rule.onAllNodesWithText("Output").assertCountEquals(0)
        rule.onAllNodesWithContentDescription("复制").assertCountEquals(0)
    }

    @Test fun compositeNativeReadsUseOneReadCardInsteadOfParametersAndOutputBlocks() {
        val command = "/bin/bash -lc \"cat src/CodexContractTest.kt | head -n 135; cat src/ContentBlocksInteractionTest.kt | head -n190; sed -n '660,730p' src/ProcessTimeline.kt; sed -n '440,470p' src/ProcessTimeline.kt\""
        val body = "package sample\nclass CodexContractTest\npackage sample\nclass ContentBlocksInteractionTest\nlast read line"
        val actions = JSONArray().apply {
            listOf("CodexContractTest.kt", "ContentBlocksInteractionTest.kt", "ProcessTimeline.kt", "ProcessTimeline.kt").forEach {
                put(obj("type" to "read", "command" to "", "path" to "src/$it", "name" to it))
            }
        }
        val item = CodexProjection.item(obj("id" to "composite-read", "type" to "commandExecution", "status" to "completed",
            "command" to command, "aggregatedOutput" to body, "exitCode" to 0, "commandActions" to actions))
        assertTrue(groupProcessRunSlices(listOf(item), "Codex", item.id).single() is ProcessRunSlice.Single)
        rule.setContent { MaterialTheme {
            Box(Modifier.width(350.dp)) {
                ProcessGroup(listOf(item), cli = "Codex", initiallyExpanded = true, initiallyOpenItem = item.id)
            }
        } }
        rule.onNodeWithText("合并读取输出", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText(body).assertIsDisplayed()
        rule.onAllNodesWithText("Parameters").assertCountEquals(0)
        rule.onAllNodesWithText("Output").assertCountEquals(0)
        rule.onAllNodesWithContentDescription("复制").assertCountEquals(0)
        rule.onAllNodes(isSelectable()).assertCountEquals(0)
    }

    @Test fun shellEditsKeepTheirCommandAndResultRatherThanAnInventedDiff() {
        val command = "sed -i 's/old/new/g' src/Message.kt"
        val item = CodexProjection.item(obj("id" to "shell-edit", "type" to "commandExecution", "status" to "completed",
            "command" to command, "aggregatedOutput" to "updated", "exitCode" to 0))
        assertEquals(ProcessCategory.Edit, processCategory(item))
        assertTrue(groupProcessRunSlices(listOf(item), "Codex", null).single() is ProcessRunSlice.Single)
        rule.setContent { MaterialTheme {
            Box(Modifier.width(350.dp)) {
                ProcessGroup(listOf(item), cli = "Codex", initiallyExpanded = true, initiallyOpenItem = item.id)
            }
        } }
        rule.onNodeWithText(command).assertIsDisplayed()
        rule.onNodeWithText("updated").assertIsDisplayed()
        rule.onAllNodesWithText("Diff").assertCountEquals(0)
    }
}
