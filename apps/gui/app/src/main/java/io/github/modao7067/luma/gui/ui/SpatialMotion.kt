package io.github.modao7067.luma.gui.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

private const val REVEAL_TOE = 0.001f
private val TITLE_SPRING_STIFFNESS = (2f * PI.toFloat() / 0.33f).pow(2)
private val ClaudeSlowSettleEasing = CubicBezierEasing(0.165f, 0.84f, 0.44f, 1f)

internal val LocalTurnLayoutReaders = compositionLocalOf<MutableSet<() -> Any?>?> { null }
internal val LocalTurnAnimatingReaders = compositionLocalOf<MutableSet<() -> Boolean>?> { null }

internal fun spatialSpring(): SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = TITLE_SPRING_STIFFNESS, visibilityThreshold = .001f)
internal fun spatialPxSpring(): SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = TITLE_SPRING_STIFFNESS, visibilityThreshold = 0.5f)
internal fun revealSpring(): SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = TITLE_SPRING_STIFFNESS, visibilityThreshold = REVEAL_TOE)
internal fun revealPxSpring(): SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = TITLE_SPRING_STIFFNESS, visibilityThreshold = 0.5f)
internal fun slowSettleTween(): TweenSpec<Float> = tween(durationMillis = 450, easing = ClaudeSlowSettleEasing)
internal fun revealFraction(raw: Float): Float = when {
    raw <= REVEAL_TOE -> 0f
    raw >= 1f - REVEAL_TOE -> 1f
    else -> ((raw - REVEAL_TOE) / (1f - REVEAL_TOE * 2f)).coerceIn(0f, 1f)
}
internal fun spatialSizeSpring(): SpringSpec<IntSize> = spring(dampingRatio = 1f, stiffness = TITLE_SPRING_STIFFNESS, visibilityThreshold = IntSize(1, 1))
internal fun spatialOffsetSpring(): SpringSpec<IntOffset> = spring(dampingRatio = 1f, stiffness = TITLE_SPRING_STIFFNESS, visibilityThreshold = IntOffset(1, 1))
internal fun spatialDpSpring(): SpringSpec<androidx.compose.ui.unit.Dp> = spring(dampingRatio = 1f, stiffness = TITLE_SPRING_STIFFNESS)

internal fun composerControlsFraction(fraction: Float) = (fraction / .6f).coerceIn(0f, 1f)
internal fun composerTextWidthFraction(fraction: Float) = ((fraction - .6f) / .4f).coerceIn(0f, 1f)
internal fun composerToolsInteractive(expanded: Boolean, fraction: Float) = expanded && composerTextWidthFraction(fraction) >= .5f
internal fun spatialContentVisible(expanded: Boolean, fraction: Float) = expanded || fraction > 0f
internal fun spatialContentInteractive(expanded: Boolean, alpha: Float) = expanded && alpha >= .5f

