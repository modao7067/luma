// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0
// Adapted from miuix-blur 0.9.4 HighlightStyle.kt / internal/Shaders.kt.
// SukiSU's four-tab base Highlight supplies the constants; see third-party/sukisu-glass.
package io.github.modao7067.luma.gui.ui

import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.sqrt

@RequiresApi(33)
internal class SukiGlassHighlight {
    private val shader = RuntimeShader(BLOOM_STROKE_DUAL).also {
        it.setFloatUniform("highlightAlpha", SukiGlassDefaults.highlightAlpha)
        it.setColorUniform("strokeColor", android.graphics.Color.WHITE)
        it.setFloatUniform("strokeAlphaMul", SukiGlassDefaults.highlightStrokeAlpha)
    }
    private val paint = Paint().apply {
        blendMode = BlendMode.Plus
        shader = this@SukiGlassHighlight.shader
    }
    private var lastWidth = -1f
    private var lastHeight = -1f
    private var lastRadius = -1f
    private var lastInner = -1f

    init {
        setLight("1", -0.70710677f, -0.70710677f, -0.05f, 1f)
        setLight("2", 0f, 0.1f, -0.5f, 0.4f)
    }

    fun DrawScope.draw(radius: Float, alpha: Float = 1f) {
        val inner = SukiGlassDefaults.highlightInnerBlurDp.dp.toPx()
        if (size.width != lastWidth || size.height != lastHeight || radius != lastRadius || inner != lastInner) {
            shader.setFloatUniform("halfView", size.width / 2, size.height / 2)
            shader.setFloatUniform("halfViewFloor", floor(size.width / 2), floor(size.height / 2))
            shader.setFloatUniform("cornerRadii", radius, radius, radius, radius)
            shader.setFloatUniform("strokeWidth", SukiGlassDefaults.highlightWidthDp.dp.toPx().coerceAtMost(size.minDimension / 2))
            shader.setFloatUniform("innerBlurRadius", inner)
            shader.setFloatUniform("innerBlurRadiusSq", inner * inner)
            lastWidth = size.width
            lastHeight = size.height
            lastRadius = radius
            lastInner = inner
        }
        paint.alpha = alpha.coerceIn(0f, 1f)
        drawIntoCanvas { it.drawRect(0f, 0f, size.width, size.height, paint) }
    }

    private fun setLight(suffix: String, x: Float, y: Float, z: Float, intensity: Float) {
        val length = sqrt(x * x + y * y + z * z)
        shader.setFloatUniform("lightDir$suffix", x / length, y / length, z / length)
        shader.setColorUniform("lightColor$suffix", android.graphics.Color.WHITE)
        shader.setFloatUniform("lightIntensity$suffix", intensity)
    }
}

private const val BLOOM_STROKE_DUAL = """
uniform float2 halfView;
uniform float2 halfViewFloor;
uniform float4 cornerRadii;
uniform float strokeWidth;
uniform float innerBlurRadius;
uniform float innerBlurRadiusSq;
uniform float highlightAlpha;

layout(color) uniform half4 strokeColor;
uniform float strokeAlphaMul;

uniform float3 lightDir1;
layout(color) uniform half4 lightColor1;
uniform float lightIntensity1;

uniform float3 lightDir2;
layout(color) uniform half4 lightColor2;
uniform float lightIntensity2;

float pickRadius(float2 fragCoord, float4 radii) {
    float2 up = fragCoord.y < halfView.y ? radii.xy : radii.zw;
    return fragCoord.x < halfView.x ? up.x : up.y;
}

// caller passes non-negative pos (already abs-folded), so skip the redundant abs.
float roundedBoxSDF(float2 pos, float2 halfSize, float radius) {
    radius = min(radius, min(halfSize.x, halfSize.y));
    float2 d = pos - halfSize + radius;
    return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0) - radius;
}

float3 getNormal(float2 fragCoord, float sdf, float R) {
    float2 xy = fragCoord - halfViewFloor;
    float2 xy_a = abs(xy);
    float t = smoothstep(-innerBlurRadius, 0.0, sdf);
    float z = sqrt(max(innerBlurRadiusSq - t * t, 0.0));
    float3 coord = float3(xy_a, -z);

    float2 corner = halfView - R;
    corner.x = min(corner.x, xy_a.x);
    corner.y = min(corner.y, xy_a.y);

    float2 delta = coord.xy - corner.xy;
    float deltaLength = length(delta);
    // On a straight interior face, clamping corner to xy makes delta zero.
    // Its normal is flat; normalize(0) otherwise produces black NaN seams.
    if (deltaLength < 0.0001) return float3(0.0, 0.0, -1.0);
    float2 dir = delta / deltaLength;
    corner += dir * (R - innerBlurRadius);

    if (any(lessThan(xy_a, corner))) {
        return float3(0.0, 0.0, -1.0);
    }

    float2 signal = sign(xy);
    float3 n = normalize(coord - float3(corner, 0.0));
    n.xy *= signal;
    return n;
}

half4 main(float2 fragCoord) {
    float2 xy = abs(fragCoord - halfView);

    float originRadius = pickRadius(fragCoord, cornerRadii);
    float R = max(originRadius, innerBlurRadius);

    if (all(lessThan(xy, halfView - R))) {
        return half4(0.0);
    }

    float sdf = roundedBoxSDF(xy, halfView, originRadius);
    half outMask = half(smoothstep(0.0, -1.0, sdf));
    float strokeAlpha = smoothstep(-strokeWidth, -strokeWidth + 1.0, sdf);

    // Native: stroke = uStrokeColor * sa; result += stroke.rgb * stroke.a
    //       = strokeColor.rgb * strokeColor.a * sa^2
    half3 rgb = strokeColor.rgb * half(strokeAlphaMul * strokeAlpha * strokeAlpha);

    float3 n = getNormal(fragCoord, sdf, R);
    float l1 = dot(n.xy, lightDir1.xy);
    rgb += half(l1 * l1 * lightIntensity1) * lightColor1.rgb;
    float l2 = dot(n.xy, lightDir2.xy);
    rgb += half(l2 * l2 * lightIntensity2) * lightColor2.rgb;
    return half4(rgb * half(highlightAlpha), 1.0) * outMask;
}
"""
