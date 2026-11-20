// Adapted from chen08209/prism_ui under GPL-3.0. See apps/gui/third-party/prism-ui/.
package io.github.modao7067.luma.gui.ui

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.RequiresApi
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

internal object PrismGlassDefaults {
    const val regularBlurDp = 2.5f
    const val hazeBlurDp = 12f
    const val regularScale = 0.5f
    const val hazeScale = 0.25f
    const val regularLightAlpha = 0x8A / 255f
    const val regularDarkAlpha = 0.62f
    const val dockLightAlpha = 0.82f
    const val dockDarkAlpha = 0.78f
    const val hairlineWidthDp = 0.67f
    private const val captureFrostPx = 2f
    private const val maxCaptureDivisor = 8
    private const val captureSoftnessPx = 0.5f

    fun captureScale(blurDp: Float, density: Float): Float {
        val divisor = floor(blurDp * density / captureFrostPx).toInt().coerceIn(1, maxCaptureDivisor)
        return 1f / divisor
    }

    fun regularScaleFor(density: Float): Float = captureScale(regularBlurDp, density)

    fun hazeScaleFor(density: Float): Float = captureScale(hazeBlurDp, density)

    fun effectiveCaptureSigma(blurDp: Float, density: Float, scale: Float): Float {
        val frost = blurDp * density * scale
        val softness = if (scale < 0.999f) captureSoftnessPx else 0f
        return if (frost > softness) sqrt(frost * frost - softness * softness) else 0f
    }

    fun blurSigmaToAndroidRadius(sigmaPx: Float): Float =
        ((sigmaPx - 0.5f) / 0.57735f).coerceAtLeast(0.01f)

    fun cascadeExtraSigma(targetSigmaPx: Float, existingSigmaPx: Float): Float =
        sqrt(max(0f, targetSigmaPx * targetSigmaPx - existingSigmaPx * existingSigmaPx))
}

internal fun prismDarkGlassTint(scheme: ColorScheme): Color =
    lerp(scheme.surfaceContainerHigh, Color.White, 0.05f)

