package io.github.modao7067.luma.gui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import io.github.modao7067.luma.gui.color.lumaColorScheme
import io.github.modao7067.luma.gui.color.systemThemeSeed
import io.github.modao7067.luma.gui.ui.actionActivityWindow

@Composable
internal fun LumaTheme(
    seedArgb: Int? = null,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val view = LocalView.current
    val inspection = LocalInspectionMode.current
    if (!inspection) {
        io.github.modao7067.luma.gui.i18n.LumaI18n.onConfigurationChanged(configuration)
    }
    val seed = seedArgb ?: remember(context, configuration) { systemThemeSeed(context).argb }
    val colors = remember(seed, darkTheme) { lumaColorScheme(seed, darkTheme) }
    if (!inspection && !view.isInEditMode) {
        SideEffect {
            val window = context.actionActivityWindow() ?: return@SideEffect
            @Suppress("DEPRECATION")
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    io.github.modao7067.luma.gui.ui.GuiScale {
        MaterialTheme(colorScheme = colors) {
            io.github.modao7067.luma.gui.ui.TextSelectionHost { io.github.modao7067.luma.gui.ui.SessionTypography(content = content) }
        }
    }
}
