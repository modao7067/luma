// Adapted from chen08209/prism_ui under GPL-3.0. See apps/gui/third-party/prism-ui/.
package io.github.modao7067.luma.gui.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp
import io.github.modao7067.luma.gui.ui.shapes.addSquircleRect
import kotlinx.coroutines.CancellationException
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt

internal enum class MorphPlacement { Start, End, Center }

private const val OPEN_SECONDS = 0.45f
private const val DISMISS_SECONDS = 0.5f
private const val CORNERS_DELAY = 0.07f
private const val CORNERS_REACH_PER_WIDTH = 0.48f
private const val PLATTER_IN_CONTROL = 0.41f
private const val DROPLET_SHARE = 0.25f
private const val GLYPH_UNDER_AT = 0.25f
private const val DROPLET_CONTENT_OUT = 0.1f
private const val DROPLET_GLYPH_IN = 0.47f
private val CardRadius = 28.dp
private val DropletBlend = 28.dp
private val RimDepth = 8.dp
private val ScreenMargin = 16.dp
private val AnchorOverlap = 8.dp
private val OpenContentBlur = 4.dp
private val OpenBlurBump = 1.9.dp
private val MaxContentBlur = 8.dp
private val UnseenBlur = 0.1.dp

private val openSpring = SpringCurve(400f, 0.754f)
private val cornersSpring = SpringCurve(324f, 0.75f, from = 1f, to = 0f)
private val dropletSpring = SpringCurve(165f, 0.8f)
private val easeIn = CubicBezierEasing(0.42f, 0f, 1f, 1f)
private val easeOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)
private val easeInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
private val glyphCurve = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

@Stable
internal class MorphMenuHost {
    internal val entries = mutableStateListOf<MorphMenuEntry>()
    internal var root: LayoutCoordinates? = null
    private var nextId = 0L
    internal fun newId() = nextId++
}

internal val LocalMorphMenuHost = staticCompositionLocalOf<MorphMenuHost?> { null }

internal interface MorphCarried {
    fun move(position: Offset)
    fun release(position: Offset)
    fun cancel()
}

@Stable
internal class MorphAnchor {
    var bounds by mutableStateOf(Rect.Zero)
    internal var activeCount by mutableIntStateOf(0)
    internal var reopenTick by mutableIntStateOf(0)
    val hidden: Boolean get() = activeCount > 0 && bounds.width > 0f && bounds.height > 0f
    internal var snapshot: GraphicsLayer? = null
    internal var coordinates: LayoutCoordinates? = null
    internal var host: MorphMenuHost? = null
    internal var carried: MorphCarried? = null
    var sourceColor: Color = Color.Transparent

    internal fun toHost(local: Offset): Offset? {
        val root = host?.root
        val node = coordinates
        return if (root != null && node != null && root.isAttached && node.isAttached) root.localPositionOf(node, local) else null
    }
}

@Composable
internal fun rememberMorphAnchor(): MorphAnchor = remember { MorphAnchor() }

internal class MorphRows {
    internal class Row(var enabled: Boolean, var onClick: () -> Unit) {
        var coordinates: LayoutCoordinates? = null
    }

    internal val rows = mutableListOf<Row>()
    var held by mutableStateOf<Row?>(null)

    fun rowAt(root: LayoutCoordinates?, position: Offset): Row? {
        if (root == null || !root.isAttached) return null
        return rows.firstOrNull { row ->
            val node = row.coordinates
            row.enabled && node != null && node.isAttached && root.localBoundingBoxOf(node).contains(position)
        }
    }
}

internal val LocalMorphRows = compositionLocalOf<MorphRows?> { null }

@Stable
internal class MorphMenuEntry(val id: Long, val anchor: MorphAnchor) {
    var expanded by mutableStateOf(true)
    var width by mutableStateOf(228.dp)
    var placement by mutableStateOf(MorphPlacement.End)
    var preferAbove by mutableStateOf(false)
    var onDismiss: () -> Unit = {}
    var content: @Composable ColumnScope.() -> Unit = {}
    val rows = MorphRows()
    var card by mutableStateOf(Rect.Zero)
    internal var viewport = Int.MAX_VALUE
    internal var removed by mutableStateOf(false)
    internal var openSignal = 0
}