@Immutable
internal data class PrismGlassStyle(
    val tint: Color,
    val contrast: Float = 0f,
    val blurDp: Float = PrismGlassDefaults.regularBlurDp,
    val useHaze: Boolean = false,
    val refractionDp: Float = 60f,
    val bevelDp: Float = 20f,
    val saturation: Float = 1.5f,
    val brightness: Float = 1f,
    val rim: Float = 0.28f,
    val rimWidthDp: Float = 1.2f,
    val rimTail: Float = 0.17f,
    val rimFocus: Float = 1f,
    val rimAdded: Float = 0.38f,
    val endShade: Float = 0f,
    val hairline: Float = 0f,
    val hairlineSide: Float = 0.75f,
) {
    companion object {
        fun regular(scheme: ColorScheme, tonalTint: Color? = null): PrismGlassStyle {
            val dark = scheme.surface.luminance() <= 0.5f
            return if (dark) {
                val baseTint = tonalTint ?: prismDarkGlassTint(scheme)
                PrismGlassStyle(
                    tint = baseTint.copy(alpha = PrismGlassDefaults.regularDarkAlpha),
                    contrast = -0.4f,
                    blurDp = PrismGlassDefaults.regularBlurDp,
                    useHaze = false,
                    saturation = 2.84f,
                    rim = 0.28f,
                    rimWidthDp = 1.2f,
                    rimTail = 0.17f,
                    rimFocus = 1f,
                    rimAdded = 0.38f,
                    hairline = 0f,
                    hairlineSide = 0.75f,
                )
            } else {
                val baseTint = tonalTint ?: Color(0xFFFDFDFD)
                PrismGlassStyle(
                    tint = baseTint.copy(alpha = PrismGlassDefaults.regularLightAlpha),
                    contrast = 0.4f,
                    blurDp = PrismGlassDefaults.regularBlurDp,
                    useHaze = false,
                    saturation = 2.08f,
                    rim = 0.28f,
                    rimWidthDp = 1.2f,
                    rimTail = 0.17f,
                    rimFocus = 1f,
                    rimAdded = 0.38f,
                    hairline = 0.25f,
                    hairlineSide = 0.75f,
                )
            }
        }

        fun dock(scheme: ColorScheme, tonalTint: Color? = null): PrismGlassStyle {
            val dark = scheme.surface.luminance() <= 0.5f
            return if (dark) {
                val baseTint = tonalTint ?: prismDarkGlassTint(scheme)
                PrismGlassStyle(
                    tint = baseTint.copy(alpha = PrismGlassDefaults.dockDarkAlpha),
                    contrast = -0.4f,
                    blurDp = PrismGlassDefaults.hazeBlurDp,
                    useHaze = true,
                    saturation = 1.925f,
                    rim = 0.28f,
                    rimWidthDp = 1.2f,
                    rimTail = 0.17f,
                    rimFocus = 1f,
                    rimAdded = 0.38f,
                    hairline = 0f,
                    hairlineSide = 0.75f,
                )
            } else {
                val baseTint = tonalTint ?: Color(0xFFF4F4F4)
                PrismGlassStyle(
                    tint = baseTint.copy(alpha = PrismGlassDefaults.dockLightAlpha),
                    contrast = 0.4f,
                    blurDp = PrismGlassDefaults.hazeBlurDp,
                    useHaze = true,
                    saturation = 2.1f,
                    rim = 0.28f,
                    rimWidthDp = 1.2f,
                    rimTail = 0.17f,
                    rimFocus = 1f,
                    rimAdded = 0.38f,
                    hairline = 0.25f,
                    hairlineSide = 0.75f,
                )
            }
        }

        fun menu(scheme: ColorScheme): PrismGlassStyle {
            val dark = scheme.surface.luminance() <= 0.5f
            return if (dark) {
                PrismGlassStyle(
                    tint = prismDarkGlassTint(scheme).copy(alpha = 0.63f),
                    contrast = -0.53f,
                    blurDp = PrismGlassDefaults.hazeBlurDp,
                    useHaze = true,
                    saturation = 1.75f,
                    brightness = 1.8f,
                    rim = 0f,
                    rimWidthDp = 1.2f,
                    rimTail = 0f,
                    rimFocus = 0f,
                    rimAdded = 0f,
                    hairline = 0f,
                    hairlineSide = 0f,
                )
            } else {
                PrismGlassStyle(
                    tint = scheme.surfaceBright.copy(alpha = 0.55f),
                    contrast = 0f,
                    blurDp = PrismGlassDefaults.hazeBlurDp,
                    useHaze = true,
                    saturation = 2.0f,
                    brightness = 1.1f,
                    rim = 0f,
                    rimWidthDp = 1.2f,
                    rimTail = 0f,
                    rimFocus = 0f,
                    rimAdded = 0f,
                    hairline = 0f,
                    hairlineSide = 0f,
                )
            }
        }

        fun lerp(a: PrismGlassStyle, b: PrismGlassStyle, t: Float): PrismGlassStyle {
            val f = t.coerceIn(0f, 1f)
            return PrismGlassStyle(
                tint = lerp(a.tint, b.tint, f),
                contrast = lerp(a.contrast, b.contrast, f),
                blurDp = lerp(a.blurDp, b.blurDp, f),
                useHaze = if (f >= 0.5f) b.useHaze else a.useHaze,
                refractionDp = lerp(a.refractionDp, b.refractionDp, f),
                bevelDp = lerp(a.bevelDp, b.bevelDp, f),
                saturation = lerp(a.saturation, b.saturation, f),
                brightness = lerp(a.brightness, b.brightness, f),
                rim = lerp(a.rim, b.rim, f),
                rimWidthDp = lerp(a.rimWidthDp, b.rimWidthDp, f),
                rimTail = lerp(a.rimTail, b.rimTail, f),
                rimFocus = lerp(a.rimFocus, b.rimFocus, f),
                rimAdded = lerp(a.rimAdded, b.rimAdded, f),
                endShade = lerp(a.endShade, b.endShade, f),
                hairline = lerp(a.hairline, b.hairline, f),
                hairlineSide = lerp(a.hairlineSide, b.hairlineSide, f),
            )
        }
    }
}

