// Adapted from chen08209/prism_ui under GPL-3.0. See apps/gui/third-party/prism-ui/.
package io.github.modao7067.luma.gui.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private const val PRESS_GROWTH = 0.25f
private const val LIFT_SNAP = 0.975f
private const val PULL_LIMIT = 7f / 32f
private const val PULL_STRETCH = 0.5f
private const val HOVER_SWELL = 0.4f
private const val GLOW_IN_MS = 120
private const val GLOW_OUT_MS = 360
private const val GLOW_SPREAD = 3f
private const val GLOW_STRENGTH = 0.16f

@Stable
internal class ElasticPressState(private val scope: CoroutineScope) {
    private val lift = ContinuousSpring(scope, 0f, rest = 0.0005f)
    private val hover = ContinuousSpring(scope, 0f, rest = 0.0005f)
    private val pullX = ContinuousSpring(scope, 0f, rest = 0.05f)
    private val pullY = ContinuousSpring(scope, 0f, rest = 0.05f)
    private val glow = Animatable(0f)
    private var origin = Offset.Zero
    private var touchX by mutableFloatStateOf(Float.NaN)
    private var touchY by mutableFloatStateOf(Float.NaN)
    private var glowFading by mutableStateOf(false)
    private var swelling = false
    private var settleJob: Job? = null
    private var glowJob: Job? = null
    private var hovers = 0
    internal var panel: Size = Size.Zero

    val swell: Float get() = lift.value + (1f - lift.value) * hover.value
    val pull: Offset get() = Offset(pullX.value, pullY.value)
    val glowStrength: Float get() = glow.value * GLOW_STRENGTH

    fun glowCenter(size: Size): Offset =
        if (touchX.isNaN() || touchY.isNaN()) Offset(size.width * 0.5f, size.height * 0.5f) else Offset(touchX, touchY)

    fun glowReach(size: Size): Float =
        (size.height / sqrt(2f)) * (1f + if (glowFading) GLOW_SPREAD * (1f - glow.value) else 0f)

    fun press(position: Offset) {
        origin = position
        touchX = position.x
        touchY = position.y
        glowFading = false
        swelling = true
        settleJob?.cancel()
        settleJob = null
        glowJob?.cancel()
        glowJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            glow.animateTo(1f, tween(GLOW_IN_MS, easing = PrismCurves.EaseOut))
        }
        lift.springTo(1f, PrismSprings.press())
    }

    fun drag(position: Offset, pastEdges: Boolean = false) {
        touchX = position.x
        touchY = position.y
        val p = panel
        if (p.width <= 0f || p.height <= 0f) return
        val limit = min(p.width, p.height) * PULL_LIMIT
        val pull = if (pastEdges) {
            position - Offset(position.x.coerceIn(0f, p.width), position.y.coerceIn(0f, p.height))
        } else {
            position - origin
        }
        pullX.springTo(rubberBand(pull.x, limit), PrismSprings.track())
        pullY.springTo(rubberBand(pull.y, limit), PrismSprings.track())
    }

    fun release() {
        glowFading = true
        glowJob?.cancel()
        glowJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            glow.animateTo(0f, tween(GLOW_OUT_MS, easing = PrismCurves.EaseOut))
            if (glow.value <= 0f) {
                touchX = Float.NaN
                touchY = Float.NaN
            }
        }
        pullX.springTo(0f, PrismSprings.pressPx())
        pullY.springTo(0f, PrismSprings.pressPx())
        settleJob?.cancel()
        val waitForSwell = swelling && lift.value < LIFT_SNAP
        settleJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (waitForSwell) snapshotFlow { lift.value }.first { it >= LIFT_SNAP }
            swelling = false
            lift.springTo(0f, PrismSprings.press())
        }
    }

    fun hover(entered: Boolean) {
        hovers = max(0, hovers + if (entered) 1 else -1)
        val target = if (hovers > 0) HOVER_SWELL else 0f
        hover.springTo(target, PrismSprings.settle())
    }
}

