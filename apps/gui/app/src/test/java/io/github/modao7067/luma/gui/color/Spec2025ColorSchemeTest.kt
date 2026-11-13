package io.github.modao7067.luma.gui.color

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.github.modao7067.luma.gui.color.mcu.contrast.Contrast
import io.github.modao7067.luma.gui.color.mcu.dynamiccolor.ColorSpec.SpecVersion
import io.github.modao7067.luma.gui.color.mcu.dynamiccolor.DynamicScheme
import io.github.modao7067.luma.gui.color.mcu.dynamiccolor.Variant
import io.github.modao7067.luma.gui.color.mcu.hct.Hct
import io.github.modao7067.luma.gui.color.mcu.scheme.SchemeTonalSpot
import io.github.modao7067.luma.gui.color.mcu.utils.ColorUtils
import org.junit.Assert.*
import org.junit.Test

class Spec2025ColorSchemeTest {
    private val seeds = listOf(0xFF6750A4, 0xFF4058A0, 0xFFB3261E, 0xFF008577, 0xFF808080, 0xFFFBBC04).map { it.toInt() }
    private fun ratio(a: Color, b: Color) = Contrast.ratioOfTones(ColorUtils.lstarFromArgb(a.toArgb()), ColorUtils.lstarFromArgb(b.toArgb()))

    @Test fun systemColorAndMissingSourceHaveExplicitDifferentOrigins() {
        assertEquals(ThemeSeed(0xFF4058A0.toInt(), ThemeSeedSource.SYSTEM_ACCENT), themeSeedFromSystemColor(0xFF4058A0.toInt()))
        assertEquals(ThemeSeed(0xFF6750A4.toInt(), ThemeSeedSource.DEFAULT), themeSeedFromSystemColor(null))
        assertEquals(ThemeSeedSource.SYSTEM_ACCENT, themeSeedFromSystemColor(0xFF808080.toInt()).source)
    }

    @Test fun selectsReal2025RatherThanUpstreamDefault2021() {
        val seed = 0xFF4058A0.toInt()
        val actual = lumaColorScheme(seed, true)
        val old = SchemeTonalSpot(Hct.fromInt(seed), true, 0.0)
        assertEquals(SpecVersion.SPEC_2021, old.specVersion)
        assertNotEquals(old.surface, actual.surface.toArgb())
        assertEquals(4.0, ColorUtils.lstarFromArgb(actual.surface.toArgb()), .2)
        assertEquals(actual.surface, actual.background)
        assertEquals(actual.onSurface, actual.onBackground)
    }

    @Test fun everyMaterial3RoleMapsToThePinnedOfficial2025Output() {
        for (seed in seeds) for (dark in listOf(false, true)) {
            val c = lumaColorScheme(seed, dark)
            val s = lumaDynamicScheme(seed, dark)
            assertEquals(SpecVersion.SPEC_2025, s.specVersion)
            assertEquals(Variant.NEUTRAL, s.variant)
            assertEquals(DynamicScheme.Platform.PHONE, s.platform)
            val pairs = listOf(
                c.primary to s.primary, c.onPrimary to s.onPrimary, c.primaryContainer to s.primaryContainer, c.onPrimaryContainer to s.onPrimaryContainer, c.inversePrimary to s.inversePrimary,
                c.secondary to s.secondary, c.onSecondary to s.onSecondary, c.secondaryContainer to s.secondaryContainer, c.onSecondaryContainer to s.onSecondaryContainer,
                c.tertiary to s.tertiary, c.onTertiary to s.onTertiary, c.tertiaryContainer to s.tertiaryContainer, c.onTertiaryContainer to s.onTertiaryContainer,
                c.background to s.background, c.onBackground to s.onBackground, c.surface to s.surface, c.onSurface to s.onSurface, c.surfaceVariant to s.surfaceVariant, c.onSurfaceVariant to s.onSurfaceVariant, c.surfaceTint to s.surfaceTint,
                c.inverseSurface to s.inverseSurface, c.inverseOnSurface to s.inverseOnSurface, c.error to s.error, c.onError to s.onError, c.errorContainer to s.errorContainer, c.onErrorContainer to s.onErrorContainer,
                c.outline to s.outline, c.outlineVariant to s.outlineVariant, c.scrim to s.scrim, c.surfaceBright to s.surfaceBright, c.surfaceDim to s.surfaceDim,
                c.surfaceContainer to s.surfaceContainer, c.surfaceContainerHigh to s.surfaceContainerHigh, c.surfaceContainerHighest to s.surfaceContainerHighest, c.surfaceContainerLow to s.surfaceContainerLow, c.surfaceContainerLowest to s.surfaceContainerLowest,
                c.primaryFixed to s.primaryFixed, c.primaryFixedDim to s.primaryFixedDim, c.onPrimaryFixed to s.onPrimaryFixed, c.onPrimaryFixedVariant to s.onPrimaryFixedVariant,
                c.secondaryFixed to s.secondaryFixed, c.secondaryFixedDim to s.secondaryFixedDim, c.onSecondaryFixed to s.onSecondaryFixed, c.onSecondaryFixedVariant to s.onSecondaryFixedVariant,
                c.tertiaryFixed to s.tertiaryFixed, c.tertiaryFixedDim to s.tertiaryFixedDim, c.onTertiaryFixed to s.onTertiaryFixed, c.onTertiaryFixedVariant to s.onTertiaryFixedVariant,
            )
            assertEquals(48, pairs.size)
            pairs.forEachIndexed { i, (actual, expected) -> assertEquals("seed=$seed dark=$dark role=$i", expected, actual.toArgb()) }
        }
    }

    @Test fun bodyButtonContainerAndActualCodeCombinationsMeetTextContrast() {
        for (seed in seeds) for (dark in listOf(false, true)) {
            val c = lumaColorScheme(seed, dark)
            val pairs = listOf(
                c.onPrimary to c.primary, c.onPrimaryContainer to c.primaryContainer,
                c.onSecondary to c.secondary, c.onSecondaryContainer to c.secondaryContainer,
                c.onTertiary to c.tertiary, c.onTertiaryContainer to c.tertiaryContainer,
                c.onSurface to c.surface, c.onSurfaceVariant to c.surfaceContainerHigh,
                c.primary to c.surfaceContainerLow, c.secondary to c.surfaceContainerLow, c.tertiary to c.surfaceContainerLow,
                c.error to c.surfaceContainerLow, c.primary to c.surfaceContainerHigh,
            )
            pairs.forEachIndexed { i, (fg,bg) -> assertTrue("seed=$seed dark=$dark pair=$i ratio=${ratio(fg,bg)}", ratio(fg,bg) >= 4.5) }
        }
    }

    @Test fun bothSeedAndSystemDayNightAffectGeneratedRoles() {
        assertNotEquals(lumaColorScheme(seeds[1], false).primary, lumaColorScheme(seeds[2], false).primary)
        assertNotEquals(lumaColorScheme(seeds[1], false).surface, lumaColorScheme(seeds[1], true).surface)
        assertNotEquals(lumaColorScheme(seeds[1], false).onSurface, lumaColorScheme(seeds[1], true).onSurface)
    }
}
