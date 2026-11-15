package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.i18n.tr

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun DetailPageHeader(
    title: String,
    onBack: () -> Unit,
    subtitle: String = "",
    backLabel: String = tr("返回", "Back"),
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        LiquidGlass(
            Modifier.size(FloatingChromeSize)
                .liquidClickable(cornerRadius = FloatingChromeRadius, onClick = onBack)
                .semantics { contentDescription = backLabel },
            cornerRadius = FloatingChromeRadius,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ComposerGlyph("back") }
        }
        LiquidGlass(Modifier.weight(1f).height(FloatingChromeSize), cornerRadius = FloatingChromeRadius) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.Center) {
                Text(title, style = MaterialTheme.typography.titleSmall.copy(fontSize = 16.sp, lineHeight = 20.sp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) Text(subtitle,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 14.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing()
    }
}
