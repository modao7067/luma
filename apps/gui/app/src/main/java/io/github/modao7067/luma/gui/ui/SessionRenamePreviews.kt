package io.github.modao7067.luma.gui.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.modao7067.luma.gui.LumaTheme
import io.github.modao7067.luma.gui.session.SessionSummary

@Preview(name = "Session rename", widthDp = 393, heightDp = 600)
@Composable
internal fun SessionRenamePreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) {
    SessionTypography {
        SessionRenameDialog(SessionSummary("rename", "修复终端里的链接预览", "noroot", "/workspace"), { _, _ -> }, {})
    }
}
