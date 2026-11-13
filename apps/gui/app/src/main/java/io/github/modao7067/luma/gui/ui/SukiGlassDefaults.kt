package io.github.modao7067.luma.gui.ui

// SukiSU e67b14e6 FloatingBottomBar base constants; blur sigma mapping adapted from miuix-blur 0.9.4 (Apache-2.0).
internal object SukiGlassDefaults {
    const val containerAlpha = 0.4f
    const val saturation = 1.5f
    const val blurRadiusDp = 4f
    const val refractionHeightDp = 24f
    const val refractionAmountDp = 24f
    const val samplePaddingDp = 40f
    const val highlightWidthDp = 1f
    const val highlightAlpha = 0.75f
    const val highlightStrokeAlpha = 0.12f
    const val highlightInnerBlurDp = 2f
    const val shadowRadiusDp = 10f
    const val lightShadowAlpha = 0.1f
    const val darkShadowAlpha = 0.2f

    fun nativeBlurRadius(radiusPx: Float): Float = ((radiusPx * 0.45f - 0.5f) / 0.57735f).coerceAtLeast(0.01f)
}
