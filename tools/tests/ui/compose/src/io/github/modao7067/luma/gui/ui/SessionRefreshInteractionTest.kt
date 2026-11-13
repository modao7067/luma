package io.github.modao7067.luma.gui.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.modao7067.luma.gui.session.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
class SessionRefreshInteractionTest {
    @get:Rule val rule = createComposeRule()
    private fun refresh(connected: Boolean, loading: Boolean) {
        var reconnects = 0; var dashboardReads = 0; var interruptions = 0
        val actions = object : CockpitActions by previewActions {
            override fun reconnect() { reconnects++ }
            override fun refreshDashboard() { dashboardReads++ }
            override fun stop() { interruptions++ }
        }
        val view = SessionView(SessionSummary("local", "Original session", "root", "/workspace"), connected = connected, loading = loading)
        rule.setContent { MaterialTheme { CompositionLocalProvider(LocalInspectionMode provides true) {
            ConversationHeader(view, actions, {}, {})
        } } }
        rule.onNodeWithContentDescription("会话 Dashboard").performClick()
        rule.mainClock.advanceTimeBy(400); rule.waitForIdle()
        rule.runOnIdle { dashboardReads = 0 }
        rule.onNodeWithText("刷新", substring = false).assertIsDisplayed().assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick) { click -> assertTrue(click()) }
        rule.runOnIdle { assertEquals(1, reconnects); assertEquals(0, dashboardReads); assertEquals(0, interruptions) }
    }
    @Test fun loadingConnectionStillAllowsExplicitRefresh() = refresh(connected = false, loading = true)
    @Test fun staleConnectedProjectionRefreshesTransportRatherThanOnlyReadingDeadEndpoint() = refresh(connected = true, loading = false)
}
