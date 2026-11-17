package io.github.modao7067.luma.gui.ui

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.modao7067.luma.gui.LumaTheme
import io.github.modao7067.luma.gui.session.SessionSummary

private const val PREVIEW_NOW_MS = 1_794_310_020_000L

private val sidebarSamples = listOf(
    SessionSummary("1", "修复输入框的交互", "noroot", "/workspace/project", modelLabel = "GPT-6-Astra", cli = "Codex", lastActiveAt = PREVIEW_NOW_MS - 1_800_000L),
    SessionSummary("2", "新会话", "noroot", "/workspace", cli = "Codex", lastActiveAt = PREVIEW_NOW_MS - 86_400_000L),
    SessionSummary("3", "测试 OpenCode 工具调用", "noroot", "/workspace/project", modelLabel = "Claude Sonnet", cli = "OpenCode", lastActiveAt = PREVIEW_NOW_MS - 2 * 86_400_000L),
    SessionSummary("4", "整理浏览器接口与内置 Skill 的说明", "noroot", "/workspace", cli = "OpenCode", lastActiveAt = PREVIEW_NOW_MS - 8 * 86_400_000L),
    SessionSummary("5", "验证 Pi 原生 RPC 工具链", "noroot", "/workspace/project", modelLabel = "Claude Opus", cli = "pi", lastActiveAt = PREVIEW_NOW_MS - 5 * 86_400_000L),
    SessionSummary("7", "ACP 协议流式回归", "noroot", "/workspace/project", modelLabel = "deepseek-reasoner", cli = "DeepSeek Harness", lastActiveAt = PREVIEW_NOW_MS - 380 * 86_400_000L),
)
@Preview(widthDp = 365, heightDp = 720)
@Composable
internal fun TwoLevelSidebarPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) { SessionTypography {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.width(320.dp).fillMaxHeight()) {
        SessionSidebar(sidebarSamples, "3", {}, {}, nowMs = PREVIEW_NOW_MS)
    }
} }
@Preview(widthDp = 320, heightDp = 720, fontScale = 1.5f)
@Composable
internal fun TwoLevelSidebarLargePreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = true) { SessionTypography {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) { SessionSidebar(sidebarSamples,"3",{}, {}, nowMs = PREVIEW_NOW_MS) }
} }
@Preview(widthDp = 320, heightDp = 400)
@Composable
internal fun AcpCliSidebarPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) { SessionTypography {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        SessionSidebar(emptyList(), null, {}, {}, enabledClis = setOf("Qoder CLI", "Grok Build"), nowMs = PREVIEW_NOW_MS)
    }
} }


@Preview(widthDp = 320, heightDp = 720)
@Composable
internal fun ExpandedSidebarLightPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) { SessionTypography {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        SessionSidebar(sidebarSamples, "1", {}, {}, initiallyExpandedClis = setOf("Codex", "OpenCode"), nowMs = PREVIEW_NOW_MS)
    }
} }
@Preview(widthDp = 320, heightDp = 720, fontScale = 1.3f)
@Composable
internal fun ExpandedSidebarDarkPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = true) { SessionTypography {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        SessionSidebar(sidebarSamples, "1", {}, {}, initiallyExpandedClis = setOf("Codex", "OpenCode"), nowMs = PREVIEW_NOW_MS)
    }
} }
