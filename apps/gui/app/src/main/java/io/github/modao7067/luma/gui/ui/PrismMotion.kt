// Adapted from chen08209/prism_ui under GPL-3.0. See apps/gui/third-party/prism-ui/.
package io.github.modao7067.luma.gui.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

internal class SpringCurve(stiffness: Float, private val dampingRatio: Float, private val from: Float = 0f, private val to: Float = 1f) {
    private val omega = sqrt(stiffness.toDouble())
    private val zeta = dampingRatio.toDouble()

    fun x(time: Float): Float {
        if (time <= 0f) return from
        val t = time.toDouble()
        val c = (from - to).toDouble()
        val value = when {
            zeta < 1.0 -> {
                val damped = omega * sqrt(1.0 - zeta * zeta)
                exp(-zeta * omega * t) * (c * cos(damped * t) + (zeta * omega * c / damped) * sin(damped * t))
            }
            zeta == 1.0 -> (c + omega * c * t) * exp(-omega * t)
            else -> {
                val root = omega * sqrt(zeta * zeta - 1.0)
                val r1 = -zeta * omega + root
                val r2 = -zeta * omega - root
                val c2 = r1 * c / (r1 - r2)
                (c - c2) * exp(r1 * t) + c2 * exp(r2 * t)
            }
        }
        return (to + value).toFloat()
    }

    companion object {
        fun durationBounce(millis: Int, bounce: Float, from: Float = 0f, to: Float = 1f): SpringCurve {
            val seconds = millis / 1000f
            val stiffness = (2f * PI.toFloat() / seconds).pow(2)
            return SpringCurve(stiffness, 1f - bounce, from, to)
        }
    }
}

internal object PrismCurves {
    val EaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)
    val EaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
    val EaseOutCubic = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)
    val EaseInCubic = CubicBezierEasing(0.32f, 0f, 0.67f, 0f)
    val StandardDecelerate = CubicBezierEasing(0f, 0f, 0f, 1f)
    const val COLLAPSIBLE_DURATION_MS = 240

    fun sideSheetEnter(): TweenSpec<Float> = tween(300, easing = StandardDecelerate)
    fun sideSheetExit(): TweenSpec<Float> = tween(200, easing = StandardDecelerate)
    fun fadeTab(): TweenSpec<Float> = tween(220, easing = EaseInOut)
}

internal object PrismSprings {
    const val PAGE_PUSH_LAUNCH_SPEED = 9f
    const val ALERT_ENTRY_SCALE = 1.2f

    fun press(): SpringSpec<Float> = spring(dampingRatio = 0.593f, stiffness = 311f, visibilityThreshold = 0.0005f)
    fun pressPx(): SpringSpec<Float> = spring(dampingRatio = 0.593f, stiffness = 311f, visibilityThreshold = 0.05f)
    fun track(): SpringSpec<Float> = durationBounce(120, 0f, 0.05f)
    fun settle(): SpringSpec<Float> = durationBounce(500, 0.32f)
    fun pagePush(): SpringSpec<Float> = durationBounce(300, 0f, 0.001f)
    fun pagePushOffset(): SpringSpec<IntOffset> = spring(dampingRatio = 1f, stiffness = (2f * PI.toFloat() / 0.3f).pow(2), visibilityThreshold = IntOffset(1, 1))
    fun dragBackRelease(): SpringSpec<Float> = durationBounce(300, 0.15f, 0.001f)
    fun sheet(threshold: Float = 0.001f): SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 1000f / 3f, visibilityThreshold = threshold)
    fun sheetPx(): SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 1000f / 3f, visibilityThreshold = 0.5f)
    fun title(threshold: Float = 0.001f): SpringSpec<Float> = durationBounce(330, 0f, threshold)
    fun unfold(threshold: Float = 0.001f): SpringSpec<Float> = durationBounce(420, 0.15f, threshold)
    fun fold(threshold: Float = 0.001f): SpringSpec<Float> = durationBounce(340, 0f, threshold)
    fun unfoldSize(): SpringSpec<IntSize> = spring(dampingRatio = 0.85f, stiffness = (2f * PI.toFloat() / 0.42f).pow(2), visibilityThreshold = IntSize(1, 1))
    fun foldSize(): SpringSpec<IntSize> = spring(dampingRatio = 1f, stiffness = (2f * PI.toFloat() / 0.34f).pow(2), visibilityThreshold = IntSize(1, 1))
    fun alert(): SpringSpec<Float> = durationBounce(275, 0f, 0.001f)
    fun durationBounce(millis: Int, bounce: Float, threshold: Float = 0.0005f): SpringSpec<Float> {
        val seconds = millis / 1000f
        return spring(dampingRatio = 1f - bounce, stiffness = (2f * PI.toFloat() / seconds).pow(2), visibilityThreshold = threshold)
    }
}

