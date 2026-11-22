package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.i18n.tr

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.cos
import kotlin.math.sin

internal data class ProcessRowPalette(
    val dark: Boolean,
    val emphasis: Color,
    val activeGroupTitle: Color,
    val text: Color,
    val separator: Color,
    val iconTint: Color,
    val activeDot: Color,
    val completedDot: Color,
    val shimmerPeak: Color,
)

internal fun processRowPalette(colors: ColorScheme): ProcessRowPalette {
    val dark = codePaletteDark(colors)
    val emphasis = if (dark) Color(0xFFD4D3CE) else Color(0xFF474642)
    return ProcessRowPalette(
        dark = dark,
        emphasis = emphasis,
        activeGroupTitle = if (dark) Color(0xFF95948E) else emphasis,
        text = if (dark) Color(0xFF8E8D87) else Color(0xFF87857F),
        separator = if (dark) Color(0xFF666560) else Color(0xFF9E9C95),
        iconTint = emphasis,
        activeDot = if (dark) Color(0xFFD97757) else Color(0xFFC6613F),
        completedDot = if (dark) Color(0xFF5E5D58) else Color(0xFFB4B2AB),
        shimmerPeak = if (dark) Color(0xE6FFFFFF) else Color(0x80FFFFFF),
    )
}

internal fun processSummaryText(
    parts: List<ProcessSummaryPart>,
    emphasisColor: Color? = null,
) = buildAnnotatedString {
    parts.forEachIndexed { index, part ->
        if (index > 0) append(" · ")
        val start = length
        append(part.text)
        if (part.emphasized && emphasisColor != null) addStyle(SpanStyle(color = emphasisColor), start, length)
    }
}

internal const val PROCESS_ROW_SHIMMER_DURATION_MS = 1500
internal const val PROCESS_GROUP_BREATH_DURATION_MS = 2200
internal const val PROCESS_GROUP_TITLE_SHIMMER_DURATION_MS = PROCESS_GROUP_BREATH_DURATION_MS

@Composable
internal fun rememberProcessBreathPhase(enabled: Boolean): State<Float> {
    val phase = remember { Animatable(0f) }
    LaunchedEffect(enabled) {
        val scale = coroutineContext[MotionDurationScale]
        snapshotFlow { (scale?.scaleFactor ?: 1f) > 0f }.collectLatest { motionEnabled ->
            phase.snapTo(0f)
            if (enabled && motionEnabled) {
                phase.animateTo(1f, infiniteRepeatable(tween(PROCESS_GROUP_BREATH_DURATION_MS, easing = LinearEasing)))
            }
        }
    }
    return phase.asState()
}

