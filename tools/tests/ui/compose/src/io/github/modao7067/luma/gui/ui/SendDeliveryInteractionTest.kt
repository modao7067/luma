package io.github.modao7067.luma.gui.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
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
class SendDeliveryInteractionTest {
    @get:Rule val rule = createComposeRule()
    private var view by mutableStateOf(sendDeliveryFixture().copy(messages = emptyList(), draft = "你好", sending = false))
    private val calls = mutableListOf<String>()
    private val actions = object : CockpitActions by previewActions {
        override fun draft(text: String) { view = view.copy(draft = text) }
        override fun send() {
            calls += "send:${view.summary.id}"
            view = view.copy(messages = view.messages + ConversationItem("stable-local-id", "user", view.draft, delivery = "pending"), draft = "", sending = true, awaitingHandshake = view.activeTurnId == null)
        }
        override fun stop() { calls += "stop" }
        override fun reconnect() { calls += "reconnect" }
        override fun discardUncertainSubmission() { calls += "discard"; view = view.copy(uncertainSubmission = false) }
    }
    private fun fixture(handlers: CockpitActions = actions) {
        rule.setContent { MaterialTheme { key(view.summary.id) {
            SessionDetail(CockpitState(selected = view), view, handlers, onPickImages = {})
        } } }
        rule.waitForIdle()
    }
    private fun editor() = rule.onNodeWithContentDescription("消息草稿")
    private fun accept() { view = view.copy(sending = false, awaitingHandshake = false, uncertainSubmission = false,
        activeTurnId = "still-running", messages = view.messages.map { it.copy(delivery = "") }) }

