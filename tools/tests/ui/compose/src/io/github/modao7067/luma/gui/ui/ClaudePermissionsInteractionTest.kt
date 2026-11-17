package io.github.modao7067.luma.gui.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
class ClaudePermissionsInteractionTest {
    @get:Rule val rule = createComposeRule()
    @Test fun autoIsExplicitNativeIdAndUnavailableBypassIsNotFabricated() {
        var view by mutableStateOf(sampleView().let { it.copy(summary = it.summary.copy(cli = "Claude Code"),
            activeTurnId = null, queue = emptyList(), permission = "default", permissions = listOf(
                RequestChoice("default", "Manual"), RequestChoice("acceptEdits", "Accept edits"),
                RequestChoice("auto", "Auto"), RequestChoice("plan", "Plan"), RequestChoice("dontAsk", "Don't ask"))) })
        val calls = mutableListOf<String>()
        val actions = object : CockpitActions by previewActions {
            override fun configurePermission(id: String) { calls += id }
        }
        rule.setContent { MaterialTheme { CompositionLocalProvider(LocalInspectionMode provides true) {
            Box(Modifier.fillMaxSize()) { Box(Modifier.align(Alignment.BottomCenter)) {
                CompactComposer(CockpitState(selected = view), view, actions, {}, {}, previewExpanded = true)
            } }
        } } }
        rule.runOnIdle { assertTrue(calls.isEmpty()) }
        rule.onNodeWithContentDescription("权限：Manual").performClick()
        rule.onNodeWithText("Auto").assertIsDisplayed()
        rule.onNodeWithText("Don't ask").assertIsDisplayed()
        rule.onNodeWithText("Bypass permissions").assertDoesNotExist()
        val planTop = rule.onNodeWithText("Plan").fetchSemanticsNode().boundsInRoot.top
        val manualTop = rule.onNodeWithText("Manual").fetchSemanticsNode().boundsInRoot.top
        assertTrue(planTop < manualTop)
        rule.onNodeWithText("Auto").performClick()
        rule.runOnIdle { assertEquals(listOf("auto"), calls); assertEquals("default", view.permission) }
        rule.onNodeWithContentDescription("权限：Manual").performClick()
        rule.runOnIdle { view = view.copy(configuring = true) }
        rule.onNodeWithText("Auto").assertIsNotEnabled()
        rule.runOnIdle { view = view.copy(configuring = false, activeTurnId = "busy") }
        rule.onNodeWithText("Auto").assertIsNotEnabled()
        rule.runOnIdle { view = view.copy(activeTurnId = null, permission = "auto") }
        rule.onNodeWithContentDescription("权限：Auto").assertExists()
        rule.runOnIdle { view = view.copy(permission = "plan") }
        rule.onNodeWithContentDescription("权限：Plan").assertExists()
        rule.onNodeWithText("Plan").assertIsSelected().performClick()
        rule.runOnIdle { assertEquals(listOf("auto"), calls) }
        println("PASS Claude Auto is visible and explicit, exact native ID only, Plan sorted first with no-op reclick")
    }
}
