package io.github.modao7067.luma.gui.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class ContentBlockColorsTest {
    @Test fun allBlockFillsStayNearNeutralAndWarmInsteadOfGreenInBothThemes() {
        for (scheme in listOf(lightColorScheme(), darkColorScheme())) {
            val palette = contentBlockColors(scheme)
            for (color in listOf(palette.surface, palette.header, palette.selected, palette.tab)) {
                assertTrue(color.red >= color.green)
                assertTrue(color.green >= color.blue)
                assertTrue(color.red - color.blue <= 4f / 255f + 0.00001f)
                assertEquals(1f, color.alpha, 0f)
            }
        }
    }

    @Test fun lightCardsSeparateFromTheCanvasAndTheHeader() {
        val palette = contentBlockColors(lightColorScheme())
        assertTrue(contrast(Color.White, palette.surface) >= 1.10f)
        assertTrue(contrast(palette.surface, palette.header) >= 1.06f)
        assertTrue(contrast(palette.selected, palette.tab) >= 1.25f)
    }

    @Test fun darkCardsSeparateFromTheCanvasAndTheSelectedTab() {
        val palette = contentBlockColors(darkColorScheme())
        assertTrue(contrast(palette.surface, Color(0xFF0F0F0F)) >= 1.45f)
        assertTrue(contrast(palette.header, palette.surface) >= 1.10f)
        assertTrue(contrast(palette.selected, palette.tab) >= 1.45f)
    }

    @Test fun blockDepthsFollowTheReferenceWithoutCopyingItsRgbColors() {
        val light = contentBlockColors(lightColorScheme()).surface
        val dark = contentBlockColors(darkColorScheme()).surface
        val lightReference = Color(0xFFF3F3F3)
        val darkReference = Color(0xFF303030)
        assertEquals(lightReference.luminance(), light.luminance(), 0.005f)
        assertEquals(darkReference.luminance(), dark.luminance(), 0.002f)
        assertNotEquals(lightReference, light)
        assertNotEquals(darkReference, dark)
    }

    private fun contrast(a: Color, b: Color): Float {
        val first = a.luminance()
        val second = b.luminance()
        return (maxOf(first, second) + 0.05f) / (minOf(first, second) + 0.05f)
    }
}