@Composable
internal fun ProcessGroupIndicator(
    active: Boolean,
    palette: ProcessRowPalette,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = LocalView.current
    val inspection = LocalInspectionMode.current
    val preview = LocalProcessDotsPhase.current
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var visible by remember { mutableStateOf(false) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val phaseState = rememberProcessBreathPhase(active && resumed && visible && !inspection && preview == null)
    Canvas(
        Modifier
            .size(14.dp)
            .onGloballyPositioned {
                val bounds = it.boundsInWindow()
                visible = !bounds.isEmpty && bounds.right > 0 && bounds.bottom > 0 && bounds.left < view.width && bounds.top < view.height
            },
    ) {
        val c = Offset(size.width / 2f, size.height / 2f)
        if (active) {
            val phase = (preview ?: phaseState.value).coerceIn(0f, 1f)
            val wave = (0.5 - 0.5 * cos(phase * 2.0 * Math.PI)).toFloat()
            val maxRadius = (size.minDimension / 2f).coerceAtLeast(0f)
            val coreRadius = minOf(2.75.dp.toPx(), maxRadius)
            val bloomRadius = minOf((4.4f + 2.6f * wave).dp.toPx(), maxRadius)
            val haloAlpha = if (palette.dark) 0.16f + 0.42f * wave else 0.14f + 0.34f * wave
            if (bloomRadius > coreRadius && bloomRadius > 0f) {
                val edge = (coreRadius / bloomRadius).coerceIn(0f, 0.9f)
                val span = 1f - edge
                drawCircle(
                    brush = Brush.radialGradient(
                        0.0f to palette.activeDot.copy(alpha = haloAlpha),
                        edge to palette.activeDot.copy(alpha = haloAlpha),
                        edge + span * 0.28f to palette.activeDot.copy(alpha = haloAlpha * 0.56f),
                        edge + span * 0.58f to palette.activeDot.copy(alpha = haloAlpha * 0.18f),
                        edge + span * 0.82f to palette.activeDot.copy(alpha = haloAlpha * 0.04f),
                        1.0f to Color.Transparent,
                        center = c,
                        radius = bloomRadius,
                    ),
                    radius = bloomRadius,
                    center = c,
                )
            }
            drawCircle(palette.activeDot, radius = coreRadius, center = c)
        } else {
            drawCircle(palette.completedDot, radius = 2.5.dp.toPx(), center = c)
        }
    }
}

@Composable
internal fun rememberProcessShimmerPhase(
    enabled: Boolean,
    durationMillis: Int = PROCESS_ROW_SHIMMER_DURATION_MS,
): State<Float> {
    val phase = remember { Animatable(0f) }
    LaunchedEffect(enabled, durationMillis) {
        val scale = coroutineContext[MotionDurationScale]
        snapshotFlow { (scale?.scaleFactor ?: 1f) > 0f }.collectLatest { motionEnabled ->
            phase.snapTo(0f)
            if (enabled && motionEnabled) phase.animateTo(1f, infiniteRepeatable(tween(durationMillis, easing = LinearEasing)))
        }
    }
    return phase.asState()
}

@Composable
internal fun Modifier.processRowShimmer(
    active: Boolean,
    shimmerPeak: Color,
    durationMillis: Int = PROCESS_ROW_SHIMMER_DURATION_MS,
    minBandWidth: Dp = 96.dp,
): Modifier {
    if (!active) return this
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = LocalView.current
    val inspection = LocalInspectionMode.current
    val preview = LocalProcessDotsPhase.current
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var visible by remember { mutableStateOf(false) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val phase = rememberProcessShimmerPhase(resumed && visible && !inspection && preview == null, durationMillis = durationMillis)
    return this
        .onGloballyPositioned {
            val bounds = it.boundsInWindow()
            visible = !bounds.isEmpty && bounds.right > 0 && bounds.bottom > 0 && bounds.left < view.width && bounds.top < view.height
        }
        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        .drawWithContent {
            drawContent()
            val rawPhase = (preview ?: phase.value).coerceIn(0f, 1f)
            if (rawPhase >= 0.6667f || size.width <= 1f || size.height <= 1f) return@drawWithContent
            val t = (rawPhase / 0.6667f).coerceIn(0f, 1f)
            val bandW = maxOf(size.width * 0.6f, minBandWidth.toPx())
            val startX = (-1.1f * bandW) + (size.width + 1.1f * bandW + 0.3f * size.height) * t
            val shoulder = shimmerPeak.copy(alpha = shimmerPeak.alpha * 0.28f)
            val brush = Brush.linearGradient(
                0.0f to Color.Transparent,
                0.25f to shoulder,
                0.5f to shimmerPeak,
                0.75f to shoulder,
                1.0f to Color.Transparent,
                start = Offset(startX, 0f),
                end = Offset(startX + bandW, bandW * 0.268f),
            )
            drawRect(brush = brush, blendMode = BlendMode.SrcAtop)
        }
}

internal val LocalProcessDotsPhase = staticCompositionLocalOf<Float?> { null }
internal fun processDotLift(phase: Float, dot: Int): Float {
    val local = phase - dot * .13f
    return if (local in 0f..0.42f) sin(local / .42f * Math.PI).toFloat() else 0f
}

@Composable
internal fun ProcessStatus(outcome: String, modifier: Modifier = Modifier) {
    val description = when (outcome) {
        "running" -> tr("执行中", "Running"); "succeeded" -> tr("已完成", "Done"); "failed" -> tr("失败", "Failed"); "cancelled" -> tr("已取消", "Cancelled"); "closed" -> tr("已关闭", "Closed"); "pending" -> tr("等待中", "Pending"); else -> tr("待确认", "Pending")
    }
    Box(modifier.size(24.dp, 16.dp).semantics { stateDescription = description }, contentAlignment = Alignment.Center) {
        when (outcome) {
            "running" -> RunningDots()
            "succeeded" -> MenuIcon(MenuSymbol.CHECK, size = 14.dp)
            "failed" -> MenuIcon(MenuSymbol.WARNING, size = 14.dp)
            "cancelled", "closed" -> MenuIcon(MenuSymbol.STOP, size = 12.dp)
        }
    }
}

internal data class ProcessDotsMotion(val phase: State<Float>, val position: Modifier)

@Composable
internal fun rememberProcessDotsPhase(enabled: Boolean): State<Float> {
    val phase = remember { Animatable(0f) }
    LaunchedEffect(enabled) {
        val scale = coroutineContext[MotionDurationScale]
        snapshotFlow { (scale?.scaleFactor ?: 1f) > 0f }.collectLatest { motionEnabled ->
            phase.snapTo(0f)
            if (enabled && motionEnabled) phase.animateTo(1f, infiniteRepeatable(tween(1100, easing = LinearEasing)))
        }
    }
    return phase.asState()
}

@Composable
internal fun rememberProcessDotsMotion(): ProcessDotsMotion {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = LocalView.current
    val inspection = LocalInspectionMode.current
    val preview = LocalProcessDotsPhase.current
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var visible by remember { mutableStateOf(false) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val phase = rememberProcessDotsPhase(resumed && visible && !inspection && preview == null)
    return ProcessDotsMotion(phase, Modifier.onGloballyPositioned {
        val bounds = it.boundsInWindow()
        visible = !bounds.isEmpty && bounds.right > 0 && bounds.bottom > 0 && bounds.left < view.width && bounds.top < view.height
    })
}

@Composable
private fun RunningDots() {
    val motion = rememberProcessDotsMotion()
    val preview = LocalProcessDotsPhase.current
    val color = LocalContentColor.current
    Canvas(Modifier.size(24.dp, 16.dp).then(motion.position)) {
        val value = preview ?: motion.phase.value
        repeat(3) { dot ->
            drawCircle(color, radius = 1.4.dp.toPx(), center = Offset((6 + dot * 6).dp.toPx(), 9.dp.toPx() - processDotLift(value, dot) * 2.5.dp.toPx()))
        }
    }
}
