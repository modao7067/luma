package io.github.modao7067.luma.gui.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import io.github.modao7067.luma.gui.LumaTheme
import io.github.modao7067.luma.gui.session.FilePreview

@Preview(name = "Settings edge-to-edge", device = "spec:width=1272px,height=2772px,dpi=560", showSystemUi = true)
@Composable
internal fun SettingsWindowPreview(@PreviewParameter(IconThemeProvider::class) dark: Boolean) {
    LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = dark) {
        Box(Modifier.fillMaxSize()) { SettingsDialog(setOf("Codex"), { _, _ -> }, {}) }
    }
}

@Preview(name = "File preview edge-to-edge", device = "spec:width=1272px,height=2772px,dpi=560", showSystemUi = true)
@Composable
internal fun FileWindowPreview(@PreviewParameter(IconThemeProvider::class) dark: Boolean) {
    LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = dark) {
        Box(Modifier.fillMaxSize()) {
            FilePreviewDialog(FilePreview("/workspace/README.md", "# Luma\n\n正文与控件避让系统栏，背景延伸到边缘。"), previewActions)
        }
    }
}

@Preview(name = "Phone twenty columns", device = "spec:width=1272px,height=2772px,dpi=560")
@Composable
internal fun PhoneReadabilityPreview() {
    LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = true) {
        val view = sampleView().copy(activeTurnId = null, draft = "", queue = emptyList(), messages = listOf(
            io.github.modao7067.luma.gui.session.ConversationItem("scale-user", "user", "整体放大，每行约二十个汉字。"),
            io.github.modao7067.luma.gui.session.ConversationItem("scale-body", "assistant",
                "一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十\n\n" +
                "### 检查阅读比例\n\n文字和控件一起放大，保留原来的层级和留白。\n\n" +
                "- 输入框保持完整\n- 顶栏不遮挡标题\n- 中文 English 混排正常"),
        ))
        CockpitScreen(io.github.modao7067.luma.gui.session.CockpitState(selected = view), previewActions)
    }
}
