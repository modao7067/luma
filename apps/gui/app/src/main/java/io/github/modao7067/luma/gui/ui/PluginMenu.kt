package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.i18n.tr

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.modao7067.luma.gui.session.*

@Composable
internal fun PluginMenu(catalog: PluginCatalog, view: SessionView, actions: CockpitActions, onBack: () -> Unit) {
    ActionMenuItem(tr("插件", "Plugins"), MenuSymbol.PLUGINS, trailing = MenuSymbol.UP, iconContainer = true, onClick = onBack)
    PluginMenuContents(catalog, view, actions)
}

@Composable
internal fun PluginMenuContents(
    catalog: PluginCatalog,
    view: SessionView,
    actions: CockpitActions,
    onRetry: (() -> Unit)? = null,
) {
    if (catalog.loading) {
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
    }
    catalog.error?.let {
        Text(pluginFetchErrorLabel(it), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    if (!catalog.loading) {
        if (onRetry != null && (catalog.error != null || (catalog.skills.isEmpty() && catalog.mcp.isEmpty()))) {
            ActionMenuItem(tr("重试", "Retry"), MenuSymbol.REFRESH, enabled = view.connected, onClick = onRetry)
        }
        val maxSkills = view.capabilities.maxSkills
        if (catalog.error == null || catalog.skills.isNotEmpty()) {
            Text(
                if (maxSkills in 1..10) "Skill · ${view.skills.size}/$maxSkills" else "Skill",
                Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (catalog.error == null && (!view.capabilities.skills || catalog.skills.isEmpty())) {
            Text(tr("暂无插件", "No plugins"), Modifier.padding(horizontal = 18.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        catalog.skills.forEach { skill ->
            val isSelected = view.skills.any { it.id == skill.id }
            val canSelectMore = isSelected || view.skills.size < maxSkills
            ActionMenuItem(
                label = skill.name,
                icon = MenuSymbol.SKILL,
                selected = isSelected,
                iconContainer = true,
                enabled = view.capabilities.skills && canSelectMore,
                onClick = { actions.toggleSkill(skill) },
            )
        }
        if (catalog.mcp.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(horizontal = 18.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            Text(
                "MCP",
                Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            catalog.mcp.forEach { entry ->
                var expanded by remember(entry.name) { mutableStateOf(false) }
                ActionMenuItem(
                    label = entry.name,
                    icon = MenuSymbol.MCP,
                    trailing = if (expanded) MenuSymbol.UP else MenuSymbol.DOWN,
                    iconContainer = true,
                    onClick = { expanded = !expanded },
                )
                MenuSectionReveal(
                    expanded = expanded,
                    containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
                    cornerRadius = 12.dp,
                    horizontalPadding = 14.dp,
                    verticalPadding = 4.dp,
                ) {
                    Text(
                        entry.detail,
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

internal fun pluginFetchErrorLabel(error: String): String =
    if (error.startsWith("MCP", ignoreCase = true)) tr("无法获取 MCP", "Failed to load MCP") else tr("无法获取插件", "Failed to load plugins")

internal fun inventoryFetchErrorLabel(title: String): String =
    if (title == "MCP") tr("无法获取 MCP", "Failed to load MCP") else tr("无法获取插件", "Failed to load plugins")
