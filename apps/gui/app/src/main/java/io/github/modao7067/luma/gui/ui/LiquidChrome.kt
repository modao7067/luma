// Lens portions adapted from Kyant0 / SukiSU under Apache-2.0.
// Source details and notices: apps/gui/third-party/sukisu-glass.
package io.github.modao7067.luma.gui.ui

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import androidx.compose.ui.unit.dp

internal class GlassScene(val layer: GraphicsLayer) {
    var rootCoordinates: androidx.compose.ui.layout.LayoutCoordinates? = null
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
    cornerRadius: Dp = 22.dp,
    pressScale: Float = 0.96f,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(cornerRadius),
    onClick: () -> Unit,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scaleState = animateFloatAsState(
        targetValue = if (enabled && pressed) pressScale else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "liquid-press-scale",
    )
    return this
        .graphicsLayer {
            val s = scaleState.value
            scaleX = s
            scaleY = s
        }
        .clip(shape)
        .clickable(
            interactionSource = interactionSource,
            indication = androidx.compose.foundation.LocalIndication.current,
            enabled = enabled,
            onClick = onClick,
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
    val backdrop = rememberGraphicsLayer()
    val glassScene = remember(backdrop) { GlassScene(backdrop) }
    var topInnerHeight by remember { mutableIntStateOf(0) }
    var bottomInnerHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
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
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .then(if (edgeToEdgeContent) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing).imePadding())
                .onGloballyPositioned {
                    glassScene.rootCoordinates = it
                }
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        val upper = ((topHeight.toFloat() + contentTopInset.toPx()) * 1.18f).coerceIn(1f, size.height.coerceAtLeast(1f))
                        val lower = ((bottomHeight.toFloat() + contentBottomInset.toPx()) * 1.12f).coerceIn(1f, size.height.coerceAtLeast(1f))
                        val lowerStart = (size.height - lower).coerceAtLeast(0f)
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
                            backdrop.record {
                                drawRect(background)
                                this@onDrawWithContent.drawContent()
                            }
                            drawLayer(backdrop)
                            drawRect(
                                brush = upperBrush,
                                topLeft = Offset.Zero,
                                size = androidx.compose.ui.geometry.Size(size.width, upper),
                            )
                            drawRect(
                                brush = lowerBrush,
                                topLeft = Offset(0f, lowerStart),
                                size = androidx.compose.ui.geometry.Size(size.width, (size.height - lowerStart).coerceAtLeast(0f)),
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
                if (wrapBottom) LiquidGlass(Modifier.fillMaxWidth(), content = bottomBar) else bottomBar()
            }
        }
    }
}

