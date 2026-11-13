package io.github.modao7067.luma.gui.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.modao7067.luma.gui.LumaTheme

@Composable
private fun ProviderAlignmentFrame(dark: Boolean) = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = dark) { ConversationTypography {
    Surface(color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(
                Modifier.width(minOf(300.dp, maxWidth - 64.dp)),
                shape = LumaCardShape(24.dp),
                color = if (dark) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLowest,
            ) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    ActionMenuItem(
                        "模型", MenuSymbol.MODEL,
                        description = "Gemini 4 Argon with a long model name",
                        badge = if (dark) "google-vertex" else "organization-long-provider-id",
                        badgeOnDescription = true,
                        trailing = MenuSymbol.UP,
                    ) {}
                    ActionMenuItem(
                        "模型 · 任务运行中", MenuSymbol.MODEL,
                        description = "Gemini 4 Argon with a long model name",
                        badge = "google-vertex", badgeOnDescription = true,
                        trailing = MenuSymbol.DOWN, enabled = false,
                    ) {}
                    Surface(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                        shape = LumaCardShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
                    ) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            ActionMenuItem("GPT-6-Astra", MenuSymbol.MODEL, badge = "openai", selected = true, selectionAtStart = true) {}
                            ActionMenuItem("Claude", MenuSymbol.MODEL, badge = "anthropic", selectionAtStart = true) {}
                            ActionMenuItem("Flint 40B Free", MenuSymbol.MODEL, badge = "opencode", selectionAtStart = true) {}
                            ActionMenuItem("Crouton Alpha Free", MenuSymbol.MODEL, badge = "opencode", selectionAtStart = true) {}
                            ActionMenuItem("Long Reasoning Model Name", MenuSymbol.MODEL, badge = "organization-long-provider-id", selectionAtStart = true) {}
                            ActionMenuItem("Local model", MenuSymbol.MODEL, selectionAtStart = true) {}
                        }
                    }
                }
            }
        }
    }
} }

@Preview(name = "Model providers · current scale", widthDp = 393, heightDp = 450)
@Composable
internal fun ModelProviderAlignmentPreview() = ProviderAlignmentFrame(true)

@Preview(name = "Model providers · narrow", widthDp = 260, heightDp = 450)
@Composable
internal fun ModelProviderNarrowAlignmentPreview() = ProviderAlignmentFrame(false)
