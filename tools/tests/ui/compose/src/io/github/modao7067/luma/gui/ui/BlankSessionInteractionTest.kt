package io.github.modao7067.luma.gui.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
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
class BlankSessionInteractionTest {
    @get:Rule val rule = createComposeRule()
    private var state by mutableStateOf(CockpitState(blank = BlankSessionView("blank-1", "Claude Code")))
    private val calls = mutableListOf<String>()
    private val actions = object : CockpitActions by previewActions {
        override fun draft(text: String) { state = state.copy(blank = state.blank.copy(draft = text)); calls += "draft:$text" }
        override fun send() { calls += "send:${state.blank.cli}:${state.blank.draft}" }
        override fun createCliSession(cli: String, title: String, environment: String, cwd: String, model: String) { calls += "create:$cli" }
    }
    private fun fixture(drawer: Boolean = false) {
        rule.setContent { MaterialTheme { CockpitScreen(state, actions, previewDrawerOpen = drawer,
            currentSessionId = { state.selected?.summary?.id }, currentBlankKey = { state.blank.key }) } }
        rule.waitForIdle()
    }
    @Test fun blankComposerEditsWithoutCreatingAndTheMoreMenuUsesItsRememberedCli() {
        fixture()
        rule.onNodeWithContentDescription("会话 Dashboard").assertTextContains("Claude Code")
        rule.onNodeWithContentDescription("消息草稿").performTextInput("保留在 Claude 的首条消息")
        rule.runOnIdle { assertEquals(listOf("draft:保留在 Claude 的首条消息"), calls) }
        rule.onNodeWithContentDescription("更多").performClick()
        rule.onNodeWithText("新建会话", substring = false).performClick()
        rule.runOnIdle { assertEquals("create:Claude Code", calls.last()); assertEquals("保留在 Claude 的首条消息", state.blank.draft) }
    }
    @Test fun blankSendDefersCreationToTheControllerRatherThanCallingTheNewMenu() {
        fixture()
        rule.onNodeWithContentDescription("消息草稿").performTextInput("first task")
        rule.onNodeWithContentDescription("发送").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals("send:Claude Code:first task", calls.last()); assertFalse(calls.any { it.startsWith("create:") }) }
    }
    @Test fun sidebarPlusActionUsesItsOwnExplicitCliInsteadOfTheBlankCli() {
        fixture(drawer = true)
        rule.onNodeWithContentDescription("新建 pi 会话")
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.runOnIdle { assertEquals(listOf("create:pi"), calls) }
    }
    @Test fun retiringBlankEditorAndSendCannotMutateOrDispatchIntoANewContext() {
        state = state.copy(blank = state.blank.copy(draft = "旧空白输入")); fixture()
        val edit = rule.onNodeWithContentDescription("消息草稿").fetchSemanticsNode().config[SemanticsActions.SetText].action!!
        val send = rule.onNodeWithContentDescription("发送").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        rule.runOnIdle {
            state = state.copy(blank = state.blank.copy(key = "blank-2", cli = "pi", draft = "新空白输入"))
            edit(AnnotatedString("迟到旧输入")); send()
            assertEquals("新空白输入", state.blank.draft); assertTrue(calls.isEmpty())
        }
    }
}
