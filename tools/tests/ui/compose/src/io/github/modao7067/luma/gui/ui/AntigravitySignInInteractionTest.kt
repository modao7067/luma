package io.github.modao7067.luma.gui.ui

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.modao7067.luma.gui.session.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class AntigravitySignInInteractionTest {
    @get:Rule val rule = createComposeRule()
    private var request by mutableStateOf(antigravitySignInFixture())
    private var ready by mutableStateOf(true)
    private var visible by mutableStateOf(true)
    private var browserAvailable = true
    private val intents = mutableListOf<Intent>()
    private val answers = mutableListOf<String>()
    private var reconnects = 0
    private val actions = object : CockpitActions by previewActions {
        override fun answer(requestId: String, choice: String, answers: Map<String, List<String>>) {
            this@AntigravitySignInInteractionTest.answers += "$requestId:$choice"
            request = request.copy(answering = true)
        }
        override fun reconnect() { reconnects++ }
    }
    private fun fixture(session: Boolean = false) {
        rule.setContent { MaterialTheme {
            val actual = LocalContext.current
            val captured = remember(actual) { object : ContextWrapper(actual) {
                override fun startActivity(intent: Intent) {
                    if (!browserAvailable) throw ActivityNotFoundException()
                    intents += intent
                }
            } }
            CompositionLocalProvider(LocalContext provides captured) {
                if (session) {
                    val view = sampleView().copy(summary = sampleView().summary.copy(cli = "Antigravity"),
                        connected = false, loading = ready, requests = if (visible) listOf(request) else emptyList(),
                        messages = emptyList(), subAgents = emptyList(), queue = emptyList())
                    SessionDetail(CockpitState(selected = view), view, actions, onPickImages = {})
                } else if (visible) RequestDialog(request, actions, canRespond = ready) {}
            }
        } }
    }
    @Test fun browserClickOnlyLaunchesIntentAndNativeReceiptRetiresTheRequest() {
        fixture(session = true)
        rule.onNodeWithText("处理").performClick()
        rule.runOnIdle { assertTrue(intents.isEmpty()); assertTrue(answers.isEmpty()) }
        rule.onNodeWithText("打开 Google 登录").performClick()
        rule.onNodeWithText("等待官方 ACP 确认登录…").assertExists()
        rule.runOnIdle {
            assertTrue(answers.isEmpty()); assertEquals(1, intents.size)
            assertEquals(Intent.ACTION_VIEW, intents.single().action)
            assertEquals(request.externalUrl, intents.single().dataString)
            assertTrue(intents.single().hasCategory(Intent.CATEGORY_BROWSABLE))
            visible = false
        }
        rule.onNodeWithText("打开 Google 登录").assertDoesNotExist()
        println("PASS click captures ACTION_VIEW without answer/auth success; native request removal closes dialog")
    }
    @Test fun cancelUsesOnlyOriginalNativeChoiceAndPendingCannotSubmitAgain() {
        fixture()
        rule.onNodeWithText("取消登录").performClick()
        rule.onNodeWithText("正在取消登录…").assertExists()
        rule.onNodeWithText("取消登录").assertIsNotEnabled().performTouchInput { click() }
        rule.onNodeWithText("打开 Google 登录").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(listOf("antigravity-auth-browser:cancel"), answers); assertTrue(intents.isEmpty()) }
        println("PASS explicit cancel answers native request once; it does not open browser or forge success")
    }
    @Test fun disconnectCannotLaunchStaleBrowserAndReconnectRemainsAvailable() {
        ready = false
        fixture()
        rule.onNodeWithText("打开 Google 登录").assertIsNotEnabled().performTouchInput { click() }
        rule.onNodeWithText("取消登录").assertIsNotEnabled()
        rule.onNodeWithText("重新连接").performClick()
        rule.runOnIdle { assertTrue(intents.isEmpty()); assertTrue(answers.isEmpty()); assertEquals(1, reconnects); ready = true }
        rule.onNodeWithText("打开 Google 登录").assertIsEnabled()
        println("PASS disconnected retained fixture cannot launch stale authorization; reconnect is explicit")
    }
    @Test fun missingBrowserHasSafeRetryWithoutLeakingUrlOrResolvingLogin() {
        browserAvailable = false
        fixture()
        rule.onNodeWithText("打开 Google 登录").performClick()
        rule.onNodeWithText("无法打开浏览器，请检查系统浏览器后重试。").assertExists()
        rule.runOnIdle { assertTrue(answers.isEmpty()); assertTrue(intents.isEmpty()); browserAvailable = true }
        rule.onNodeWithText("打开 Google 登录").performClick()
        rule.onNodeWithText("等待官方 ACP 确认登录…").assertExists()
        rule.onNodeWithText("无法打开浏览器，请检查系统浏览器后重试。").assertDoesNotExist()
        rule.onNodeWithText(request.externalUrl!!).assertDoesNotExist()
        println("PASS browser unavailable is retryable and never resolves native authentication or displays secret URL")
    }
    @Test fun onlyOfficialHttpsEndpointsWithOneNativeLoopbackCallbackAreLaunchable() {
        val url = request.externalUrl!!
        assertNotNull(googleSignInIntent(url))
        assertNotNull(googleSignInIntent(url.replace("/o/oauth2/v2/auth", "/o/oauth2/auth")))
        listOf(
            url.replace("https:", "http:"), url.replace("accounts.google.com", "accounts.google.com.evil.test"),
            url.replace("accounts.google.com", "evil@accounts.google.com"), url.replace("accounts.google.com", "accounts.google.com:444"),
            url.replace("/o/oauth2/v2/auth", "/signin"), "$url#fragment", "$url&redirect_uri=http%3A%2F%2F127.0.0.1%3A54321%2F",
            url.replace("127.0.0.1", "localhost"), url.replace("127.0.0.1", "evil.test"),
            url.replace("54321", "0"), url.replace("54321", "65536"), url.replace("%2F&state", "%2Fcallback&state"),
            "intent://accounts.google.com/", "not a URL", "$url&redirect_uri=%ZZ",
        ).forEach { assertNull("untrusted endpoint/callback rejected", googleSignInIntent(it)) }
        request = request.copy(externalUrl = "https://evil.test/")
        fixture()
        rule.onNodeWithText("打开 Google 登录").assertIsNotEnabled()
        rule.onNodeWithText("登录链接无效，请取消后重新连接。").assertExists()
        rule.onNodeWithText("取消登录").assertIsEnabled()
        println("PASS strict Google HTTPS endpoint + unique loopback callback whitelist; invalid URL cannot launch")
    }
}
