package io.github.modao7067.luma.gui.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.modao7067.luma.gui.LumaTheme
import io.github.modao7067.luma.gui.session.*

@Preview(name = "Empty conversation", widthDp = 393, heightDp = 852)
@Composable
internal fun EmptyConversationPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = true) {
    val summary = SessionSummary("empty", "未命名会话", "root", "/workspace", cli = "DeepSeek Harness")
    CockpitScreen(CockpitState(sessions = listOf(summary), selected = SessionView(summary,
        connected = true, capabilityNotice = "原生 ACP 能力说明")), previewActions)
}