@Composable
internal fun MorphMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    anchor: MorphAnchor?,
    width: Dp = 228.dp,
    placement: MorphPlacement = MorphPlacement.End,
    preferAbove: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val host = LocalMorphMenuHost.current
    if (host == null || anchor == null || !LocalSessionInteractive.current) {
        OriginMenu(
            expanded,
            onDismiss,
            width = width,
            horizontalAlignment = if (placement == MorphPlacement.Center) Alignment.CenterHorizontally else null,
            preferAbove = preferAbove,
            content = content,
        )
        return
    }
    val latestContent = rememberUpdatedState(content)
    val latestDismiss = rememberUpdatedState(onDismiss)
    val holder = remember(host, anchor) { mutableStateOf<MorphMenuEntry?>(null) }
    val openSignal = anchor.reopenTick
    val current = holder.value
    val live = current?.takeIf { !it.removed && it in host.entries }
    SideEffect {
        var entry = live ?: holder.value?.takeIf { !it.removed && it in host.entries }
        if (entry == null && holder.value != null) holder.value = null
        if (expanded && entry == null) {
            entry = MorphMenuEntry(host.newId(), anchor).also {
                it.onDismiss = { latestDismiss.value() }
                it.content = { latestContent.value(this) }
                holder.value = it
                host.entries.add(it)
            }
        }
        entry?.let {
            it.expanded = expanded
            it.width = width
            it.placement = placement
            it.preferAbove = preferAbove
            it.openSignal = openSignal
        }
    }
    DisposableEffect(host, anchor) {
        onDispose {
            holder.value?.let {
                it.removed = true
                host.entries.remove(it)
            }
            holder.value = null
        }
    }
}

@Composable
internal fun Modifier.morphAnchor(
    anchor: MorphAnchor,
    enabled: Boolean,
    onOpen: () -> Unit,
    elastic: ElasticPressState? = null,
    stretch: Boolean = true,
    sourceColor: Color = Color.Transparent,
    touchTarget: Boolean = false,
): Modifier {
    val host = LocalMorphMenuHost.current
    val haptic = LocalHapticFeedback.current
    val layer = rememberGraphicsLayer()
    anchor.host = host
    anchor.snapshot = layer
    anchor.sourceColor = sourceColor
    val latestOpen by rememberUpdatedState(onOpen)
    val latestEnabled by rememberUpdatedState(enabled)
    val maxGrowthPx = with(LocalDensity.current) { 16.dp.toPx() }
    return this
        .onGloballyPositioned { anchor.coordinates = it }
        .semantics(mergeDescendants = true) {
            role = Role.Button
            if (!enabled) disabled()
            onClick {
                if (latestEnabled) {
                    anchor.reopenTick++
                    latestOpen()
                }
                latestEnabled
            }
        }
        .blockHorizontalPageSwipe(enabled)
        .pointerInput(anchor, host) {
            if (host == null) {
                detectTapGestures(onTap = {
                    if (latestEnabled) {
                        haptic.tick()
                        anchor.reopenTick++
                        latestOpen()
                    }
                })
                return@pointerInput
            }
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (!latestEnabled || (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed)) return@awaitEachGesture
                haptic.tick()
                anchor.reopenTick++
                latestOpen()
                elastic?.release()
                var moved = false
                val slop = viewConfiguration.touchSlop
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Unknown) {
                            anchor.carried?.cancel()
                            break
                        }
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null) {
                            anchor.carried?.cancel()
                            break
                        }
                        if (!change.pressed) {
                            if (change.isConsumed) anchor.carried?.cancel()
                            else if (moved) anchor.toHost(change.position)?.let { anchor.carried?.release(it) }
                            change.consume()
                            break
                        }
                        if (!moved && (change.position - down.position).getDistance() > slop) moved = true
                        if (moved) anchor.toHost(change.position)?.let { anchor.carried?.move(it) }
                        change.consume()
                    }
                } catch (cancelled: CancellationException) {
                    anchor.carried?.cancel()
                    throw cancelled
                }
            }
        }
        .then(if (elastic != null) Modifier.pointerInput(elastic, stretch) { observeElastic(elastic, { latestEnabled }, stretch) } else Modifier)
        .then(if (touchTarget) Modifier.minimumInteractiveComponentSize() else Modifier)
        .onGloballyPositioned { node ->
            val root = host?.root
            if (root != null && root.isAttached) anchor.bounds = root.localBoundingBoxOf(node, clipBounds = false)
        }
        .then(
            if (elastic != null) {
                Modifier
                    .onSizeChanged { elastic.panel = it.toSize() }
                    .graphicsLayer { applyElastic(elastic, maxGrowthPx) }
            } else {
                Modifier
            },
        )
        .drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            if (!anchor.hidden) drawLayer(layer)
        }
}

@Composable
internal fun MorphMenuLayer(host: MorphMenuHost, scene: GlassScene?, insets: WindowInsets) {
    for (entry in host.entries) {
        key(entry.id) { MorphMenuSurface(host, entry, scene, insets) }
    }
}

private class Opening(val platter: Rect, val radius: Float, val droplet: Rect?, val progress: Float)

private class MorphFrame(
    val platter: Rect,
    val radius: Float,
    val droplet: Rect?,
    val blend: Float,
    val glassMix: Float,
    val hazeMix: Float,
    val sourceFill: Float,
    val shadow: Float,
    val glyph: Rect?,
    val glyphAlpha: Float,
    val glyphBlur: Float,
    val contentAlpha: Float,
    val contentBlur: Float,
    val contentScale: Float,
    val clipContent: Boolean,
)

private fun beyondRound(anchor: Rect) = Size(max(0f, anchor.width - anchor.height), max(0f, anchor.height - anchor.width))

