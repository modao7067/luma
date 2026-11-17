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
class DshPermissionsInteractionTest {
    @get:Rule val rule = createComposeRule()
    @Test fun nativeCatalogSelectionIsExplicitAndBusyOrUnavailableDoesNotDispatch() {
        var view by mutableStateOf(sampleView().let { it.copy(summary = it.summary.copy(cli = "DeepSeek Harness"),
            activeTurnId = null, queue = emptyList(), planModeSupported = true, planMode = false,
            permission = "workspace-write", permissions = listOf(
                RequestChoice("read-only", "read-only", sandbox = "read-only", approval = "ask"),
                RequestChoice("workspace-write", "workspace-write", sandbox = "workspace-write", approval = "ask"),
                RequestChoice("danger-full-access", "danger-full-access", sandbox = "danger-full-access", approval = "never"))) })
        val calls = mutableListOf<String>()
        val planCalls = mutableListOf<Boolean>()
        val actions = object : CockpitActions by previewActions {
            override fun configurePermission(permission: String) { calls += permission }
            override fun configurePlanMode(enabled: Boolean) { planCalls += enabled }
        }
        rule.setContent { MaterialTheme { CompositionLocalProvider(LocalInspectionMode provides true) {
            Box(Modifier.fillMaxSize()) { Box(Modifier.align(Alignment.BottomCenter)) {
                CompactComposer(CockpitState(selected = view), view, actions, {}, {}, previewExpanded = true)
            } }
        } } }
        rule.runOnIdle { assertTrue(calls.isEmpty()); assertTrue(planCalls.isEmpty()) }
        rule.onNodeWithContentDescription("权限：workspace-write").performClick()
        val planTop = rule.onNodeWithText("Plan").fetchSemanticsNode().boundsInRoot.top
        val readOnlyTop = rule.onNodeWithText("read-only").fetchSemanticsNode().boundsInRoot.top
        assertTrue(planTop < readOnlyTop)
        rule.onNodeWithText("Plan").performClick()
        rule.runOnIdle {
            assertEquals(listOf(true), planCalls)
            assertTrue(calls.isEmpty())
            view = view.copy(planMode = true)
        }
        rule.onNodeWithContentDescription("权限：Plan · workspace-write").performClick()
        rule.onNodeWithText("Plan").assertIsSelected()
        rule.onNodeWithText("danger-full-access").performClick()
        rule.runOnIdle { assertEquals(listOf("danger-full-access"), calls); assertEquals("workspace-write", view.permission) }
        rule.onNodeWithContentDescription("权限：Plan · workspace-write").performClick()
        rule.runOnIdle { view = view.copy(sending = true) }
        rule.onNodeWithText("danger-full-access").assertIsNotEnabled().performTouchInput { click() }
        rule.runOnIdle { assertEquals(1, calls.size); view = view.copy(sending = false, uncertainSubmission = true) }
        rule.onNodeWithText("danger-full-access").assertIsNotEnabled()
        rule.runOnIdle { view = view.copy(activeTurnId = "busy", uncertainSubmission = false) }
        rule.onNodeWithText("danger-full-access").assertIsNotEnabled()
        rule.onNodeWithText("danger-full-access").performTouchInput { click() }
        rule.runOnIdle { assertEquals(1, calls.size); view = view.copy(activeTurnId = null, permissions = emptyList(), permission = "", capabilityNotice = "原生权限服务不可用") }
        rule.onNodeWithText("原生权限服务不可用").assertIsDisplayed()
        rule.onNodeWithText("danger-full-access").assertDoesNotExist()
        println("PASS DSH dual-axis Plan toggle and native catalog are explicit; no optimistic permission success, busy mutation or fabricated fallback")
    }
}
