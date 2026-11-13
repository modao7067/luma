package io.github.modao7067.luma.gui.ui

import androidx.compose.runtime.*
import androidx.compose.ui.tooling.preview.Preview
import io.github.modao7067.luma.gui.LumaTheme
import io.github.modao7067.luma.gui.session.*

@Composable private fun SavePreview(dark: Boolean, type: String) = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = dark) {
    val bitmap = remember { mediaFixtureBitmap() }
    val resource = MediaResource("/workspace/original.$type", "原始文件-2026-11-04.$type", when(type) { "png" -> "image/png"; "pdf" -> "application/pdf"; "md" -> "text/markdown"; else -> "application/octet-stream" }, "session", "/workspace")
    SessionTypography { CompositionLocalProvider(LocalMediaFixtureFrame provides MediaFrame(bitmap, if(type == "pdf") 2 else 1)) {
        MediaViewerHost("save-preview", previewActions, MediaContext("session", "/workspace")) {
            if (type == "md") FilePreviewScreen(FilePreview(resource.name, "# 项目记录\n\n本次交付包含界面与交互调整。\n\n- 图片与文件\n- 子代理历史", sourceResource = resource), previewActions)
            else MediaViewerScreen(resource) {}
        }
    } }
}
@Preview(widthDp = 393, heightDp = 852) @Composable internal fun SaveImageLightPreview() = SavePreview(false, "png")
@Preview(widthDp = 393, heightDp = 852) @Composable internal fun SaveImageDarkPreview() = SavePreview(true, "png")
@Preview(widthDp = 393, heightDp = 852) @Composable internal fun SavePdfPreview() = SavePreview(false, "pdf")
@Preview(widthDp = 393, heightDp = 852) @Composable internal fun SaveMarkdownPreview() = SavePreview(false, "md")
@Preview(widthDp = 393, heightDp = 852) @Composable internal fun SaveBinaryPreview() = SavePreview(true, "bin")