@Immutable
internal data class PrismGlassRim(
    val side: Float,
    val corner: Float,
    val flat: Float,
    val shine: Float,
) {
    fun scale(t: Float): PrismGlassRim {
        val f = t.coerceIn(0f, 1f)
        return PrismGlassRim(side * f, corner * f, flat * f, shine * f)
    }

    fun writeUniforms(widthDp: Float, heightDp: Float, radiusDp: Float, outTurns: FloatArray, outLit: FloatArray) {
        val round = min(max(0f, radiusDp), min(widthDp, heightDp) * 0.5f)
        val halfW = widthDp * 0.5f
        val halfH = heightDp * 0.5f
        var prev = 0f
        outTurns[0] = 0f
        outLit[0] = shine
        for (i in 0 until SHINE_AROUND.size / 3) {
            val along = SHINE_AROUND[i * 3]
            val down = SHINE_AROUND[i * 3 + 1]
            val lit = SHINE_AROUND[i * 3 + 2]
            val raw = atan2(halfW - min(along * round, halfW), halfH - min(down * round, halfH))
            prev = max(prev, raw)
            outTurns[i + 1] = prev
            outLit[i + 1] = shine * lit
        }
        outTurns[7] = (PI * 0.5).toFloat()
        outLit[7] = 0f
    }

    companion object {
        val None = PrismGlassRim(0f, 0f, 0f, 0f)

        fun menu(scheme: ColorScheme): PrismGlassRim =
            if (scheme.surface.luminance() <= 0.5f) {
                PrismGlassRim(side = 0.27f, corner = 0.10f, flat = 0.02f, shine = 0.22f)
            } else {
                PrismGlassRim(side = 0.25f, corner = 0.15f, flat = 0.09f, shine = 0.16f)
            }

        private val SHINE_AROUND = floatArrayOf(
            1.53f, 0.0f, 0.68f,
            1.0f, 0.0f, 0.48f,
            0.3f, 0.3f, 0.38f,
            0.0f, 1.0f, 0.17f,
            0.0f, 1.5f, 0.07f,
            0.0f, 2.0f, 0.03f,
        )

        internal val SHINE_ENDS = floatArrayOf(
            1f / 3f, 2f / 3f, 1f, 5f / 3f,
            7f / 3f, 3f, 11f / 3f, 13f / 3f,
            5f, 5f, 5f, 5f,
        )

        internal val SHINE_SHARES = floatArrayOf(
            1.0f, 0.7f, 0.47f, 0.18f,
            0.12f, 0.085f, 0.065f, 0.045f,
            0.035f, 0f, 0f, 0f,
        )
    }
}

internal data class PrismOctant(
    val n: Float,
    val jointX: Float,
    val jointY: Float,
    val centerX: Float,
    val centerY: Float,
    val arcRadius: Float,
)

private val NO_OCTANT = PrismOctant(0f, 0f, 0f, 0f, 0f, 0f)
private val OCTANT_GAP = 1.0 - sqrt(0.5)
private val SQRT2 = sqrt(2.0)

private val OCTANT_FITS = arrayOf(
    doubleArrayOf(2.0, 2.00000000, 1.13276676),
    doubleArrayOf(2.1, 2.18349805, 1.20311921),
    doubleArrayOf(2.2, 2.33888662, 1.28698796),
    doubleArrayOf(2.3, 2.48660575, 1.36351941),
    doubleArrayOf(2.4, 2.62226596, 1.44717976),
    doubleArrayOf(2.5, 2.75148990, 1.53385819),
    doubleArrayOf(3.0, 3.36298265, 1.98288283),
    doubleArrayOf(3.5, 4.08649929, 2.23811846),
    doubleArrayOf(4.0, 4.85481134, 2.47563463),
    doubleArrayOf(4.5, 5.62945551, 2.72948597),
    doubleArrayOf(5.0, 6.43023796, 2.98020421),
)

internal fun prismOctantFit(ratio: Double): Pair<Double, Double> {
    val last = OCTANT_FITS.last()
    val past = ratio - last[0]
    if (past > 0.0) {
        return Pair(last[1] + 1.559599389 * past, 1.0 - 1.0 / (last[2] + 0.522807185 * past))
    }
    var index = 0
    while (index < OCTANT_FITS.size - 2 && ratio > OCTANT_FITS[index + 1][0]) {
        index++
    }
    val a = OCTANT_FITS[index]
    val b = OCTANT_FITS[index + 1]
    val t = max(0.0, (ratio - a[0]) / (b[0] - a[0]))
    val n = a[1] + (b[1] - a[1]) * t
    val k = a[2] + (b[2] - a[2]) * t
    return Pair(n, 1.0 - 1.0 / k)
}

