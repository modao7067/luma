package io.github.modao7067.luma.gui.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit

internal const val GUI_HAN_SCALE = 1f
internal const val GUI_SIZE_SCALE = 1f
internal const val GUI_LAYOUT_SCALE = GUI_SIZE_SCALE / GUI_HAN_SCALE

internal class GuiDensity(private val system: Density) : Density {
    override val density = system.density * GUI_LAYOUT_SCALE
    override val fontScale = system.fontScale
    override fun TextUnit.toDp(): Dp = with(system) { this@toDp.toDp() }
    override fun Dp.toSp(): TextUnit = with(system) { this@toSp.toSp() }
}

@Composable
internal fun GuiScale(content: @Composable () -> Unit) {
    if (LocalGuiEnvironment.current != null) {
        GuiWindowScale(content)
        return
    }
    val systemDensity = LocalDensity.current
    val systemViewConfiguration = LocalViewConfiguration.current
    val density = remember(systemDensity) { GuiDensity(systemDensity) }
    val viewConfiguration = remember(systemViewConfiguration) {
        object : ViewConfiguration by systemViewConfiguration {
            override val minimumTouchTargetSize = systemViewConfiguration.minimumTouchTargetSize.let {
                DpSize(it.width / GUI_LAYOUT_SCALE, it.height / GUI_LAYOUT_SCALE)
            }
        }
    }
    val environment = remember(density, viewConfiguration) { GuiEnvironment(density, viewConfiguration) }
    CompositionLocalProvider(LocalGuiEnvironment provides environment,
        LocalDensity provides density, LocalViewConfiguration provides viewConfiguration) {
        ProvideLumaHaptics(content)
    }
}

private data class GuiEnvironment(val density: Density, val viewConfiguration: ViewConfiguration)
private val LocalGuiEnvironment = androidx.compose.runtime.staticCompositionLocalOf<GuiEnvironment?> { null }

@Composable
internal fun GuiWindowScale(content: @Composable () -> Unit) {
    val environment = LocalGuiEnvironment.current
    if (environment == null) ProvideLumaHaptics(content) else CompositionLocalProvider(
        LocalDensity provides environment.density,
        LocalViewConfiguration provides environment.viewConfiguration,
    ) {
        ProvideLumaHaptics(content)
    }
}

@Composable
internal fun GuiDialog(
    onDismissRequest: () -> Unit,
    properties: androidx.compose.ui.window.DialogProperties = androidx.compose.ui.window.DialogProperties(),
    content: @Composable () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest, properties) {
        val view = androidx.compose.ui.platform.LocalView.current
        val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        val light = androidx.compose.material3.MaterialTheme.colorScheme.background.let {
            it.red * 0.2126f + it.green * 0.7152f + it.blue * 0.0722f > 0.5f
        }
        androidx.compose.runtime.SideEffect {
            window?.let {
                @Suppress("DEPRECATION")
                it.statusBarColor = android.graphics.Color.TRANSPARENT
                @Suppress("DEPRECATION")
                it.navigationBarColor = android.graphics.Color.TRANSPARENT
                it.isStatusBarContrastEnforced = false
                it.isNavigationBarContrastEnforced = false
                androidx.core.view.WindowCompat.getInsetsController(it, view).apply {
                    isAppearanceLightStatusBars = light
                    isAppearanceLightNavigationBars = light
                }
            }
        }
        GuiWindowScale { TextSelectionHost(content) }
    }
}
