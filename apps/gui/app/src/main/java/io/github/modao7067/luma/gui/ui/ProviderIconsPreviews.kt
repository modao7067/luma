package io.github.modao7067.luma.gui.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import io.github.modao7067.luma.gui.LumaTheme
import io.github.modao7067.luma.gui.session.*

internal class IconThemeProvider : PreviewParameterProvider<Boolean> {
    override val values = sequenceOf(false, true)
}

@Composable
private fun IconPreviewTheme(dark: Boolean, content: @Composable () -> Unit) =
    LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = dark) { SessionTypography { content() } }

@Preview(name = "CLI drawer · original marks", widthDp = 365, heightDp = 1100)
@Composable
internal fun ProviderSidebarPreview(@PreviewParameter(IconThemeProvider::class) dark: Boolean) = IconPreviewTheme(dark) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        SessionSidebar(
            CliCatalog.sortedIds.mapIndexed { index, cli -> SessionSummary("$index", "会话", "noroot", "/workspace", cli = cli) },
            "1", {}, {},
        )
    }
}

@Preview(name = "CLI Profile · selected / unselected", widthDp = 365, heightDp = 1120)
@Composable
internal fun ProviderSettingsPreview(@PreviewParameter(IconThemeProvider::class) dark: Boolean) = IconPreviewTheme(dark) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CliCatalog.sortedIds.forEachIndexed { index, cli -> ProviderSettingsRow(cli, index % 2 == 0) {} }
        }
    }
}

@Preview(name = "Plugin / Skill / MCP symbols", widthDp = 320, heightDp = 490)
@Composable
internal fun PluginIconsPreview(@PreviewParameter(IconThemeProvider::class) dark: Boolean) = IconPreviewTheme(dark) {
    val selected = DraftSkill("browser", "luma-browser", "/skills/browser/SKILL.md")
    val view = sampleView().copy(skills = listOf(selected), capabilities = sampleView().capabilities.copy(skills = true, maxSkills = 5))
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(Modifier.padding(vertical = 10.dp)) {
            ActionMenuItem("插件", MenuSymbol.PLUGINS, iconContainer = true, onClick = {})
            HorizontalDivider(Modifier.padding(horizontal = 18.dp))
            PluginMenu(
                PluginCatalog(listOf(selected, DraftSkill("review", "code-review", "/skills/review/SKILL.md")), listOf(ReadOnlyEntry("docs", "connected"))),
                view, previewActions, {},
            )
        }
    }
}

@Preview(name = "Plugin retrieval failure", widthDp = 320, heightDp = 190)
@Composable
internal fun PluginFailurePreview(@PreviewParameter(IconThemeProvider::class) dark: Boolean) = IconPreviewTheme(dark) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column { PluginMenu(PluginCatalog(error = "IOException: native skill query failed"), sampleView(), previewActions, {}) }
    }
}

@Preview(name = "MCP retrieval failure", widthDp = 320, heightDp = 260)
@Composable
internal fun McpFailurePreview(@PreviewParameter(IconThemeProvider::class) dark: Boolean) = IconPreviewTheme(dark) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column {
            PluginMenu(PluginCatalog(skills = listOf(DraftSkill("browser", "luma-browser", "/skills/browser/SKILL.md")), error = "MCP 读取失败：fixture"),
                sampleView().copy(capabilities = sampleView().capabilities.copy(skills = true)), previewActions, {})
        }
    }
}

@Preview(name = "Native permission · selected / unselected", widthDp = 320, heightDp = 390)
@Composable
internal fun PermissionIconsPreview(@PreviewParameter(IconThemeProvider::class) dark: Boolean) = IconPreviewTheme(dark) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(Modifier.padding(vertical = 10.dp)) {
            listOf("default", "acceptEdits", "auto", "plan", "dontAsk", "bypassPermissions").forEach { id ->
                val permission = permissionPresentation(id, cli = "Claude Code")
                ActionMenuItem(permission.title, permission.icon, selected = id == "auto", warning = permission.warning, iconContainer = true, onClick = {})
            }
        }
    }
}
