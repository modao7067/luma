package io.github.modao7067.luma.gui.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.modao7067.luma.gui.GuiProduct
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
class ProductEnvironmentInteractionTest {
    @get:Rule val rule = createComposeRule()
    private val calls = mutableListOf<String>()
    private var blank by mutableStateOf(BlankSessionView())
    private val actions = object : CockpitActions by previewActions {
        override val environment get() = GuiProduct.environment
        override fun createCliSession(cli: String, title: String, environment: String, cwd: String, model: String) {
            calls += "create:$cli:$environment:$cwd"
        }
        override fun openShell(environment: String, cwd: String) { calls += "shell:$environment:$cwd" }
        override fun draft(text: String) { blank = blank.copy(draft = text) }
        override fun send() { calls += "send:${blank.cli}:${GuiProduct.environment}" }
    }
    private fun content() {
        rule.setContent { MaterialTheme { CompositionLocalProvider(LocalInspectionMode provides true) {
            CockpitScreen(CockpitState(blank = blank), actions)
        } } }
    }
    @Test fun firstScreenCreatesInItsProductEnvironmentWithoutAnInstallerOrEnvironmentPicker() {
        content()
        rule.onNodeWithText("想做点什么？").assertIsDisplayed()
        rule.onNodeWithContentDescription("消息草稿").performTextInput("first task")
        rule.onNodeWithContentDescription("发送").performClick()
        rule.runOnIdle { assertEquals(listOf("send:Codex:${GuiProduct.environment}"), calls) }
        rule.onNodeWithText("Debian").assertDoesNotExist()
        rule.onNodeWithText("选择环境").assertDoesNotExist()
        println("PASS real first-screen create routes to ${GuiProduct.environment} without environment selection")
    }
    @Test fun ordinaryTerminalMenuUsesTheProductDefaultBeforeAnySessionExists() {
        content()
        rule.onNodeWithContentDescription("更多").performClick()
        rule.onNodeWithText("终端").performClick()
        rule.runOnIdle { assertEquals(listOf("shell:${GuiProduct.environment}:/workspace"), calls) }
        println("PASS ordinary terminal entry routes to ${GuiProduct.environment} independently of a selected chat")
    }
    @Test fun nativeTerminalCapabilityCanHandleStartupConsentBeforeTransportConnects() {
        var view by mutableStateOf(SessionView(SessionSummary("native", "原生会话", "root", "/workspace"),
            loading = true, connected = false, canOpenTui = false))
        val nativeActions = object : CockpitActions by actions {
            override fun openTui() { calls += "native-tui" }
        }
        rule.setContent { MaterialTheme { ConversationHeader(view, nativeActions, {}, {}, previewDashboard = true) } }
        rule.onNodeWithText("终端", substring = false).assertIsNotEnabled()
        rule.runOnIdle { view = view.copy(canOpenTui = true) }
        rule.onNodeWithText("终端", substring = false).assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(listOf("native-tui"), calls) }
    }

}
