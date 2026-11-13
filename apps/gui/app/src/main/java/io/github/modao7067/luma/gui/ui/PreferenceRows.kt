package io.github.modao7067.luma.gui.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun PreferenceChoice(title: String, selected: Boolean, summary: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = LumaCardShape(18.dp)
    LiquidSurface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        cornerRadius = 18.dp,
        tonalTint = if (selected) colors.secondaryContainer.copy(alpha = 0.85f) else colors.surfaceContainerLow.copy(alpha = 0.85f),
    ) {
        ListItem(
            headlineContent = { Text(title, style = MaterialTheme.typography.bodyLarge) },
            supportingContent = summary?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
            trailingContent = { RadioButton(selected = selected, onClick = null, enabled = enabled) },
            colors = ListItemDefaults.colors(
                containerColor = Color.Transparent,
                headlineColor = if (selected) colors.onSecondaryContainer else colors.onSurface,
                supportingColor = if (selected) colors.onSecondaryContainer.copy(alpha = 0.8f) else colors.onSurfaceVariant,
            ),
        )
    }
}
