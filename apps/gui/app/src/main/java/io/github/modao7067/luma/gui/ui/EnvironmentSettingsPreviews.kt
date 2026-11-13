package io.github.modao7067.luma.gui.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import io.github.modao7067.luma.gui.LumaTheme
import io.github.modao7067.luma.gui.runtime.*

@Preview(name = "NoRoot Environment settings", device = "spec:width=1272px,height=2772px,dpi=560", showSystemUi = true)
@Composable
internal fun EnvironmentSettingsPreview(@PreviewParameter(IconThemeProvider::class) dark: Boolean) {
    val access = remember(dark) { object : EnvironmentSettingsAccess {
        override fun read() = EnvironmentSettingsState(!dark, !dark, true, true, !dark, if (dark) emptyList() else listOf("192.0.2.12"))
        override fun observe(onChanged: () -> Unit) = AutoCloseable {}
        override fun open(setting: EnvironmentSetting) {}
    } }
    LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = dark) {
        Box(Modifier.fillMaxSize()) { SettingsDialog(setOf("Codex"), { _, _ -> }, {}, "noroot", "environment", access) }
    }
}
