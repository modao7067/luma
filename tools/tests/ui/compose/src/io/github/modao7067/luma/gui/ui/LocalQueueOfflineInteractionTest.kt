package io.github.modao7067.luma.gui.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
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
class LocalQueueOfflineInteractionTest {
    @get:Rule val rule = createComposeRule()
    private var view by mutableStateOf(SessionView(SessionSummary("local", "Title", "root", "/work"), connected = false,
        queue = listOf(QueueEntry("submission-id", "App local", "本地待发", canEdit = true, canRemove = true, clientUserMessageId = "id", localSubmission = true),
            QueueEntry("native-id", "Native owner", "待发", canEdit = true, canRemove = true))))
    private val calls = mutableListOf<String>()
    private val target = object : CockpitActions by previewActions {
        override fun editQueued(id: String, text: String) { calls += "edit:$id:$text" }
        override fun restoreQueuedToComposer(id: String) { calls += "restore:$id" }
        override fun removeQueued(id: String) { calls += "remove:$id" }
    }
    private var current = true
    private val actions = CurrentSessionActions(target) { current }
    private fun fixture() {
        rule.setContent { MaterialTheme { Box(Modifier.width(360.dp).height(640.dp)) { QueuedMessages(view, actions) } } }
        rule.waitForIdle()
    }
    @Test fun onlyTheActualLocalOwnerAllowsOfflineEditAndCancellation() {
        fixture()
        rule.onNodeWithText("App local").performClick()
        rule.onNodeWithText("编辑").assertIsEnabled().performClick()
        rule.onNode(hasSetTextAction()).assertDoesNotExist()
        rule.runOnIdle { assertEquals(listOf("restore:submission-id"), calls) }
        rule.onNodeWithText("App local").performClick(); rule.onNodeWithText("删除").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.runOnIdle { assertEquals("remove:submission-id", calls.last()) }
        rule.onNodeWithText("Native owner").performClick()
        rule.onNodeWithText("编辑").assertIsNotEnabled(); rule.onNodeWithText("删除").assertIsNotEnabled()
    }
    @Test fun localReturnMenuLosesAuthorityAfterHandoffAndRetiringCallbacksCannotReachAnotherSession() {
        fixture()
        rule.onNodeWithText("App local").performClick()
        val oldClick = rule.onNodeWithText("编辑").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        rule.runOnIdle { current = false; oldClick() }
        rule.runOnIdle { assertTrue(calls.isEmpty()); current = true }
        rule.runOnIdle { view = view.copy(queue = view.queue.map { if (it.localSubmission) it.copy(status = "发送中", canEdit = false, canRemove = false) else it }) }
        rule.onNodeWithText("编辑").assertDoesNotExist()
        rule.runOnIdle { view = view.copy(queue = view.queue.filterNot { it.localSubmission }) }
        rule.waitForIdle(); rule.onNodeWithText("编辑").assertDoesNotExist()
        rule.runOnIdle { assertTrue(calls.isEmpty()) }
    }
    @Test fun everyDismissibleStateRemainsDeletableOfflineAndBusyWithoutNativeCapabilities() {
        view = view.copy(queueBusy = true, queueRecovery = QueueRecovery("核对中的消息", "unknown"), queue = listOf(QueueEntry("unknown", "待确认内容", "状态未知",
            canEdit = false, canSteer = false, canRemove = false, canDismiss = true)))
        fixture()
        for (status in listOf("状态未知", "发送中", "等待接收", "发送失败", "本地待发")) {
            rule.runOnIdle { view = view.copy(queue = view.queue.map { it.copy(status = status) }) }
            rule.onNodeWithText("待确认内容").performClick()
            rule.onNodeWithText("编辑").assertDoesNotExist()
            rule.onNodeWithText("插话").assertDoesNotExist()
            rule.onNodeWithText("删除").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { it() }
        }
        rule.runOnIdle { assertEquals(List(5) { "remove:unknown" }, calls) }
    }
    @Test fun durableHostQueueUsesTheSameMenuEditActionAndWrapperNotTheNativeTextEditor() {
        view = view.copy(queue = listOf(QueueEntry("host-id", "Host pending", "后台待发", hostOwned = true,
            clientUserMessageId = "host-id", canEdit = true, canSteer = false)))
        fixture()
        rule.onNodeWithText("Host pending").performClick()
        rule.onNodeWithText("编辑").assertIsEnabled().performClick()
        rule.onNode(hasSetTextAction()).assertDoesNotExist()
        rule.runOnIdle { assertEquals(listOf("restore:host-id"), calls) }
    }

}
