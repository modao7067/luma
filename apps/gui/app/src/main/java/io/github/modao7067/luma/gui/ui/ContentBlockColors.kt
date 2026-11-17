package io.github.modao7067.luma.gui.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

internal data class ContentBlockColors(
    val surface: Color,
    val header: Color,
    val selected: Color,
    val tab: Color,
)

private val lightContentBlockColors = ContentBlockColors(
    surface = Color(0xFFF4F3F2),
    header = Color(0xFFECEBEA),
    selected = Color.White,
    tab = Color(0xFFE5E4E3),
)

private val darkContentBlockColors = ContentBlockColors(
    surface = Color(0xFF31302F),
    header = Color(0xFF383736),
    selected = Color(0xFF4B4947),
    tab = Color(0xFF2B2A29),
)

internal fun contentBlockColors(colors: ColorScheme): ContentBlockColors =
    if (codePaletteDark(colors)) darkContentBlockColors else lightContentBlockColors
