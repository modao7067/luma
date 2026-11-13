package io.github.modao7067.luma.gui.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.modao7067.luma.gui.LumaTheme
import io.github.modao7067.luma.gui.session.RequestChoice
import io.github.modao7067.luma.gui.session.UserRequest

internal fun requestReliabilityFixture() = UserRequest("request-preview", "命令执行审批",
    "读取当前项目的测试结果。",
    listOf(RequestChoice("0", "允许这次"), RequestChoice("1", "允许本会话的同类命令"),
        RequestChoice("2", "拒绝，继续任务"), RequestChoice("3", "拒绝并中断")))

@Preview(name = "Native approval choices on narrow screen", widthDp = 320, heightDp = 760, fontScale = 1.3f)
@Composable internal fun RequestChoicesNarrowPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) {
    SessionTypography { RequestDialog(requestReliabilityFixture(), previewActions) {} }
}

@Preview(name = "Disconnected request has a recovery action", widthDp = 393, heightDp = 760)
@Composable internal fun RequestDisconnectedPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = true) {
    SessionTypography { RequestDialog(requestReliabilityFixture(), previewActions, canRespond = false) {} }
}