internal fun prismSuperellipseOctant(halfSide: Float, radius: Float): PrismOctant {
    if (radius <= 1e-3f || halfSide <= 1e-3f) return NO_OCTANT
    val a = halfSide.toDouble()
    val r = min(radius.toDouble(), a)
    val (n, jointAt) = prismOctantFit(a * 2.0 / r)
    val jx = jointAt * a
    val jy = (1.0 - jointAt.pow(n)).coerceAtLeast(0.0).pow(1.0 / n) * a
    val tangent = if (jy > 1e-9) (jx / jy).pow(n - 1.0) else 0.0
    val diagonal = (jx - tangent * jy) / (1.0 - tangent)
    val meet = a - OCTANT_GAP * r
    val arc = (meet - diagonal) * SQRT2
    val chordX = meet - jx
    val chordY = meet - jy
    val chordDistSq = chordX * chordX + chordY * chordY
    val chordDist = sqrt(chordDistSq)
    if (chordDist < 1e-9) return NO_OCTANT
    val reach = sqrt(max(0.0, arc * arc - chordDistSq * 0.25))
    val cx = (jx + meet) * 0.5 + (chordY / chordDist) * reach
    val cy = (jy + meet) * 0.5 + (-chordX / chordDist) * reach
    return PrismOctant(
        n = n.toFloat(),
        jointX = jx.toFloat(),
        jointY = jy.toFloat(),
        centerX = cx.toFloat(),
        centerY = cy.toFloat(),
        arcRadius = arc.toFloat(),
    )
}

internal fun prismOpticsColorFilter(saturation: Float, brightness: Float): RenderEffect {
    val red = 0.2126f * brightness
    val green = 0.7152f * brightness
    val blue = 0.0722f * brightness
    val rest = 1f - saturation
    val kept = saturation * brightness
    val matrix = ColorMatrix(
        floatArrayOf(
            red * rest + kept, green * rest, blue * rest, 0f, 0f,
            red * rest, green * rest + kept, blue * rest, 0f, 0f,
            red * rest, green * rest, blue * rest + kept, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
    return RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(matrix))
}

internal fun DrawScope.drawPrismFallbackOverlay(
    path: Path,
    rect: Rect,
    style: PrismGlassStyle,
    alpha: Float,
) {
    val a = alpha.coerceIn(0f, 1f)
    if (a <= 0.001f) return
    drawPath(path, style.tint.copy(alpha = style.tint.alpha * a))
    if (style.rim > 0.001f) {
        val rimAlpha = style.rim * a
        drawPath(
            path,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = rimAlpha),
                    Color.White.copy(alpha = rimAlpha * 0.15f),
                    Color.White.copy(alpha = rimAlpha * 0.5f),
                ),
                start = rect.topLeft,
                end = rect.bottomRight,
            ),
            style = Stroke(width = style.rimWidthDp.dp.toPx() * 2f),
        )
    }
    val hairlineAlpha = ((style.hairline + style.hairlineSide) * 0.5f * 0.3f * a).coerceIn(0f, 1f)
    if (hairlineAlpha > 0.001f) {
        drawPath(
            path,
            color = Color.Black.copy(alpha = hairlineAlpha),
            style = Stroke(width = PrismGlassDefaults.hairlineWidthDp.dp.toPx() * 2f),
        )
    }
}

@RequiresApi(33)
internal class PrismGlassShader {
    private val shader = RuntimeShader(PRISM_GLASS_AGSL).also {
        it.setFloatUniform("uShineEnds", PrismGlassRim.SHINE_ENDS)
        it.setFloatUniform("uShineShares", PrismGlassRim.SHINE_SHARES)
    }
    private val shineTurns = FloatArray(8)
    private val shineLit = FloatArray(8)
    private var lastBoundsW = -1f
    private var lastBoundsH = -1f
    private var lastDensity = -1f
    private var lastPlatter: Rect? = null
    private var lastRadius = -1f
    private var lastDroplet: Rect? = null
    private var lastBlend = -1f
    private var lastStyle: PrismGlassStyle? = null
    private var lastRim: PrismGlassRim? = null
    private var lastGlowX = Float.NaN
    private var lastGlowY = Float.NaN
    private var lastGlowStrength = -1f
    private var lastGlowReach = -1f
    private var lastAlpha = -1f
    private var cached: androidx.compose.ui.graphics.RenderEffect? = null