private fun lerpOffset(a: Offset, b: Offset, t: Float) = Offset(lerp(a.x, b.x, t), lerp(a.y, b.y, t))

private fun Density.openingOf(time: Float, card: Rect, anchor: Rect, withSource: Boolean = true): Opening {
    val progress = openSpring.x(time)
    val scale = lerp(PLATTER_IN_CONTROL * anchor.minDimension / card.width, 1f, progress)
    val height = lerp(card.width, card.height, progress)
    val toCard = card.center.y - anchor.center.y
    val distanceDp = kotlin.math.abs(toCard) / density
    val dip = if (!withSource) 0f else sign(toCard) * (min(1f, distanceDp / 30f) * 23f + 0.124f * distanceDp) * riseAndFall(time, 0.098f, 3.3f) * density
    val beyondFactor = (1f - progress).coerceIn(0f, 1f)
    val beyond = beyondRound(anchor)
    val center = lerpOffset(anchor.center, card.center, progress) + Offset(0f, dip)
    val pw = max(0f, card.width * scale + beyond.width * beyondFactor)
    val ph = max(0f, height * scale + beyond.height * beyondFactor)
    val platter = Rect(center.x - pw / 2f, center.y - ph / 2f, center.x + pw / 2f, center.y + ph / 2f)
    val corners = CardRadius.toPx() + card.width * CORNERS_REACH_PER_WIDTH * cornersSpring.x(max(0f, time - CORNERS_DELAY))
    val extent = Size(anchor.width * lerp(1f, DROPLET_SHARE, progress), anchor.height * lerp(1f, DROPLET_SHARE, progress))
    val inner = card.deflate(anchor.maxDimension * DROPLET_SHARE / 2f + DropletBlend.toPx() + RimDepth.toPx())
    val sunk = lerpOffset(anchor.center, card.center, DROPLET_SHARE)
    val target = if (inner.width <= 0f || inner.height <= 0f) card.center else Offset(sunk.x.coerceIn(inner.left, inner.right), sunk.y.coerceIn(inner.top, inner.bottom))
    val dropletCenter = lerpOffset(anchor.center, target, progress)
    val droplet = Rect(dropletCenter.x - extent.width / 2f, dropletCenter.y - extent.height / 2f, dropletCenter.x + extent.width / 2f, dropletCenter.y + extent.height / 2f)
    return Opening(platter, max(0f, min(height / 2f, corners) * scale), droplet, progress)
}

private fun Density.frameOf(openTime: Float, backTime: Float, card: Rect, anchor: Rect): MorphFrame {
    if (backTime <= 0f) {
        if (openTime >= OPEN_SECONDS) {
            return MorphFrame(card, CardRadius.toPx(), null, 0f, 1f, 1f, 0f, 1f, null, 0f, 0f, 1f, 0f, 1f, false)
        }
        val opening = openingOf(openTime, card, anchor)
        val settled = opening.progress.coerceIn(0f, 1f)
        val under = min(1f, settled / GLYPH_UNDER_AT)
        val blurDp = sqrt((OpenContentBlur.value * (1f - settled)).pow(2) + (OpenBlurBump.value * riseAndFall(openTime, 0.069f, 2.1f)).pow(2))
        return MorphFrame(
            platter = opening.platter,
            radius = opening.radius,
            droplet = opening.droplet,
            blend = DropletBlend.toPx(),
            glassMix = settled,
            hazeMix = 1f,
            sourceFill = 1f - settled,
            shadow = settled,
            glyph = opening.droplet,
            glyphAlpha = 1f - under,
            glyphBlur = OpenContentBlur.toPx() * under,
            contentAlpha = settled,
            contentBlur = blurDp * density,
            contentScale = opening.platter.width / card.width,
            clipContent = true,
        )
    }
    val start = if (openTime < OPEN_SECONDS) openingOf(openTime, card, anchor) else null
    val from = start?.platter ?: card
    val fromRadius = start?.radius ?: CardRadius.toPx()
    val fromDroplet = start?.droplet ?: run {
        val c = lerpOffset(anchor.center, from.center, DROPLET_SHARE)
        val w = anchor.width * DROPLET_SHARE
        val h = anchor.height * DROPLET_SHARE
        Rect(c.x - w / 2f, c.y - h / 2f, c.x + w / 2f, c.y + h / 2f)
    }
    val t = backTime
    val ringing = 1f - easeIn.transform(((t - 0.4f) / 0.1f).coerceIn(0f, 1f))
    val progress = 1f + (dropletSpring.x(t) - 1f) * ringing
    val toCard = from.center.y - anchor.center.y
    val away = sign(toCard)
    val distance = kotlin.math.abs(toCard)
    val dip = min(0.21f * distance, 22.dp.toPx()) * riseAndFall(t, 0.072f, 2.4f)
    val lift = min(0.25f * distance, 19.dp.toPx()) * riseAndFall(t, 0.176f, 4.9f)
    val scale = lerp(1f, PLATTER_IN_CONTROL * anchor.minDimension / max(from.width, from.height), progress)
    val beyond = beyondRound(anchor)
    val beyondFactor = progress.coerceIn(0f, 1f)
    val platterCenter = lerpOffset(from.center, anchor.center, progress) + Offset(0f, away * dip * ringing)
    val pw = max(0f, from.width * scale + beyond.width * beyondFactor)
    val ph = max(0f, lerp(from.height, from.width, progress) * scale + beyond.height * beyondFactor)
    val platter = Rect(platterCenter.x - pw / 2f, platterCenter.y - ph / 2f, platterCenter.x + pw / 2f, platterCenter.y + ph / 2f)
    val platterRadius = max(0f, lerp(fromRadius, from.width / 2f, progress) * scale)
    val dropletCenter = lerpOffset(fromDroplet.center, anchor.center, progress) - Offset(0f, away * lift * ringing)
    val dw = lerp(fromDroplet.width, anchor.width, progress)
    val dh = lerp(fromDroplet.height, anchor.height, progress)
    val droplet = Rect(dropletCenter.x - dw / 2f, dropletCenter.y - dh / 2f, dropletCenter.x + dw / 2f, dropletCenter.y + dh / 2f)
    val absorbed = easeInOut.transform((((t / DISMISS_SECONDS).coerceIn(0f, 1f) - 0.6f) / 0.3f).coerceIn(0f, 1f))
    val blend = DropletBlend.toPx() * min(1f, t / 0.05f) * (1f - absorbed)
    val reveal = (start?.progress?.coerceIn(0f, 1f) ?: 1f) * (1f - easeOut.transform((t / DROPLET_CONTENT_OUT).coerceIn(0f, 1f)))
    val remaining = (1f - progress).coerceIn(0f, 1f)
    return MorphFrame(
        platter = platter,
        radius = platterRadius,
        droplet = droplet,
        blend = blend,
        glassMix = remaining,
        hazeMix = 1f - absorbed,
        sourceFill = 1f - remaining,
        shadow = remaining,
        glyph = droplet,
        glyphAlpha = lerp(0.2f, 1f, glyphCurve.transform((t / DROPLET_GLYPH_IN).coerceIn(0f, 1f))),
        glyphBlur = 0f,
        contentAlpha = reveal,
        contentBlur = MaxContentBlur.toPx() * (1f - reveal),
        contentScale = min(platter.width / card.width, platter.height / card.height),
        clipContent = false,
    )
}