@Composable
internal fun TopReveal(
    expanded: Boolean,
    previewFraction: Float? = null,
    collapsedContent: (@Composable () -> Unit)? = null,
    fractionProvider: (() -> Float)? = null,
    keepDetailComposed: Boolean = false,
    content: @Composable () -> Unit,
) {
    var retainedCollapsed by remember { mutableStateOf(collapsedContent) }
    if (collapsedContent != null) retainedCollapsed = collapsedContent
    var detailEverVisible by remember {
        mutableStateOf(expanded || (previewFraction ?: 0f) > 0f)
    }
    if (expanded) detailEverVisible = true
    val animatedState = if (previewFraction == null && fractionProvider == null) {
        animateFloatAsState(if (expanded) 1f else 0f, revealSpring(), label = "title-detail-reveal")
    } else null
    if (!detailEverVisible && !expanded && collapsedContent == null && retainedCollapsed == null && previewFraction == null && fractionProvider == null) {
        return
    }
    val currentFraction: () -> Float = remember(previewFraction, fractionProvider, animatedState) {
        {
            (previewFraction ?: fractionProvider?.invoke() ?: revealFraction(animatedState?.value ?: 0f)).coerceIn(0f, 1f)
        }
    }
    var snapFirstExcerpt by remember { mutableStateOf(collapsedContent != null) }
    val excerptAnim = remember { Animatable(0f, Float.VectorConverter, 0.5f) }
    var excerptTargetPx by remember { mutableIntStateOf(0) }
    var excerptCurrentPx by remember { mutableFloatStateOf(0f) }
    val hasCollapsedNow = collapsedContent != null
    val excerptVisible = hasCollapsedNow || retainedCollapsed != null
    val latestCollapsed by rememberUpdatedState(collapsedContent)
    if (excerptVisible && previewFraction == null) {
        LaunchedEffect(Unit) {
            snapshotFlow { (latestCollapsed != null) to excerptTargetPx }
                .distinctUntilChanged()
                .collectLatest { (hasCollapsed, targetPx) ->
                    val targetFloat = if (hasCollapsed) targetPx.toFloat() else 0f
                    if (abs(excerptAnim.value - excerptCurrentPx) > 1f) {
                        excerptAnim.snapTo(excerptCurrentPx)
                    }
                    if (abs(excerptCurrentPx - targetFloat) > 0.5f) {
                        excerptAnim.animateTo(targetFloat, revealPxSpring()) {
                            excerptCurrentPx = value
                        }
                    }
                    excerptCurrentPx = targetFloat
                    if (!hasCollapsed && excerptCurrentPx <= 0.5f) {
                        retainedCollapsed = null
                    }
                }
        }
    }
    val composeDetail = if (keepDetailComposed && detailEverVisible) {
        true
    } else {
        val detailVisible by remember(expanded, previewFraction, fractionProvider) {
            derivedStateOf { spatialContentVisible(expanded, currentFraction()) }
        }
        if (detailVisible) detailEverVisible = true
        detailVisible
    }
    val turnReaders = LocalTurnLayoutReaders.current
    if (turnReaders != null && (composeDetail || excerptVisible)) {
        DisposableEffect(turnReaders, currentFraction) {
            val reader: () -> Any? = { currentFraction() + excerptCurrentPx }
            turnReaders.add(reader)
            onDispose { turnReaders.remove(reader) }
        }
    }
    val turnAnimReaders = LocalTurnAnimatingReaders.current
    if (turnAnimReaders != null && (composeDetail || excerptVisible)) {
        val latestExpanded by rememberUpdatedState(expanded)
        val latestPreview by rememberUpdatedState(previewFraction)
        DisposableEffect(turnAnimReaders, currentFraction) {
            val animReader: () -> Boolean = {
                if (latestPreview != null) {
                    false
                } else {
                    val targetF = if (latestExpanded) 1f else 0f
                    val mainAnimating = abs(currentFraction() - targetF) > REVEAL_TOE
                    val targetExcerpt = if (latestCollapsed != null) excerptTargetPx.toFloat() else 0f
                    val excerptAnimating = excerptVisible && abs(excerptCurrentPx - targetExcerpt) > 0.5f
                    mainAnimating || excerptAnimating
                }
            }
            turnAnimReaders.add(animReader)
            onDispose { turnAnimReaders.remove(animReader) }
        }
    }
    val slideRangePx = remember { IntArray(1) }
    if (composeDetail || excerptVisible) {
        Layout(
            content = {
                if (composeDetail) {
                    val detailInteractive = LocalSessionInteractive.current && expanded
                    Box(
                        Modifier
                            .layoutId("detail")
                            .graphicsLayer {
                                val f = currentFraction()
                                alpha = if (!excerptVisible) f else ((f - .25f) / .75f).coerceIn(0f, 1f)
                                translationY = -(1f - f) * slideRangePx[0]
                            }
                            .then(if (expanded) Modifier else Modifier.clearAndSetSemantics {}),
                    ) {
                        CompositionLocalProvider(LocalSessionInteractive provides detailInteractive) {
                            content()
                        }
                    }
                }
                val activeCollapsed = collapsedContent ?: retainedCollapsed
                if (activeCollapsed != null) {
                    Box(
                        Modifier
                            .layoutId("excerpt")
                            .graphicsLayer {
                                val f = currentFraction()
                                val collapseFade = if (!hasCollapsedNow && excerptTargetPx > 0 && previewFraction == null) {
                                    (excerptCurrentPx / excerptTargetPx.toFloat()).coerceIn(0f, 1f)
                                } else 1f
                                alpha = (1f - f / .25f).coerceIn(0f, 1f) * collapseFade
                            }
                            .then(if (expanded) Modifier.clearAndSetSemantics {} else Modifier),
                    ) { activeCollapsed() }
                }
            },
            modifier = Modifier.clipToBounds(),
        ) { children, constraints ->
            val loose = constraints.copy(minHeight = 0)
            val f = currentFraction()
            val measureDetail = expanded || f > 0f
            val child = if (measureDetail) children.firstOrNull { it.layoutId == "detail" }?.measure(loose) else null
            val excerpt = children.firstOrNull { it.layoutId == "excerpt" }?.measure(loose)
            val measuredExcerptH = excerpt?.height ?: 0
            if (hasCollapsedNow) {
                snapFirstExcerpt = false
                excerptTargetPx = measuredExcerptH
                excerptCurrentPx = measuredExcerptH.toFloat()
            }
            val collapsedHeight = if (previewFraction != null) {
                if (hasCollapsedNow) measuredExcerptH else 0
            } else {
                excerptCurrentPx.roundToInt().coerceIn(0, maxOf(measuredExcerptH, excerptTargetPx))
            }
            val detailHeight = child?.height ?: collapsedHeight
            val height = (collapsedHeight + (detailHeight - collapsedHeight) * f).roundToInt()
            val width = maxOf(child?.width ?: 0, excerpt?.width ?: 0, constraints.minWidth)
            val detailAlpha = if (!excerptVisible) f else ((f - .25f) / .75f).coerceIn(0f, 1f)
            val excerptAlpha = (1f - f / .25f).coerceIn(0f, 1f)
            val maxSlidePx = minOf((detailHeight - collapsedHeight).coerceAtLeast(0), 20.dp.roundToPx())
            slideRangePx[0] = maxSlidePx
            layout(width, constraints.constrainHeight(height)) {
                if (excerptAlpha > 0f && collapsedHeight > 0) excerpt?.placeRelative(0, 0)
                if (child != null && (detailAlpha > 0f || (keepDetailComposed && !excerptVisible))) {
                    child.placeRelative(0, 0)
                }
            }
        }
    }
}