internal suspend fun Animatable<Float, AnimationVector1D>.animatePagePush(
    target: Float,
    launchVelocity: Float? = null,
) {
    if (!isRunning && abs(target - value) <= 0.001f) {
        snapTo(target)
        return
    }
    animateTo(
        target,
        PrismSprings.pagePush(),
        initialVelocity = launchVelocity ?: if (isRunning) velocity else (target - value) * PrismSprings.PAGE_PUSH_LAUNCH_SPEED,
    )
}

internal object MaterialStandardMotion {
    private const val DEFAULT_SPATIAL_DAMPING = 0.9f
    private const val DEFAULT_SPATIAL_STIFFNESS = 700f
    private const val DEFAULT_EFFECTS_DAMPING = 1f
    private const val DEFAULT_EFFECTS_STIFFNESS = 1600f
    private const val FAST_EFFECTS_DAMPING = 1f
    private const val FAST_EFFECTS_STIFFNESS = 3800f

    fun <T> defaultSpatialSpec(visibilityThreshold: T? = null): SpringSpec<T> =
        spring(
            dampingRatio = DEFAULT_SPATIAL_DAMPING,
            stiffness = DEFAULT_SPATIAL_STIFFNESS,
            visibilityThreshold = visibilityThreshold,
        )

    fun <T> defaultEffectsSpec(visibilityThreshold: T? = null): SpringSpec<T> =
        spring(
            dampingRatio = DEFAULT_EFFECTS_DAMPING,
            stiffness = DEFAULT_EFFECTS_STIFFNESS,
            visibilityThreshold = visibilityThreshold,
        )

    fun <T> fastEffectsSpec(visibilityThreshold: T? = null): SpringSpec<T> =
        spring(
            dampingRatio = FAST_EFFECTS_DAMPING,
            stiffness = FAST_EFFECTS_STIFFNESS,
            visibilityThreshold = visibilityThreshold,
        )
}

internal fun collapsibleEnter(expandFrom: Alignment.Vertical = Alignment.Bottom): EnterTransition =
    expandVertically(
        animationSpec = MaterialStandardMotion.defaultSpatialSpec(IntSize(1, 1)),
        expandFrom = expandFrom,
    ) + fadeIn(MaterialStandardMotion.defaultEffectsSpec())

internal fun collapsibleExit(shrinkTowards: Alignment.Vertical = Alignment.Bottom): ExitTransition =
    shrinkVertically(
        animationSpec = MaterialStandardMotion.defaultSpatialSpec(IntSize(1, 1)),
        shrinkTowards = shrinkTowards,
    ) + fadeOut(MaterialStandardMotion.fastEffectsSpec())

internal fun menuUnfoldEnter(): EnterTransition =
    expandVertically(PrismSprings.unfoldSize()) + fadeIn(tween(240, easing = PrismCurves.EaseOutCubic))

internal fun menuFoldExit(): ExitTransition =
    shrinkVertically(PrismSprings.foldSize()) + fadeOut(tween(180, easing = PrismCurves.EaseInCubic))

