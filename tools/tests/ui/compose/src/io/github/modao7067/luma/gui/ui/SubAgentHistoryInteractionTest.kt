package io.github.modao7067.luma.gui.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.modao7067.luma.gui.session.SubAgentState
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
@OptIn(ExperimentalTestApi::class)
class SubAgentHistoryInteractionTest {
    private val scale = object : MotionDurationScale { override var scaleFactor by mutableFloatStateOf(1f) }
    @get:Rule val rule = createComposeRule(effectContext = scale)
    @Test fun terminalStatesOnlyEnterHistoryAndRunningSortsFirst() {
        assertEquals(listOf("running-ui", "running-protocol", "waiting", "unknown"), orderedCurrentAgents(historyFixtureAgents).map { it.id })
        listOf("succeeded", "failed", "cancelled", "closed").forEach { assertTrue(subAgentIsHistorical(it)) }
        listOf("running", "pending", "unknown", "custom").forEach { assertFalse(subAgentIsHistorical(it)) }
    }
    @Test fun historyIsCollapsedThenStaysExpandedAcrossNativeUpdates() {
        var agents by mutableStateOf(historyFixtureAgents)
        var expanded by mutableStateOf(false)
        val selected = mutableListOf<String>()
        rule.setContent { MaterialTheme {
            Column(Modifier.width(300.dp)) {
                SubAgentPopupItems(agents, null, expanded, { expanded = !expanded }, true) { selected += it.id }
            }
        } }
        rule.onNodeWithText("整理材料").assertDoesNotExist()
        rule.onNodeWithText("外部代理").assertExists()
        rule.onNodeWithText("等待资源").assertExists()
        rule.onNodeWithText("历史").performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.onNodeWithText("整理材料").assertExists()
        rule.onNodeWithText("验证脚本").assertExists()
        rule.runOnIdle { agents = agents.map { if (it.id == "running-ui") it.copy(status = "succeeded") else it } }
        rule.onNodeWithText("整理材料").assertExists()
        rule.onNodeWithText("界面实现").performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.runOnIdle { assertTrue(expanded); assertEquals(listOf("running-ui"), selected) }
    }
    @Test fun countShrinksToSameLeftAnchoredCircleAndReversesWithoutMovingJump() {
        var agents by mutableStateOf(historyFixtureAgents)
        rule.setContent { MaterialTheme {
            Box(Modifier.fillMaxSize()) { ConversationScrollOverlay(true, PaddingValues(bottom = 12.dp), agents, onJump = {}) }
        } }
        val before = rule.onNodeWithTag("subagent-capsule").fetchSemanticsNode().boundsInRoot
        val jump = rule.onNodeWithContentDescription("↓ 回到底部").fetchSemanticsNode().boundsInRoot
        assertTrue(before.width > before.height)
        rule.onNodeWithText("2", substring = false).assertIsDisplayed()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { agents = agents.map { if (it.status == "running") it.copy(status = "succeeded") else it } }
        rule.mainClock.advanceTimeByFrame(); rule.waitForIdle()
        rule.mainClock.advanceTimeBy(96); rule.waitForIdle()
        val middle = rule.onNodeWithTag("subagent-capsule").fetchSemanticsNode().boundsInRoot
        assertEquals(before.left, middle.left, .5f)
        assertTrue(middle.width <= before.width)
        rule.runOnIdle { agents = historyFixtureAgents }
        rule.mainClock.advanceTimeByFrame(); rule.waitForIdle()
        rule.mainClock.advanceTimeBy(64); rule.waitForIdle()
        val reversed = rule.onNodeWithTag("subagent-capsule").fetchSemanticsNode().boundsInRoot
        assertEquals(before.left, reversed.left, .5f)
        rule.runOnIdle { agents = agents.map { if (it.status == "running") it.copy(status = "closed") else it } }
        rule.mainClock.advanceTimeByFrame(); rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1200); rule.waitForIdle()
        val circle = rule.onNodeWithTag("subagent-capsule").fetchSemanticsNode().boundsInRoot
        assertEquals(circle.height, circle.width, .5f)
        assertEquals(before.left, circle.left, .5f)
        assertEquals(jump, rule.onNodeWithContentDescription("↓ 回到底部").fetchSemanticsNode().boundsInRoot)
        rule.onNodeWithText("2", substring = false).assertDoesNotExist()
        rule.onNodeWithTag("subagent-capsule").performClick()
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithText("历史").assertExists()
        rule.runOnIdle { scale.scaleFactor = 0f; agents = historyFixtureAgents }
        rule.mainClock.advanceTimeByFrame(); rule.waitForIdle(); rule.mainClock.advanceTimeByFrame(); rule.waitForIdle()
        val instant = rule.onNodeWithTag("subagent-capsule").fetchSemanticsNode().boundsInRoot
        assertEquals(before.width, instant.width, .5f)
    }
}