@Composable
internal fun MenuSectionReveal(
    expanded: Boolean,
    containerColor: androidx.compose.ui.graphics.Color,
    cornerRadius: Dp = 16.dp,
    horizontalPadding: Dp = 10.dp,
    verticalPadding: Dp = 4.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val active = LocalMenuActive.current
    AnimatedVisibility(
        visible = expanded,
        modifier = Modifier.padding(horizontal = horizontalPadding),
        enter = menuUnfoldEnter(),
        exit = menuFoldExit(),
    ) {
        CompositionLocalProvider(LocalMenuActive provides (active && expanded)) {
            Column(Modifier.fillMaxWidth().clipToBounds().background(containerColor, RoundedCornerShape(cornerRadius)).padding(vertical = verticalPadding), content = content)
        }
    }
}

@Composable
internal fun SmoothTailGrowth(
    rowId: String,
    contentKey: Any,
    knownHeights: MutableMap<String, Int>,
    animateArrival: Boolean,
    animatePop: Boolean = animateArrival,
    topSpacing: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    if (LocalInspectionMode.current) {
        Box(Modifier.padding(top = topSpacing)) { content() }
        return
    }
    var initialized by remember(rowId) { mutableStateOf(false) }
    var lastContentKey by remember(rowId) { mutableStateOf(contentKey) }
    val isLivePop = remember(rowId) { animatePop && knownHeights[rowId] == null }
    val popScale = remember(rowId) { Animatable(if (isLivePop) 0.96f else 1f, Float.VectorConverter, 0.001f) }
    val popAlpha = remember(rowId) { Animatable(if (isLivePop) 0f else 1f, Float.VectorConverter, 0.001f) }
    var targetPx by remember(rowId) { mutableIntStateOf(knownHeights[rowId] ?: if (animateArrival) 0 else -1) }
    var currentPx by remember(rowId) { mutableFloatStateOf((knownHeights[rowId] ?: 0).toFloat()) }
    val anim = remember(rowId) { Animatable(currentPx, Float.VectorConverter, 0.5f) }
    val turnReaders = LocalTurnLayoutReaders.current
    val parentAnimReaders = LocalTurnAnimatingReaders.current
    val localAnimReaders = remember(rowId) { LinkedHashSet<() -> Boolean>() }
    if (parentAnimReaders != null) {
        DisposableEffect(parentAnimReaders, localAnimReaders) {
            val forwarder: () -> Boolean = { localAnimReaders.any { it.invoke() } }
            parentAnimReaders.add(forwarder)
            onDispose { parentAnimReaders.remove(forwarder) }
        }
    }
    if (turnReaders != null) {
        DisposableEffect(turnReaders, rowId) {
            val reader: () -> Any? = { currentPx }
            turnReaders.add(reader)
            onDispose { turnReaders.remove(reader) }
        }
    }
    LaunchedEffect(rowId, isLivePop) {
        if (isLivePop) {
            launch { popAlpha.animateTo(1f, spatialSpring()) }
            launch { popScale.animateTo(1f, spatialSpring()) }
        }
    }
    LaunchedEffect(rowId) {
        snapshotFlow { targetPx }
            .distinctUntilChanged()
            .collectLatest { target ->
                if (target < 0) return@collectLatest
                val goal = target.toFloat()
                if (abs(anim.value - currentPx) > 1f) {
                    anim.snapTo(currentPx)
                }
                if (abs(currentPx - goal) > 0.5f) {
                    anim.animateTo(goal, spatialPxSpring()) {
                        currentPx = value
                        knownHeights[rowId] = value.roundToInt()
                    }
                }
                currentPx = goal
                knownHeights[rowId] = target
            }
    }
    Layout(
        content = {
            CompositionLocalProvider(LocalTurnAnimatingReaders provides localAnimReaders) {
                Box(
                    Modifier.graphicsLayer {
                        val s = popScale.value
                        scaleX = s
                        scaleY = s
                        alpha = popAlpha.value
                        transformOrigin = TransformOrigin(1f, 1f)
                    },
                ) {
                    content()
                }
            }
        },
        modifier = if (isLivePop) Modifier else Modifier.clipToBounds(),
    ) { measurables, constraints ->
        val placeable = measurables.first().measure(constraints.copy(minHeight = 0))
        val spacingPx = topSpacing.roundToPx()
        val fullHeight = placeable.height + spacingPx
        if (!initialized) {
            initialized = true
            lastContentKey = contentKey
            val known = knownHeights[rowId]
            if (known == null && !animateArrival) {
                targetPx = fullHeight
                currentPx = fullHeight.toFloat()
                knownHeights[rowId] = fullHeight
            } else {
                val startPx = (known ?: 0).coerceAtMost(fullHeight)
                currentPx = startPx.toFloat()
                targetPx = fullHeight
                knownHeights[rowId] = startPx
            }
        } else {
            lastContentKey = contentKey
            if (localAnimReaders.any { it.invoke() }) {
                targetPx = fullHeight
                currentPx = fullHeight.toFloat()
                knownHeights[rowId] = fullHeight
            } else if (fullHeight > targetPx) {
                targetPx = fullHeight
            } else if (fullHeight < targetPx) {
                targetPx = fullHeight
                if (currentPx > fullHeight.toFloat()) {
                    currentPx = fullHeight.toFloat()
                    knownHeights[rowId] = fullHeight
                }
            }
        }
        val height = currentPx.roundToInt().coerceIn(0, maxOf(fullHeight, targetPx))
        layout(placeable.width, constraints.constrainHeight(height)) {
            if (height > 0) placeable.placeRelative(0, spacingPx)
        }
    }
}
internal fun spatialSample(milliseconds: Long, from: Float = 0f, to: Float = 1f, velocity: Float = 0f, durationScale: Float = 1f): Float {
    if (durationScale <= 0f) return to
    val animation = TargetBasedAnimation(spatialSpring(), Float.VectorConverter, from, to, velocity)
    return animation.getValueFromNanos((milliseconds * 1_000_000L / durationScale).toLong())
}
