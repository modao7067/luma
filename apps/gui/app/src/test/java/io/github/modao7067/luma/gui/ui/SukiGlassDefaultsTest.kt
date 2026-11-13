package io.github.modao7067.luma.gui.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SukiGlassDefaultsTest {
    @Test fun fourTabBaseRecipeIsNotThePressedIndicatorRecipe() {
        assertEquals(0.4f, SukiGlassDefaults.containerAlpha, 0f)
        assertEquals(1.5f, SukiGlassDefaults.saturation, 0f)
        assertEquals(4f, SukiGlassDefaults.blurRadiusDp, 0f)
        assertEquals(24f, SukiGlassDefaults.refractionHeightDp, 0f)
        assertEquals(24f, SukiGlassDefaults.refractionAmountDp, 0f)
        assertEquals(40f, SukiGlassDefaults.samplePaddingDp, 0f)
        assertEquals(1f, SukiGlassDefaults.highlightWidthDp, 0f)
        assertEquals(0.75f, SukiGlassDefaults.highlightAlpha, 0f)
        assertEquals(0.12f, SukiGlassDefaults.highlightStrokeAlpha, 0f)
        assertEquals(2f, SukiGlassDefaults.highlightInnerBlurDp, 0f)
    }

    @Test fun nativeBlurHasTheUpstreamGaussianSigmaAtPhoneDensities() {
        listOf(1f, 1.5f, 2f, 3f, 4f).forEach { density ->
            val sourceRadius = SukiGlassDefaults.blurRadiusDp * density
            val androidRadius = SukiGlassDefaults.nativeBlurRadius(sourceRadius)
            assertEquals(sourceRadius * 0.45f, androidRadius * 0.57735f + 0.5f, 0.0001f)
            assertTrue(androidRadius > 0f)
            assertTrue(SukiGlassDefaults.samplePaddingDp * density > sourceRadius * 0.45f * 3)
        }
    }
}
