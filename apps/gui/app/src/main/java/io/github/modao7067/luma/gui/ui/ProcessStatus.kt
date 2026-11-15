package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.i18n.tr

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.sin

internal fun processSummaryText(parts: List<ProcessSummaryPart>) = buildAnnotatedString {
    parts.forEachIndexed { index, part ->
        if (index > 0) append(" · ")
        append(part.prefix)
        val start = length
        append(part.label)
        addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), start, length)
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
