// Adapted from chen08209/prism_ui under GPL-3.0. See apps/gui/third-party/prism-ui/.
package io.github.modao7067.luma.gui.ui

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

internal class GlassScene(
    val layer: GraphicsLayer,
    val regularLayer: GraphicsLayer,
    val hazeLayer: GraphicsLayer,
    val regularScale: Float = PrismGlassDefaults.regularScale,
    val hazeScale: Float = PrismGlassDefaults.hazeScale,
) {
    var rootCoordinates: androidx.compose.ui.layout.LayoutCoordinates? = null
    var regularConsumers by mutableIntStateOf(0)
        internal set
    var hazeConsumers by mutableIntStateOf(0)
        internal set
    fun localOffsetOf(coordinates: androidx.compose.ui.layout.LayoutCoordinates): Offset {
        val root = rootCoordinates
        return if (root != null && root.isAttached && coordinates.isAttached) {
            root.localPositionOf(coordinates, Offset.Zero)
        } else {
            Offset.Zero
        }
    }
}
internal val LocalGlassScene = staticCompositionLocalOf<GlassScene?> { null }

internal val FloatingChromeSize = 44.dp
internal val FloatingChromeRadius = 22.dp
internal val FloatingChromeHorizontalPadding = 12.dp
internal val FloatingChromeVerticalPadding = 6.dp
internal val FloatingOverlayBottomGap = 8.dp

@Composable
internal fun Modifier.liquidClickable(
    enabled: Boolean = true,
    stretch: Boolean = true,
    blockDrawerSwipe: Boolean = true,
    press: ElasticPressState = rememberElasticPress(),
    onClick: () -> Unit,
): Modifier {
    val haptic = LocalHapticFeedback.current
    return this
        .elasticPress(enabled = enabled, stretch = stretch, state = press)
        .blockHorizontalPageSwipe(enabled = enabled && blockDrawerSwipe)
        .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            enabled = enabled,
        ) {
            haptic.tick()
            onClick()
        }
}

internal class PrismBackdropBlur(
    val regularScale: Float,
    val hazeScale: Float,
    val regularEffect: androidx.compose.ui.graphics.RenderEffect,
    val hazeEffect: androidx.compose.ui.graphics.RenderEffect,
)

@Composable
internal fun rememberPrismBackdropBlur(density: Float): PrismBackdropBlur = remember(density) {
    val regScale = PrismGlassDefaults.regularScaleFor(density)
    val hzScale = PrismGlassDefaults.hazeScaleFor(density)
    val regSigma = PrismGlassDefaults.effectiveCaptureSigma(PrismGlassDefaults.regularBlurDp, density, regScale)
    val regRadius = PrismGlassDefaults.blurSigmaToAndroidRadius(regSigma)
    val step = hzScale / regScale
    val targetHazeSigma = PrismGlassDefaults.effectiveCaptureSigma(PrismGlassDefaults.hazeBlurDp, density, hzScale)
    val existingHazeSigma = PrismGlassDefaults.regularBlurDp * density * regScale * step
    val extraHazeSigma = PrismGlassDefaults.cascadeExtraSigma(targetHazeSigma, existingHazeSigma)
    val hazeRadius = PrismGlassDefaults.blurSigmaToAndroidRadius(extraHazeSigma)
    PrismBackdropBlur(
        regularScale = regScale,
        hazeScale = hzScale,
        regularEffect = RenderEffect.createBlurEffect(regRadius, regRadius, Shader.TileMode.CLAMP).asComposeRenderEffect(),
        hazeEffect = RenderEffect.createBlurEffect(hazeRadius, hazeRadius, Shader.TileMode.CLAMP).asComposeRenderEffect(),
    )
}

