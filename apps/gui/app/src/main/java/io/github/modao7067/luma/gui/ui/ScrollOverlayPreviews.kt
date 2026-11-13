package io.github.modao7067.luma.gui.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.modao7067.luma.gui.LumaTheme
import io.github.modao7067.luma.gui.session.CockpitState
import io.github.modao7067.luma.gui.session.ConversationItem
import io.github.modao7067.luma.gui.session.SubAgentDetailView
import io.github.modao7067.luma.gui.session.SubAgentState

@Preview(name = "Scroll detached glass overlay", widthDp = 393, heightDp = 852)
@Composable
internal fun ScrollOverlayPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = true) {
    SessionTypography { ConversationTypography {
        val view = queueAgentsFixture(queue = false, agents = true)
        LiquidScaffold(
            wrapTop = false,
            wrapBottom = false,
            topBar = { ConversationHeader(view, previewActions, {}, {}) },
            bottomBar = { ComposerGlass(Modifier.fillMaxWidth(0.88f)) { CompactComposer(CockpitState(selected = view), view, previewActions, {}, {}) } },
            overlay = { padding ->
                ConversationScrollOverlay(
                    visible = true,
                    padding = padding,
                    subAgents = view.subAgents,
                    onJump = {},
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                repeat(7) { index ->
                    Text("对话 ${index + 1}", style = MaterialTheme.typography.titleMedium)
                    Text("列表正文是玻璃的背景；子代理胶囊与回到底部按钮位于同一悬浮层。", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    } }
}

@Preview(name = "Sub-agent read-only full screen detail", widthDp = 393, heightDp = 852)
@Composable
internal fun SubAgentDetailPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) {
    SessionTypography {
        val agent = SubAgentState(
            id = "agent-protocol",
            name = "协议与布局检查",
            role = "explorer",
            turnId = "turn-1",
            itemId = "spawn",
            task = "核对子代理胶囊圆心对齐、队列避让与全屏只读详情。",
            status = "running",
            detail = "已完成顶底控件 44dp 圆心对齐与 8dp 避让核对。",
        )
        val detail = SubAgentDetailView(
            parentSessionId = "session-1",
            agent = agent,
            modelLabel = "GPT-5.4",
            effort = "high",
            messages = listOf(
                ConversationItem("t1", "tool", text = "读取 LiquidChrome.kt", kind = "read", outcome = "succeeded"),
                ConversationItem("a1", "assistant", text = "### 检查结论\n顶部与底部圆心对齐，阅读留白仅作用于消息列表。"),
            ),
        )
        SubAgentDetailScreen(detail = detail, cli = "Codex", actions = previewActions, onBack = {})
    }
}
