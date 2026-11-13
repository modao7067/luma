package io.github.modao7067.luma.gui.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.modao7067.luma.gui.LumaTheme
import io.github.modao7067.luma.gui.session.RequestChoice
import io.github.modao7067.luma.gui.session.UserRequest

internal fun antigravitySignInFixture() = UserRequest(
    id = "antigravity-auth-browser", title = "登录 Google Antigravity",
    body = "在浏览器完成 Google 登录；返回后由官方 ACP 确认。关闭网页不等于取消登录。",
    choices = listOf(RequestChoice("cancel", "取消登录")),
    externalUrl = "https://accounts.google.com/o/oauth2/v2/auth?client_id=fixture&redirect_uri=http%3A%2F%2F127.0.0.1%3A54321%2F&state=fixture",
)

@Preview(name = "Official ACP personal sign-in", widthDp = 393, heightDp = 760)
@Composable internal fun AntigravitySignInPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) {
    SessionTypography { RequestDialog(antigravitySignInFixture(), previewActions) {} }
}
@Preview(name = "Sign-in disconnected · narrow large type", widthDp = 320, heightDp = 760, fontScale = 1.3f)
@Composable internal fun AntigravityDisconnectedPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = true) {
    SessionTypography { RequestDialog(antigravitySignInFixture(), previewActions, canRespond = false) {} }
}
