package io.github.modao7067.luma.gui.ui

import android.app.Application
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import io.github.modao7067.luma.gui.session.ConversationItem
import io.github.modao7067.luma.gui.ui.thirdparty.miuix.LocalOverScrollState
import io.github.modao7067.luma.gui.ui.thirdparty.miuix.overScrollVertical
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ManualTailScrollTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var list: LazyListState
    private lateinit var navigation: ConversationTailFollow
    private lateinit var scope: CoroutineScope
    private var identity by mutableStateOf("parent")
    private var bodyHeight by mutableIntStateOf(100)
    private var appendCount by mutableIntStateOf(0)
    private var shortContent = false
    private var header = Rect.Zero
    private var composer = Rect.Zero
    private var tail = Rect.Zero
    private var head = Rect.Zero
    private val updateNanos = mutableListOf<Long>()
    private var baseline = false
    private var tools by mutableStateOf(listOf<ConversationItem>())
    private var automaticCorrections = 0
    private var imeHeightDp by mutableIntStateOf(0)
    private var imeExtraPaddingDp by mutableIntStateOf(0)
    private var imeBottomPx by mutableIntStateOf(0)
    private var useCollapseSpacer by mutableStateOf(false)

    private fun fixture() {
        rule.setContent {
            MaterialTheme {
                val saved = rememberSaveableStateHolder()
                Column(Modifier.width(360.dp).height((600 - imeHeightDp).dp)) {
                    Text("固定标题", Modifier.fillMaxWidth().height(50.dp).onGloballyPositioned { header = it.boundsInRoot() })
                    Box(Modifier.weight(1f)) {
                        saved.SaveableStateProvider(identity) {
                            list = rememberLazyListState()
                            scope = rememberCoroutineScope()
                            navigation = rememberConversationTailFollow(list, identity, imeBottomPx = imeBottomPx)
                            if (baseline) BaselineTailLock(list) { automaticCorrections++ }
                            CompositionLocalProvider(LocalOverScrollState provides navigation.overscrollState) {
                                LazyColumn(
                                    state = list,
                                    overscrollEffect = null,
                                    modifier = Modifier.fillMaxSize().testTag("list")
                                        .nestedScroll(navigation.nestedScrollConnection).overScrollVertical(),
                                    contentPadding = PaddingValues(top = 16.dp, bottom = (80 + imeExtraPaddingDp).dp),
                                ) {
                                    if (!shortContent) items((1..18).toList(), key = { "history-$it" }, contentType = { "history" }) { row ->
                                        Text("历史 $row", Modifier.fillMaxWidth().height(90.dp).then(if (row == 1) Modifier.onGloballyPositioned { head = Rect(it.localToRoot(Offset.Zero), it.localToRoot(Offset(it.size.width.toFloat(), it.size.height.toFloat()))) } else Modifier))
                                    }
                                    item(key = "body", contentType = "body") {
                                        Text("正文/图片/表格占位几何", Modifier.fillMaxWidth().height(bodyHeight.dp).testTag("tail")
                                            .onGloballyPositioned { tail = Rect(it.localToRoot(Offset.Zero), it.localToRoot(Offset(it.size.width.toFloat(), it.size.height.toFloat()))) })
                                    }
                                    if (tools.isNotEmpty()) item(key = "tools", contentType = "process") {
                                        ProcessGroup(tools, initiallyExpanded = true, animateInitialArrival = false)
                                    }
                                    items((0 until appendCount).toList(), key = { "append-$it" }, contentType = { "append" }) {
                                        Text("新正文 $it", Modifier.fillMaxWidth().height(90.dp))
                                    }
                                    if (useCollapseSpacer) item(key = "collapse-spacer", contentType = "spacer") {
                                        val holdDp = with(androidx.compose.ui.platform.LocalDensity.current) { navigation.collapseHoldPx.toDp() }
                                        Spacer(Modifier.fillMaxWidth().height(holdDp))
                                    }
                                }
                            }
                        }
                    }
                    Text("固定输入栏", Modifier.fillMaxWidth().height(60.dp).onGloballyPositioned { composer = it.boundsInRoot() })
                }
            }
        }
        rule.waitForIdle()
    }

    private fun position() = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
    private fun update(block: () -> Unit) { val start = System.nanoTime(); rule.runOnIdle(block); rule.waitForIdle(); updateNanos += System.nanoTime() - start }
    private fun tool(lines: Int = 4, outcome: String = "running") = ConversationItem("tool", "tool", text = "检查", kind = "commandExecution",
        input = "echo test", detail = (1..lines).joinToString("\n") { "output $it" }, outcome = outcome)

    @Test fun idleAtBottomDoesNotRelockAfterStreamingToolInsertionCompletionOrRemoval() {
        fixture()
        val anchor = rule.runOnIdle { assertTrue(navigation.atTail); position() }
        val series = mutableListOf<Pair<Int, Int>>()
        update { bodyHeight = 220 }; series += rule.runOnIdle { position() }
        update { tools = listOf(tool()) }; series += rule.runOnIdle { position() }
        update { tools = listOf(tool(lines = 20)) }; series += rule.runOnIdle { position() }
        update { tools = listOf(tool(lines = 20, outcome = "succeeded")) }; series += rule.runOnIdle { position() }
        update { tools = emptyList() }; series += rule.runOnIdle { position() }
        update { appendCount = 1 }; series += rule.runOnIdle { position() }
        rule.runOnIdle {
            assertTrue("No automatic alignments after initial positioning: $series", series.all { it == anchor })
            assertTrue("Tail must be allowed to grow below the screen", list.canScrollForward)
        }
        println("CANDIDATE idle initially-at-bottom input=body100→220,tool4→20→completed→removed,append1 anchor=$anchor positions=$series JVM_idle_update_us=${updateNanos.map { it / 1000 }}")
        update { appendCount = 0; bodyHeight = 40 }
        val clamped = rule.runOnIdle {
            assertFalse("Native range clamp may return to the end after net content shrink", list.canScrollForward)
            assertNotEquals("Distinguish native clamping from our removed follow loop", anchor, position())
            position()
        }
        println("CANDIDATE native shrink below original total extent (body40,append0): $anchor → $clamped; legal LazyColumn range clamp, no artificial spacer")
    }

    @Test fun baselineSameInputReproducesRepeatedBottomEdgeLock() {
        baseline = true
        fixture()
        val anchor = rule.runOnIdle { position() }
        val series = mutableListOf<Pair<Int, Int>>()
        update { bodyHeight = 220 }; series += rule.runOnIdle { position() }
        update { tools = listOf(tool()) }; series += rule.runOnIdle { position() }
        update { tools = listOf(tool(lines = 20)) }; series += rule.runOnIdle { position() }
        update { tools = listOf(tool(lines = 20, outcome = "succeeded")) }; series += rule.runOnIdle { position() }
        update { tools = emptyList() }; series += rule.runOnIdle { position() }
        update { appendCount = 1 }; series += rule.runOnIdle { position() }
        rule.runOnIdle {
            assertTrue("Negative control must reproduce bottom-lock movement", series.any { it != anchor })
            assertFalse(list.canScrollForward)
            assertTrue(automaticCorrections > 0)
        }
        println("BASELINE same input anchor=$anchor positions=$series geometryCorrectionCount=$automaticCorrections JVM_idle_update_us=${updateNanos.map { it / 1000 }}")
        update { appendCount = 0; bodyHeight = 40 }
        println("BASELINE same net-shrink input finalPosition=${rule.runOnIdle { position() }}")
    }

    @Test fun upwardReadingGestureAndProgrammaticPositioningNeverRequestTailSnap() {
        fixture()
        rule.onNodeWithTag("list").performTouchInput { swipe(center - Offset(0f, 90f), center + Offset(0f, 90f), 800) }
        rule.waitForIdle()
        val reading = rule.runOnIdle { assertTrue(list.canScrollForward); position() }
        update { appendCount = 2; bodyHeight = 180 }
        rule.runOnIdle { assertEquals(reading, position()) }
        rule.runOnIdle { scope.launch { list.scrollToItem(list.layoutInfo.totalItemsCount - 1); list.scrollBy(-24f) } }
        rule.waitForIdle()
        val programmatic = rule.runOnIdle { position() }
        update { bodyHeight = 190 }
        rule.runOnIdle { assertEquals("Programmatic near-bottom movement is not a user gesture", programmatic, position()) }
    }

    @Test fun downwardGestureCompletionSnapsOnlyNearbyOnceAndJumpDoesNotEnableFollow() {
        fixture()
        rule.runOnIdle { scope.launch { list.scrollBy(-24f) } }; rule.waitForIdle()
        rule.runOnIdle { scope.launch {
            navigation.nestedScrollConnection.onPreScroll(Offset(0f, -1f), NestedScrollSource.UserInput)
            navigation.nestedScrollConnection.onPostFling(Velocity.Zero, Velocity.Zero)
        } }; rule.waitForIdle()
        val aligned = rule.runOnIdle { assertFalse(list.canScrollForward); position() }
        update { bodyHeight = 220 }
        rule.runOnIdle { assertEquals(aligned, position()); assertTrue(list.canScrollForward) }
        rule.runOnIdle { scope.launch { navigation.jumpToTail() } }; rule.waitForIdle()
        val jumped = rule.runOnIdle { assertFalse(list.canScrollForward); position() }
        update { appendCount = 1 }
        rule.runOnIdle { assertEquals(jumped, position()); assertTrue(list.canScrollForward) }
        rule.runOnIdle { scope.launch { list.scrollToItem(2) } }; rule.waitForIdle()
        val far = rule.runOnIdle { position() }
        rule.runOnIdle { scope.launch {
            navigation.nestedScrollConnection.onPreScroll(Offset(0f, -1f), NestedScrollSource.UserInput)
            navigation.nestedScrollConnection.onPostFling(Velocity.Zero, Velocity.Zero)
        } }; rule.waitForIdle()
        rule.runOnIdle { assertEquals(far, position()) }
        println("PASS injected downward nested-scroll completion: 24px snaps once; far-from-tail unchanged; later growth never follows")
    }

    @Test fun sessionSwitchRestoresReadingPositionInsteadOfReinitializingAtTail() {
        fixture()
        rule.runOnIdle { scope.launch { list.scrollToItem(3, 17) } }; rule.waitForIdle()
        val parent = rule.runOnIdle { position() }
        update { identity = "child" }
        rule.runOnIdle { assertFalse("New session initial positioning", list.canScrollForward) }
        rule.runOnIdle { scope.launch { list.scrollToItem(6, 23) } }; rule.waitForIdle()
        val child = rule.runOnIdle { position() }
        update { identity = "parent" }
        rule.runOnIdle { assertEquals(parent, position()) }
        update { identity = "child" }
        rule.runOnIdle { assertEquals(child, position()) }
    }

    private fun overscrollGeometry(short: Boolean) {
        shortContent = short
        fixture()
        val fixedHeader = header
        val fixedComposer = composer
        val original = tail
        val offsets = mutableListOf<Float>()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("list").performTouchInput { down(center); moveBy(Offset(0f, -80f), 100) }
        repeat(3) {
            rule.onNodeWithTag("list").performTouchInput { moveBy(Offset(0f, -60f), 100) }
            rule.mainClock.advanceTimeByFrame(); rule.waitForIdle()
            rule.runOnIdle {
                offsets += tail.top - original.top
                assertEquals(original.width, tail.width, 0.01f)
                assertEquals(original.height, tail.height, 0.01f)
                assertEquals(fixedHeader, header); assertEquals(fixedComposer, composer)
            }
        }
        assertTrue("Bottom overflow moves content up: $offsets", offsets.all { it < 0f })
        assertTrue("Equal drags meet increasing resistance: $offsets", kotlin.math.abs(offsets[2] - offsets[1]) < kotlin.math.abs(offsets[1] - offsets[0]))
        rule.onNodeWithTag("list").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(1600); rule.mainClock.autoAdvance = true; rule.waitForIdle()
        rule.runOnIdle { assertEquals("Bottom rebound", original.top, tail.top, 1f) }
        rule.runOnIdle { scope.launch { list.scrollToItem(0) } }; rule.waitForIdle()
        val top = if (short) tail else head
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("list").performTouchInput { down(center); moveBy(Offset(0f, 100f), 100); moveBy(Offset(0f, 60f), 100) }
        rule.mainClock.advanceTimeByFrame(); rule.waitForIdle()
        rule.runOnIdle {
            val pulled = if (short) tail else head
            assertTrue("Top overflow translates content downward", pulled.top > top.top)
            assertEquals(top.height, pulled.height, 0.01f)
            assertEquals(top.width, pulled.width, 0.01f)
            assertEquals(fixedHeader, header); assertEquals(fixedComposer, composer)
        }
        rule.onNodeWithTag("list").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(1600); rule.mainClock.autoAdvance = true; rule.waitForIdle()
        rule.runOnIdle { assertEquals("Top rebound", top.top, (if (short) tail else head).top, 1f) }
        println("PASS Miuix ${if (short) "short" else "overflowing"} content bottom offsets=$offsets size=${original.width}x${original.height}; fixed chrome unchanged; rebound returns to origin")
    }

    @Test fun translationOverscrollPreservesGeometryAndFixedChrome() = overscrollGeometry(false)
    @Test fun shortContentUsesTheSameTranslationBounce() = overscrollGeometry(true)

    @Test fun openingKeyboardAtTailAlignsOnceWithoutFollowingSubsequentGrowthOrMovingScrolledReader() {
        fixture()
        val initialAnchor = rule.runOnIdle { assertTrue(navigation.atTail); position() }
        fun stepManualClock(ms: Long = 32L, block: () -> Unit) {
            rule.runOnIdle(block)
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            rule.mainClock.advanceTimeBy(ms)
            rule.waitForIdle()
        }

        rule.mainClock.autoAdvance = false
        stepManualClock { imeBottomPx = 100; imeHeightDp = 100; imeExtraPaddingDp = 24 }
        rule.runOnIdle { assertFalse("Mid-animation IME open keeps tail visible", list.canScrollForward) }

        stepManualClock { imeBottomPx = 240; imeHeightDp = 240; imeExtraPaddingDp = 48 }
        val imeAlignedAnchor = rule.runOnIdle {
            assertFalse("Completed IME open keeps tail visible", list.canScrollForward)
            assertTrue("IME open must advance scroll from $initialAnchor", position() != initialAnchor)
            position()
        }

        stepManualClock { bodyHeight = 220 }
        rule.runOnIdle {
            assertEquals("Streaming during IME settle must not move anchor", imeAlignedAnchor, position())
            assertTrue("Streamed content grows below viewport", list.canScrollForward)
        }

        rule.mainClock.advanceTimeBy(500); rule.mainClock.autoAdvance = true; rule.waitForIdle()
        update { tools = listOf(tool()) }
        rule.runOnIdle { assertEquals("Tool arrival with keyboard open must not move anchor", imeAlignedAnchor, position()) }

        update { imeBottomPx = 0; imeHeightDp = 0; imeExtraPaddingDp = 0 }
        rule.runOnIdle { scope.launch { list.scrollToItem(3, 17) } }; rule.waitForIdle()
        val readingAnchor = rule.runOnIdle { assertTrue(list.canScrollForward); position() }
        update { imeBottomPx = 240; imeHeightDp = 240; imeExtraPaddingDp = 48 }
        rule.runOnIdle { assertEquals("Scrolled-up reader must not be moved by IME open", readingAnchor, position()) }
    }

    @Test fun nearBottomImeOpenAlignsOnceWithoutLosingPartialDisplacement() {
        fixture()
        rule.runOnIdle { scope.launch { list.scrollBy(-36f) } }; rule.waitForIdle()
        rule.runOnIdle { assertTrue("Starts near bottom with small tail distance", list.canScrollForward) }

        rule.mainClock.autoAdvance = false
        rule.runOnIdle { imeBottomPx = 120; imeHeightDp = 120; imeExtraPaddingDp = 24 }
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeBy(32); rule.waitForIdle()
        rule.runOnIdle { assertFalse("Near-bottom IME open must align tail on first step", list.canScrollForward) }

        rule.runOnIdle { imeBottomPx = 240; imeHeightDp = 240; imeExtraPaddingDp = 48 }
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeBy(32); rule.waitForIdle()
        val aligned = rule.runOnIdle {
            assertFalse("Completed IME open keeps tail visible", list.canScrollForward)
            position()
        }

        rule.runOnIdle { bodyHeight = 220 }
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeBy(32); rule.waitForIdle()
        rule.runOnIdle {
            assertEquals("Streaming growth after IME align must not move anchor", aligned, position())
            assertTrue(list.canScrollForward)
        }
    }

    @Test fun interruptedCollapseHoldNeverLeaksDeadSpace() {
        useCollapseSpacer = true
        fixture()
        rule.runOnIdle {
            navigation.collapseHoldPx = 240
            navigation.heldTotalPx = 240
            navigation.anyRevealAnimating = true
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { navigation.anyRevealAnimating = false }
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeBy(32); rule.waitForIdle()
        rule.onNodeWithTag("list").performTouchInput { swipe(center, center + Offset(0f, 120f), 100) }
        rule.mainClock.advanceTimeBy(1600); rule.mainClock.autoAdvance = true; rule.waitForIdle()
        rule.onNodeWithTag("list").performTouchInput { swipe(center + Offset(0f, 120f), center - Offset(0f, 300f), 100) }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals("Interrupted collapseHoldPx must settle/clear to 0 without leaking", 0, navigation.collapseHoldPx)
        }
    }
}

@Composable
private fun BaselineTailLock(list: LazyListState, onCorrection: () -> Unit) {
    LaunchedEffect(list) {
        snapshotFlow {
            val info = list.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            Triple(info.totalItemsCount, last?.index, last?.let { it.offset + it.size + info.afterContentPadding - info.viewportEndOffset } ?: 0) to list.isScrollInProgress
        }.distinctUntilChanged().collect { (geometry, scrolling) ->
            if (!scrolling && geometry.first > 0) {
                if (geometry.second != geometry.first - 1) { onCorrection(); list.scrollToItem(geometry.first - 1) }
                else if (geometry.third > 0) { onCorrection(); list.scrollBy(geometry.third.toFloat()) }
            }
        }
    }
}
