package io.github.modao7067.luma.gui.ui

import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCardLayoutTest {
    @Test fun smallBadgesAndIconsKeepIntrinsicSizeWithoutCropping() {
        val badge = mediaImageCardLayout(136, 20)
        assertEquals(136f, badge.widthDp, 0.01f)
        assertEquals(20f, badge.heightDp, 0.01f)
        assertEquals(5f, badge.cornerDp, 0.01f)
        assertFalse(badge.crop)

        val icon = mediaImageCardLayout(48, 48)
        assertEquals(48f, icon.widthDp, 0.01f)
        assertEquals(48f, icon.heightDp, 0.01f)
        assertEquals(12f, icon.cornerDp, 0.01f)
        assertFalse(icon.crop)

        val favicon = mediaImageCardLayout(16, 16)
        assertEquals(16f, favicon.widthDp, 0.01f)
        assertEquals(16f, favicon.heightDp, 0.01f)
        assertEquals(4f, favicon.cornerDp, 0.01f)
        assertFalse(favicon.crop)
    }

    @Test fun wideBannersFitFullWidthWithoutCropping() {
        val banner = mediaImageCardLayout(800, 120)
        assertEquals(280f, banner.widthDp, 0.01f)
        assertEquals(42f, banner.heightDp, 0.01f)
        assertFalse(banner.crop)

        val strip = mediaImageCardLayout(1200, 20)
        assertEquals(280f, strip.widthDp, 0.01f)
        assertEquals(20f, strip.heightDp, 0.01f)
        assertFalse(strip.crop)
    }

    @Test fun standardPhotosAndUltraTallScreenshotsPreserveExpectedBounds() {
        val photo = mediaImageCardLayout(1200, 800)
        assertEquals(280f, photo.widthDp, 0.01f)
        assertEquals(186.67f, photo.heightDp, 0.1f)
        assertEquals(12f, photo.cornerDp, 0.01f)
        assertFalse(photo.crop)

        val portrait = mediaImageCardLayout(600, 1200)
        assertEquals(180f, portrait.widthDp, 0.01f)
        assertEquals(360f, portrait.heightDp, 0.01f)
        assertFalse(portrait.crop)

        val receipt = mediaImageCardLayout(400, 3000)
        assertEquals(144f, receipt.widthDp, 0.01f)
        assertEquals(360f, receipt.heightDp, 0.01f)
        assertTrue(receipt.crop)
    }

    @Test fun vectorDecoderScalesSmallSvgsByDisplayDensityUpToLimit() {
        assertEquals(IntSize(408, 60), decodedVectorSize(136, 20, 768, density = 3f))
        assertEquals(IntSize(768, 614), decodedVectorSize(1000, 800, 768, density = 3f))
        assertEquals(IntSize(136, 20), decodedVectorSize(136, 20, 768, density = 1f))
    }
}
