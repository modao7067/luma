package io.github.modao7067.luma.gui.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.modao7067.luma.gui.LumaTheme
import io.github.modao7067.luma.gui.session.SubAgentState

@Composable
private fun DetachedPanelFrame(kind: String) = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = true) {
    ConversationTypography {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().padding(24.dp)) {
            Box(Modifier.align(Alignment.BottomStart).size(44.dp)) {
            IconButton(onClick = {}) { MenuIcon(if (kind == "agents") MenuSymbol.MODEL else MenuSymbol.PLUGINS) }
            OriginMenu(true, {}, if (kind == "agents") 280.dp else 300.dp) {
                when (kind) {
                    "agents" -> CompositionLocalProvider(LocalProcessDotsPhase provides .3f) {
                        SubAgentPopupItems(
                            listOf(SubAgentState("gauss", name = "Gauss", status = "running"), SubAgentState("nash", name = "Nash", status = "running")) +
                                (0..29).map { SubAgentState("history-$it", name = "历史代理 $it", status = "succeeded") },
                            null, true, {}, true, onSelect = {},
                        )
                    }
                    "plugins" -> {
                        ActionMenuItem("插件", MenuSymbol.PLUGINS, trailing = MenuSymbol.UP) {}
                        Text("Skill", Modifier.padding(18.dp), style = MaterialTheme.typography.labelMedium)
                        repeat(5) { ActionMenuItem("Skill ${it + 1}", MenuSymbol.SKILL) {} }
                        Text("MCP", Modifier.padding(18.dp), style = MaterialTheme.typography.labelMedium)
                        ActionMenuItem("本地工具", MenuSymbol.MCP, trailing = MenuSymbol.DOWN) {}
                    }
                    else -> {
                        ActionMenuItem("模型", MenuSymbol.MODEL, description = "Gemini 4 Argon", badge = "google-vertex", badgeOnDescription = true, trailing = MenuSymbol.UP) {}
                        MenuSectionReveal(true, MaterialTheme.colorScheme.surfaceContainer) {
                            MenuChoiceList((0..49).toList(), key = { "$it" }) {
                                ActionMenuItem("Gemini model $it", MenuSymbol.MODEL, badge = "google-vertex", selectionAtStart = true, selected = it == 0) {}
                            }
                        }
                        ActionMenuItem("思考强度", MenuSymbol.THINKING, description = "high", trailing = MenuSymbol.DOWN) {}
                        ActionMenuItem("速度", MenuSymbol.SPEED, description = "默认", trailing = MenuSymbol.DOWN) {}
                    }
                }
            }
            }
            }
        }
    }
}

@Preview(name = "Detached model actions", widthDp = 393, heightDp = 852)
@Composable internal fun DetachedModelActionsPreview() = DetachedPanelFrame("models")
@Preview(name = "Detached plugins", widthDp = 393, heightDp = 852)
@Composable internal fun DetachedPluginActionsPreview() = DetachedPanelFrame("plugins")
@Preview(name = "Detached agent history narrow", widthDp = 300, heightDp = 620)
@Composable internal fun DetachedAgentHistoryPreview() = DetachedPanelFrame("agents")
