package io.github.modao7067.luma.gui.color

import android.content.Context
import android.content.res.Resources
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import io.github.modao7067.luma.gui.color.mcu.dynamiccolor.ColorSpec.SpecVersion
import io.github.modao7067.luma.gui.color.mcu.dynamiccolor.ColorSpecs
import io.github.modao7067.luma.gui.color.mcu.dynamiccolor.DynamicScheme
import io.github.modao7067.luma.gui.color.mcu.dynamiccolor.Variant
import io.github.modao7067.luma.gui.color.mcu.hct.Hct

internal enum class ThemeSeedSource { SYSTEM_ACCENT, DEFAULT }
internal data class ThemeSeed(val argb: Int, val source: ThemeSeedSource)
internal const val fallbackThemeSeedArgb: Int = -10006364
internal fun themeSeedFromSystemColor(systemArgb: Int?): ThemeSeed =
    if (systemArgb != null) ThemeSeed(systemArgb, ThemeSeedSource.SYSTEM_ACCENT)
    else ThemeSeed(fallbackThemeSeedArgb, ThemeSeedSource.DEFAULT)

internal fun systemThemeSeed(context: Context): ThemeSeed = themeSeedFromSystemColor(
    try { context.getColor(android.R.color.system_accent1_500) }
    catch (_: Resources.NotFoundException) { null },
)

internal fun lumaDynamicScheme(
    seedArgb: Int,
    darkTheme: Boolean,
    contrastLevel: Double = 0.0,
    variant: Variant = Variant.NEUTRAL,
): DynamicScheme {
    val sourceColorHct = Hct.fromInt(seedArgb)
    val specVersion = SpecVersion.SPEC_2025
    val platform = DynamicScheme.Platform.PHONE
    val spec = ColorSpecs.get(specVersion)
    return DynamicScheme(
        sourceColorHct,
        variant,
        darkTheme,
        contrastLevel,
        platform,
        specVersion,
        spec.getPrimaryPalette(variant, sourceColorHct, darkTheme, platform, contrastLevel),
        spec.getSecondaryPalette(variant, sourceColorHct, darkTheme, platform, contrastLevel),
        spec.getTertiaryPalette(variant, sourceColorHct, darkTheme, platform, contrastLevel),
        spec.getNeutralPalette(variant, sourceColorHct, darkTheme, platform, contrastLevel),
        spec.getNeutralVariantPalette(variant, sourceColorHct, darkTheme, platform, contrastLevel),
        spec.getErrorPalette(variant, sourceColorHct, darkTheme, platform, contrastLevel),
    )
}

internal fun lumaColorScheme(
    seedArgb: Int,
    darkTheme: Boolean,
    contrastLevel: Double = 0.0,
    variant: Variant = Variant.NEUTRAL,
): ColorScheme {
    require(contrastLevel in -1.0..1.0) { "contrastLevel must be between -1 and 1" }
    val scheme = lumaDynamicScheme(seedArgb, darkTheme, contrastLevel, variant)
    return ColorScheme(
        primary = Color(scheme.primary),
        onPrimary = Color(scheme.onPrimary),
        primaryContainer = Color(scheme.primaryContainer),
        onPrimaryContainer = Color(scheme.onPrimaryContainer),
        inversePrimary = Color(scheme.inversePrimary),
        secondary = Color(scheme.secondary),
        onSecondary = Color(scheme.onSecondary),
        secondaryContainer = Color(scheme.secondaryContainer),
        onSecondaryContainer = Color(scheme.onSecondaryContainer),
        tertiary = Color(scheme.tertiary),
        onTertiary = Color(scheme.onTertiary),
        tertiaryContainer = Color(scheme.tertiaryContainer),
        onTertiaryContainer = Color(scheme.onTertiaryContainer),
        background = Color(scheme.background),
        onBackground = Color(scheme.onBackground),
        surface = Color(scheme.surface),
        onSurface = Color(scheme.onSurface),
        surfaceVariant = Color(scheme.surfaceVariant),
        onSurfaceVariant = Color(scheme.onSurfaceVariant),
        surfaceTint = Color(scheme.surfaceTint),
        inverseSurface = Color(scheme.inverseSurface),
        inverseOnSurface = Color(scheme.inverseOnSurface),
        error = Color(scheme.error),
        onError = Color(scheme.onError),
        errorContainer = Color(scheme.errorContainer),
        onErrorContainer = Color(scheme.onErrorContainer),
        outline = Color(scheme.outline),
        outlineVariant = Color(scheme.outlineVariant),
        scrim = Color(scheme.scrim),
        surfaceBright = Color(scheme.surfaceBright),
        surfaceDim = Color(scheme.surfaceDim),
        surfaceContainer = Color(scheme.surfaceContainer),
        surfaceContainerHigh = Color(scheme.surfaceContainerHigh),
        surfaceContainerHighest = Color(scheme.surfaceContainerHighest),
        surfaceContainerLow = Color(scheme.surfaceContainerLow),
        surfaceContainerLowest = Color(scheme.surfaceContainerLowest),
        primaryFixed = Color(scheme.primaryFixed),
        primaryFixedDim = Color(scheme.primaryFixedDim),
        onPrimaryFixed = Color(scheme.onPrimaryFixed),
        onPrimaryFixedVariant = Color(scheme.onPrimaryFixedVariant),
        secondaryFixed = Color(scheme.secondaryFixed),
        secondaryFixedDim = Color(scheme.secondaryFixedDim),
        onSecondaryFixed = Color(scheme.onSecondaryFixed),
        onSecondaryFixedVariant = Color(scheme.onSecondaryFixedVariant),
        tertiaryFixed = Color(scheme.tertiaryFixed),
        tertiaryFixedDim = Color(scheme.tertiaryFixedDim),
        onTertiaryFixed = Color(scheme.onTertiaryFixed),
        onTertiaryFixedVariant = Color(scheme.onTertiaryFixedVariant),
    )
}