    @Test fun clickImmediatelyShowsBubbleClearsEditorAndSpinnerNeverDispatchesASecondAction() {
        fixture()
        rule.onNodeWithContentDescription("发送").performClick()
        rule.onNodeWithText("你好").assertIsDisplayed()
        editor().assertTextEquals("")
        rule.onNodeWithText("发送中").assertIsDisplayed()
        rule.onNodeWithText("核对").assertDoesNotExist()
        rule.onNodeWithContentDescription("等待送达确认").assertIsNotEnabled().performTouchInput { click() }
        rule.runOnIdle { view = view.copy(activeTurnId = "already-started") }
        rule.onNodeWithContentDescription("停止任务").assertIsEnabled()
        rule.runOnIdle { assertEquals(1, calls.size); accept() }
        rule.onNodeWithContentDescription("停止任务").assertIsEnabled()
        rule.onNodeWithText("发送中").assertDoesNotExist()
        rule.onAllNodesWithText("你好").assertCountEquals(1)
        println("PASS click → optimistic user bubble + empty editor + disabled spinner; active turn exposes stop independently of send ACK")
    }
    @Test fun typingDuringPendingAndLateReceiptKeepTheNewDraftAndOneMessage() {
        fixture()
        rule.onNodeWithContentDescription("发送").performClick()
        editor().assertIsNotEnabled()
        rule.runOnIdle { view = view.copy(sending = false, awaitingHandshake = false, uncertainSubmission = true, submissionRecovery = "preserved",
            messages = view.messages.map { it.copy(delivery = "unknown") }) }
        editor().assertIsEnabled()
        editor().performTextInput("下一条不要被覆盖")
        editor().assertTextEquals("下一条不要被覆盖")
        rule.onNodeWithText("30 秒内未确认送达，已保留你新输入的内容。").assertDoesNotExist()
        rule.onNodeWithText("30 秒内未确认送达，草稿已恢复。").assertDoesNotExist()
        rule.onNodeWithText("发送中").assertIsDisplayed()
        rule.onNodeWithContentDescription("发送").assertIsEnabled()
        rule.runOnIdle { accept() }
        editor().assertTextEquals("下一条不要被覆盖")
        rule.onNodeWithText("核对").assertDoesNotExist()
        rule.onAllNodesWithText("你好").assertCountEquals(1)
        rule.runOnIdle { assertEquals(1, calls.count { it.startsWith("send:") }); assertFalse(calls.contains("stop")) }
        println("PASS composer locks during handshake, unlocks on unknown/accepted, and late receipt preserves new typing")
    }
    @Test fun switchingSessionsNeverDispatchesThePreviousSessionsDraft() {
        fixture()
        rule.onNodeWithContentDescription("发送").performClick()
        val a = rule.runOnIdle { view }
        rule.runOnIdle { view = a.copy(summary = a.summary.copy(id = "B"), messages = emptyList(), draft = "B草稿", sending = false, awaitingHandshake = false) }
        editor().assertTextEquals("B草稿")
        rule.onNodeWithContentDescription("等待送达确认").assertDoesNotExist()
        rule.runOnIdle { view = a.copy(sending = false, awaitingHandshake = false, uncertainSubmission = true, draft = "A后续草稿",
            messages = a.messages.map { it.copy(delivery = "unknown") }) }
        editor().assertTextEquals("A后续草稿")
        rule.onNodeWithText("允许重发").assertDoesNotExist()
        rule.onNodeWithContentDescription("发送").assertIsEnabled()
        editor().assertTextEquals("A后续草稿")
        rule.runOnIdle { assertEquals(1, calls.count { it.startsWith("send:") }); assertFalse(calls.contains("discard")) }
        println("PASS session-scoped pending UI; no unlock modal; current draft retained without sending")
    }
    @Test fun failedPayloadHasAnExplicitEditActionWithoutRetryingOrOverwritingNewInput() {
        view = view.copy(draft = "new draft", messages = listOf(ConversationItem("failed", "user", "old text", delivery = "rejected")))
        val handlers = object : CockpitActions by actions {
            override fun recoverSubmission(id: String) { calls += "recover:$id" }
        }
        fixture(handlers)
        rule.onNodeWithContentDescription("编辑未发送消息").performClick()
        editor().assertTextEquals("new draft")
        assertEquals(listOf("recover:failed"), calls)
    }
    @Test fun sendFromScrolledHistoryCollapsesComposerFirstThenAnchorsNewUserBubbleToTop() {
        view = view.copy(messages = (0 until 24).map {
            ConversationItem("history-$it", "assistant", "历史消息 $it\n保留正在阅读的位置。\n第三行正文。")
        })
        fixture()
        val list = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
        list.performTouchInput { swipeDown() }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("↓ 回到底部").assertIsDisplayed()
        rule.onNodeWithContentDescription("发送").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("你好").assertIsDisplayed()
        rule.runOnIdle { accept() }
        rule.onNodeWithText("你好").assertIsDisplayed()
        println("PASS sending after browsing history serializes composer collapse then anchors new user row to top")
    }
    @Test fun newBubbleUsesTheExpertsLiveHeightEnvelopeInsteadOfAnInspectionShortcut() {
        fixture()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithContentDescription("发送").performClick()
        val heights = mutableListOf<Float>()
        repeat(55) {
            rule.mainClock.advanceTimeByFrame(); rule.waitForIdle()
            heights += rule.onAllNodesWithTag("user-bubble:stable-local-id", useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.height ?: 0f
        }
        assertTrue("bubble stays hidden while composer collapses first: $heights", heights.first() == 0f)
        assertTrue("arrival must have intermediate visible geometry: $heights", heights.filter { it > 0f }.distinct().size > 3)
        assertTrue("arrival finishes above zero: $heights", heights.last() > 0f)
        rule.mainClock.autoAdvance = true
        println("PASS serialized collapse -> non-inspection production message arrival heights=$heights")
    }
    @Test fun actualLedgerCrossesThirtySecondsThenMergesALateNativeReceipt() {
        val record = SessionRecord("ledger", "Delivery", "noroot", "/workspace", draft = "你好")
        val outbox = linkedMapOf<String, ConversationItem>()
        var native = emptyList<ConversationItem>()
        var clock = 100L
        fun project() { view = view.copy(draft = record.draft, messages = SubmissionLedger.messages(record, native, outbox),
            sending = SubmissionLedger.pending(record), uncertainSubmission = SubmissionLedger.uncertain(record),
            submissionRecovery = record.submissionRecovery) }
        val handlers = object : CockpitActions by actions {
            override fun send() { calls += "ledger-send"; SubmissionLedger.begin(record, "receipt-id", clock); project() }
            override fun draft(text: String) { record.draft = text; record.draftRevision++; project() }
        }
        fixture(handlers)
        rule.onNodeWithContentDescription("发送").performClick()
        editor().assertTextEquals("")
        rule.runOnIdle { clock = 30_099; assertFalse(SubmissionLedger.expire(record, clock)); project() }
        rule.onNodeWithContentDescription("等待送达确认").assertExists()
        rule.onNodeWithText("核对").assertDoesNotExist()
        rule.runOnIdle { clock++; assertFalse(SubmissionLedger.expire(record, clock)); project() }
        editor().assertTextEquals("")
        rule.onNodeWithText("30 秒内未确认送达，草稿已恢复。").assertDoesNotExist()
        editor().performTextReplacement("超时之后的新输入")
        rule.runOnIdle {
            native = listOf(ConversationItem("receipt-id", "user", "你好"))
            SubmissionLedger.accepted(record); project()
        }
        editor().assertTextEquals("超时之后的新输入")
        rule.onAllNodesWithText("你好").assertCountEquals(1)
        rule.onNodeWithText("核对").assertDoesNotExist()
        assertEquals(listOf("ledger-send"), calls)
        println("PASS actual production ledger 29,999/30,000ms boundary → still sending → later edit → late native receipt merge")
    }
    @Test fun pendingDeliveryCoexistsWithQueueAndConcurrentAgentsWithoutBlockingNewTyping() {
        view = sendDeliveryFixture(mixed = true)
        fixture()
        rule.onNodeWithContentDescription("停止任务").assertIsEnabled()
        rule.onNodeWithText("待发消息 · 2").assertExists()
        rule.onNodeWithText("3 次 子代理", substring = true).assertExists()
        editor().performTextInput("下一个问题")
        editor().assertTextEquals("下一个问题")
        rule.onNodeWithText("核对").assertDoesNotExist()
        rule.runOnIdle { accept() }
        rule.onNodeWithContentDescription("发送").assertIsEnabled()
        rule.runOnIdle { assertTrue(calls.isEmpty()) }
        println("PASS pending + original queue + concurrent agents; typing works, no notification or implicit action")
    }
    @Test fun openCodeInterleavedReasoningRendersMergedReadBatchCleanCodeAndDiffEditGroup() {
        val items = listOf(
            ConversationItem("u1", "user", "检查并修改"),
            ConversationItem("th1", "tool", "思考", "先读第一个文件", kind = "reasoning", outcome = "succeeded"),
            ConversationItem(
                "r1", "tool", "read",
                detail = "Read file /workspace/project/First.kt, lines 220-221\n\n220: val alpha = 10\n221: val beta = 20",
                kind = "tool", toolName = "read",
                input = """{"path":"/workspace/project/First.kt","offset":220,"limit":2}""",
                outcome = "succeeded",
            ),
            ConversationItem("th2", "tool", "思考", "再读第二个文件", kind = "reasoning", outcome = "succeeded"),
            ConversationItem(
                "r2", "tool", "read",
                detail = "Read file /workspace/project/Second.kt, lines 10-11\n\n10: fun run() = Unit\n11: val ok = true",
                kind = "tool", toolName = "read",
                input = """{"path":"/workspace/project/Second.kt","offset":10,"limit":2}""",
                outcome = "succeeded",
            ),
            ConversationItem("th3", "tool", "思考", "执行第一处修改", kind = "reasoning", outcome = "succeeded"),
            ConversationItem(
                "e1", "tool", "edit",
                detail = "Index: project/First.kt\n===================================================================\n--- project/First.kt\toriginal\n+++ project/First.kt\tmodified\n@@ -220,2 +220,3 @@\n val alpha = 10\n-val beta = 20\n+val beta = 30\n+val gamma = 40",
                kind = "tool", toolName = "edit",
                input = """{"path":"/workspace/project/First.kt","oldString":"val beta = 20","newString":"val beta = 30\nval gamma = 40"}""",
                outcome = "succeeded",
            ),
            ConversationItem("th4", "tool", "思考", "执行第二处修改", kind = "reasoning", outcome = "running"),
            ConversationItem(
                "e2", "tool", "edit",
                detail = "",
                kind = "tool", toolName = "edit",
                input = """{"path":"/workspace/project/Second.kt","oldString":"val ok = true","newString":"val ok = false"}""",
                outcome = "running",
            ),
        )
        view = view.copy(
            summary = view.summary.copy(cli = "OpenCode"),
            activeTurnId = "turn-oc",
            draft = "",
            messages = items,
        )
        fixture()
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "进行中 · 已折叠执行过程")).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("read · First.kt, Second.kt", substring = true).assertIsDisplayed().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("L220-221").assertIsDisplayed()
        rule.onNodeWithText("val alpha = 10", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Read file /workspace/project/First.kt", substring = true).assertDoesNotExist()
        rule.onNodeWithText("220: val alpha", substring = true).assertDoesNotExist()
        rule.onNodeWithText("+2").assertIsDisplayed()
        rule.onNodeWithText("Parameters").assertDoesNotExist()
    }
}
