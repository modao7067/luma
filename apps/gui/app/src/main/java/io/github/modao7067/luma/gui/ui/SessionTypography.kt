package io.github.modao7067.luma.gui.ui

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.sp

internal const val TABULAR_NUMS_FEATURE = "'tnum' 1"

private val cachedAssetFonts = java.util.concurrent.ConcurrentHashMap<String, FontFamily>()

@Composable
private fun assetFont(path: String): FontFamily {
    val assets = LocalContext.current.assets
    return remember(assets, path) {
        cachedAssetFonts.getOrPut(path) { FontFamily(Font(path = path, assetManager = assets)) }
    }
}

@Composable
internal fun terminalFont(): FontFamily = assetFont("LumaGuiHan-Regular.ttf")

internal fun uiSansFont(): FontFamily = FontFamily.SansSerif

@Composable
internal fun editorialBodyStyle(): TextStyle =
    MaterialTheme.typography.bodyLarge.copy(
        fontFamily = uiSansFont(),
        fontFeatureSettings = null,
        letterSpacing = 0.sp,
        lineHeight = 24.5.sp,
        lineBreak = LineBreak.Paragraph,
    )

@Composable
internal fun editorialHeadingStyle(level: Int): TextStyle {
    val font = uiSansFont()
    val typography = MaterialTheme.typography
    return when (level) {
        1 -> typography.headlineMedium.copy(
            fontFamily = font,
            fontFeatureSettings = null,
            fontSize = 23.sp,
            lineHeight = 31.sp,
            letterSpacing = (-0.35).sp,
            fontWeight = FontWeight.SemiBold,
            lineBreak = LineBreak.Heading,
        )
        2 -> typography.titleLarge.copy(
            fontFamily = font,
            fontFeatureSettings = null,
            fontSize = 19.5.sp,
            lineHeight = 27.sp,
            letterSpacing = (-0.25).sp,
            fontWeight = FontWeight.SemiBold,
            lineBreak = LineBreak.Heading,
        )
        3 -> typography.titleMedium.copy(
            fontFamily = font,
            fontFeatureSettings = null,
            fontSize = 17.sp,
            lineHeight = 24.sp,
            letterSpacing = (-0.15).sp,
            fontWeight = FontWeight.SemiBold,
            lineBreak = LineBreak.Heading,
        )
        else -> typography.titleSmall.copy(
            fontFamily = font,
            fontFeatureSettings = null,
            fontSize = 15.5.sp,
            lineHeight = 22.sp,
            letterSpacing = 0.sp,
            fontWeight = FontWeight.SemiBold,
            lineBreak = LineBreak.Heading,
        )
    }
}

@Composable
internal fun editorialTableStyle(header: Boolean): TextStyle =
    MaterialTheme.typography.bodyMedium.copy(
        fontFamily = uiSansFont(),
        fontFeatureSettings = TABULAR_NUMS_FEATURE,
        fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
    )

@Composable
internal fun editorialGreetingStyle(): TextStyle =
    MaterialTheme.typography.headlineMedium.copy(
        fontFamily = uiSansFont(),
        fontFeatureSettings = null,
        fontSize = 26.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.4).sp,
        fontWeight = FontWeight.SemiBold,
        lineBreak = LineBreak.Simple,
    )

