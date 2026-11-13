package io.github.modao7067.luma.gui.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.modao7067.luma.gui.LumaTheme
import io.github.modao7067.luma.gui.session.*

@Preview(name = "Direct creation · initial entry", widthDp = 393, heightDp = 852)
@Composable
internal fun DirectCreationInitialPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) {
    CockpitScreen(CockpitState(), previewActions)
}

@Preview(name = "Direct creation · native setup error", widthDp = 393, heightDp = 852)
@Composable
internal fun DirectCreationErrorPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) {
    val summary = SessionSummary("new-native", "未命名会话", "noroot", "/workspace", threadId = "native")
    CockpitScreen(CockpitState(sessions = listOf(summary), selected = SessionView(summary,
        error = "连接中断，请重新连接以继续初始化原会话。")), previewActions)
}

@Preview(name = "Archived · same CLI blank composer", widthDp = 393, heightDp = 852)
@Composable
internal fun ArchivedBlankPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) {
    CockpitScreen(CockpitState(blank = BlankSessionView(cli = "Claude Code")), previewActions)
}

@Preview(name = "Archived · draft before native create", widthDp = 393, heightDp = 852)
@Composable
internal fun ArchivedBlankDraftPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) {
    CockpitScreen(CockpitState(blank = BlankSessionView(cli = "Claude Code", draft = "继续检查工作区中的实现，先报告实际问题。")), previewActions)
}