private class ElasticFrame(val sx: Float, val sy: Float, val pull: Offset, val center: Offset) {
    fun map(rect: Rect): Rect {
        val c = Offset(center.x + (rect.center.x - center.x) * sx + pull.x, center.y + (rect.center.y - center.y) * sy + pull.y)
        val w = rect.width * sx
        val h = rect.height * sy
        return Rect(c.x - w / 2f, c.y - h / 2f, c.x + w / 2f, c.y + h / 2f)
    }
}

private fun elasticFrame(state: ElasticPressState, card: Rect, maxGrowthPx: Float): ElasticFrame {
    val lift = state.swell
    val pull = state.pull
    if (card.width <= 0f || card.height <= 0f || (lift == 0f && pull == Offset.Zero)) return ElasticFrame(1f, 1f, Offset.Zero, card.center)
    val swell = 1f + lift * elasticGrowth(max(card.width, card.height), maxGrowthPx)
    return ElasticFrame(
        swell * (1f + kotlin.math.abs(pull.x) / card.width * 0.5f),
        swell * (1f + kotlin.math.abs(pull.y) / card.height * 0.5f),
        pull,
        card.center,
    )
}

internal class PlatterClipShape(private val width: Float, private val height: Float, private val rx: Float, private val ry: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val left = (size.width - width) / 2f
        val top = (size.height - height) / 2f
        return Outline.Rounded(RoundRect(left, top, left + width, top + height, CornerRadius(rx, ry)))
    }
}

internal const val MORPH_OPEN_SECONDS = OPEN_SECONDS
internal const val MORPH_SHRINK_MILLIS = 200f
internal const val MORPH_DISMISS_SCALE = 0.8f
internal val morphShrinkEasing = easeOut

internal class DetachedFrame(val platter: Rect, val radius: Float, val settled: Float, val contentBlur: Float, val contentScale: Float)

internal fun Density.detachedFrame(openTime: Float, card: Rect, anchor: Rect): DetachedFrame {
    if (openTime >= OPEN_SECONDS || card.width <= 0f || card.height <= 0f) {
        return DetachedFrame(card, CardRadius.toPx(), 1f, 0f, 1f)
    }
    val resolvedAnchor = if (anchor.width > 0f && anchor.height > 0f) anchor else {
        val r = 22.dp.toPx()
        Rect(card.center.x - r, card.top, card.center.x + r, card.top + r * 2f)
    }
    val opening = openingOf(openTime, card, resolvedAnchor, withSource = false)
    val settled = opening.progress.coerceIn(0f, 1f)
    val blurDp = sqrt((OpenContentBlur.value * (1f - settled)).pow(2) + (OpenBlurBump.value * riseAndFall(openTime, 0.069f, 2.1f)).pow(2))
    return DetachedFrame(opening.platter, opening.radius, settled, blurDp * density, opening.platter.width / card.width)
}