@Stable
internal class ContinuousSpring(
    private val scope: CoroutineScope,
    initial: Float = 0f,
    private val rest: Float = 1e-3f,
) {
    var value by mutableFloatStateOf(initial)
        private set

    private var target = initial
    private var activeSpec: SpringSpec<Float>? = null
    private var anim: TargetBasedAnimation<Float, AnimationVector1D>? = null
    private var startNanos = Long.MIN_VALUE
    private var nowNanos = Long.MIN_VALUE
    private var playTimeNanos = 0L
    private var job: Job? = null

    val velocity: Float
        get() {
            val current = anim ?: return 0f
            return current.getVelocityVectorFromNanos(playTimeNanos).value
        }

    fun springTo(nextTarget: Float, spec: SpringSpec<Float>) {
        if (anim == null && abs(nextTarget - value) <= rest) {
            value = nextTarget
            target = nextTarget
            return
        }
        if (anim != null && abs(nextTarget - target) <= rest * 0.1f && spec == activeSpec) {
            return
        }
        val v = velocity
        target = nextTarget
        activeSpec = spec
        anim = TargetBasedAnimation(spec, Float.VectorConverter, value, nextTarget, v)
        playTimeNanos = 0L
        if (job?.isActive == true) {
            if (nowNanos != Long.MIN_VALUE) startNanos = nowNanos
            return
        }
        startNanos = Long.MIN_VALUE
        nowNanos = Long.MIN_VALUE
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val scale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
            if (scale <= 0f) {
                value = target
                anim = null
                activeSpec = null
                playTimeNanos = 0L
                if (job === coroutineContext[Job]) job = null
                return@launch
            }
            try {
                while (anim != null) {
                    val done = withFrameNanos { frameNanos ->
                        val active = anim ?: return@withFrameNanos true
                        val liveScale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
                        if (liveScale <= 0f) {
                            value = target
                            anim = null
                            activeSpec = null
                            playTimeNanos = 0L
                            return@withFrameNanos true
                        }
                        nowNanos = frameNanos
                        if (startNanos == Long.MIN_VALUE) startNanos = frameNanos
                        val elapsed = ((frameNanos - startNanos) / liveScale).toLong().coerceAtLeast(0L)
                        if (active.isFinishedFromNanos(elapsed)) {
                            value = target
                            anim = null
                            activeSpec = null
                            playTimeNanos = 0L
                            true
                        } else {
                            playTimeNanos = elapsed
                            value = active.getValueFromNanos(elapsed)
                            false
                        }
                    }
                    if (done) break
                }
            } finally {
                if (anim == null) {
                    activeSpec = null
                    playTimeNanos = 0L
                }
                if (job === coroutineContext[Job]) job = null
            }
        }
    }
}

internal fun riseAndFall(time: Float, peak: Float, sharpness: Float): Float {
    if (time <= 0f) return 0f
    val x = time / peak
    return x.pow(sharpness) * exp(sharpness * (1f - x))
}

internal fun rubberBand(overshoot: Float, limit: Float): Float {
    if (limit <= 0f) return 0f
    val pull = 1f - 1f / (abs(overshoot) * 0.55f / limit + 1f)
    return limit * pull * sign(overshoot)
}

internal fun lerpFloat(a: Float, b: Float, t: Float) = a + (b - a) * t

private val pulseUp = SpringCurve.durationBounce(292, 0.5f)
private val pulseDownAcross = SpringCurve.durationBounce(416, 0.5f)
private val pulseDownDown = SpringCurve.durationBounce(416, 0.416f)
private const val PULSE_ACROSS_LAG = 0.142f
private const val PULSE_DOWN_LAG = 0.113f
private const val PULSE_SWELL = 0.2f
private const val PULSE_SECONDS = 0.9f

internal fun glyphPulseAt(time: Float): Pair<Float, Float> {
    val up = pulseUp.x(time)
    val across = 1f + PULSE_SWELL * (up - pulseDownAcross.x(time - PULSE_ACROSS_LAG))
    val down = 1f + PULSE_SWELL * (up - pulseDownDown.x(time - PULSE_DOWN_LAG))
    return across to down
}

@Composable
internal fun Modifier.glyphPulse(key: Any?): Modifier {
    val clock = remember { Animatable(PULSE_SECONDS) }
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(key) {
        if (first[0]) {
            first[0] = false
            return@LaunchedEffect
        }
        clock.snapTo(0f)
        clock.animateTo(PULSE_SECONDS, tween((PULSE_SECONDS * 1000).toInt(), easing = LinearEasing))
    }
    return graphicsLayer {
        val t = clock.value
        if (t < PULSE_SECONDS) {
            val (across, down) = glyphPulseAt(t)
            scaleX = across
            scaleY = down
        }
    }
}
