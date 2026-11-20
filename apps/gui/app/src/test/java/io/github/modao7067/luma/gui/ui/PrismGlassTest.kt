// Adapted from chen08209/prism_ui under GPL-3.0. See apps/gui/third-party/prism-ui/.
package io.github.modao7067.luma.gui.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrismGlassTest {
    @Test fun prismStylesMatchUpstreamOpticsAndUseSharedDownscaledLayers() {
        val light = lightColorScheme()
        val dark = darkColorScheme()
        val lightRegular = PrismGlassStyle.regular(light)
        val darkRegular = PrismGlassStyle.regular(dark)
        val lightDock = PrismGlassStyle.dock(light)
        val darkDock = PrismGlassStyle.dock(dark)
        val lightMenu = PrismGlassStyle.menu(light)
        val darkMenu = PrismGlassStyle.menu(dark)

        assertFalse(lightRegular.useHaze)
        assertFalse(darkRegular.useHaze)
        assertEquals(2.5f, lightRegular.blurDp, 0f)
        assertEquals(2.08f, lightRegular.saturation, 0.001f)
        assertEquals(2.84f, darkRegular.saturation, 0.001f)
        assertEquals(0.4f, lightRegular.contrast, 0.001f)
        assertEquals(-0.4f, darkRegular.contrast, 0.001f)
        assertEquals(0.28f, lightRegular.rim, 0.001f)
        assertEquals(0.25f, lightRegular.hairline, 0.001f)
        assertEquals(0f, darkRegular.hairline, 0.001f)
        assertEquals(0.75f, darkRegular.hairlineSide, 0.001f)

        assertTrue(lightDock.useHaze)
        assertTrue(darkDock.useHaze)
        assertTrue(lightDock.tint.alpha > lightRegular.tint.alpha)
        assertTrue(darkDock.tint.alpha > darkRegular.tint.alpha)
        assertEquals(0.28f, lightDock.rim, 0.001f)
        assertEquals(0.28f, darkDock.rim, 0.001f)
        assertEquals(0.75f, darkDock.hairlineSide, 0.001f)

        assertTrue(lightMenu.useHaze)
        assertTrue(darkMenu.useHaze)
        assertEquals(0f, lightMenu.rim, 0f)
        assertEquals(0f, darkMenu.rim, 0f)
        assertEquals(1.8f, darkMenu.brightness, 0.001f)
        assertEquals(1.1f, lightMenu.brightness, 0.001f)
    }

    @Test fun superellipseOctantMatchesCircleAtRatioTwoAndTightensForWidePanes() {
        val circle = prismSuperellipseOctant(22f, 22f)
        assertEquals(2.0f, circle.n, 0.001f)
        assertTrue(circle.arcRadius > 0f)

        val pillLongSide = prismSuperellipseOctant(100f, 22f)
        assertTrue(pillLongSide.n > 5f)
        assertTrue(pillLongSide.jointX in 0f..100f)
        assertTrue(pillLongSide.jointY in 0f..100f)
        assertTrue(pillLongSide.arcRadius > 0f)

        val sharp = prismSuperellipseOctant(50f, 0f)
        assertEquals(0f, sharp.n, 0f)
    }

    @Test fun downscaledBlurSigmasAndCascadeStayPositiveAcrossPhoneDensities() {
        assertEquals(0.25f, PrismGlassDefaults.regularScaleFor(3.5f), 0.0001f)
        assertEquals(0.125f, PrismGlassDefaults.hazeScaleFor(3.5f), 0.0001f)
        listOf(1f, 1.5f, 2f, 3f, 3.5f, 4f).forEach { density ->
            val regularScale = PrismGlassDefaults.regularScaleFor(density)
            val hazeScale = PrismGlassDefaults.hazeScaleFor(density)
            val regularSigma = PrismGlassDefaults.effectiveCaptureSigma(PrismGlassDefaults.regularBlurDp, density, regularScale)
            val regularRadius = PrismGlassDefaults.blurSigmaToAndroidRadius(regularSigma)
            assertTrue(regularRadius > 0f)
            val step = hazeScale / regularScale
            val targetHazeSigma = PrismGlassDefaults.effectiveCaptureSigma(PrismGlassDefaults.hazeBlurDp, density, hazeScale)
            val existingHazeSigma = PrismGlassDefaults.regularBlurDp * density * regularScale * step
            val extraHazeSigma = PrismGlassDefaults.cascadeExtraSigma(targetHazeSigma, existingHazeSigma)
            assertTrue(extraHazeSigma > 0f)
            assertTrue(PrismGlassDefaults.blurSigmaToAndroidRadius(extraHazeSigma) > 0f)
        }
    }

    @Test fun glassShaderMatchesUpstreamRimParametersAndUniformHairlineGate() {
        assertTrue(PRISM_GLASS_AGSL.contains("const float kRimTail = 4.5;"))
        assertTrue(PRISM_GLASS_AGSL.contains("const float kEndShade = 3.0;"))
        assertTrue(PRISM_GLASS_AGSL.contains("halfMinor * 0.5"))
        assertTrue(PRISM_GLASS_AGSL.contains("bool hasLine = uEdge.x > 0.0 || uRim.w > 0.0 || uEdge.y > 0.0 || uEdge.z > 0.0 || uEdge.w > 0.0;"))
        assertFalse(PRISM_GLASS_AGSL.contains("if (darken > 0.0)"))
    }

    @Test fun menuRimProducesMonotonicTurnsAndDecayingHighlights() {
        val rim = PrismGlassRim.menu(darkColorScheme())
        val turns = FloatArray(8)
        val lit = FloatArray(8)
        rim.writeUniforms(220f, 320f, 28f, turns, lit)
        assertEquals(0f, turns[0], 0f)
        assertEquals(rim.shine, lit[0], 0.0001f)
        assertEquals(0f, lit[7], 0f)
        for (i in 1 until turns.size) {
            assertTrue(turns[i] >= turns[i - 1])
            assertTrue(lit[i] <= lit[i - 1])
        }
    }
}