@Composable
internal fun LiquidScaffold(
    topBar: @Composable () -> Unit,
    bottomBar: @Composable () -> Unit,
    wrapTop: Boolean = true,
    wrapBottom: Boolean = true,
    floatingBottomClearance: Dp = 0.dp,
    edgeToEdgeContent: Boolean = true,
    overlay: @Composable BoxScope.(PaddingValues) -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val density = LocalDensity.current
    val backdropBlur = rememberPrismBackdropBlur(density.density)
    val backdrop = rememberGraphicsLayer()
    val regularBackdrop = rememberGraphicsLayer()
    val hazeBackdrop = rememberGraphicsLayer()
    val glassScene = remember(backdrop, regularBackdrop, hazeBackdrop, backdropBlur) {
        GlassScene(backdrop, regularBackdrop, hazeBackdrop, backdropBlur.regularScale, backdropBlur.hazeScale)
    }
    val menuHost = remember { MorphMenuHost() }
    var topInnerHeight by remember { mutableIntStateOf(0) }
    var bottomInnerHeight by remember { mutableIntStateOf(0) }
    val background = MaterialTheme.colorScheme.background
    val fullInsets = WindowInsets.safeDrawing.asPaddingValues()
    val contentTopInset = if (edgeToEdgeContent) fullInsets.calculateTopPadding() else 0.dp
    val contentBottomInset = if (edgeToEdgeContent) maxOf(fullInsets.calculateBottomPadding(), with(density) { WindowInsets.ime.getBottom(this).toDp() }) else 0.dp
    val contentLeftInset = if (edgeToEdgeContent) fullInsets.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr) else 0.dp
    val contentRightInset = if (edgeToEdgeContent) fullInsets.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr) else 0.dp
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val lineHeight = bodyLineHeight()
    val targetGap = conversationReadingGap(imeVisible, lineHeight)
    val readingGap by animateDpAsState(targetGap, spatialDpSpring(), label = "scaffold-reading-gap")
    val vPadPx = with(density) { FloatingChromeVerticalPadding.roundToPx() }
    val topHeight = if (topInnerHeight > 0) topInnerHeight + vPadPx * 2 else 0
    val bottomHeight = if (bottomInnerHeight > 0) bottomInnerHeight + vPadPx * 2 else 0
    val topDp = with(density) { topHeight.toDp() }
    val bottomDp = with(density) { bottomHeight.toDp() }
    val bottomChromeDp = if (bottomInnerHeight > 0) {
        with(density) { bottomInnerHeight.toDp() } + FloatingChromeVerticalPadding
    } else {
        FloatingChromeVerticalPadding
    }
    CompositionLocalProvider(
        LocalContentColor provides MaterialTheme.colorScheme.onSurface,
        LocalGlassScene provides glassScene,
        LocalMorphMenuHost provides menuHost,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .then(if (edgeToEdgeContent) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing).imePadding())
                .onGloballyPositioned {
                    glassScene.rootCoordinates = it
                    menuHost.root = it
                }
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        val upper = ((topHeight.toFloat() + contentTopInset.toPx()) * 1.18f).coerceIn(1f, size.height.coerceAtLeast(1f))
                        val lower = ((bottomHeight.toFloat() + contentBottomInset.toPx()) * 1.12f).coerceIn(1f, size.height.coerceAtLeast(1f))
                        val lowerStart = (size.height - lower).coerceAtLeast(0f)
                        val regularSize = IntSize(
                            ceil(size.width * backdropBlur.regularScale).toInt().coerceAtLeast(1),
                            ceil(size.height * backdropBlur.regularScale).toInt().coerceAtLeast(1),
                        )
                        val hazeSize = IntSize(
                            ceil(size.width * backdropBlur.hazeScale).toInt().coerceAtLeast(1),
                            ceil(size.height * backdropBlur.hazeScale).toInt().coerceAtLeast(1),
                        )
                        val hazeStepScale = backdropBlur.hazeScale / backdropBlur.regularScale
                        regularBackdrop.renderEffect = backdropBlur.regularEffect
                        hazeBackdrop.renderEffect = backdropBlur.hazeEffect
                        val upperBrush = Brush.verticalGradient(
                            0f to background.copy(alpha = 0.95f),
                            0.62f to background.copy(alpha = 0.62f),
                            1f to background.copy(alpha = 0f),
                            startY = 0f,
                            endY = upper,
                        )
                        val lowerBrush = Brush.verticalGradient(
                            0f to background.copy(alpha = 0f),
                            0.38f to background.copy(alpha = 0.62f),
                            1f to background.copy(alpha = 0.96f),
                            startY = lowerStart,
                            endY = size.height,
                        )
                        onDrawWithContent {
                            val needHaze = glassScene.hazeConsumers > 0 || menuHost.entries.isNotEmpty()
                            val needRegular = needHaze || glassScene.regularConsumers > 0
                            if (needRegular) {
                                backdrop.record {
                                    drawRect(background)
                                    this@onDrawWithContent.drawContent()
                                }
                                drawLayer(backdrop)
                                regularBackdrop.record(size = regularSize) {
                                    scale(backdropBlur.regularScale, backdropBlur.regularScale, pivot = Offset.Zero) {
                                        drawLayer(backdrop)
                                    }
                                }
                                if (needHaze) {
                                    hazeBackdrop.record(size = hazeSize) {
                                        scale(hazeStepScale, hazeStepScale, pivot = Offset.Zero) {
                                            drawLayer(regularBackdrop)
                                        }
                                    }
                                }
                            } else {
                                this@onDrawWithContent.drawContent()
                            }
                            drawRect(
                                brush = upperBrush,
                                topLeft = Offset.Zero,
                                size = Size(size.width, upper),
                            )
                            drawRect(
                                brush = lowerBrush,
                                topLeft = Offset(0f, lowerStart),
                                size = Size(size.width, (size.height - lowerStart).coerceAtLeast(0f)),
                            )
                        }
                    }
                    .background(background),
            ) {
                CompositionLocalProvider(LocalGlassScene provides null) {
                    content(
                        PaddingValues(
                            top = topDp + 10.dp + contentTopInset,
                            bottom = maxOf(bottomDp + readingGap, bottomChromeDp + floatingBottomClearance) + contentBottomInset,
                            start = contentLeftInset, end = contentRightInset,
                        ),
                    )
                }
            }
            Box(Modifier.fillMaxSize()) {
                overlay(PaddingValues(top = topDp + 10.dp + contentTopInset, bottom = bottomChromeDp + contentBottomInset))
            }
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .then(if (edgeToEdgeContent) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)) else Modifier)
                    .padding(horizontal = FloatingChromeHorizontalPadding, vertical = FloatingChromeVerticalPadding)
                    .onSizeChanged { topInnerHeight = it.height },
            ) {
                if (wrapTop) LiquidGlass(Modifier.fillMaxWidth(), content = topBar) else topBar()
            }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .then(if (edgeToEdgeContent) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)).imePadding() else Modifier)
                    .padding(horizontal = FloatingChromeHorizontalPadding, vertical = FloatingChromeVerticalPadding)
                    .onSizeChanged { bottomInnerHeight = it.height },
            ) {
                if (wrapBottom) LiquidGlass(Modifier.fillMaxWidth(), style = PrismGlassStyle.dock(MaterialTheme.colorScheme), shadow = GlassShadow.Dock, content = bottomBar) else bottomBar()
            }
            MorphMenuLayer(
                menuHost,
                glassScene,
                if (edgeToEdgeContent) WindowInsets.safeDrawing.union(WindowInsets.ime) else WindowInsets(0, 0, 0, 0),
            )
        }
    }
}

