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
import io.github.modao7067.luma.gui.session.SessionSummary
import io.github.modao7067.luma.gui.session.SessionView
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
        val command = rule.onNodeWithText("shell · printf ready")
        val labelBounds = label.fetchSemanticsNode().boundsInRoot
        val commandBounds = command.fetchSemanticsNode().boundsInRoot
        for (sample in listOf(.25f, .5f, .75f, 1f)) {
            rule.runOnIdle { phase = sample }
            assertEquals(labelBounds, label.fetchSemanticsNode().boundsInRoot)
            assertEquals(commandBounds, command.fetchSemanticsNode().boundsInRoot)
        }
        rule.onNodeWithText("正在执行命令…").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        command.assertExists()
        rule.runOnIdle { outcome = "succeeded" }
        rule.onAllNodesWithText("正在执行命令…", useUnmergedTree = true).assertCountEquals(0)
        command.assertDoesNotExist()
        rule.onNodeWithText("1 步 · ", substring = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        command.assertExists()
    }

    @Test fun summaryStaysUnweightedAndOnlyTintsLiveVerbsAndEdits() {
        val settled = processSummaryText(listOf(ProcessSummaryPart("12 步"), ProcessSummaryPart("修改 3 处", emphasized = true), ProcessSummaryPart("2m3s")), androidx.compose.ui.graphics.Color.Red)
        assertEquals("12 步 · 修改 3 处 · 2m3s", settled.text)
        assertEquals(listOf(7 to 12), settled.spanStyles.map { it.start to it.end })
        val live = processSummaryText(listOf(ProcessSummaryPart("正在执行命令…", emphasized = true)), androidx.compose.ui.graphics.Color.Red)
        assertEquals(listOf(0 to 7), live.spanStyles.map { it.start to it.end })
        assertTrue(live.spanStyles.all { it.item.fontWeight == null && it.item.color == androidx.compose.ui.graphics.Color.Red })
        for (phase in listOf(0f, .15f, .3f, .45f, .65f, 1f)) {
            for (dot in 0..2) assertTrue(processDotLift(phase, dot) in 0f..1f)
        }
        assertTrue(processDotLift(.15f, 0) > processDotLift(.15f, 1))
    }

    @Test fun completedDotIsNeutralAndDistinctFromRunning() {
        val light = processRowPalette(androidx.compose.material3.lightColorScheme())
        val dark = processRowPalette(androidx.compose.material3.darkColorScheme())
        assertEquals(androidx.compose.ui.graphics.Color(0xFFB4B2AB), light.completedDot)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF5E5D58), dark.completedDot)
        assertNotEquals(light.activeDot, light.completedDot)
        assertNotEquals(dark.activeDot, dark.completedDot)
    }

    @Test fun shimmerClockStopsWhenDisabledOrMotionOffWithoutRecomposition() {
        var enabled by mutableStateOf(true)
        lateinit var phase: State<Float>
        var compositions = 0
        rule.mainClock.autoAdvance = false
        rule.setContent { phase = rememberProcessShimmerPhase(enabled); SideEffect { compositions++ } }
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

        val groupRow = rule.onNodeWithText("6 步 · 修改 3 处").fetchSemanticsNode().boundsInRoot
        val groupSummary = rule.onNodeWithText("6 步 · 修改 3 处", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(0f, groupRow.left, 0.5f)
        assertTrue(groupSummary.left > groupRow.left + 14f)
        assertEquals(grepTitle.left, readTitle.left, 0.5f)
        assertEquals(grepTitle.left, editBaseCollapsed.left, 0.5f)
        assertEquals(grepTitle.left, editKtCollapsed.left, 0.5f)
        assertTrue(grepTitle.left > groupSummary.left + 8f)
        rule.onAllNodes(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "已完成"), useUnmergedTree = true).assertCountEquals(0)
        rule.onNodeWithText("2 处", useUnmergedTree = true).assertExists()

        editBaseNode.performClick()
        var prevKtTop = editKtCollapsed.top
        val sampledTops = mutableListOf<Float>()
        repeat(18) {
            rule.mainClock.advanceTimeBy(16)
            rule.waitForIdle()
            val curBase = editBaseNode.fetchSemanticsNode().boundsInRoot
            val curKt = editKtNode.fetchSemanticsNode().boundsInRoot
            assertEquals("header X must not shift during frame appear", editBaseCollapsed.left, curBase.left, 0.5f)
            assertEquals("header Y must not shift during frame appear", editBaseCollapsed.top, curBase.top, 0.5f)
            assertTrue("neighbor below must move monotonically downward: $prevKtTop -> ${curKt.top}", curKt.top >= prevKtTop - 0.5f)
            sampledTops += curKt.top
            prevKtTop = curKt.top
        }
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        val editKtExpandedTop = editKtNode.fetchSemanticsNode().boundsInRoot.top
        assertTrue("diff body must expand", editKtExpandedTop > editKtCollapsed.top + 40f)
        assertTrue(
            "edit card must animate smoothly across frames instead of snapping on frame 1: $sampledTops -> $editKtExpandedTop",
            sampledTops.any { it > editKtCollapsed.top + 4f && it < editKtExpandedTop - 4f },
        )
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

    @Test fun activeTurnShowsWorkingBeforeFirstStepAndDuringInterStepGapUntilSealedOrEof() {
        var view by mutableStateOf(
            SessionView(
                summary = SessionSummary("open-turn-session", "测试会话", "noroot", "/workspace", cli = "Codex"),
                connected = true,
                loading = false,
                activeTurnId = "turn-1",
                messages = listOf(ConversationItem("u1", "user", "开始干活", delivery = "pending")),
            ),
        )
        rule.setContent {
            MaterialTheme {
                SessionDetail(io.github.modao7067.luma.gui.session.CockpitState(selected = view), view, previewActions, onPickImages = {})
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("发送中").assertIsDisplayed()
        rule.onNodeWithText("工作中…").assertDoesNotExist()

        rule.runOnIdle {
            view = view.copy(
                messages = listOf(ConversationItem("u1", "user", "开始干活", delivery = "")),
            )
        }
        rule.waitForIdle()

        rule.onNodeWithText("发送中").assertDoesNotExist()
        rule.onNodeWithText("工作中…").assertIsDisplayed()
        rule.onNode(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "进行中 · 已折叠执行过程")).assertExists()

        rule.runOnIdle {
            view = view.copy(
                messages = view.messages + ConversationItem("t1", "tool", text = "ls", kind = "commandExecution", input = "ls", outcome = "running"),
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("正在执行命令…").assertIsDisplayed()
        rule.onNodeWithText("工作中…").assertDoesNotExist()

        rule.runOnIdle {
            view = view.copy(
                messages = listOf(
                    ConversationItem("u1", "user", "开始干活"),
                    ConversationItem("t1", "tool", text = "ls", kind = "commandExecution", input = "ls", outcome = "succeeded"),
                ),
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("工作中…").assertIsDisplayed()
        rule.onNodeWithText("1 步", substring = true).assertDoesNotExist()

        rule.runOnIdle {
            view = view.copy(
                messages = view.messages + ConversationItem("a1", "assistant", "正在输出正文"),
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("1 步 · ", substring = true).assertIsDisplayed()
        rule.onNodeWithText("工作中…").assertDoesNotExist()

        rule.runOnIdle {
            view = view.copy(
                messages = view.messages + ConversationItem("t2", "tool", text = "cat b", kind = "commandExecution", input = "cat b", outcome = "succeeded"),
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("工作中…").assertIsDisplayed()

        rule.runOnIdle {
            view = view.copy(activeTurnId = null)
        }
        rule.waitForIdle()
        rule.onNodeWithText("工作中…").assertDoesNotExist()
        rule.onAllNodesWithText("1 步 · ", substring = true).assertCountEquals(2)
    }

    @Test fun manualExpandedRunningToolStaysOpenAfterCompletionUntilUserCollapses() {
        var entries by mutableStateOf(
            listOf(
                ConversationItem(
                    "cmd-1",
                    "tool",
                    text = "ls -la",
                    kind = "commandExecution",
                    input = "ls -la",
                    detail = "total 8\nREADME.md",
                    outcome = "running",
                ),
            ),
        )
        rule.mainClock.autoAdvance = false
        rule.setContent {
            MaterialTheme {
                Box(Modifier.width(360.dp)) {
                    ProcessGroup(entries, cli = "Codex", initiallyExpanded = false)
                }
            }
        }
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()

        rule.onNodeWithText("shell · ls -la").performClick()
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        rule.onNode(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "进行中 · 收起shell")).assertExists()

        rule.runOnIdle {
            entries = listOf(entries.first().copy(outcome = "succeeded"))
        }
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
        rule.onNode(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "收起shell")).assertExists()

        rule.onNodeWithText("shell · ls -la").performClick()
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
        rule.onNodeWithText("shell · ls -la").assertDoesNotExist()
    }

    @Test fun manualExpandedReadStaysOpenAcrossSequentialMultiFileReads() {
        val r1 = ConversationItem(
            "r1",
            "tool",
            text = "Alpha.kt",
            kind = "tool",
            toolName = "read",
            input = """{"path":"/w/Alpha.kt"}""",
            detail = "1: class Alpha",
            outcome = "running",
        )
        val r2 = ConversationItem(
            "r2",
            "tool",
            text = "Beta.kt",
            kind = "tool",
            toolName = "read",
            input = """{"path":"/w/Beta.kt"}""",
            detail = "1: class Beta",
            outcome = "running",
        )
        val r3 = ConversationItem(
            "r3",
            "tool",
            text = "Gamma.kt",
            kind = "tool",
            toolName = "read",
            input = """{"path":"/w/Gamma.kt"}""",
            detail = "1: class Gamma",
            outcome = "running",
        )
        var entries by mutableStateOf(listOf(r1))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            MaterialTheme {
                Box(Modifier.width(360.dp)) {
                    ProcessGroup(entries, cli = "OpenCode", initiallyExpanded = false)
                }
            }
        }
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()

        rule.onNodeWithText("read · Alpha.kt").performClick()
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        rule.onNode(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "进行中 · 收起read")).assertExists()

        rule.runOnIdle {
            entries = listOf(r1.copy(outcome = "succeeded"), r2)
        }
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
        rule.onNode(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "进行中 · 收起批量读取")).assertExists()

        rule.onNodeWithText("Beta.kt", useUnmergedTree = true).performClick()
        rule.mainClock.advanceTimeBy(200)
        rule.waitForIdle()

        rule.runOnIdle {
            entries = listOf(r1.copy(outcome = "succeeded"), r2.copy(outcome = "succeeded"), r3)
        }
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
        rule.onNode(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "进行中 · 收起批量读取")).assertExists()

        rule.runOnIdle {
            entries = listOf(r1.copy(outcome = "succeeded"), r2.copy(outcome = "succeeded"), r3.copy(outcome = "succeeded"))
        }
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
        rule.onNode(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "收起批量读取")).assertExists()
        rule.onNodeWithText("Beta.kt").assertIsSelected()
    }

}
