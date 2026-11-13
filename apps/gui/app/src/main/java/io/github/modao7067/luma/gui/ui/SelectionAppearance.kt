package io.github.modao7067.luma.gui.ui

import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import io.github.modao7067.luma.gui.color.mcu.hct.Hct

internal fun nativeTextSelectionColors(colors: ColorScheme): TextSelectionColors {
    val backing = Hct.fromInt(colors.primary.toArgb()).apply {
        tone = if (colors.background.luminance() < .5f) 18.0 else 88.0
    }
    return TextSelectionColors(handleColor = colors.primary, backgroundColor = Color(backing.toInt()))
}