internal enum class GlassShadow { None, Dock }

@Composable
internal fun LiquidGlass(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    tonalTint: Color? = null,
    @Suppress("UNUSED_PARAMETER") elevated: Boolean = false,
    style: PrismGlassStyle? = null,
    alphaProvider: (() -> Float)? = null,
    shadow: GlassShadow = GlassShadow.None,
    press: ElasticPressState? = null,
    content: @Composable () -> Unit,
) {
    val scene = LocalGlassScene.current
    if (scene != null) {
        val resolvedStyle = style ?: PrismGlassStyle.regular(MaterialTheme.colorScheme, tonalTint)
        LiquidPanel(scene, modifier, cornerRadius, resolvedStyle, alphaProvider, shadow, press, content)
    } else {
        LiquidSurface(
            modifier = if (alphaProvider != null) modifier.graphicsLayer { alpha = alphaProvider().coerceIn(0f, 1f) } else modifier,
            cornerRadius = cornerRadius,
            tonalTint = tonalTint,
            shape = RoundedCornerShape(cornerRadius),
            content = content,
        )
    }
}

internal object ComposerGlassDefaults {
    const val blurRadiusDp = PrismGlassDefaults.hazeBlurDp
    const val containerAlpha = PrismGlassDefaults.dockLightAlpha
}

@Composable
internal fun ComposerGlass(modifier: Modifier = Modifier, content: @Composable () -> Unit) = LiquidGlass(
    modifier = modifier.blockHorizontalPageSwipe(),
    cornerRadius = 24.dp,
    style = PrismGlassStyle.dock(MaterialTheme.colorScheme),
    shadow = GlassShadow.Dock,
    content = content,
)

