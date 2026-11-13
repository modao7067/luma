package io.github.modao7067.luma.gui.ui

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
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
class SessionExitInteractionTest {
    @get:Rule val rule = createComposeRule()
    private val request = UserRequest("same-request", "确认操作", "当前会话的请求", listOf(RequestChoice("allow", "允许")))
    private val drafts = mutableMapOf("A" to "A草稿", "B" to "B草稿")
    private fun view(id: String) = sampleView().copy(summary = sampleView().summary.copy(id = id, title = "会话$id"),
        draft = drafts.getValue(id), activeTurnId = null, queue = emptyList(), subAgents = emptyList(),
        messages = listOf(ConversationItem("user-$id", "user", "历史$id")), requests = listOf(request))
    private val selectedFlow = kotlinx.coroutines.flow.MutableStateFlow<SessionView?>(view("A"))
    private var selected: SessionView?
        get() = selectedFlow.value
        set(value) { selectedFlow.value = value }
    private val calls = mutableListOf<String>()
    private val actions = object : CockpitActions by previewActions {
        override fun draft(text: String) { selected?.let { drafts[it.summary.id] = text; calls += "draft:${it.summary.id}" } }
        override fun send() { calls += "send:${selected?.summary?.id}" }
        override fun answer(requestId: String, choice: String, answers: Map<String, List<String>>) { calls += "answer:${selected?.summary?.id}" }
    }
    private fun fixture() { rule.setContent {
        val projected by selectedFlow.collectAsState()
        MaterialTheme { CockpitScreen(CockpitState(selected = projected), actions, currentSessionId = { selected?.summary?.id }) }
    }; rule.waitForIdle() }
    @Test fun retiringEditorsAndCapturedActionsCannotWriteOrSendIntoTheNewSession() {
        fixture()
        val oldEdit = rule.onNodeWithContentDescription("消息草稿").fetchSemanticsNode().config[SemanticsActions.SetText].action!!
        val oldSend = rule.onNodeWithContentDescription("发送").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        rule.onNodeWithText("处理").performClick()
        val oldAnswer = rule.onNodeWithText("允许").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { selected = view("B") }
        rule.mainClock.advanceTimeByFrame(); rule.waitForIdle()
        rule.runOnIdle {
            oldEdit(AnnotatedString("A的迟到输入")); oldSend(); oldAnswer()
            assertEquals("B草稿", drafts["B"])
            assertTrue("outgoing callbacks must be revoked, not routed to B: $calls", calls.isEmpty())
        }
        rule.onNodeWithText("想做点什么？").assertDoesNotExist()
        rule.mainClock.autoAdvance = true
        rule.onNodeWithText("历史B").assertExists()
        println("PASS retained exit pixels do not retain edit/send/approval authority")
    }
    @Test fun quickReverseAndEmptySelectionDoNotResurrectTransientApproval() {
        fixture()
        rule.onNodeWithText("处理").performClick()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { selected = view("B") }
        rule.mainClock.advanceTimeBy(32); rule.waitForIdle()
        rule.onNodeWithText("允许").assertDoesNotExist()
        rule.runOnIdle { selected = view("A") }
        rule.mainClock.advanceTimeBy(32); rule.waitForIdle()
        rule.onNodeWithText("允许").assertDoesNotExist()
        rule.mainClock.autoAdvance = true
        rule.onNodeWithText("处理").performClick()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { selected = null }
        rule.mainClock.advanceTimeBy(32); rule.waitForIdle()
        rule.onNodeWithText("允许").assertDoesNotExist()
        rule.mainClock.autoAdvance = true
        println("PASS A→B→A during exit and A→empty both retire transient approval")
    }
    @Test fun quickReverseRetiresComposerPopupWithoutResettingTheDraft() {
        fixture()
        rule.onNodeWithContentDescription("添加附件").performClick()
        rule.onNodeWithText("照片").assertExists()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { selected = view("B") }
        rule.mainClock.advanceTimeBy(32); rule.waitForIdle()
        rule.onNodeWithText("照片").assertDoesNotExist()
        rule.runOnIdle { selected = view("A") }
        rule.mainClock.advanceTimeBy(32); rule.waitForIdle()
        rule.onNodeWithText("照片").assertDoesNotExist()
        rule.mainClock.autoAdvance = true
        rule.onNodeWithContentDescription("消息草稿").assertTextEquals("A草稿")
        println("PASS quick reverse retires popup, preserving per-session draft")
    }
    @Test fun sessionGuardExplicitlyCoversEveryActionIncludingInterfaceDefaults() {
        fun java.lang.reflect.Method.signature() = name + parameterTypes.joinToString { it.name }
        val guarded = CurrentSessionActions::class.java.declaredMethods.map { it.signature() }.toSet()
        val missing = CockpitActions::class.java.methods.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .filterNot { it.signature() in guarded }
        assertTrue("New actions must preserve the same ownership boundary: ${missing.map { it.name }}", missing.isEmpty())
    }
    @Test fun dismissedErrorRetainsButtonGeometryWhileDisablingItsExitHitTarget() {
        var shown by mutableStateOf(true)
        var dismisses = 0
        rule.setContent { MaterialTheme {
            Box(Modifier.width(320.dp).testTag("error-shell")) {
                AnimatedVisibility(shown, enter = expandVertically(spatialSizeSpring()), exit = shrinkVertically(spatialSizeSpring())) {
                    ErrorNotice("一条短错误", if (shown) ({ dismisses++; shown = false }) else null)
                }
            }
        } }
        val beforeWidth = rule.onNodeWithText("一条短错误").fetchSemanticsNode().boundsInRoot.width
        val beforeHeight = rule.onNodeWithTag("error-shell").fetchSemanticsNode().boundsInRoot.height
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("知道了").performClick()
        rule.runOnIdle { println("ERROR_CLICK shown=$shown dismisses=$dismisses") }
        repeat(5) { rule.mainClock.advanceTimeByFrame(); rule.waitForIdle() }
        rule.onNodeWithText("知道了").assertExists().assertIsNotEnabled().performTouchInput { click() }
        assertEquals(beforeWidth, rule.onNodeWithText("一条短错误").fetchSemanticsNode().boundsInRoot.width, 0.5f)
        val heights = mutableListOf(beforeHeight)
        repeat(35) { rule.mainClock.advanceTimeByFrame(); rule.waitForIdle(); heights += rule.onNodeWithTag("error-shell").fetchSemanticsNode().boundsInRoot.height }
        assertTrue("real exit geometry: $heights", heights.any { it > 0 && it < beforeHeight })
        assertEquals(0f, heights.last(), 0.5f)
        assertEquals(1, dismisses)
        rule.mainClock.autoAdvance = true
        println("PASS actual retained error exit heights=$heights; disabled dismiss preserves text width=$beforeWidth")
    }
}