    fun effect(
        boundsPx: Size,
        density: Float,
        platterPx: Rect,
        radiusPx: Float,
        style: PrismGlassStyle,
        alpha: Float = 1f,
        dropletPx: Rect? = null,
        blendPx: Float = 0f,
        rim: PrismGlassRim = PrismGlassRim.None,
        glowCenterPx: Offset = Offset.Zero,
        glowStrength: Float = 0f,
        glowReachPx: Float = 1f,
    ): androidx.compose.ui.graphics.RenderEffect {
        val d = density.coerceAtLeast(0.5f)
        val bw = boundsPx.width.coerceAtLeast(1f)
        val bh = boundsPx.height.coerceAtLeast(1f)
        val clampedAlpha = alpha.coerceIn(0f, 1f)
        val safeBlend = blendPx.coerceAtLeast(0f)
        val safeRadius = radiusPx.coerceAtLeast(0f)
        val gx = if (glowStrength > 0.0005f) glowCenterPx.x else 0f
        val gy = if (glowStrength > 0.0005f) glowCenterPx.y else 0f
        val gs = if (glowStrength > 0.0005f) glowStrength else 0f
        val gr = if (glowStrength > 0.0005f) glowReachPx.coerceAtLeast(1f) else 1f
        if (cached != null &&
            bw == lastBoundsW && bh == lastBoundsH && d == lastDensity &&
            platterPx == lastPlatter && safeRadius == lastRadius &&
            dropletPx == lastDroplet && safeBlend == lastBlend &&
            style == lastStyle && rim == lastRim &&
            gx == lastGlowX && gy == lastGlowY && gs == lastGlowStrength && gr == lastGlowReach &&
            clampedAlpha == lastAlpha
        ) {
            return cached!!
        }
        val pwDp = (platterPx.width / d).coerceAtLeast(1f)
        val phDp = (platterPx.height / d).coerceAtLeast(1f)
        val halfWDp = pwDp * 0.5f
        val halfHDp = phDp * 0.5f
        val rDp = min(safeRadius / d, min(halfWDp, halfHDp))
        val along = prismSuperellipseOctant(halfWDp, rDp)
        val across = prismSuperellipseOctant(halfHDp, rDp)

        shader.setFloatUniform("uBoundsPx", bw, bh)
        shader.setFloatUniform("uDensity", d, 1f / d)
        shader.setFloatUniform(
            "uPlatter",
            platterPx.center.x / d,
            platterPx.center.y / d,
            halfWDp,
            halfHDp,
        )
        shader.setFloatUniform("uRadius", rDp, style.bevelDp, safeBlend / d, clampedAlpha)
        shader.setFloatUniform("uAlong", along.n, along.jointX, along.jointY, along.arcRadius)
        shader.setFloatUniform("uAcross", across.n, across.jointX, across.jointY, across.arcRadius)
        shader.setFloatUniform("uCenters", along.centerX, along.centerY, across.centerX, across.centerY)
        if (dropletPx != null && dropletPx.width > 0f && dropletPx.height > 0f) {
            shader.setFloatUniform(
                "uDroplet",
                dropletPx.center.x / d,
                dropletPx.center.y / d,
                dropletPx.width * 0.5f / d,
                dropletPx.height * 0.5f / d,
            )
        } else {
            shader.setFloatUniform("uDroplet", 0f, 0f, 0f, 0f)
        }
        shader.setFloatUniform("uOptics", style.refractionDp, style.saturation, style.brightness)
        shader.setFloatUniform("uTint", style.tint.red, style.tint.green, style.tint.blue, style.tint.alpha)
        shader.setFloatUniform("uLight", style.rim, style.rimWidthDp, style.endShade)
        shader.setFloatUniform("uGlow", gx / d, gy / d, gs, style.contrast)
        shader.setFloatUniform("uGlowReach", gr / d)
        shader.setFloatUniform("uRim", style.rimTail, style.rimFocus, style.rimAdded, style.hairlineSide)
        shader.setFloatUniform("uEdge", style.hairline, rim.side, rim.corner, rim.flat)
        if (rim.shine > 0.0005f) {
            rim.writeUniforms(pwDp, phDp, rDp, shineTurns, shineLit)
        } else {
            shineTurns.fill(0f)
            shineLit.fill(0f)
        }
        shader.setFloatUniform("uShineTurns", shineTurns)
        shader.setFloatUniform("uShineLit", shineLit)

        cached = RenderEffect.createRuntimeShaderEffect(shader, "scene").asComposeRenderEffect()
        lastBoundsW = bw
        lastBoundsH = bh
        lastDensity = d
        lastPlatter = platterPx
        lastRadius = safeRadius
        lastDroplet = dropletPx
        lastBlend = safeBlend
        lastStyle = style
        lastRim = rim
        lastGlowX = gx
        lastGlowY = gy
        lastGlowStrength = gs
        lastGlowReach = gr
        lastAlpha = clampedAlpha
        return cached!!
    }
}