@Composable
internal fun SessionTypography(content: @Composable () -> Unit) {
    val font = uiSansFont()
    val base = MaterialTheme.typography
    val typography = remember(font, base) {
        Typography(
            displayLarge = base.displayLarge.copy(fontFamily = font, fontFeatureSettings = null),
            displayMedium = base.displayMedium.copy(fontFamily = font, fontFeatureSettings = null),
            displaySmall = base.displaySmall.copy(fontFamily = font, fontFeatureSettings = null),
            headlineLarge = base.headlineLarge.copy(fontFamily = font, fontFeatureSettings = null, fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = (-0.2).sp, fontWeight = FontWeight.SemiBold),
            headlineMedium = base.headlineMedium.copy(fontFamily = font, fontFeatureSettings = null, fontSize = 22.sp, lineHeight = 30.sp, letterSpacing = (-0.2).sp, fontWeight = FontWeight.SemiBold),
            headlineSmall = base.headlineSmall.copy(fontFamily = font, fontFeatureSettings = null, fontSize = 20.sp, lineHeight = 28.sp, letterSpacing = (-0.15).sp, fontWeight = FontWeight.SemiBold),
            titleLarge = base.titleLarge.copy(fontFamily = font, fontFeatureSettings = null, fontSize = 18.sp, lineHeight = 26.sp, letterSpacing = (-0.1).sp, fontWeight = FontWeight.SemiBold),
            titleMedium = base.titleMedium.copy(fontFamily = font, fontFeatureSettings = null, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp, fontWeight = FontWeight.SemiBold),
            titleSmall = base.titleSmall.copy(fontFamily = font, fontFeatureSettings = null, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = base.bodyLarge.copy(fontFamily = font, fontFeatureSettings = null, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.1.sp),
            bodyMedium = base.bodyMedium.copy(fontFamily = font, fontFeatureSettings = null, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
            bodySmall = base.bodySmall.copy(fontFamily = font, fontFeatureSettings = null, fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp),
            labelLarge = base.labelLarge.copy(fontFamily = font, fontFeatureSettings = TABULAR_NUMS_FEATURE, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp, fontWeight = FontWeight.Medium),
            labelMedium = base.labelMedium.copy(fontFamily = font, fontFeatureSettings = TABULAR_NUMS_FEATURE, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.1.sp, fontWeight = FontWeight.Medium),
            labelSmall = base.labelSmall.copy(fontFamily = font, fontFeatureSettings = TABULAR_NUMS_FEATURE, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.1.sp, fontWeight = FontWeight.Medium),
        )
    }
    val selectionColors = LocalTextSelectionColors.current
    MaterialTheme(typography = typography) {
        CompositionLocalProvider(LocalTextSelectionColors provides selectionColors, content = content)
    }
}

@Composable
internal fun bodyLineHeight(): androidx.compose.ui.unit.Dp {
    val measure = androidx.compose.ui.text.rememberTextMeasurer()
    val layout = measure.measure("M\nM", style = MaterialTheme.typography.bodyLarge)
    return with(androidx.compose.ui.platform.LocalDensity.current) { (layout.getLineBottom(1) - layout.getLineBottom(0)).toDp() }
}

@Composable
internal fun ConversationTypography(content: @Composable () -> Unit) {
    val font = uiSansFont()
    val base = MaterialTheme.typography
    val typography = remember(font, base) {
        base.copy(
            displayLarge = base.displayLarge.copy(fontFamily = font, fontFeatureSettings = null),
            displayMedium = base.displayMedium.copy(fontFamily = font, fontFeatureSettings = null),
            displaySmall = base.displaySmall.copy(fontFamily = font, fontFeatureSettings = null),
            headlineLarge = base.headlineLarge.copy(fontFamily = font, fontFeatureSettings = null, fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = (-0.25).sp, fontWeight = FontWeight.SemiBold),
            headlineMedium = base.headlineMedium.copy(fontFamily = font, fontFeatureSettings = null, fontSize = 22.sp, lineHeight = 30.sp, letterSpacing = (-0.2).sp, fontWeight = FontWeight.SemiBold),
            headlineSmall = base.headlineSmall.copy(fontFamily = font, fontFeatureSettings = null, fontSize = 20.sp, lineHeight = 28.sp, letterSpacing = (-0.15).sp, fontWeight = FontWeight.SemiBold),
            titleLarge = base.titleLarge.copy(fontFamily = font, fontFeatureSettings = null, fontSize = 18.sp, lineHeight = 26.sp, letterSpacing = (-0.1).sp, fontWeight = FontWeight.SemiBold),
            titleMedium = base.titleMedium.copy(fontFamily = font, fontFeatureSettings = null, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp, fontWeight = FontWeight.SemiBold),
            titleSmall = base.titleSmall.copy(fontFamily = font, fontFeatureSettings = null, fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = base.bodyLarge.copy(fontFamily = font, fontFeatureSettings = null, fontSize = 16.sp, lineHeight = 26.sp, letterSpacing = 0.2.sp),
            bodyMedium = base.bodyMedium.copy(fontFamily = font, fontFeatureSettings = null, fontSize = 14.sp, lineHeight = 21.sp, letterSpacing = 0.1.sp),
            bodySmall = base.bodySmall.copy(fontFamily = font, fontFeatureSettings = null, fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp),
            labelLarge = base.labelLarge.copy(fontFamily = font, fontFeatureSettings = TABULAR_NUMS_FEATURE, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp, fontWeight = FontWeight.Medium),
            labelMedium = base.labelMedium.copy(fontFamily = font, fontFeatureSettings = TABULAR_NUMS_FEATURE, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.1.sp, fontWeight = FontWeight.Medium),
            labelSmall = base.labelSmall.copy(fontFamily = font, fontFeatureSettings = TABULAR_NUMS_FEATURE, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.1.sp, fontWeight = FontWeight.Medium),
        )
    }
    val selectionColors = LocalTextSelectionColors.current
    MaterialTheme(typography = typography) {
        CompositionLocalProvider(LocalTextSelectionColors provides selectionColors, content = content)
    }
}
