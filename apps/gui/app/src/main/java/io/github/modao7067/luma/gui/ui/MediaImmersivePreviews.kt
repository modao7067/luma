package io.github.modao7067.luma.gui.ui

import androidx.compose.runtime.*
import androidx.compose.ui.tooling.preview.Preview
import io.github.modao7067.luma.gui.LumaTheme
import io.github.modao7067.luma.gui.session.*

@Composable private fun ImmersivePreview(dark: Boolean, document: Boolean) = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = dark) {
    val bitmap = remember { mediaFixtureBitmap() }
    SessionTypography { CompositionLocalProvider(LocalMediaFixtureFrame provides MediaFrame(bitmap)) {
        MediaViewerHost("immersive", previewActions, MediaContext("fixture", "/work")) {
            if (document) FilePreviewScreen(FilePreview("项目记录.md", (1..35).joinToString("\n\n") { "## 第${it}节\n\n完整页面保留图片与文件记录。阅读内容随滚动经过悬浮顶栏。" },
                sourceResource = MediaResource("notes.md", "项目记录.md", "text/markdown")), previewActions, previewScrollOffset = 420)
            else MediaViewerScreen(MediaResource("fixture.png", "全屏图片.png", "image/png"), previewZoom = 2.5f) {}
        }
    } }
}
@Preview(widthDp = 393, heightDp = 852, showSystemUi = true) @Composable internal fun ImmersiveImageLightPreview() = ImmersivePreview(false, false)
@Preview(widthDp = 393, heightDp = 852, showSystemUi = true) @Composable internal fun ImmersiveImageDarkPreview() = ImmersivePreview(true, false)
@Preview(widthDp = 393, heightDp = 852, showSystemUi = true) @Composable internal fun ImmersiveTextLightPreview() = ImmersivePreview(false, true)
@Preview(widthDp = 393, heightDp = 852, showSystemUi = true) @Composable internal fun ImmersiveTextDarkPreview() = ImmersivePreview(true, true)