internal const val PRISM_GLASS_AGSL = """
uniform shader scene;
uniform float2 uBoundsPx;
uniform float2 uDensity;
uniform float4 uPlatter;
uniform float4 uRadius;
uniform float4 uAlong;
uniform float4 uAcross;
uniform float4 uCenters;
uniform float4 uDroplet;
uniform float3 uOptics;
uniform float4 uTint;
uniform float3 uLight;
uniform float4 uGlow;
uniform float uGlowReach;
uniform float4 uRim;
uniform float4 uEdge;
uniform float4 uShineTurns[2];
uniform float4 uShineLit[2];
uniform float4 uShineEnds[3];
uniform float4 uShineShares[3];

const float3 kLuma = float3(0.2126, 0.7152, 0.0722);
const float kLine = 0.67;
const float kLineCap = 0.298;
const float kCornerGap = 0.03;
const float kEndShade = 3.0;
const float kRimTail = 4.5;

half4 sampleDp(float2 dp) {
    float2 px = clamp(dp * uDensity.x, float2(0.5), uBoundsPx - float2(0.5));
    return scene.eval(px);
}

float roundedBoxDistance(float2 p, float2 halfSize, float radius) {
    float2 q = abs(p) - halfSize + float2(radius);
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
}

float2 roundedBoxNormal(float2 p, float2 halfSize, float radius) {
    float2 q = abs(p) - halfSize + float2(radius);
    float2 n = (q.x > 0.0 && q.y > 0.0)
        ? normalize(q)
        : (q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0));
    return n * float2(p.x < 0.0 ? -1.0 : 1.0, p.y < 0.0 ? -1.0 : 1.0);
}

float octantInset(float2 point, float a, float4 curve, float2 center) {
    float2 fromCenter = point - center;
    float2 toJoint = curve.yz - center;
    if (toJoint.x * fromCenter.y < toJoint.y * fromCenter.x) {
        return curve.w - length(fromCenter);
    }
    float n = curve.x;
    float2 s = max(point, 0.0) / max(a, 1e-4);
    float major = max(max(s.x, s.y), 1e-4);
    float ratio = clamp(min(s.x, s.y) / major, 0.0, 1.0);
    float rp = ratio > 1e-5 ? pow(ratio, n) : 0.0;
    float norm = major * pow(1.0 + rp, 1.0 / n);
    float2 sn = max(s / max(norm, 1e-4), float2(1e-5));
    float2 slope = pow(sn, float2(n - 1.0));
    return a * (1.0 - norm) / max(length(slope), 1e-4);
}

float cornerInset(float2 p, float2 halfSize) {
    float2 q = abs(p);
    if (uAlong.x < 2.0) {
        return min(halfSize.x - q.x, halfSize.y - q.y);
    }
    float c = halfSize.x - halfSize.y;
    if (q.y + c >= q.x) {
        return octantInset(float2(q.x, q.y + c), halfSize.x, uAlong, uCenters.xy);
    }
    return octantInset(float2(q.y, q.x - c), halfSize.y, uAcross, uCenters.zw);
}

float shineAcross(float a, float b) {
    float sum = 0.0;
    float start = 0.0;
    for (int i = 0; i < 3; i++) {
        float4 ends = uShineEnds[i];
        float4 starts = float4(start, ends.xyz);
        sum += dot(uShineShares[i], max(min(float4(b), ends) - max(float4(a), starts), 0.0));
        start = ends.w;
    }
    return sum / max(b - a, 1e-4);
}

float shineAround(float turn) {
    float4 startTurns = uShineTurns[0];
    float4 lastTurns = uShineTurns[1];
    float4 startLit = uShineLit[0];
    float4 lastLit = uShineLit[1];
    float4 firstEnds = float4(startTurns.yzw, lastTurns.x);
    float3 lastEnds = lastTurns.yzw;
    float4 firstRise = float4(startLit.yzw, lastLit.x) - startLit;
    float3 lastRise = lastLit.yzw - lastLit.xyz;
    return startLit.x
        + dot(firstRise, clamp((turn - startTurns) / max(firstEnds - startTurns, 1e-5), 0.0, 1.0))
        + dot(lastRise, clamp((turn - lastTurns.xyz) / max(lastEnds - lastTurns.xyz, 1e-5), 0.0, 1.0));
}

half4 main(float2 fragCoord) {
    float texelDp = uDensity.y;
    float2 point = fragCoord * texelDp;
    float2 halfSize = uPlatter.zw;
    float halfMinor = min(halfSize.x, halfSize.y);
    float2 centered = point - uPlatter.xy;
    float radius = min(uRadius.x, halfMinor);
    float dPlatter = roundedBoxDistance(centered, halfSize, radius);
    float2 normal = roundedBoxNormal(centered, halfSize, radius);
    float signedEdge;
    float inset;
    if (uDroplet.z > 0.0 && uDroplet.w > 0.0) {
        float2 qDrop = point - uDroplet.xy;
        float dropR = min(uDroplet.z, uDroplet.w);
        float dDrop = roundedBoxDistance(qDrop, uDroplet.zw, dropR);
        float2 nDrop = roundedBoxNormal(qDrop, uDroplet.zw, dropR);
        float k = uRadius.z;
        float d;
        if (k > 1e-3) {
            float h = clamp(0.5 + 0.5 * (dDrop - dPlatter) / k, 0.0, 1.0);
            d = mix(dDrop, dPlatter, h) - k * h * (1.0 - h);
            float2 m = mix(nDrop, normal, h);
            normal = dot(m, m) > 1e-8 ? normalize(m) : normal;
        } else {
            if (dDrop < dPlatter) {
                d = dDrop;
                normal = nDrop;
            } else {
                d = dPlatter;
            }
        }
        if (d > texelDp) {
            return half4(0.0);
        }
        inset = max(-d, 0.0);
        float lost = 0.0;
        if (dPlatter <= dDrop && -dPlatter < kLine + uLight.y + 5.0 + texelDp + kCornerGap * radius) {
            lost = max(-dPlatter - cornerInset(centered, halfSize), 0.0);
        }
        float nearest = min(dPlatter, dDrop);
        signedEdge = -d - max(lost - (nearest - d), 0.0);
    } else {
        if (dPlatter > texelDp) {
            return half4(0.0);
        }
        inset = max(-dPlatter, 0.0);
        float traced = max(kLine + uLight.y, uShineLit[0].x > 0.0 ? uShineEnds[2].w : 0.0);
        if (inset < traced + texelDp + kCornerGap * radius) {
            signedEdge = cornerInset(centered, halfSize);
        } else {
            signedEdge = inset;
        }
    }
    float aa = clamp(signedEdge / texelDp + 0.5, 0.0, 1.0);
    if (aa <= 0.001) {
        return half4(0.0);
    }
    float edge = max(signedEdge, 0.0);
    float bevel = max(min(uRadius.y, halfMinor * 0.5), 1e-3);
    float rise = clamp(1.0 - inset / max(bevel, 1e-3), 0.0, 1.0);
    float depth = min(uOptics.x, halfMinor) * (1.0 - sqrt(max(0.0, 1.0 - rise * rise)));
    float2 shift = -normal * depth;

    half4 baseH = sampleDp(point + shift);
    float4 base = float4(baseH);
    float alpha = base.a;
    float3 color = base.rgb / max(alpha, 1e-4);
    color = mix(float3(dot(color, kLuma)), color, uOptics.y) * uOptics.z;
    if (uLight.z > 0.0) {
        color *= 1.0 - uLight.z * abs(normal.x) * exp(-inset / kEndShade);
    }

    float clash = 0.0;
    if (uGlow.w != 0.0) {
        float4 behind = float4(sampleDp(uPlatter.xy))
            + float4(sampleDp(uPlatter.xy + halfSize * float2(-0.6, -0.4)))
            + float4(sampleDp(uPlatter.xy + halfSize * float2(0.6, -0.4)))
            + float4(sampleDp(uPlatter.xy + halfSize * float2(-0.6, 0.4)))
            + float4(sampleDp(uPlatter.xy + halfSize * float2(0.6, 0.4)));
        behind *= 0.2;
        float behindLuma = dot(behind.rgb / max(behind.a, 1e-4), kLuma);
        clash = clamp((sign(uGlow.w) * (dot(uTint.rgb, kLuma) - behindLuma) - 0.3) / 0.5, 0.0, 1.0);
    }
    float cover = uTint.a + (1.0 - uTint.a) * clash * abs(uGlow.w);
    float coverage = alpha * (1.0 - cover) + cover;
    float3 glass = color * alpha * (1.0 - cover) + uTint.rgb * cover;

    if (uLight.x > 0.0) {
        float within = max(edge - kLine, 0.0);
        float rise2 = normal.y * normal.y;
        float rise4 = rise2 * rise2;
        float rise16 = rise4 * rise4 * rise4 * rise4;
        float facing = mix(rise2, 0.5 * rise2 * abs(normal.y) * (1.0 + rise16), uRim.y);
        float band = clamp(1.0 - within / max(uLight.y, 1e-3), 0.0, 1.0);
        float tail = clamp(1.0 - max(inset - kLine, 0.0) / kRimTail, 0.0, 1.0);
        float shine = min(uLight.x * facing * mix(band, tail, uRim.x), 1.0);
        glass += (float3(coverage) - (1.0 - uRim.z) * glass) * shine;
    }

    float line = clamp((kLine - edge) / texelDp + 0.5, 0.0, 1.0);
    if (line > 0.0) {
        float across = abs(normal.x);
        float darken = mix(uEdge.x, uRim.w, across * across * across);
        if (uEdge.y > 0.0 || uEdge.z > 0.0 || uEdge.w > 0.0) {
            float xFrac = abs(centered.x) / max(halfSize.x, 1e-3);
            float roundFrac = min(radius / max(halfSize.x * 2.0, 1e-3), 0.4);
            float tCorner = clamp((xFrac - (1.0 - 2.0 * roundFrac)) / max(1.5 * roundFrac, 1e-3), 0.0, 1.0);
            float tSide = clamp((xFrac - (1.0 - 0.5 * roundFrac)) / max(0.5 * roundFrac, 1e-3), 0.0, 1.0);
            float menuDarken = mix(mix(uEdge.w, uEdge.z, tCorner), uEdge.y, tSide);
            darken = max(darken, menuDarken);
        }
        bool hasLine = uEdge.x > 0.0 || uRim.w > 0.0 || uEdge.y > 0.0 || uEdge.z > 0.0 || uEdge.w > 0.0;
        if (hasLine) {
            float4 under = float4(sampleDp(point));
            float3 dark = max(under.rgb - kLineCap * under.a, 0.22 * under.rgb);
            glass = mix(glass, mix(under.rgb, dark, darken), line);
            coverage = mix(coverage, under.a, line);
        }
    }

    if (uGlow.z > 0.0) {
        float2 toGlow = point - uGlow.xy;
        float reach = max(uGlowReach * uGlowReach, 1.0);
        glass += uGlow.z * coverage * exp(-dot(toGlow, toGlow) / reach);
    }

    if (uShineLit[0].x > 0.0 && edge < uShineEnds[2].w + texelDp) {
        float turn = atan(abs(centered.x), max(abs(centered.y), 1e-4));
        float halfPx = 0.5 * texelDp;
        glass += coverage * shineAround(turn) * shineAcross(max(edge - halfPx, 0.0), edge + halfPx);
    }

    float outScale = aa * uRadius.w;
    return half4(half3(min(glass, float3(coverage)) * outScale), half(coverage * outScale));
}
"""