@Composable
internal fun LiquidSurface(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    tonalTint: Color? = null,
    shape: androidx.compose.ui.graphics.Shape = LumaCardShape(cornerRadius),
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .clip(shape)
            .background(tonalTint ?: MaterialTheme.colorScheme.surfaceContainerHigh),
    ) { content() }
}

@Composable
private fun LiquidPanel(
    scene: GlassScene,
    modifier: Modifier,
    cornerRadius: Dp,
    style: PrismGlassStyle,
    alphaProvider: (() -> Float)? = null,
    shadow: GlassShadow = GlassShadow.None,
    press: ElasticPressState? = null,
    content: @Composable () -> Unit,
) {
    DisposableEffect(scene, style.useHaze) {
        if (style.useHaze) scene.hazeConsumers++ else scene.regularConsumers++
        onDispose {
            if (style.useHaze) {
                scene.hazeConsumers = (scene.hazeConsumers - 1).coerceAtLeast(0)
            } else {
                scene.regularConsumers = (scene.regularConsumers - 1).coerceAtLeast(0)
            }
        }
    }
    val layer = rememberGraphicsLayer()
    val hasDockShadow = shadow == GlassShadow.Dock
    val dropShadowLayer = if (hasDockShadow) rememberGraphicsLayer() else null
    val contactShadowLayer = if (hasDockShadow) rememberGraphicsLayer() else null
    var offset by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val radius = with(density) { cornerRadius.toPx() }
    val scheme = MaterialTheme.colorScheme
    val darkSurface = scheme.surface.luminance() <= 0.5f
    val shadowStrength = if (darkSurface) 3f else 1f
    val dropBlurPx = if (hasDockShadow) with(density) { 24.dp.toPx() } else 0f
    val dropOffsetY = if (hasDockShadow) with(density) { 8.dp.toPx() } else 0f
    val contactBlurPx = if (hasDockShadow) with(density) { 3.dp.toPx() } else 0f
    val contactOffsetY = if (hasDockShadow) with(density) { 1.dp.toPx() } else 0f
    val dropShadowScale = 0.25f
    val dropBlur = remember(hasDockShadow, dropBlurPx) {
        if (hasDockShadow) {
            val scaled = dropBlurPx * dropShadowScale
            RenderEffect.createBlurEffect(scaled, scaled, Shader.TileMode.DECAL).asComposeRenderEffect()
        } else null
    }
    val contactBlur = remember(hasDockShadow, contactBlurPx) {
        if (hasDockShadow) RenderEffect.createBlurEffect(contactBlurPx, contactBlurPx, Shader.TileMode.DECAL).asComposeRenderEffect() else null
    }
    val shader = remember { if (Build.VERSION.SDK_INT >= 33) PrismGlassShader() else null }
    val fallbackFilter = remember(style.saturation, style.brightness) {
        if (Build.VERSION.SDK_INT < 33) prismOpticsColorFilter(style.saturation, style.brightness).asComposeRenderEffect() else null
    }
    val sourceLayer = if (style.useHaze) scene.hazeLayer else scene.regularLayer
    val sourceScale = if (style.useHaze) scene.hazeScale else scene.regularScale
    val invScale = 1f / sourceScale
    Box(
        Modifier
            .then(modifier)
            .onGloballyPositioned {
                val next = scene.localOffsetOf(it)
                if (offset != next) offset = next
            }
            .drawWithCache {
                val r = radius.coerceAtMost(size.minDimension / 2f)
                val panelSize = size
                val platterRect = Rect(0f, 0f, size.width, size.height)
                val silhouette = if (hasDockShadow || Build.VERSION.SDK_INT < 33) {
                    Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(r))) }
                } else null
                val dropApron = dropBlurPx * 2f + 2f
                val contactApron = contactBlurPx * 2f + 2f
                if (hasDockShadow && dropShadowLayer != null && contactShadowLayer != null) {
                    val dropSize = IntSize(
                        ceil((size.width + dropApron * 2) * dropShadowScale).toInt().coerceAtLeast(1),
                        ceil((size.height + dropApron * 2) * dropShadowScale).toInt().coerceAtLeast(1),
                    )
                    dropShadowLayer.record(size = dropSize) {
                        scale(dropShadowScale, dropShadowScale, pivot = Offset.Zero) {
                            drawRoundRect(
                                Color.Black.copy(alpha = 0.08f * shadowStrength),
                                topLeft = Offset(dropApron, dropApron),
                                size = panelSize,
                                cornerRadius = CornerRadius(r),
                            )
                        }
                    }
                    dropShadowLayer.renderEffect = dropBlur
                    val contactSize = IntSize(ceil(size.width + contactApron * 2).toInt(), ceil(size.height + contactApron * 2).toInt())
                    contactShadowLayer.record(size = contactSize) {
                        drawRoundRect(
                            Color.Black.copy(alpha = 0.04f * shadowStrength),
                            topLeft = Offset(contactApron, contactApron),
                            size = panelSize,
                            cornerRadius = CornerRadius(r),
                        )
                    }
                    contactShadowLayer.renderEffect = contactBlur
                }
                val captureSize = IntSize(ceil(size.width).toInt().coerceAtLeast(1), ceil(size.height).toInt().coerceAtLeast(1))
                if (Build.VERSION.SDK_INT < 33) {
                    layer.renderEffect = fallbackFilter
                }
                onDrawWithContent {
                    val a = (alphaProvider?.invoke() ?: 1f).coerceIn(0f, 1f)
                    if (a > 0f) {
                        if (hasDockShadow && dropShadowLayer != null && contactShadowLayer != null && silhouette != null) {
                            clipPath(silhouette, clipOp = ClipOp.Difference) {
                                if (dropShadowLayer.alpha != a) dropShadowLayer.alpha = a
                                translate(-dropApron, -dropApron + dropOffsetY) {
                                    scale(1f / dropShadowScale, 1f / dropShadowScale, pivot = Offset.Zero) {
                                        drawLayer(dropShadowLayer)
                                    }
                                }
                                if (contactShadowLayer.alpha != a) contactShadowLayer.alpha = a
                                translate(-contactApron, -contactApron + contactOffsetY) { drawLayer(contactShadowLayer) }
                            }
                        }
                        layer.record(size = captureSize) {
                            scale(invScale, invScale, pivot = Offset.Zero) {
                                translate(-offset.x * sourceScale, -offset.y * sourceScale) {
                                    drawLayer(sourceLayer)
                                }
                            }
                        }
                        if (Build.VERSION.SDK_INT >= 33 && shader != null) {
                            val glowStrength = press?.glowStrength ?: 0f
                            val glowCenter = if (glowStrength > 0.0005f && press != null) press.glowCenter(size) else Offset.Zero
                            val glowReach = if (glowStrength > 0.0005f && press != null) press.glowReach(size) else 1f
                            layer.renderEffect = shader.effect(
                                boundsPx = size,
                                density = density.density,
                                platterPx = platterRect,
                                radiusPx = r,
                                style = style,
                                alpha = a,
                                glowCenterPx = glowCenter,
                                glowStrength = glowStrength,
                                glowReachPx = glowReach,
                            )
                            layer.alpha = 1f
                            drawLayer(layer)
                        } else if (silhouette != null) {
                            layer.alpha = a
                            clipPath(silhouette) {
                                drawLayer(layer)
                                drawPrismFallbackOverlay(silhouette, platterRect, style, a)
                            }
                        }
                        this@onDrawWithContent.drawContent()
                    }
                }
            },
    ) {
        if (alphaProvider != null) {
            Box(Modifier.graphicsLayer { alpha = alphaProvider().coerceIn(0f, 1f) }) { content() }
        } else {
            content()
        }
    }
}

internal fun conversationReadingGap(imeVisible: Boolean, lineHeight: Dp): Dp =
    if (imeVisible) lineHeight * 3 else 12.dp

internal fun floatingConversationClearance(visible: Boolean): Dp =
    if (visible) FloatingChromeSize + FloatingOverlayBottomGap * 2 else 0.dp

internal fun steadyConversationBottomPaddingDp(
    floatingClearance: Dp,
    safeBottomInset: Dp,
): Dp {
    val steadyBottomDp = 48.dp + FloatingChromeVerticalPadding * 2
    val steadyChromeDp = 48.dp + FloatingChromeVerticalPadding
    val steadyGapDp = conversationReadingGap(false, 0.dp)
    return maxOf(steadyBottomDp + steadyGapDp, steadyChromeDp + floatingClearance) + safeBottomInset
}
