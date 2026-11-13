package io.github.modao7067.luma.gui.ui

import android.app.Application
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import io.github.modao7067.luma.gui.session.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class QueueAgentsInteractionTest {
    @get:Rule val rule = createComposeRule()
    private var view by mutableStateOf(queueAgentsFixture())
    private val calls = mutableListOf<String>()
    private val actions = object : CockpitActions by previewActions {
        override fun editQueued(id: String, text: String) { calls += "edit:$id:$text" }
        override fun steerQueued(id: String) { calls += "steer:$id" }
        override fun removeQueued(id: String) { calls += "remove:$id" }
    }
    private fun queueFixture() {
        rule.setContent { MaterialTheme { Box(Modifier.width(360.dp).height(640.dp)) {
            Box(Modifier.align(androidx.compose.ui.Alignment.BottomCenter)) { QueuedMessages(view, actions) }
        } } }
        rule.waitForIdle()
    }
    @Test fun menuEditCancelSaveAndSteerUseTheOriginalQueueIdentity() {
        queueFixture()
        rule.onNodeWithText("完成后补充窄屏和长消息验收").performClick()
        rule.onNodeWithText("编辑").performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement("仅检查窄屏")
        rule.onNodeWithText("取消", substring = false).performClick()
        rule.runOnIdle { assertTrue(calls.isEmpty()); assertEquals("完成后补充窄屏和长消息验收", view.queue.first().text) }
        rule.onNodeWithText("完成后补充窄屏和长消息验收").performClick()
        rule.onNodeWithText("编辑").performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement("只改这一条")
        rule.onNodeWithText("保存").performClick()
        rule.runOnIdle { assertEquals(listOf("edit:q1:只改这一条"), calls) }
        rule.onNodeWithText("完成后补充窄屏和长消息验收").performClick()
        rule.onNodeWithText("插话").performClick()
        rule.runOnIdle { assertEquals("steer:q1", calls.last()) }
        println("PASS actual queue popup → edit → cancel, reopen → save, popup → steer; stable q1, no cancellation send")
    }
    @Test fun queueExitKeepsPixelsButCannotDispatchActionsAndStaleEditorCloses() {
        queueFixture()
        rule.onNodeWithText("完成后补充窄屏和长消息验收").performClick()
        rule.onNodeWithText("编辑").performClick()
        rule.runOnIdle { view = view.copy(queue = emptyList()) }
        rule.waitForIdle()
        rule.onNodeWithText("保存").assertDoesNotExist()
        rule.runOnIdle { assertTrue(calls.isEmpty()); view = queueAgentsFixture() }
        rule.waitForIdle()
        rule.onNodeWithText("完成后补充窄屏和长消息验收").performClick()
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { view = view.copy(queue = emptyList()) }
        repeat(3) { rule.mainClock.advanceTimeByFrame(); rule.waitForIdle() }
        rule.onNodeWithText("插话").assertDoesNotExist()
        rule.runOnIdle { assertTrue(calls.isEmpty()) }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.onNodeWithText("待发消息 · 2").assertDoesNotExist()
        println("PASS disappearing queue disables exit actions immediately; stale editing dialog removed without save")
    }
    @Test fun agentDetailAndGroupReverseContinuouslyWithoutReopeningOnStream() {
        view = view.copy(subAgents = view.subAgents.take(2).map { it.copy(status = "succeeded", detail = "结果正文\n".repeat(2)) })
        rule.setContent { MaterialTheme { Column(Modifier.width(360.dp).height(700.dp)) {
            Box(Modifier.fillMaxWidth().testTag("agents")) { SubAgentGroup(view.subAgents, initiallyExpanded = true) }
        } } }
        rule.onNodeWithText("协议检查").performClick()
        rule.waitForIdle()
        fun height() = rule.onNodeWithTag("agents").fetchSemanticsNode().boundsInRoot.height
        val expanded = height()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("2 次 子代理").performClick()
        repeat(6) { rule.mainClock.advanceTimeByFrame(); rule.waitForIdle() }
        val closing = height()
        assertTrue("group must shrink: open=$expanded close96=$closing", closing < expanded)
        rule.onNodeWithText("2 次 子代理").performClick()
        val reverseStart = height()
        assertTrue("reverse must retain intermediate geometry: $reverseStart", reverseStart > 40f && reverseStart < expanded)
        val reverseFrames = mutableListOf(reverseStart)
        repeat(6) { rule.mainClock.advanceTimeByFrame(); rule.waitForIdle() }
        val reversing = height()
        reverseFrames += reversing
        repeat(12) { rule.mainClock.advanceTimeByFrame(); rule.waitForIdle(); reverseFrames += height() }
        assertTrue("reverse must recover toward its original height: $reverseFrames", reverseFrames.last() > reverseStart)
        println("Actual reverse frame heights: $reverseFrames")
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(expanded, height(), 1f)
        rule.onNodeWithText("2 次 子代理").performClick()
        rule.waitForIdle()
        val collapsed = height()
        rule.runOnIdle { view = view.copy(subAgents = view.subAgents.map { it.copy(detail = it.detail + "流式追加") }) }
        rule.waitForIdle()
        assertEquals(collapsed, height(), 1f)
        println("PASS actual group open=$expanded close96=$closing reverse0=$reverseStart reverse96=$reversing final=$expanded collapsed=$collapsed; stream does not reopen")
    }
    @Test fun mixedAgentAndQueueUpdatesKeepReaderAndJumpOnlyOnce() {
        lateinit var list: LazyListState
        lateinit var follow: ConversationTailFollow
        lateinit var scope: CoroutineScope
        rule.setContent { MaterialTheme { CompositionLocalProvider(LocalOverscrollFactory provides null) {
            list = rememberLazyListState(); follow = rememberConversationTailFollow(list); scope = rememberCoroutineScope()
            Column(Modifier.width(360.dp).height(640.dp)) {
                val history = (1..18).map { ConversationItem("h$it", "assistant", "历史消息 $it") }
                val rows = conversationRows(history + view.messages, view.subAgents)
                LazyColumn(Modifier.weight(1f).testTag("list").nestedScroll(follow.nestedScrollConnection), state = list,
                    contentPadding = PaddingValues(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(rows, key = { it.id }) { row ->
                        if (row.process) Column {
                            ProcessGroup(processGroupEntries(row))
                        } else Text(row.entries.single().text, Modifier.heightIn(min = 90.dp))
                    }
                }
                QueuedMessages(view, actions)
            }
        } } }
        rule.waitForIdle()
        rule.runOnIdle { assertFalse(list.canScrollForward) }
        rule.onNodeWithTag("list").performTouchInput { swipe(center - Offset(0f, 150f), center + Offset(0f, 150f), 600) }
        rule.waitForIdle()
        val anchor = rule.runOnIdle { assertFalse(follow.atTail); list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
        repeat(4) { i ->
            rule.runOnIdle { view = view.copy(subAgents = view.subAgents.mapIndexed { index, a -> a.copy(
                detail = "结果 $i\n".repeat(20), status = if (i == 3 && index == 0) "succeeded" else a.status) },
                queue = if (i == 2) view.queue.take(1) else view.queue) }
            rule.waitForIdle()
            rule.runOnIdle { assertEquals("mixed update $i, following=${follow.atTail}", anchor, list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset) }
        }
        rule.runOnIdle { scope.launch { follow.jumpToTail(list) } }
        rule.waitForIdle()
        rule.runOnIdle { assertTrue(follow.atTail); assertFalse(list.canScrollForward) }
        val afterJump = rule.runOnIdle { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
        rule.runOnIdle { view = view.copy(subAgents = view.subAgents + view.subAgents.first().copy(id = "new", name = "新增检查"), queue = emptyList()) }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(afterJump, list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset) }
        println("PASS actual upward swipe anchor=$anchor retained over four mixed stream/queue/completion changes; jump is one-shot and appended agent retains position")
    }
}
