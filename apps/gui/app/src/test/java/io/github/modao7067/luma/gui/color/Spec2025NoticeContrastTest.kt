package io.github.modao7067.luma.gui.color

import androidx.compose.ui.graphics.toArgb
import io.github.modao7067.luma.gui.color.mcu.contrast.Contrast
import io.github.modao7067.luma.gui.color.mcu.utils.ColorUtils
import org.junit.Assert.assertTrue
import org.junit.Test

class Spec2025NoticeContrastTest {
    @Test fun errorNoticeContentMeetsNormalTextContrast() {
        val seeds = listOf(0xFF6750A4, 0xFF4058A0, 0xFFB3261E, 0xFF008577, 0xFF808080, 0xFFFBBC04)
        for (seed in seeds) for (dark in listOf(false, true)) {
            val colors = lumaColorScheme(seed.toInt(), dark)
            val ratio = Contrast.ratioOfTones(ColorUtils.lstarFromArgb(colors.onErrorContainer.toArgb()), ColorUtils.lstarFromArgb(colors.errorContainer.toArgb()))
            assertTrue("seed=$seed dark=$dark contrast=$ratio", ratio >= 4.5)
        }
    }
}
