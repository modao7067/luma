package io.github.modao7067.luma.gui.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

import android.app.Application
import android.view.WindowManager
import android.view.Window
import android.view.View
import android.view.KeyEvent
import android.view.inspector.WindowInspector
import android.view.WindowInsets
import android.graphics.Insets
import org.robolectric.shadows.ShadowDialog
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.modao7067.luma.gui.session.SubAgentState
import io.github.modao7067.luma.gui.session.CockpitState
import io.github.modao7067.luma.gui.session.CockpitActions
import io.github.modao7067.luma.gui.runtime.NativeCliRuntimePolicy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import java.io.File

private fun baselineMenuScale(fraction: Float) = .85f + .15f * fraction.coerceIn(0f, 1f)

@Composable
private fun AnchoredBaselineMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    previewFraction: Float? = null,
    width: Dp = 228.dp,
    preserveEditorFocus: Boolean = false,
    dismissOnClickOutside: Boolean = true,
    centerHorizontally: Boolean = false,
    onWindowSize: (IntSize) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!LocalSessionInteractive.current) {
        SideEffect { if (expanded) onDismiss() }
        return
    }
    BackHandler(enabled = expanded && preserveEditorFocus, onBack = onDismiss)
    val visible = remember { MutableTransitionState(false) }
    visible.targetState = expanded
    val currentVisible = visible.currentState
    val transition = rememberTransition(visible, label = "menu-anchor")
    val progressState = transition.animateFloat(transitionSpec = { menuMotionSpec(targetState) }, label = "menu-enter-exit") { if (it) 1f else 0f }
    var pivot by remember { mutableStateOf(TransformOrigin(0.5f, 0f)) }
    val halo = 24.dp
    val density = LocalDensity.current
    val marginPx = with(density) { 8.dp.roundToPx() }
    val haloPx = with(density) { halo.roundToPx() }
    val anchorGapPx = if (preserveEditorFocus && !dismissOnClickOutside) haloPx else marginPx
    var anchorSurfaceHeightPx by remember { mutableStateOf<Int?>(null) }
    val position = remember(marginPx, haloPx, anchorGapPx, centerHorizontally, preserveEditorFocus, dismissOnClickOutside) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val controlledOutside = preserveEditorFocus && !dismissOnClickOutside
                val maxPopupHeight = baselinePopupMaxHeight(anchorBounds, windowSize, marginPx)
                if (controlledOutside) {
                    val nextSurfaceHeight = (maxPopupHeight - 2 * haloPx).coerceAtLeast(1)
                    if (anchorSurfaceHeightPx != nextSurfaceHeight) anchorSurfaceHeightPx = nextSurfaceHeight
                }
                val placement = baselinePopupPlacement(anchorBounds, windowSize, popupContentSize, marginPx, haloPx, anchorGapPx, centerHorizontally)
                if (pivot != placement.pivot) pivot = placement.pivot
                return placement.position
            }
        }
    }
    if (expanded || currentVisible || visible.targetState || (previewFraction ?: 0f) > 0f) {
        Popup(
            popupPositionProvider = position,
            onDismissRequest = onDismiss,
            properties = PopupProperties(focusable = !preserveEditorFocus, dismissOnClickOutside = dismissOnClickOutside, clippingEnabled = false),
        ) {
            GuiWindowScale {
                val scaledDensity = LocalDensity.current
                val widthPx = with(scaledDensity) { width.roundToPx() }
                val reservePx = with(scaledDensity) { (halo * 2 + 16.dp).roundToPx() }
                val maxMenuHeightPx = with(scaledDensity) { 460.dp.roundToPx() }
                val scheme = MaterialTheme.colorScheme
                val dark = scheme.surface.luminance() < 0.5f
                val baseTint = if (dark) scheme.surfaceContainerHigh else scheme.surfaceContainerLowest
                Box(
                    Modifier
                        .onSizeChanged(onWindowSize)
                        .graphicsLayer {
                            val fraction = (previewFraction ?: progressState.value).coerceIn(0f, 1f)
                            compositingStrategy = CompositingStrategy.Offscreen
                            transformOrigin = pivot
                            scaleX = baselineMenuScale(fraction)
                            scaleY = baselineMenuScale(fraction)
                            alpha = fraction
                        }
                        .then(if (expanded) Modifier else Modifier.clearAndSetSemantics {})
                        .pointerInput(onDismiss) { detectTapGestures(onTap = { onDismiss() }) }
                        .padding(halo),
                ) {
                    Surface(
                        modifier = Modifier.layout { measurable, constraints ->
                            val maxW = if (constraints.hasBoundedWidth) {
                                minOf(widthPx, (constraints.maxWidth - reservePx + haloPx * 2).coerceAtLeast(1))
                            } else widthPx
                            val windowMaxH = if (constraints.hasBoundedHeight) {
                                (constraints.maxHeight - reservePx + haloPx * 2).coerceIn(1, maxMenuHeightPx)
                            } else maxMenuHeightPx
                            val maxH = anchorSurfaceHeightPx?.let { minOf(windowMaxH, it) } ?: windowMaxH
                            val placeable = measurable.measure(Constraints(minWidth = maxW, maxWidth = maxW, minHeight = 0, maxHeight = maxH))
                            layout(placeable.width, placeable.height) {
                                placeable.placeRelative(0, 0)
                            }
                        },
                        shape = RoundedCornerShape(24.dp),
                        color = baseTint,
                        tonalElevation = 0.dp,
                        shadowElevation = 6.dp,
                    ) {
                        CompositionLocalProvider(LocalMenuActive provides expanded) {
                            Column(
                                Modifier
                                    .verticalScroll(rememberScrollState())
                                    .padding(vertical = 8.dp),
                                content = content,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun baselinePopupMaxHeight(anchor: IntRect, window: IntSize, margin: Int): Int =
    maxOf(anchor.top - margin, window.height - anchor.bottom - margin).coerceAtLeast(1)
private fun baselinePopupPlacement(anchor: IntRect, window: IntSize, popup: IntSize, margin: Int, halo: Int, anchorGap: Int = margin, centerHorizontally: Boolean = false): BaselinePlacement {
    val surface = IntSize((popup.width - halo * 2).coerceAtLeast(1), (popup.height - halo * 2).coerceAtLeast(1))
    val anchored = baselineInitialPlacement(anchor, window, surface, margin, anchorGap)
    val placed = if (centerHorizontally) anchored.copy(position = IntOffset(
        ((window.width - surface.width) / 2).coerceAtLeast(margin), anchored.position.y,
    )) else anchored
    val px = (anchor.center.x - placed.position.x).coerceIn(0, surface.width)
    val py = (anchor.center.y - placed.position.y).coerceIn(0, surface.height)
    return BaselinePlacement(IntOffset(placed.position.x - halo, placed.position.y - halo),
        TransformOrigin((halo + px).toFloat() / popup.width.coerceAtLeast(1), (halo + py).toFloat() / popup.height.coerceAtLeast(1)))
}

private data class BaselinePlacement(val position: IntOffset, val pivot: TransformOrigin)
private fun baselineInitialPlacement(anchor: IntRect, window: IntSize, popup: IntSize, margin: Int, anchorGap: Int = margin): BaselinePlacement {
    val x = anchor.left.coerceIn(margin, (window.width - popup.width - margin).coerceAtLeast(margin))
    val above = anchor.top - popup.height - anchorGap
    val y = if (above >= margin) above else (anchor.bottom + anchorGap).coerceAtMost((window.height - popup.height - margin).coerceAtLeast(margin))
    return BaselinePlacement(IntOffset(x, y), TransformOrigin(
        (anchor.center.x - x).toFloat() / popup.width.coerceAtLeast(1),
        (anchor.center.y - y).toFloat() / popup.height.coerceAtLeast(1),
    ))
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class MenuDetachedWindowBenchmark {
    @get:Rule val rule = createComposeRule()
    private fun output(name: String, text: String) {
        val dir = File(System.getProperty("java.io.tmpdir"), "luma-verification/menu-dropdown-live-20261105").apply { mkdirs() }
        File(dir, name).writeText(text)
    }
    private fun syntheticInsets(ime: Int) = WindowInsets.Builder()
        .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, 24, 0, 34))
        .setVisible(WindowInsets.Type.systemBars(), true)
        .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, ime))
        .setVisible(WindowInsets.Type.ime(), ime > 0).build()

    @Test fun nativeFloatingMenuKeepsItsCarrierAndEditorWhileOldPopupChangesSize() {
        var baseline by mutableStateOf(true)
        var open by mutableStateOf(false)
        var historyExpanded by mutableStateOf(false)
        var generation by mutableIntStateOf(0)
        var anchorShift by mutableIntStateOf(0)
        val oldSizes = mutableListOf<IntSize>()
        val carrierBounds = mutableListOf<androidx.compose.ui.geometry.Rect>()
        val windowAttributes = mutableListOf<String>()
        val selected = mutableListOf<String>()
        val agents = listOf(SubAgentState("gauss", "Gauss", status = "running"), SubAgentState("nash", "Nash", status = "running")) +
            (0..39).map { SubAgentState("history-$it", "History $it", status = "succeeded") }
        val editor = mutableStateOf(TextFieldValue("保留草稿与光标", TextRange(3)))
        val originalEditor = editor.value
        var hidden = 0
        var shown = 0
        var dismissals = 0
        val keyboard = object : SoftwareKeyboardController {
            override fun hide() { hidden++ }
            override fun show() { shown++ }
        }
        rule.setContent { CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) { MaterialTheme {
            Box(Modifier.fillMaxSize()) {
                BasicTextField(editor.value, { editor.value = it }, Modifier.testTag("editor"))
                Box(Modifier.align(androidx.compose.ui.Alignment.BottomCenter).offset(y = anchorShift.dp).size(44.dp)) {
                    key(generation) {
                        val content: @Composable ColumnScope.() -> Unit = {
                            SubAgentPopupItems(agents, null, historyExpanded, { historyExpanded = !historyExpanded }, true,
                                onSelect = { selected += it.id; open = false })
                        }
                        if (baseline) AnchoredBaselineMenu(open, { open = false; dismissals++ }, width = 280.dp,
                            preserveEditorFocus = true, previewFraction = if (open) 1f else 0f,
                            onWindowSize = { oldSizes += it }, content = content)
                        else OriginMenu(open, { open = false; dismissals++ }, width = 280.dp, content = content)
                    }
                }
            }
        } } }
        rule.onNodeWithTag("editor").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        rule.onNodeWithTag("editor").assertIsFocused()
        rule.runOnIdle { open = true }
        for (expanded in listOf(false, true, false, true, false)) {
            rule.runOnIdle { historyExpanded = expanded }
            rule.waitForIdle()
        }
        assertTrue("old popup carrier follows expanded content size: $oldSizes", oldSizes.distinct().size >= 2)
        rule.runOnIdle { baseline = false; generation++; historyExpanded = false }
        fun popupRoot(): View = WindowInspector.getGlobalWindowViews().last {
            (it.layoutParams as? WindowManager.LayoutParams)?.type == WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL
        }
        fun record() {
            carrierBounds += rule.onNodeWithTag("action-menu-carrier").fetchSemanticsNode().boundsInRoot
            val attributes = popupRoot().layoutParams as WindowManager.LayoutParams
            windowAttributes += "${attributes.x},${attributes.y},${attributes.width},${attributes.height}"
            assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING,
                attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
            assertTrue(attributes.flags and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM != 0)
            assertEquals(0, attributes.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            assertEquals(0, attributes.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        }
        record()
        rule.mainClock.autoAdvance = false
        for (expanded in listOf(true, false, true, false, true, false)) {
            rule.runOnIdle { historyExpanded = expanded }
            rule.mainClock.advanceTimeBy(64); rule.waitForIdle(); record()
        }
        rule.mainClock.autoAdvance = true
        rule.runOnIdle { anchorShift = -100; popupRoot().dispatchApplyWindowInsets(syntheticInsets(420)) }
        record()
        assertEquals(1, carrierBounds.distinct().size)
        assertEquals(1, windowAttributes.distinct().size)
        assertEquals(originalEditor, editor.value)
        assertEquals(0, hidden)
        assertEquals(0, shown)
        val bars = syntheticInsets(0).getInsets(WindowInsets.Type.systemBars())
        for (ime in listOf(0, 240, 500)) {
            val filtered = withoutMenuIme(syntheticInsets(ime))
            assertEquals(Insets.NONE, filtered.getInsets(WindowInsets.Type.ime()))
            assertEquals(bars, filtered.getInsets(WindowInsets.Type.systemBars()))
        }
        rule.onNodeWithText("历史").performClick()
        val outer = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy) and !hasScrollToIndexAction())
        outer.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 1000f) }
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(39)
        val tail = rule.onNodeWithText("History 39").performScrollTo().assertIsEnabled()
        println("history-tail-before-click=" + tail.fetchSemanticsNode().boundsInRoot +
            "; carrier=" + rule.onNodeWithTag("action-menu-carrier").fetchSemanticsNode().boundsInRoot +
            "; window=" + (popupRoot().layoutParams as WindowManager.LayoutParams).let { "${it.x},${it.y},${it.width},${it.height}" })
        tail.assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(listOf("history-39"), selected) }
        rule.onNodeWithTag("action-menu-carrier").assertDoesNotExist()
        rule.onNodeWithTag("editor").assertIsFocused()
        assertEquals(originalEditor, editor.value)
        rule.runOnIdle { historyExpanded = false; open = true }
        rule.onNodeWithTag("action-menu-carrier").performTouchInput {
            click(androidx.compose.ui.geometry.Offset(4f, bottomRight.y - 4f))
        }
        rule.onNodeWithTag("action-menu-carrier").assertDoesNotExist()
        assertEquals(1, dismissals)
        rule.runOnIdle { open = true }
        rule.runOnIdle {
            val root = popupRoot()
            root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            root.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        }
        rule.onNodeWithTag("action-menu-carrier").assertDoesNotExist()
        assertEquals(2, dismissals)
        assertEquals(0, hidden)
        assertEquals(0, shown)
        output("detached-window-benchmark.txt", "Robolectric API35 LEGACY; official M3 1.4.0 floating menu seam, not device WM/GPU timing\noldPopupSizes=$oldSizes\nnewCarrierBounds=$carrierBounds\nwindowAttributes=$windowAttributes\ntail=history-39; draftSelectionPreserved=true; hide=0 show=0; outside/backDismiss=$dismissals\n")
    }

    @Test fun existingMaterialDrawerUsesFullHeightAndRestoresItsWindowPolicy() {
        var window: Window? = null
        var originalSoftInput = -1
        rule.setContent { MaterialTheme {
            window = LocalContext.current.actionActivityWindow()
            val original = remember(window) { window!!.attributes.softInputMode }
            SideEffect { originalSoftInput = original }
            CockpitScreen(CockpitState(), previewActions, previewDrawerOpen = true)
        } }
        rule.runOnIdle { window!!.decorView.dispatchApplyWindowInsets(syntheticInsets(0)) }
        val before = rule.onNodeWithTag("session-sidebar-list").fetchSemanticsNode().boundsInRoot
        assertTrue(window!!.attributes.flags and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM != 0)
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING,
            window!!.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
        rule.runOnIdle { window!!.decorView.dispatchApplyWindowInsets(syntheticInsets(420)) }
        assertEquals(before, rule.onNodeWithTag("session-sidebar-list").fetchSemanticsNode().boundsInRoot)
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        rule.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(root.width - 4f, root.height / 2)) }
        rule.waitForIdle()
        assertEquals(0, window!!.attributes.flags and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        assertEquals(originalSoftInput, window!!.attributes.softInputMode)
        output("drawer-window-policy.txt", "Material ModalNavigationDrawer bounds before/IME420=$before\nALT_FOCUSABLE_IM + ADJUST_NOTHING while open, flags restored after native outside dismissal\n")
    }
    @Test fun bridgeUpdateRequiresTypedFlagAndExplicitConfirmation() {
        val impact = "共享 CLI 桥范围：Grok Build。\n本 App 当前已连接且执行中：2 个会话（Grok Build）。"
        var view by mutableStateOf(sampleView().copy(cliServiceUpdateRequired = false, cliServiceUpdateImpact = impact))
        var restarts = 0
        var reconnects = 0
        val target = object : CockpitActions by previewActions {
            override fun restartCliBridge() { restarts++ }
            override fun reconnect() { reconnects++ }
        }
        val actions = CurrentSessionActions(target) { true }
        rule.setContent { MaterialTheme {
            ConversationHeader(view, actions, {}, {}, previewMore = true)
        } }
        rule.onNodeWithText("更新CLI桥服务").assertDoesNotExist()
        rule.runOnIdle { view = view.copy(cliServiceUpdateRequired = true) }
        rule.onNodeWithText("更新CLI桥服务").performClick()
        rule.onNodeWithText(NativeCliRuntimePolicy.restartWarning).assertExists()
        rule.onNodeWithText(impact).assertExists()
        rule.onNodeWithText("取消").performClick()
        assertEquals(0, restarts)
        assertEquals(0, reconnects)
        rule.onNodeWithContentDescription("更多").performClick()
        rule.onNodeWithText("更新CLI桥服务").performClick()
        rule.onNodeWithText("停止并更新CLI桥").performClick()
        assertEquals(1, restarts)
        assertEquals(0, reconnects)
        rule.runOnIdle { view = view.copy(cliServiceUpdateRequired = false, cliServiceUpdateAvailable = true) }
        rule.onNodeWithContentDescription("更多").performClick()
        rule.onNodeWithText("更新CLI桥服务").performClick()
        rule.onNodeWithText("可选更新：支持后台待发消息返回输入框编辑。").assertExists()
        rule.onNodeWithText(NativeCliRuntimePolicy.restartWarning).assertExists()
        rule.onNodeWithText(impact).assertExists()
        rule.onNodeWithText("取消").performClick()
        assertEquals(1, restarts)
        rule.onNodeWithContentDescription("更多").performClick()
        rule.onNodeWithText("更新CLI桥服务").performClick()
        rule.onNodeWithText("停止并更新CLI桥").performClick()
        assertEquals(2, restarts)
        rule.onNodeWithContentDescription("会话 Dashboard").performClick()
        val beforeRefresh = reconnects
        rule.onNodeWithText("刷新").performClick()
        assertEquals(beforeRefresh + 1, reconnects)
        assertEquals(2, restarts)
        output("bridge-update-confirmation.txt", "required/optional typed flags; cancel=0 new actions; each explicit confirmation reaches CurrentSessionActions once; ordinary refresh=reconnect only\n")
    }

}
