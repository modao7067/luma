package io.github.modao7067.luma.gui.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.modao7067.luma.gui.R

internal fun cliDisplayLabel(cli: String): String = when (cli) {
    "pi" -> "Pi"
    "DeepSeek Harness" -> "Deepseek"
    "CodeBuddy Code" -> "CodeBuddy"
    "Qoder CLI" -> "Qoder"
    else -> cli
}

@DrawableRes
internal fun providerIconResource(cli: String): Int = when (cli) {
    "Codex" -> R.drawable.ic_provider_openai
    "OpenCode" -> R.drawable.ic_provider_opencode
    "pi" -> R.drawable.ic_provider_pi
    "DeepSeek Harness" -> R.drawable.ic_provider_deepseek
    "Kimi Code" -> R.drawable.ic_provider_kimi
    "Claude Code" -> R.drawable.ic_provider_claude
    "Antigravity" -> R.drawable.ic_provider_antigravity
    "Qoder CLI" -> R.drawable.ic_provider_qoder
    "Grok Build" -> R.drawable.ic_provider_grok
    "CodeBuddy Code" -> R.drawable.ic_provider_codebuddy
    else -> R.drawable.ic_menu_terminal
}

@Composable
internal fun ProviderIcon(cli: String, selected: Boolean, size: Dp = 30.dp) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.size(size).background(
            if (selected) colors.primary else colors.surfaceContainerHighest,
            CircleShape,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(providerIconResource(cli)),
            contentDescription = null,
            modifier = Modifier.size(size * 0.62f),
            tint = if (selected) colors.onPrimary else colors.onSurfaceVariant,
        )
    }
}

@Composable
internal fun ProviderSettingsRow(cli: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = { onCheckedChange(!checked) },
        shape = LumaCardShape(18.dp),
        color = if (checked) colors.secondaryContainer else colors.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProviderIcon(cli, selected = checked, size = 34.dp)
            Text(
                cliDisplayLabel(cli),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = if (checked) colors.onSecondaryContainer else colors.onSurfaceVariant,
            )
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}
