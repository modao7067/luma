// Adapted from chen08209/prism_ui under GPL-3.0. See apps/gui/third-party/prism-ui/.
package io.github.modao7067.luma.gui.ui

import android.app.Application
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DismissibleDrawerSheet
import androidx.compose.material3.DismissibleNavigationDrawer
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PrismMotionInteractionTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun springAndPulseMathMatchesUpstreamInvariants() {
        val open = SpringCurve(400f, 0.754f)
        assertEquals(0f, open.x(0f), 1e-4f)
        assertTrue((1..40).any { open.x(it * 0.01f) > 1f })
        assertEquals(1f, open.x(0.45f), 0.02f)

        assertEquals(0f, rubberBand(0f, 10f), 1e-5f)
        val band = rubberBand(1000f, 10f)
        assertTrue(band > 0f && band < 10f)

        assertEquals(1f, riseAndFall(0.098f, 0.098f, 3.3f), 1e-4f)

        val (across, down) = glyphPulseAt(0.2f)
        assertTrue(across > 1f)
        assertTrue(down > 1f)
        assertNotEquals(across, down, 1e-4f)
    }

    @Test
    fun elasticPressShortTapLatchesUntilFullSwellBeforeSettling() {
        lateinit var elastic: ElasticPressState
        rule.setContent {
            MaterialTheme {
                elastic = rememberElasticPress()
                Box(Modifier.size(44.dp).elasticPress(state = elastic).testTag("pill"))
            }
        }
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("pill").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(16)
        rule.waitForIdle()
        assertTrue("expected swell < 0.975 after 16ms but was ${elastic.swell}", elastic.swell < 0.975f)
        rule.onNodeWithTag("pill").performTouchInput { up() }
        val samples = mutableListOf(elastic.swell)
        repeat(90) {
            rule.mainClock.advanceTimeBy(16)
            rule.waitForIdle()
            samples += elastic.swell
        }
        rule.mainClock.autoAdvance = true
        val peak = samples.maxOrNull() ?: 0f
        assertTrue("expected peak swell >= 0.975 but was $peak", peak >= 0.975f)
        assertTrue("expected final swell < 0.01 but was ${samples.last()}", samples.last() < 0.01f)
    }

    @Test
    fun originMenuReversesSmoothlyFromMidOpenAndMidShrink() {
        var expanded by mutableStateOf(false)
        rule.setContent {
            MaterialTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    Box(Modifier.size(44.dp)) {
                        OriginMenu(expanded, { expanded = false }) {
                            ActionMenuItem("Item", MenuSymbol.EDIT, onClick = {})
                        }
                    }
                }
            }
        }
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { expanded = true }
        repeat(6) {
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
        }
        rule.onNodeWithTag("action-menu-carrier").assertExists()

        rule.runOnIdle { expanded = false }
        repeat(6) {
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
        }
        rule.onNodeWithTag("action-menu-carrier").assertExists()

        rule.runOnIdle { expanded = true }
        repeat(35) {
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
        }
        rule.onNodeWithTag("action-menu-carrier").assertExists()
        rule.onNodeWithText("Item").assertIsDisplayed().assertIsEnabled()

        rule.runOnIdle { expanded = false }
        repeat(25) {
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
        }
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("action-menu-carrier").assertDoesNotExist()
    }

    @Test
    fun morphMenuOpensOnPointerDownAndSelectsViaCarriedSlide() {
        var expanded by mutableStateOf(false)
        val picked = mutableListOf<String>()
        rule.setContent {
            MaterialTheme {
                LiquidScaffold(
                    topBar = {
                        val anchor = rememberMorphAnchor()
                        Box(Modifier.fillMaxWidth()) {
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .size(44.dp)
                                    .morphAnchor(anchor, enabled = true, onOpen = { expanded = true })
                                    .testTag("anchor"),
                            )
                            MorphMenu(expanded, { expanded = false }, anchor, width = 200.dp, placement = MorphPlacement.End) {
                                ActionMenuItem("RowA", MenuSymbol.EDIT, onClick = { expanded = false; picked += "A" })
                                ActionMenuItem("RowB", MenuSymbol.SETTINGS, onClick = { expanded = false; picked += "B" })
                            }
                        }
                    },
                    bottomBar = {},
                    wrapTop = false,
                    wrapBottom = false,
                ) {
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        rule.onNodeWithTag("anchor").performTouchInput { down(center) }
        rule.waitForIdle()
        rule.onNodeWithText("RowB").assertIsDisplayed()
        val anchorRect = rule.onNodeWithTag("anchor").fetchSemanticsNode().boundsInRoot
        val rowRect = rule.onNodeWithText("RowB").fetchSemanticsNode().boundsInRoot
        val targetInAnchor = rowRect.center - anchorRect.topLeft
        rule.onNodeWithTag("anchor").performTouchInput {
            moveTo(targetInAnchor)
            up()
        }
        rule.runOnIdle {
            assertEquals(listOf("B"), picked)
        }
    }

    @Test
    fun conversationButtonsBlockDrawerSwipeExceptTopLeftDrawerButton() {
        var drawerOpen = false
        var sendCount = 0
        var capsuleCount = 0
        var drawerCount = 0
        rule.setContent {
            MaterialTheme {
                val drawer = rememberDrawerState(DrawerValue.Closed)
                drawerOpen = drawer.isOpen
                CompositionLocalProvider(LocalDrawerOpen provides drawer.isOpen) {
                    DismissibleNavigationDrawer(
                        drawerState = drawer,
                        gesturesEnabled = true,
                        drawerContent = {
                            DismissibleDrawerSheet {
                                Text("Drawer")
                            }
                        },
                    ) {
                        Column(Modifier.fillMaxSize()) {
                            LiquidGlass(
                                Modifier
                                    .size(44.dp)
                                    .liquidClickable(blockDrawerSwipe = false) { drawerCount++ }
                                    .testTag("drawer-btn"),
                            ) {}
                            LiquidGlass(
                                Modifier
                                    .size(44.dp)
                                    .liquidClickable { capsuleCount++ }
                                    .testTag("capsule-btn"),
                            ) {}
                            FilledIconButton(
                                onClick = { sendCount++ },
                                modifier = Modifier
                                    .size(34.dp)
                                    .elasticPress()
                                    .blockHorizontalPageSwipe()
                                    .testTag("send-btn"),
                            ) {}
                        }
                    }
                }
            }
        }
        fun swipeRightFromCenter(tag: String) {
            rule.onNodeWithTag(tag).performTouchInput {
                down(center)
                var dx = 0f
                repeat(12) {
                    dx += 50f
                    advanceEventTime(16)
                    moveTo(center + Offset(dx, 0f))
                }
                up()
            }
            rule.waitForIdle()
        }
        swipeRightFromCenter("send-btn")
        assertTrue(!drawerOpen)
        assertEquals(0, sendCount)
        swipeRightFromCenter("capsule-btn")
        assertTrue(!drawerOpen)
        assertEquals(0, capsuleCount)
        swipeRightFromCenter("drawer-btn")
        assertTrue(drawerOpen)
        assertEquals(0, drawerCount)
    }

    @Test
    fun lumaHapticsMapsTicksToClockTickAndTapsToVirtualKey() {
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, LumaHapticFeedback.resolveConstant(HapticFeedbackType.TextHandleMove))
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, LumaHapticFeedback.resolveConstant(HapticFeedbackType.SegmentTick))
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, LumaHapticFeedback.resolveConstant(HapticFeedbackType.SegmentFrequentTick))
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, LumaHapticFeedback.resolveConstant(HapticFeedbackType.VirtualKey))
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, LumaHapticFeedback.resolveConstant(HapticFeedbackType.ToggleOn))
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, LumaHapticFeedback.resolveConstant(HapticFeedbackType.ToggleOff))
        assertEquals(HapticFeedbackConstants.LONG_PRESS, LumaHapticFeedback.resolveConstant(HapticFeedbackType.LongPress))

        val calls = mutableListOf<Int>()
        val view = object : View(RuntimeEnvironment.getApplication()) {
            override fun performHapticFeedback(feedbackConstant: Int): Boolean {
                calls += feedbackConstant
                return true
            }
        }
        val haptics = LumaHapticFeedback(view)
        haptics.tick()
        haptics.tap()
        haptics.longPress()
        assertEquals(
            listOf(
                HapticFeedbackConstants.CLOCK_TICK,
                HapticFeedbackConstants.VIRTUAL_KEY,
                HapticFeedbackConstants.LONG_PRESS,
            ),
            calls,
        )
    }

    @Test
    fun elasticPressTracksFingerGlowAndFadesOutAfterRelease() {
        lateinit var state: ElasticPressState
        rule.mainClock.autoAdvance = false
        rule.setContent {
            MaterialTheme {
                state = rememberElasticPress()
                LiquidGlass(
                    Modifier
                        .size(64.dp)
                        .liquidClickable(press = state) {}
                        .testTag("glow-btn"),
                    press = state,
                ) {}
            }
        }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        assertEquals(0f, state.glowStrength, 0.0001f)
        rule.onNodeWithTag("glow-btn").performTouchInput { down(Offset(14f, 18f)) }
        rule.mainClock.advanceTimeBy(120)
        rule.waitForIdle()
        assertTrue(state.glowStrength > 0.1f)
        assertEquals(Offset(14f, 18f), state.glowCenter(Size(64f, 64f)))
        rule.onNodeWithTag("glow-btn").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        assertEquals(0f, state.glowStrength, 0.001f)
    }

    @Test
    fun subAgentCapsuleAndQueuedMessagesOpenSameWindowMorphMenuInsideLiquidScaffold() {
        var selectedSubAgent: String? = null
        var steeredQueueId: String? = null
        val view = queueAgentsFixture()
        val actions = object : io.github.modao7067.luma.gui.session.CockpitActions by previewActions {
            override fun steerQueued(id: String) { steeredQueueId = id }
        }
        rule.setContent {
            MaterialTheme {
                LiquidScaffold(
                    topBar = {},
                    bottomBar = { QueuedMessages(view, actions) },
                    wrapTop = false,
                    wrapBottom = false,
                    overlay = { padding ->
                        ConversationScrollOverlay(
                            visible = false,
                            padding = padding,
                            subAgents = historyFixtureAgents,
                            onSelectSubAgent = { selectedSubAgent = it.id },
                            onJump = {},
                        )
                    },
                ) {
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("subagent-capsule").performTouchInput { down(center); up() }
        rule.waitForIdle()
        rule.onNodeWithTag("action-menu-carrier").assertDoesNotExist()
        rule.onNodeWithText("历史").assertIsDisplayed()
        rule.onNodeWithText("界面实现").performTouchInput { down(center); up() }
        rule.waitForIdle()
        assertEquals("running-ui", selectedSubAgent)

        rule.onNodeWithText("完成后补充窄屏和长消息验收").performTouchInput { down(center); up() }
        rule.waitForIdle()
        rule.onNodeWithTag("action-menu-carrier").assertDoesNotExist()
        rule.onNodeWithText("插话").assertIsDisplayed()
        rule.onNodeWithText("插话").performTouchInput { down(center); up() }
        rule.waitForIdle()
        assertEquals("q1", steeredQueueId)
    }

    @Test
    fun morphMenuRecoversWhenReopenedAtDismissCompletionOrAfterRemovedEntry() {
        var expanded by mutableStateOf(false)
        var clicks = 0
        var host: MorphMenuHost? = null
        rule.setContent {
            MaterialTheme {
                LiquidScaffold(
                    topBar = {
                        host = LocalMorphMenuHost.current
                        val anchor = rememberMorphAnchor()
                        Box(Modifier.fillMaxWidth()) {
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .size(44.dp)
                                    .morphAnchor(anchor, enabled = true, onOpen = { expanded = true })
                                    .testTag("anchor"),
                            )
                            MorphMenu(expanded, { expanded = false }, anchor, width = 200.dp, placement = MorphPlacement.End) {
                                ActionMenuItem("RowA", MenuSymbol.EDIT, onClick = { expanded = false; clicks++ })
                            }
                        }
                    },
                    bottomBar = {},
                    wrapTop = false,
                    wrapBottom = false,
                ) {
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("anchor").performTouchInput { down(center); up() }
        rule.waitForIdle()
        rule.onNodeWithText("RowA").assertIsDisplayed()

        rule.mainClock.autoAdvance = false
        rule.runOnIdle { expanded = false }
        repeat(31) {
            rule.mainClock.advanceTimeBy(16)
            rule.waitForIdle()
        }
        rule.onNodeWithTag("anchor").performTouchInput { down(center); up() }
        repeat(35) {
            rule.mainClock.advanceTimeBy(16)
            rule.waitForIdle()
        }
        rule.mainClock.autoAdvance = true
        rule.onNodeWithText("RowA").assertIsDisplayed()

        rule.runOnIdle {
            val leaked = host!!.entries.single()
            leaked.removed = true
            host!!.entries.remove(leaked)
        }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(1, host!!.entries.size) }
        rule.onNodeWithText("RowA").assertIsDisplayed()
        rule.onNodeWithText("RowA").performTouchInput { down(center); up() }
        rule.runOnIdle { assertEquals(1, clicks) }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Test
    fun morphMenuScrimConsumesFullDismissGestureWithoutLeakingOutsideTap() {
        var expanded by mutableStateOf(false)
        var bgClicks = 0
        var bgLongPresses = 0
        rule.setContent {
            MaterialTheme {
                LiquidScaffold(
                    topBar = {
                        val anchor = rememberMorphAnchor()
                        Box(Modifier.fillMaxWidth()) {
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .size(44.dp)
                                    .morphAnchor(anchor, enabled = true, onOpen = { expanded = true })
                                    .testTag("anchor"),
                            )
                            MorphMenu(expanded, { expanded = false }, anchor, width = 200.dp, placement = MorphPlacement.End) {
                                ActionMenuItem("RowA", MenuSymbol.EDIT, onClick = { expanded = false })
                            }
                        }
                    },
                    bottomBar = {},
                    wrapTop = false,
                    wrapBottom = false,
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .combinedClickable(
                                onClick = { bgClicks++ },
                                onLongClick = { bgLongPresses++ },
                            )
                            .testTag("background"),
                    )
                }
            }
        }
        rule.onNodeWithTag("anchor").performTouchInput { down(center); up() }
        rule.waitForIdle()
        rule.onNodeWithText("RowA").assertIsDisplayed()

        rule.onNodeWithTag("background").performTouchInput {
            val outside = Offset(width * 0.2f, height * 0.85f)
            down(outside)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100L)
            up()
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(false, expanded)
            assertEquals(0, bgClicks)
            assertEquals(0, bgLongPresses)
        }
        rule.onNodeWithText("RowA").assertDoesNotExist()

        rule.onNodeWithTag("background").performTouchInput {
            val outside = Offset(width * 0.2f, height * 0.85f)
            down(outside)
            up()
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(1, bgClicks)
            assertEquals(0, bgLongPresses)
        }
    }
}
