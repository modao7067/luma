package io.github.modao7067.luma.gui.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.github.modao7067.luma.gui.color.lumaColorScheme
import io.github.modao7067.luma.gui.color.mcu.contrast.Contrast
import io.github.modao7067.luma.gui.color.mcu.utils.ColorUtils
import org.junit.Assert.*
import org.junit.Test

class SelectionAppearanceTest {
    private fun ratio(a: Color, b: Color) = Contrast.ratioOfTones(ColorUtils.lstarFromArgb(a.toArgb()), ColorUtils.lstarFromArgb(b.toArgb()))
    @Test fun selectionBackingKeepsActualBodyBubbleAndSyntaxColorsReadableInBothThemes() {
        var minimum = Double.MAX_VALUE
        for (seed in listOf(0xFF6750A4, 0xFF4058A0, 0xFFB3261E, 0xFF008577, 0xFF808080, 0xFFFBBC04)) {
            for (dark in listOf(false, true)) {
                val colors = lumaColorScheme(seed.toInt(), dark)
                val selection = nativeTextSelectionColors(colors)
                val (bubbleBg, bubbleFg) = userBubbleColors(colors)
                assertEquals(colors.primary, selection.handleColor)
                assertEquals(1f, selection.backgroundColor.alpha)
                assertNotEquals(colors.secondaryContainer, selection.backgroundColor)
                assertNotEquals(bubbleBg, selection.backgroundColor)
                assertNotEquals(colors.surfaceContainerHigh, selection.backgroundColor)
                for (ink in listOf(colors.onSurface, colors.onSecondaryContainer, bubbleFg, colors.onSurfaceVariant,
                    colors.primary, colors.secondary, colors.tertiary, colors.error)) {
                    val contrast = ratio(ink, selection.backgroundColor)
                    minimum = minOf(minimum, contrast)
                    assertTrue("seed=$seed dark=$dark selected text contrast=$contrast", contrast >= 4.5)
                }
            }
        }
        println("SELECTION_CONTRAST seeds=6 themes=2 textRoles=7 minimum=$minimum")
    }
}
