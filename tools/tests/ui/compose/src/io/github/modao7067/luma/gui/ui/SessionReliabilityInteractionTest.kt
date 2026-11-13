package io.github.modao7067.luma.gui.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.modao7067.luma.gui.session.*
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
class SessionReliabilityInteractionTest {
    @get:Rule val rule = createComposeRule()
    private val approval = UserRequest("same-native-request-id", "允许这次读取？", "读取本轮选择的文件。",
        listOf(RequestChoice("allow", "允许"), RequestChoice("deny", "拒绝")))
    private val drafts = mutableMapOf<String, String>()
    private fun view(id: String) = sampleView().copy(summary = sampleView().summary.copy(id = id, title = "会话 $id"),
        queue = emptyList(), subAgents = emptyList(), draft = drafts[id] ?: "草稿 $id", messages = emptyList(), requests = listOf(approval))
    private var selected by mutableStateOf(view("A"))
    private val calls = mutableListOf<String>()
    private val actions = object : CockpitActions by previewActions {
        override fun draft(text: String) { drafts[selected.summary.id] = text; selected = selected.copy(draft = text) }
        override fun send() { calls += "send:${selected.summary.id}" }
        override fun answer(requestId: String, choice: String, answers: Map<String, List<String>>) { calls += "answer:${selected.summary.id}:$requestId:$choice" }
        override fun reconnect() { calls += "reconnect:${selected.summary.id}" }
        override fun discardUncertainSubmission() { calls += "discard:${selected.summary.id}" }
    }
    private fun fixture() {
        rule.setContent { MaterialTheme { CompositionLocalProvider(LocalInspectionMode provides true) {
            CockpitScreen(CockpitState(selected = selected), actions)
        } } }
        rule.waitForIdle()
    }
    @Test fun disconnectedApprovalCannotSilentlyNoOpAndReconnectIsSingleWhileLoading() {
        fixture()
        rule.onNodeWithText("处理").performClick()
        rule.onNodeWithText("允许").assertIsEnabled()
        rule.runOnIdle { selected = selected.copy(connected = false) }
        rule.onNodeWithText("允许").assertIsNotEnabled()
        rule.onNodeWithText("允许").performTouchInput { click() }
        rule.runOnIdle { assertTrue(calls.isEmpty()) }
        rule.onNodeWithText("重新连接").performClick()
        rule.runOnIdle { assertEquals(listOf("reconnect:A"), calls); selected = selected.copy(loading = true) }
        rule.onNodeWithText("重新连接").assertIsNotEnabled()
        rule.runOnIdle { selected = selected.copy(connected = true, loading = false) }
        rule.onNodeWithText("允许").performClick()
        rule.runOnIdle { assertEquals("answer:A:same-native-request-id:allow", calls.last()) }
        rule.runOnIdle { selected = selected.copy(requests = selected.requests.map { it.copy(answering = true) }) }
        rule.onNodeWithText("允许").assertIsNotEnabled()
        println("PASS approval disables on disconnect; recovery is available, blocked while loading, and same request submits after reconnect")
    }
    @Test
    @Config(qualifiers = "w320dp-h760dp")
    fun allNativeDecisionLabelsRemainReadableAndReachableOnNarrowScreen() {
        rule.setContent {
            val density = LocalDensity.current
            MaterialTheme { CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                RequestDialog(requestReliabilityFixture(), actions) {}
            } }
        }
        requestReliabilityFixture().choices.forEach { choice ->
            val node = rule.onNodeWithText(choice.label)
            node.performScrollTo().assertIsDisplayed()
            assertTrue("Native choice must retain readable width: ${choice.label}", node.fetchSemanticsNode().boundsInRoot.width >= 120f)
        }
        rule.onNodeWithText("拒绝并中断").performClick()
        rule.runOnIdle { assertEquals(listOf("answer:A:request-preview:3"), calls) }
        println("PASS all four native approval labels are reachable and last decision keeps native ID 3")
    }
    @Test fun nativeStartupApprovalRemainsAvailableBeforeSessionConnectionCompletes() {
        selected = view("A").copy(summary = view("A").summary.copy(cli = "pi"), connected = false, loading = true)
        fixture()
        rule.onNodeWithText("处理").performClick()
        rule.onNodeWithText("允许").assertIsEnabled()
        rule.onNodeWithText("允许").performClick()
        rule.runOnIdle { assertEquals(listOf("answer:A:same-native-request-id:allow"), calls) }
        println("PASS native bridge startup approval remains actionable while loading and not yet connected")
    }
    @Test fun requestDialogDoesNotResurrectAcrossSessionSwitchEvenWhenNativeIdsMatch() {
        fixture()
        rule.onNodeWithText("处理").performClick()
        rule.onNodeWithText("允许").assertExists()
        rule.runOnIdle { selected = view("B") }
        rule.waitForIdle()
        rule.onNodeWithText("允许").assertDoesNotExist()
        rule.runOnIdle { selected = view("A") }
        rule.waitForIdle()
        rule.onNodeWithText("允许").assertDoesNotExist()
        rule.runOnIdle { selected = view("B") }
        rule.waitForIdle()
        rule.onNodeWithText("处理").performClick()
        rule.onNodeWithText("拒绝").performClick()
        rule.runOnIdle { assertEquals(listOf("answer:B:same-native-request-id:deny"), calls) }
        println("PASS A→B→A does not restore a transient approval dialog; matching native request IDs cannot route A actions into B")
    }
    @Test fun resolvedUncertaintyClosesDestructiveConfirmationWithoutClearingNewDraft() {
        selected = view("A").copy(uncertainSubmission = true)
        fixture()
        rule.onNodeWithText("允许重发").performClick()
        rule.onNodeWithText("允许再次发送？").assertExists()
        rule.runOnIdle { selected = selected.copy(uncertainSubmission = false, draft = "新草稿") }
        rule.waitForIdle()
        rule.onNodeWithText("允许再次发送？").assertDoesNotExist()
        rule.runOnIdle { selected = selected.copy(uncertainSubmission = true) }
        rule.waitForIdle()
        rule.onNodeWithText("允许再次发送？").assertDoesNotExist()
        rule.runOnIdle { assertTrue(calls.isEmpty()); assertEquals("新草稿", selected.draft) }
        println("PASS resolved uncertainty retires old confirmation; a later uncertain send requires a fresh deliberate click")
    }
    @Test fun editorDraftStaysWithItsSessionAndSwitchingDoesNotSend() {
        fixture()
        rule.onNodeWithContentDescription("消息草稿").performTextReplacement("A 未发送的草稿")
        rule.runOnIdle { selected = view("B") }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("消息草稿").assertTextEquals("草稿 B")
        rule.onNodeWithContentDescription("消息草稿").performTextReplacement("B 的另一份草稿")
        rule.runOnIdle { selected = view("A") }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("消息草稿").assertTextEquals("A 未发送的草稿")
        rule.runOnIdle { assertTrue(calls.isEmpty()); assertEquals("B 的另一份草稿", drafts["B"]) }
        println("PASS real composer edits remain session-owned through A→B→A; switching never sends either draft")
    }
    @Test fun processExpansionRemainsSavedPerSessionWhileTransientDialogsDoNot() {
        fun process(id: String) = view(id).copy(messages = listOf(ConversationItem("same-tool-id", "tool",
            text = "检查 $id", kind = "commandExecution", input = "echo $id", detail = "result $id", outcome = "succeeded")))
        selected = process("A")
        fixture()
        rule.onNodeWithText("1 次终端").performClick()
        rule.onNodeWithText("终端", substring = false).performClick()
        rule.onNodeWithText("shell", substring = false).assertExists()
        rule.runOnIdle { selected = process("B") }
        rule.waitForIdle()
        rule.onNodeWithText("shell", substring = false).assertDoesNotExist()
        rule.runOnIdle { selected = process("A") }
        rule.waitForIdle()
        rule.onNodeWithText("shell", substring = false).assertExists()
        println("PASS process expansion remains per-session saved state; an equal tool ID in B does not inherit A expansion")
    }
}
