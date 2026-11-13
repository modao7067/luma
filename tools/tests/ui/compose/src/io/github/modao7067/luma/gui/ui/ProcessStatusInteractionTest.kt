package io.github.modao7067.luma.gui.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clipToBounds
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.modao7067.luma.gui.session.ConversationItem
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
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class ProcessStatusInteractionTest {
    private val scale = object : MotionDurationScale { override var scaleFactor by mutableFloatStateOf(1f) }
    @get:Rule val rule = createComposeRule(effectContext = scale)

    @Test fun nativeClockStopsWhenHiddenBackgroundedOrMotionDisabledWithoutRecomposition() {
        var enabled by mutableStateOf(true)
        lateinit var phase: State<Float>
        var compositions = 0
        rule.mainClock.autoAdvance = false
        rule.setContent { phase = rememberProcessDotsPhase(enabled); SideEffect { compositions++ } }
        rule.mainClock.advanceTimeBy(1700)
        rule.runOnIdle { assertTrue(phase.value > 0f); assertEquals(1, compositions) }
        rule.runOnIdle { enabled = false }
        rule.mainClock.advanceTimeBy(100)
        rule.runOnIdle { assertEquals(0f, phase.value) }
        rule.mainClock.advanceTimeBy(5000)
        rule.runOnIdle { assertEquals(0f, phase.value); enabled = true }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1700)
        rule.runOnIdle { assertTrue("restart phase=${phase.value}, compositions=$compositions", phase.value > 0f); scale.scaleFactor = 0f }
        rule.mainClock.advanceTimeBy(100)
        rule.runOnIdle { assertEquals(0f, phase.value) }
        rule.mainClock.advanceTimeBy(5000)
        rule.runOnIdle { assertEquals(0f, phase.value) }
    }

    @Test fun drawingPhasesPreserveLabelAndCommandBoundsAndNativeSettlementRemovesLiveSummary() {
        var phase by mutableFloatStateOf(0f)
        var outcome by mutableStateOf("running")
        rule.setContent { MaterialTheme { CompositionLocalProvider(LocalProcessDotsPhase provides phase) {
            Box(Modifier.width(360.dp)) {
                ProcessGroup(listOf(ConversationItem("shell", "tool", text = "printf ready", kind = "commandExecution", outcome = outcome)), initiallyExpanded = true)
            }
        } } }
        rule.onAllNodesWithText("进行中", useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
        val label = rule.onNodeWithText("shell · printf ready", useUnmergedTree = true)
        val command = rule.onNodeWithText("shell · printf ready", useUnmergedTree = true)
        val labelBounds = label.fetchSemanticsNode().boundsInRoot
        val commandBounds = command.fetchSemanticsNode().boundsInRoot
        for (sample in listOf(.25f, .5f, .75f, 1f)) {
            rule.runOnIdle { phase = sample }
            assertEquals(labelBounds, label.fetchSemanticsNode().boundsInRoot)
            assertEquals(commandBounds, command.fetchSemanticsNode().boundsInRoot)
        }
        rule.onNodeWithText("1 次 shell").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        command.assertExists()
        rule.runOnIdle { outcome = "succeeded" }
        rule.onAllNodesWithText("进行中", useUnmergedTree = true).assertCountEquals(0)
        command.assertDoesNotExist()
        rule.onNodeWithText("1 次 shell").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        command.assertExists()
    }

    @Test fun summaryOnlyBoldsCategoriesNeverCountsOrSeparators() {
        val parts = listOf(ProcessSummaryPart("3 次 ", "shell、read"), ProcessSummaryPart("5 次 ", "思考"))
        val text = processSummaryText(parts)
        assertEquals(listOf("shell、read", "思考"), text.spanStyles.map { text.text.substring(it.start, it.end) })
        assertEquals("3 次 shell、read · 5 次 思考", text.text)
        assertEquals(listOf(4 to 14, 21 to 23), text.spanStyles.map { it.start to it.end })
        for (phase in listOf(0f, .15f, .3f, .45f, .65f, 1f)) {
            for (dot in 0..2) assertTrue(processDotLift(phase, dot) in 0f..1f)
        }
        assertTrue(processDotLift(.15f, 0) > processDotLift(.15f, 1))
    }

    @Test fun actualViewportGateStartsAfterInitialClippingAndRestartsAfterBackground() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        owner.registry.currentState = Lifecycle.State.RESUMED
        var offscreen by mutableStateOf(true)
        lateinit var motion: ProcessDotsMotion
        rule.mainClock.autoAdvance = false
        rule.setContent { MaterialTheme { CompositionLocalProvider(LocalLifecycleOwner provides owner) {
            Box(Modifier.size(200.dp, 60.dp).clipToBounds()) {
                motion = rememberProcessDotsMotion()
                Box(Modifier.size(24.dp, 16.dp).offset(y = if (offscreen) 100.dp else 0.dp).then(motion.position))
            }
        } } }
        fun frames(count: Int) { repeat(count) { rule.mainClock.advanceTimeByFrame(); rule.waitForIdle() } }
        frames(3)
        frames(50)
        assertEquals("initially clipped label must not animate", 0f, motion.phase.value)
        rule.runOnIdle { offscreen = false }
        frames(3)
        frames(70)
        assertTrue("scrolling into view must start the real animation gate", motion.phase.value > 0f)
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        frames(3)
        frames(50)
        assertEquals("background must stop animation invalidations", 0f, motion.phase.value)
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        frames(3)
        frames(70)
        assertTrue("foreground must restart animation", motion.phase.value > 0f)
        rule.runOnIdle { offscreen = true }
        frames(3)
        frames(50)
        assertEquals("scrolling out must stop animation invalidations", 0f, motion.phase.value)
    }

    @Test fun editGroupAlignsWithSiblingToolsAndAnimatesFrameAndNeighborDockingWithoutJitter() {
        val entries = listOf(
            ConversationItem("g1", "tool", text = "carrierRevision", kind = "tool", toolName = "grep", outcome = "succeeded"),
            ConversationItem("r1", "tool", input = """{"path":"/w/luma_cli/base.py","offset":1,"limit":40}""", kind = "tool", toolName = "read", outcome = "succeeded"),
            ConversationItem("r2", "tool", input = """{"path":"/w/luma_cli/base.py","offset":41,"limit":40}""", kind = "tool", toolName = "read", outcome = "succeeded"),
            ConversationItem(
                "e1", "tool", kind = "tool", toolName = "edit", outcome = "succeeded",
                input = """{"path":"/w/cli_bridge/luma_cli/base.py","oldString":"a","newString":"b"}""",
                detail = "--- a/base.py\n+++ b/base.py\n@@ -60,2 +60,3 @@\n keep\n-a\n+b\n+c",
            ),
            ConversationItem(
                "e2", "tool", kind = "tool", toolName = "edit", outcome = "succeeded",
                input = """{"path":"/w/cli_bridge/luma_cli/base.py","oldString":"x","newString":"y"}""",
                detail = "--- a/base.py\n+++ b/base.py\n@@ -402,2 +403,2 @@\n-x\n+y\n tail",
            ),
            ConversationItem(
                "e3", "tool", kind = "tool", toolName = "edit", outcome = "succeeded",
                input = """{"path":"/w/io/github/modao7067/luma/gui/session/NativeCliSession.kt","oldString":"old","newString":"new"}""",
                detail = "--- a/NativeCliSession.kt\n+++ b/NativeCliSession.kt\n@@ -321,2 +321,2 @@\n-old\n+new\n done",
            ),
        )
        rule.mainClock.autoAdvance = false
        rule.setContent {
            SessionTypography {
                ConversationTypography {
                    Box(Modifier.width(340.dp)) {
                        ProcessGroup(entries, cli = "OpenCode", initiallyExpanded = true)
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()

        val grepTitle = rule.onNodeWithText("grep · carrierRevision", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val readTitle = rule.onNodeWithText("read · base.py ×2", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val editBaseNode = rule.onNodeWithText("edit · base.py", useUnmergedTree = true)
        val editKtNode = rule.onNodeWithText("edit · NativeCliSession.kt", useUnmergedTree = true)
        val editBaseCollapsed = editBaseNode.fetchSemanticsNode().boundsInRoot
        val editKtCollapsed = editKtNode.fetchSemanticsNode().boundsInRoot

        assertEquals(grepTitle.left, readTitle.left, 0.5f)
        assertEquals(grepTitle.left, editBaseCollapsed.left, 0.5f)
        assertEquals(grepTitle.left, editKtCollapsed.left, 0.5f)
        val statusNodes = rule.onAllNodes(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "已完成"), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(4, statusNodes.size)
        val firstStatusRight = statusNodes.first().boundsInRoot.right
        statusNodes.forEach { node ->
            assertEquals("right status column must align across grep, read batch, and edit rows", firstStatusRight, node.boundsInRoot.right, 0.5f)
        }
        rule.onNodeWithText("2 处", useUnmergedTree = true).assertExists()

        editBaseNode.performClick()
        var prevKtTop = editKtCollapsed.top
        repeat(18) {
            rule.mainClock.advanceTimeBy(16)
            rule.waitForIdle()
            val curBase = editBaseNode.fetchSemanticsNode().boundsInRoot
            val curKt = editKtNode.fetchSemanticsNode().boundsInRoot
            assertEquals("header X must not shift during frame appear", editBaseCollapsed.left, curBase.left, 0.5f)
            assertEquals("header Y must not shift during frame appear", editBaseCollapsed.top, curBase.top, 0.5f)
            assertTrue("neighbor below must move monotonically downward: $prevKtTop -> ${curKt.top}", curKt.top >= prevKtTop - 0.5f)
            prevKtTop = curKt.top
        }
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        assertTrue("diff body must expand", prevKtTop > editKtCollapsed.top + 40f)
        rule.onNodeWithText("⋯  L403–404 · 第 2/2 处", useUnmergedTree = true).assertExists()

        val seamKtTopBeforeDock = editKtNode.fetchSemanticsNode().boundsInRoot.top
        editKtNode.performClick()
        repeat(18) {
            rule.mainClock.advanceTimeBy(16)
            rule.waitForIdle()
            val curKt = editKtNode.fetchSemanticsNode().boundsInRoot
            assertEquals("docking neighbor X must not shift", editKtCollapsed.left, curKt.left, 0.5f)
            assertEquals("docking seam Y must stay locked with zero margin jump", seamKtTopBeforeDock, curKt.top, 0.5f)
        }
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()

        editBaseNode.performClick()
        var prevShrinkTop = editKtNode.fetchSemanticsNode().boundsInRoot.top
        repeat(20) {
            rule.mainClock.advanceTimeBy(16)
            rule.waitForIdle()
            val curKt = editKtNode.fetchSemanticsNode().boundsInRoot
            assertTrue("collapsing must settle monotonically upward: $prevShrinkTop -> ${curKt.top}", curKt.top <= prevShrinkTop + 0.5f)
            prevShrinkTop = curKt.top
        }
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        assertEquals(editKtCollapsed.top, editKtNode.fetchSemanticsNode().boundsInRoot.top, 0.5f)
    }

}