internal fun squirclePath(rect: Rect, radius: Float): Path {
    val half = rect.minDimension / 2f
    val r = min(radius, half).coerceAtLeast(0f)
    return Path().apply {
        when {
            rect.width <= 0f || rect.height <= 0f -> Unit
            r <= 0f -> addRect(rect)
            r >= half - 0.01f -> addRoundRect(RoundRect(rect, CornerRadius(r)))
            else -> {
                addSquircleRect(rect.width, rect.height, r)
                translate(rect.topLeft)
            }
        }
    }
}

internal class OffsetSquircleShape(private val rect: Rect, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(squirclePath(rect, radius))
}

@Composable
private fun MorphMenuSurface(host: MorphMenuHost, entry: MorphMenuEntry, scene: GlassScene?, insets: WindowInsets) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val haptic = LocalHapticFeedback.current
    val anchor = entry.anchor
    val open = remember { Animatable(0f) }
    val back = remember { Animatable(0f) }
    val cardElastic = rememberElasticPress()
    val maxGrowthPx = with(density) { 16.dp.toPx() }
    val expanded = entry.expanded
    val inspecting = LocalInspectionMode.current
    fun openTime() = if (inspecting && expanded) OPEN_SECONDS else open.value
    fun backTime() = if (inspecting) 0f else back.value
    fun anchorReady() = inspecting || (anchor.bounds.width > 0f && anchor.bounds.height > 0f)
    DisposableEffect(entry, inspecting) {
        if (!inspecting) anchor.activeCount++
        var carrying = false
        val carried = object : MorphCarried {
            override fun move(position: Offset) {
                val card = entry.card
                val settled = openTime() >= OPEN_SECONDS && backTime() <= 0f
                if (!carrying && settled) {
                    carrying = true
                    cardElastic.press(position - card.topLeft)
                }
                if (carrying) cardElastic.drag(position - card.topLeft, pastEdges = true)
                val row = entry.rows.rowAt(host.root, position)
                if (row !== entry.rows.held) {
                    if (row != null) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    entry.rows.held = row
                }
            }

            override fun release(position: Offset) {
                carrying = false
                cardElastic.release()
                val row = entry.rows.rowAt(host.root, position)
                entry.rows.held = null
                if (entry.expanded) row?.onClick?.invoke()
            }

            override fun cancel() {
                carrying = false
                cardElastic.release()
                entry.rows.held = null
            }
        }
        anchor.carried = carried
        onDispose {
            if (anchor.carried === carried) anchor.carried = null
            if (!inspecting) anchor.activeCount = max(0, anchor.activeCount - 1)
        }
    }
    LaunchedEffect(expanded) {
        if (expanded) {
            if (back.value > 0f) back.animateTo(0f, tween((back.value * 1000f).roundToInt(), easing = LinearEasing))
            open.animateTo(OPEN_SECONDS, tween(((OPEN_SECONDS - open.value) * 1000f).roundToInt(), easing = LinearEasing))
        } else {
            entry.rows.held = null
            back.animateTo(DISMISS_SECONDS, tween(((DISMISS_SECONDS - back.value) * 1000f).roundToInt(), easing = LinearEasing))
            if (!entry.expanded) {
                entry.removed = true
                host.entries.remove(entry)
            }
        }
    }
    BackHandler(enabled = expanded) { entry.onDismiss() }
    val scheme = MaterialTheme.colorScheme
    val regularStyle = remember(scheme) { PrismGlassStyle.regular(scheme) }
    val menuStyle = remember(scheme) { PrismGlassStyle.menu(scheme) }
    val menuRim = remember(scheme) { PrismGlassRim.menu(scheme) }
    val darkSurface = scheme.surface.luminance() <= 0.5f
    val shader = remember { if (Build.VERSION.SDK_INT >= 33) PrismGlassShader() else null }
    val fallbackFilterCache = remember { if (Build.VERSION.SDK_INT < 33) MorphFallbackFilterCache() else null }
    val glassLayer = rememberGraphicsLayer()
    val hazeMixLayer = rememberGraphicsLayer()
    val shadowLayer = rememberGraphicsLayer()
    val shadowCache = remember { MorphShadowCache() }
    val menuShadowBlurPx = with(density) { 29.dp.toPx() }
    val menuShadowEffect = remember(menuShadowBlurPx) {
        val scaled = menuShadowBlurPx * MORPH_SHADOW_SCALE
        BlurEffect(scaled, scaled, TileMode.Decal)
    }
    val glyphLayer = rememberGraphicsLayer()
    val contentLayer = rememberGraphicsLayer()
    val contentCaptured = remember { booleanArrayOf(false) }
    val scrollState = rememberScrollState()
    val fallbackContainer = MenuDefaults.containerColor
    fun frame(): MorphFrame = density.frameOf(openTime(), backTime(), entry.card, anchor.bounds)
    var dismissingTouch by remember { mutableStateOf(false) }
    val scrim = if (expanded || dismissingTouch) {
        Modifier.pointerInput(entry) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val card = entry.card
                if (entry.expanded && card.width > 0f && card.height > 0f && !card.contains(down.position)) {
                    down.consume()
                    dismissingTouch = true
                    entry.onDismiss()
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                            if (event.type == PointerEventType.Unknown || event.changes.none { it.pressed }) break
                        }
                    } finally {
                        dismissingTouch = false
                    }
                }
            }
        }
    } else {
        Modifier
    }
    Layout(
        modifier = Modifier.fillMaxSize(),
        content = {
            Box(
                Modifier
                    .fillMaxSize()
                    .then(scrim)
                    .drawBehind {
                        val card = entry.card
                        if (card.width <= 0f || card.height <= 0f || (!anchorReady() && openTime() < OPEN_SECONDS)) return@drawBehind
                        val f = frame()
                        val elastic = elasticFrame(cardElastic, card, maxGrowthPx)
                        val platter = elastic.map(f.platter)
                        val radius = f.radius * min(elastic.sx, elastic.sy)
                        drawMorphShadow(shadowLayer, shadowCache, menuShadowEffect, menuShadowBlurPx, platter, radius, f.shadow, darkSurface)
                        val blendedStyle = PrismGlassStyle.lerp(regularStyle, menuStyle, f.glassMix)
                        val activeStyle = if (f.sourceFill > 0f && anchor.sourceColor.alpha > 0f) {
                            blendedStyle.copy(
                                tint = anchor.sourceColor.copy(alpha = anchor.sourceColor.alpha * f.sourceFill).compositeOver(blendedStyle.tint),
                            )
                        } else {
                            blendedStyle
                        }
                        val activeRim = menuRim.scale(f.glassMix)
                        drawMorphGlass(
                            glassLayer,
                            hazeMixLayer,
                            scene,
                            shader,
                            fallbackFilterCache,
                            density.density,
                            platter,
                            radius,
                            f.droplet,
                            f.blend,
                            f.hazeMix,
                            activeStyle,
                            activeRim,
                            fallbackContainer,
                        )
                        val glyph = f.glyph
                        val snapshot = anchor.snapshot
                        val source = anchor.bounds
                        if (glyph != null && snapshot != null && f.glyphAlpha > 0.004f && source.width > 0f && source.height > 0f) {
                            glyphLayer.record(size = IntSize(ceil(source.width).toInt(), ceil(source.height).toInt())) { drawLayer(snapshot) }
                            glyphLayer.alpha = f.glyphAlpha
                            glyphLayer.renderEffect = if (f.glyphBlur >= UnseenBlur.toPx()) BlurEffect(f.glyphBlur, f.glyphBlur, TileMode.Decal) else null
                            translate(glyph.left, glyph.top) {
                                scale(glyph.width / source.width, glyph.height / source.height, pivot = Offset.Zero) { drawLayer(glyphLayer) }
                            }
                        }
                        if (!expanded && contentCaptured[0] && f.contentAlpha > 0.004f) {
                            val sx = f.contentScale * elastic.sx
                            val sy = f.contentScale * elastic.sy
                            if (sx > 0f && sy > 0f) {
                                contentLayer.alpha = f.contentAlpha.coerceIn(0f, 1f)
                                contentLayer.renderEffect = if (f.contentBlur >= UnseenBlur.toPx()) BlurEffect(f.contentBlur, f.contentBlur, TileMode.Decal) else null
                                translate(platter.center.x - card.center.x, platter.center.y - card.center.y) {
                                    scale(sx, sy, pivot = card.center) {
                                        translate(card.left, card.top) {
                                            drawLayer(contentLayer)
                                        }
                                    }
                                }
                            }
                        }
                    },
            )
            if (expanded) {
                CompositionLocalProvider(
                    LocalMenuActive provides expanded,
                    LocalMenuViewportHeight provides { entry.viewport },
                    LocalMorphRows provides entry.rows,
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .pointerInput(cardElastic) {
                                observeElastic(cardElastic, { openTime() >= OPEN_SECONDS && backTime() <= 0f && entry.expanded }, stretch = true, pastEdges = true)
                            }
                            .drawWithContent {
                                contentLayer.alpha = 1f
                                contentLayer.renderEffect = null
                                contentLayer.record { this@drawWithContent.drawContent() }
                                contentCaptured[0] = true
                                drawLayer(contentLayer)
                            }
                            .padding(vertical = 8.dp)
                            .verticalScroll(scrollState),
                        content = entry.content,
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val margin = ScreenMargin.roundToPx()
        val left = insets.getLeft(this, layoutDirection) + margin
        val right = insets.getRight(this, layoutDirection) + margin
        val safeTop = insets.getTop(this)
        val safeBottom = insets.getBottom(this)
        val top = safeTop + margin
        val bottom = safeBottom + margin
        val cardWidth = min(entry.width.roundToPx(), (w - left - right).coerceAtLeast(1))
        val maxHeight = (h - top - bottom).coerceAtLeast(1)
        entry.viewport = maxHeight
        val background = measurables[0].measure(Constraints.fixed(w, h))
        val content = measurables.getOrNull(1)?.measure(Constraints(minWidth = cardWidth, maxWidth = cardWidth, maxHeight = maxHeight))
        var placeX = entry.card.left.roundToInt()
        var placeY = entry.card.top.roundToInt()
        if (content != null) {
            val a = anchor.bounds
            val rawX = when (entry.placement) {
                MorphPlacement.End -> if (layoutDirection == LayoutDirection.Ltr) a.right - content.width else a.left
                MorphPlacement.Start -> if (layoutDirection == LayoutDirection.Ltr) a.left else a.right - content.width
                MorphPlacement.Center -> a.center.x - content.width / 2f
            }
            val maxX = (w - right - content.width).coerceAtLeast(left)
            placeX = rawX.roundToInt().coerceIn(left, maxX)
            placeY = if (entry.preferAbove) {
                val rawY = (a.bottom + AnchorOverlap.toPx() - content.height).roundToInt()
                val minY = top
                val maxY = max(minY, min(h - safeBottom, max(h - bottom, (a.bottom + AnchorOverlap.toPx()).roundToInt())) - content.height)
                rawY.coerceIn(minY, maxY)
            } else {
                val rawY = (a.top - AnchorOverlap.toPx()).roundToInt()
                val minY = max(safeTop, min(top, rawY))
                val maxY = max(minY, h - bottom - content.height)
                rawY.coerceIn(minY, maxY)
            }
            val card = Rect(placeX.toFloat(), placeY.toFloat(), (placeX + content.width).toFloat(), (placeY + content.height).toFloat())
            if (entry.card != card) entry.card = card
            cardElastic.panel = card.size
        }
        layout(w, h) {
            background.place(0, 0)
            content?.placeWithLayer(placeX, placeY) {
                val current = entry.card
                if (current.width <= 0f || current.height <= 0f || (!anchorReady() && openTime() < OPEN_SECONDS)) {
                    alpha = 0f
                    return@placeWithLayer
                }
                val f = frame()
                val elastic = elasticFrame(cardElastic, current, maxGrowthPx)
                val platter = elastic.map(f.platter)
                val sx = f.contentScale * elastic.sx
                val sy = f.contentScale * elastic.sy
                transformOrigin = TransformOrigin.Center
                scaleX = sx
                scaleY = sy
                translationX = platter.center.x - current.center.x
                translationY = platter.center.y - current.center.y
                alpha = f.contentAlpha.coerceIn(0f, 1f)
                renderEffect = if (f.contentBlur >= UnseenBlur.toPx()) BlurEffect(f.contentBlur, f.contentBlur, TileMode.Decal) else null
                if (f.clipContent && sx > 0f && sy > 0f) {
                    val r = f.radius * min(elastic.sx, elastic.sy)
                    shape = PlatterClipShape(platter.width / sx, platter.height / sy, r / sx, r / sy)
                    clip = true
                } else if (backTime() <= 0f) {
                    shape = RoundedCornerShape(CardRadius)
                    clip = true
                }
            }
        }
    }
}

private const val MORPH_SHADOW_SCALE = 0.25f

private class MorphShadowCache {
    var width = -1f
    var height = -1f
    var radius = -1f
    var alpha = -1f
}

private fun DrawScope.drawMorphShadow(
    layer: GraphicsLayer,
    cache: MorphShadowCache,
    blurEffect: BlurEffect,
    blurPx: Float,
    platter: Rect,
    radius: Float,
    strength: Float,
    dark: Boolean,
) {
    if (strength <= 0.004f || platter.width <= 0f || platter.height <= 0f) return
    val apron = blurPx * 2f + 2f
    val alpha = (if (dark) 0.118f * 1.5f else 0.118f) * strength
    val clampedRadius = radius.coerceAtMost(platter.minDimension / 2f)
    val cutout = Path().apply { addRoundRect(RoundRect(platter, CornerRadius(clampedRadius))) }
    if (cache.width != platter.width || cache.height != platter.height || cache.radius != clampedRadius || cache.alpha != alpha) {
        val scaledSize = IntSize(
            ceil((platter.width + apron * 2) * MORPH_SHADOW_SCALE).toInt().coerceAtLeast(1),
            ceil((platter.height + apron * 2) * MORPH_SHADOW_SCALE).toInt().coerceAtLeast(1),
        )
        layer.record(size = scaledSize) {
            scale(MORPH_SHADOW_SCALE, MORPH_SHADOW_SCALE, pivot = Offset.Zero) {
                drawRoundRect(
                    Color.Black.copy(alpha = alpha),
                    topLeft = Offset(apron, apron),
                    size = platter.size,
                    cornerRadius = CornerRadius(clampedRadius),
                )
            }
        }
        layer.renderEffect = blurEffect
        cache.width = platter.width
        cache.height = platter.height
        cache.radius = clampedRadius
        cache.alpha = alpha
    }
    clipPath(cutout, clipOp = ClipOp.Difference) {
        translate(platter.left - apron, platter.top - apron + 8.dp.toPx()) {
            scale(1f / MORPH_SHADOW_SCALE, 1f / MORPH_SHADOW_SCALE, pivot = Offset.Zero) {
                drawLayer(layer)
            }
        }
    }
}

private class MorphFallbackFilterCache {
    private var lastSaturation = Float.NaN
    private var lastBrightness = Float.NaN
    private var cached: androidx.compose.ui.graphics.RenderEffect? = null

    fun get(saturation: Float, brightness: Float): androidx.compose.ui.graphics.RenderEffect {
        val current = cached
        if (current != null && lastSaturation == saturation && lastBrightness == brightness) {
            return current
        }
        val next = prismOpticsColorFilter(saturation, brightness).asComposeRenderEffect()
        lastSaturation = saturation
        lastBrightness = brightness
        cached = next
        return next
    }
}

private fun roundRectPath(rect: Rect, radius: Float) = squirclePath(rect, radius)

private fun DrawScope.drawMorphGlass(
    layer: GraphicsLayer,
    hazeMixLayer: GraphicsLayer,
    scene: GlassScene?,
    shader: PrismGlassShader?,
    fallbackFilterCache: MorphFallbackFilterCache?,
    density: Float,
    platter: Rect,
    radius: Float,
    droplet: Rect?,
    blend: Float,
    hazeMix: Float,
    style: PrismGlassStyle,
    rim: PrismGlassRim,
    opaque: Color,
) {
    if (platter.width <= 0f || platter.height <= 0f) return
    val shape = if (droplet != null && droplet.width > 0f && droplet.height > 0f) {
        Rect(min(platter.left, droplet.left), min(platter.top, droplet.top), max(platter.right, droplet.right), max(platter.bottom, droplet.bottom))
    } else {
        platter
    }
    if (scene == null) {
        val path = roundRectPath(platter, radius)
        if (droplet != null && droplet.width > 0f) path.op(path, roundRectPath(droplet, droplet.minDimension / 2f), PathOperation.Union)
        drawPath(path, style.tint.compositeOver(opaque.copy(alpha = 1f)))
        return
    }
    val pad = 2.dp.toPx()
    val capture = shape.inflate(pad)
    val captureSize = IntSize(ceil(capture.width).toInt().coerceAtLeast(1), ceil(capture.height).toInt().coerceAtLeast(1))
    val mixFactor = hazeMix.coerceIn(0f, 1f)
    if (mixFactor in 0.01f..0.99f) {
        val invHaze = 1f / scene.hazeScale
        hazeMixLayer.alpha = mixFactor
        hazeMixLayer.record(size = captureSize) {
            scale(invHaze, invHaze, pivot = Offset.Zero) {
                translate(-capture.left * scene.hazeScale, -capture.top * scene.hazeScale) {
                    drawLayer(scene.hazeLayer)
                }
            }
        }
    }
    layer.record(size = captureSize) {
        if (mixFactor >= 0.99f) {
            val invHaze = 1f / scene.hazeScale
            scale(invHaze, invHaze, pivot = Offset.Zero) {
                translate(-capture.left * scene.hazeScale, -capture.top * scene.hazeScale) {
                    drawLayer(scene.hazeLayer)
                }
            }
        } else {
            val invReg = 1f / scene.regularScale
            scale(invReg, invReg, pivot = Offset.Zero) {
                translate(-capture.left * scene.regularScale, -capture.top * scene.regularScale) {
                    drawLayer(scene.regularLayer)
                }
            }
            if (mixFactor > 0.01f) {
                drawLayer(hazeMixLayer)
            }
        }
    }
    if (Build.VERSION.SDK_INT >= 33 && shader != null) {
        layer.renderEffect = shader.effect(
            boundsPx = capture.size,
            density = density,
            platterPx = platter.translate(-capture.left, -capture.top),
            radiusPx = radius,
            style = style,
            dropletPx = droplet?.translate(-capture.left, -capture.top),
            blendPx = blend,
            rim = rim,
        )
        translate(capture.left, capture.top) { drawLayer(layer) }
    } else {
        layer.renderEffect = fallbackFilterCache?.get(style.saturation, style.brightness)
            ?: prismOpticsColorFilter(style.saturation, style.brightness).asComposeRenderEffect()
        val path = roundRectPath(platter, radius)
        if (droplet != null && droplet.width > 0f) path.op(path, roundRectPath(droplet, droplet.minDimension / 2f), PathOperation.Union)
        clipPath(path) {
            translate(capture.left, capture.top) { drawLayer(layer) }
            drawPrismFallbackOverlay(path, shape, style, 1f)
        }
    }
}