@Composable
internal fun LiquidGlass(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    tonalTint: Color? = null,
    @Suppress("UNUSED_PARAMETER") elevated: Boolean = false,
    blurRadius: Dp = SukiGlassDefaults.blurRadiusDp.dp,
    containerAlpha: Float = SukiGlassDefaults.containerAlpha,
    alphaProvider: (() -> Float)? = null,
    content: @Composable () -> Unit,
) {
    val scene = LocalGlassScene.current
    if (scene != null) {
        LiquidPanel(scene, modifier, cornerRadius, tonalTint, blurRadius, containerAlpha, alphaProvider, content)
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
    const val blurRadiusDp = 12f
    const val containerAlpha = 0.82f
}

@Composable
internal fun ComposerGlass(modifier: Modifier = Modifier, content: @Composable () -> Unit) = LiquidGlass(
    modifier = modifier,
    cornerRadius = 24.dp,
    blurRadius = ComposerGlassDefaults.blurRadiusDp.dp,
    containerAlpha = ComposerGlassDefaults.containerAlpha,
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
    tonalTint: Color?,
    blurRadius: Dp,
    containerAlpha: Float,
    alphaProvider: (() -> Float)? = null,
    content: @Composable () -> Unit,
) {
    val backdrop = scene.layer
    val layer = rememberGraphicsLayer()
    val shadowLayer = rememberGraphicsLayer()
    var offset by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val radius = with(density) { cornerRadius.toPx() }
    val blur = with(density) { blurRadius.toPx() }
    val samplePadding = with(density) { SukiGlassDefaults.samplePaddingDp.dp.toPx() }
    val refractionHeight = with(density) { SukiGlassDefaults.refractionHeightDp.dp.toPx() }
    val refractionAmount = with(density) { SukiGlassDefaults.refractionAmountDp.dp.toPx() }
    val scheme = MaterialTheme.colorScheme
    val tint = tonalTint ?: scheme.surfaceContainerHigh
    val shadowAlpha = if (scheme.surface.luminance() > 0.5f) SukiGlassDefaults.lightShadowAlpha else SukiGlassDefaults.darkShadowAlpha
    val shadowRadius = with(density) { SukiGlassDefaults.shadowRadiusDp.dp.toPx() }
    val shadowBlur = remember(shadowRadius) {
        RenderEffect.createBlurEffect(shadowRadius, shadowRadius, Shader.TileMode.DECAL).asComposeRenderEffect()
    }
    val lens = remember { if (Build.VERSION.SDK_INT >= 33) LiquidLens() else null }
    val highlight = remember { if (Build.VERSION.SDK_INT >= 33) SukiGlassHighlight() else null }
    val frost = remember(blur) { sukiGlassFrost(blur).asComposeRenderEffect() }
    Box(
        Modifier
            .drawWithCache {
                val r = radius.coerceAtMost(size.minDimension / 2f)
                val apron = shadowRadius * 3f
                val shadowSize = IntSize(ceil(size.width + apron * 2).toInt(), ceil(size.height + apron * 2).toInt())
                val panelSize = size
                shadowLayer.record(size = shadowSize) {
                    drawRoundRect(Color.Black.copy(alpha = shadowAlpha), topLeft = Offset(apron, apron), size = panelSize, cornerRadius = CornerRadius(r))
                }
                shadowLayer.renderEffect = shadowBlur
                onDrawBehind {
                    val a = (alphaProvider?.invoke() ?: 1f).coerceIn(0f, 1f)
                    if (a > 0f) {
                        shadowLayer.alpha = a
                        translate(-apron, -apron) { drawLayer(shadowLayer) }
                    }
                }
            }
            .then(modifier)
            .onGloballyPositioned {
                val next = scene.localOffsetOf(it)
                if (offset != next) offset = next
            }
            .drawWithCache {
                val r = radius.coerceAtMost(minOf(size.width, size.height) / 2f)
                val silhouette = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(r))) }
                val captureSize = IntSize(ceil(size.width + 2 * samplePadding).toInt(), ceil(size.height + 2 * samplePadding).toInt())
                layer.renderEffect = if (Build.VERSION.SDK_INT >= 33 && lens != null) {
                    lens.effect(size.width, size.height, r, blur, samplePadding, refractionHeight, refractionAmount)
                } else frost
                onDrawWithContent {
                    val a = (alphaProvider?.invoke() ?: 1f).coerceIn(0f, 1f)
                    if (a > 0f) {
                        layer.alpha = a
                        layer.record(size = captureSize) {
                            translate(samplePadding - offset.x, samplePadding - offset.y) { drawLayer(backdrop) }
                        }
                        clipPath(silhouette) {
                            translate(-samplePadding, -samplePadding) { drawLayer(layer) }
                            drawRoundRect(tint.copy(alpha = containerAlpha * a), cornerRadius = CornerRadius(r))
                            if (Build.VERSION.SDK_INT >= 33 && highlight != null) {
                                with(highlight) { draw(r, alpha = a) }
                            }
                            this@onDrawWithContent.drawContent()
                        }
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

private fun sukiGlassFrost(blurRadius: Float): RenderEffect {
    val saturation = SukiGlassDefaults.saturation
    val r = (1f - saturation) * 0.2126f
    val g = (1f - saturation) * 0.7152f
    val b = (1f - saturation) * 0.0722f
    val matrix = ColorMatrix(floatArrayOf(
        r + saturation, g, b, 0f, 0f,
        r, g + saturation, b, 0f, 0f,
        r, g, b + saturation, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    ))
    val vibrancy = RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(matrix))
    val nativeRadius = SukiGlassDefaults.nativeBlurRadius(blurRadius)
    return RenderEffect.createChainEffect(
        RenderEffect.createBlurEffect(nativeRadius, nativeRadius, Shader.TileMode.CLAMP),
        vibrancy,
    )
}

/** Adapted Apache-2.0 lens from SukiSU/Kyant0. See third-party/sukisu-glass. */
@RequiresApi(33)
private class LiquidLens {
    private val shader = RuntimeShader(
        """
        uniform shader scene;
        uniform float2 dimensions;
        uniform float radius;
        uniform float samplePadding;
        uniform float refractionHeight;
        uniform float refractionAmount;

        float sdRoundedRect(float2 coord, float2 halfSize, float r) {
            float2 cornerCoord = abs(coord) - (halfSize - float2(r));
            float outside = length(max(cornerCoord, 0.0)) - r;
            float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
            return outside + inside;
        }

        float2 gradSdRoundedRect(float2 coord, float2 halfSize, float r) {
            float2 cornerCoord = abs(coord) - (halfSize - float2(r));
            if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
                float2 m = max(cornerCoord, 0.0);
                return sign(coord) * (m / max(length(m), 0.0001));
            } else {
                float gradX = step(cornerCoord.y, cornerCoord.x);
                return sign(coord) * float2(gradX, 1.0 - gradX);
            }
        }

        float circleMap(float x) {
            float clamped = clamp(x, 0.0, 1.0);
            return 1.0 - sqrt(max(0.0, 1.0 - clamped * clamped));
        }

        half4 main(float2 p) {
            float2 halfSize = dimensions * 0.5;
            float2 centered = p - samplePadding - halfSize;
            float sd = sdRoundedRect(centered, halfSize, radius);
            if (-sd >= refractionHeight) {
                return scene.eval(p);
            }
            sd = min(sd, 0.0);
            float d = circleMap(1.0 - (-sd) / max(refractionHeight, 1.0)) * (-refractionAmount);
            float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
            float2 grad = gradSdRoundedRect(centered, halfSize, gradRadius);
            grad = grad / max(length(grad), 0.0001);
            float2 refracted = p + d * grad;
            return scene.eval(refracted);
        }
        """.trimIndent(),
    )
    private var lastWidth = -1f
    private var lastHeight = -1f
    private var lastRadius = -1f
    private var lastBlur = -1f
    private var lastPadding = -1f
    private var lastRefractionHeight = -1f
    private var lastRefractionAmount = -1f
    private var cached: androidx.compose.ui.graphics.RenderEffect? = null
    fun effect(
        width: Float,
        height: Float,
        radius: Float,
        blur: Float,
        samplePadding: Float,
        refractionHeight: Float,
        refractionAmount: Float,
    ): androidx.compose.ui.graphics.RenderEffect {
        if (width != lastWidth || height != lastHeight || radius != lastRadius || blur != lastBlur || samplePadding != lastPadding ||
            refractionHeight != lastRefractionHeight || refractionAmount != lastRefractionAmount || cached == null) {
            shader.setFloatUniform("dimensions", width.coerceAtLeast(1f), height.coerceAtLeast(1f))
            shader.setFloatUniform("radius", radius.coerceAtLeast(0f))
            shader.setFloatUniform("samplePadding", samplePadding)
            shader.setFloatUniform("refractionHeight", refractionHeight.coerceAtLeast(1f))
            shader.setFloatUniform("refractionAmount", refractionAmount.coerceAtLeast(0f))
            cached = RenderEffect.createChainEffect(
                RenderEffect.createRuntimeShaderEffect(shader, "scene"),
                sukiGlassFrost(blur),
            ).asComposeRenderEffect()
            lastWidth = width
            lastHeight = height
            lastRadius = radius
            lastBlur = blur
            lastPadding = samplePadding
            lastRefractionHeight = refractionHeight
            lastRefractionAmount = refractionAmount
        }
        return cached!!
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