@Composable
internal fun rememberElasticPress(): ElasticPressState {
    val scope = rememberCoroutineScope()
    return remember(scope) { ElasticPressState(scope) }
}

internal fun elasticGrowth(longestSide: Float, maxGrowthPx: Float): Float =
    if (longestSide <= 0f) 0f else min(PRESS_GROWTH, maxGrowthPx / longestSide)

internal fun GraphicsLayerScope.applyElastic(state: ElasticPressState, maxGrowthPx: Float, panel: Size = size) {
    val w = panel.width
    val h = panel.height
    if (w <= 0f || h <= 0f) return
    val lift = state.swell
    val pull = state.pull
    if (lift == 0f && pull == Offset.Zero) return
    val swell = 1f + lift * elasticGrowth(max(w, h), maxGrowthPx)
    scaleX = swell * (1f + abs(pull.x) / w * PULL_STRETCH)
    scaleY = swell * (1f + abs(pull.y) / h * PULL_STRETCH)
    translationX = pull.x
    translationY = pull.y
}

internal suspend fun PointerInputScope.observeElastic(
    state: ElasticPressState,
    enabled: () -> Boolean,
    stretch: Boolean,
    pastEdges: Boolean = false,
) {
    awaitPointerEventScope {
        var pointer: PointerId? = null
        var hovered = false
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val mouse = event.changes.firstOrNull()?.type == PointerType.Mouse
                when (event.type) {
                    PointerEventType.Enter -> if (mouse && !hovered) {
                        hovered = true
                        state.hover(true)
                    }
                    PointerEventType.Exit -> if (hovered) {
                        hovered = false
                        state.hover(false)
                    }
                    else -> {
                        val active = pointer
                        if (active == null) {
                            val down = event.changes.firstOrNull { it.changedToDownIgnoreConsumed() }
                            if (down != null && enabled() && (down.type != PointerType.Mouse || event.buttons.isPrimaryPressed)) {
                                pointer = down.id
                                state.press(down.position)
                            }
                        } else {
                            val change = event.changes.firstOrNull { it.id == active }
                            if (change == null || !change.pressed) {
                                pointer = null
                                state.release()
                            } else if (stretch && change.position != change.previousPosition) {
                                state.drag(change.position, pastEdges)
                            }
                        }
                    }
                }
            }
        } finally {
            if (hovered) state.hover(false)
            if (pointer != null) state.release()
        }
    }
}

@Composable
internal fun Modifier.elasticPress(
    enabled: Boolean = true,
    stretch: Boolean = true,
    state: ElasticPressState = rememberElasticPress(),
    maxGrowth: Dp = 16.dp,
): Modifier {
    val maxGrowthPx = with(LocalDensity.current) { maxGrowth.toPx() }
    val latestEnabled by rememberUpdatedState(enabled)
    return this
        .onSizeChanged { state.panel = it.toSize() }
        .pointerInput(state, stretch) { observeElastic(state, { latestEnabled }, stretch) }
        .graphicsLayer { applyElastic(state, maxGrowthPx) }
}

internal val LocalDrawerOpen = compositionLocalOf { false }

@Composable
internal fun Modifier.blockHorizontalPageSwipe(
    enabled: Boolean = true,
    allowVerticalScroll: Boolean = false,
): Modifier {
    if (!enabled) return this
    val drawerOpen by rememberUpdatedState(LocalDrawerOpen.current)
    return pointerInput(allowVerticalScroll) {
        val threshold = viewConfiguration.touchSlop * 0.4f
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (drawerOpen) return@awaitEachGesture
            var locked = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                val delta = change.position - down.position
                val dx = abs(delta.x)
                val dy = abs(delta.y)
                if (!locked) {
                    if (allowVerticalScroll && dy > threshold && dy > dx) break
                    if (dx > threshold || (!allowVerticalScroll && dy > threshold)) locked = true
                }
                if (locked && !change.isConsumed) change.consume()
            }
        }
    }
}
