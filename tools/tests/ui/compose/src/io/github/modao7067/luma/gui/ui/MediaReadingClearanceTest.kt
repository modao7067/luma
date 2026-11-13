package io.github.modao7067.luma.gui.ui

import android.app.Application
import android.view.View
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
class MediaReadingClearanceTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var host: View
    private var imeBottom = 0
    private var session by mutableStateOf(sampleView().copy(
        draft = "", queue = emptyList(), activeTurnId = null,
        subAgents = listOf(SubAgentState("agent", name = "检查", itemId = "spawn", status = "succeeded")),
        messages = listOf(ConversationItem("spawn", "tool", text = "启动子代理", outcome = "succeeded")) +
            (1..28).map { ConversationItem("row-$it", "assistant", "历史消息 $it\n\n第二段内容") } +
            ConversationItem("tail", "assistant", "尾部第一行\n\n尾部第二行\n\n尾部第三行"),
    ))
    private fun fixture() {
        rule.setContent { MaterialTheme {
            host = LocalView.current
            val density = LocalDensity.current
            val bottom = WindowInsets.ime.getBottom(density)
            SideEffect { imeBottom = bottom }
            val bitmap = remember { mediaFixtureBitmap() }
            CompositionLocalProvider(LocalMediaFixtureFrame provides MediaFrame(bitmap)) {
                SessionDetail(CockpitState(selected = session), session, previewActions, {})
            }
        } }
        rule.waitForIdle()
    }
    private fun ime(bottom: Int) {
        rule.runOnIdle {
            val insets = WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0,0,0,bottom))
                .setVisible(WindowInsetsCompat.Type.ime(), bottom > 0).build()
            ViewCompat.dispatchApplyWindowInsets(host, insets)
        }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals("IME fixture must reach the actual Compose WindowInsets", bottom, imeBottom) }
    }
    private fun assertTailClearsControls() {
        rule.waitForIdle()
        val tail = rule.onNodeWithText("尾部第三行").fetchSemanticsNode().boundsInRoot
        val button = rule.onNodeWithTag("subagent-capsule").fetchSemanticsNode().boundsInRoot
        assertTrue("tail=$tail must clear controls=$button", tail.bottom <= button.top - 6f)
    }
    @Test fun imeClosingKeepsLastLineAboveCircleCapsuleAndAttachmentComposer() {
        fixture()
        assertTailClearsControls()
        ime(280); assertTailClearsControls()
        ime(0); assertTailClearsControls()
        rule.runOnIdle { session = session.copy(subAgents = session.subAgents.map { it.copy(status = "running") }) }
        assertTailClearsControls()
        rule.runOnIdle { session = session.copy(attachments = listOf(Attachment("attachment", "fixture.png", "截图.png")), draft = "待发送内容") }
        assertTailClearsControls()
        ime(280); ime(0); assertTailClearsControls()
        rule.runOnIdle { session = session.copy(subAgents = session.subAgents.map { it.copy(status = "closed") }, attachments = emptyList()) }
        assertTailClearsControls()
    }
    @Test fun imeClosingDoesNotReturnAReaderToTheTail() {
        fixture(); ime(280)
        rule.onNodeWithTag("conversation-list").performTouchInput { swipeDown() }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("↓ 回到底部").assertExists()
        val visible = rule.onAllNodes(hasText("历史消息", substring = true)).fetchSemanticsNodes()
            .filter { it.boundsInRoot.top >= 70 && it.boundsInRoot.bottom < 500 }
        assertTrue(visible.isNotEmpty())
        val anchor = visible.first()
        val text = anchor.config[androidx.compose.ui.semantics.SemanticsProperties.Text].first().text
        val y = anchor.boundsInRoot.top
        ime(0)
        rule.onNodeWithContentDescription("↓ 回到底部").assertExists()
        assertEquals(y, rule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top, 2f)
    }

    @Test fun withoutSubAgentCapsuleIdleBottomHasNoExtraFloatingClearance() {
        session = session.copy(subAgents = emptyList())
        fixture()
        rule.onNodeWithTag("subagent-capsule").assertDoesNotExist()
        rule.onNodeWithContentDescription("↓ 回到底部").assertDoesNotExist()
        val listBounds = rule.onNodeWithTag("conversation-list").fetchSemanticsNode().boundsInRoot
        val tailBounds = rule.onNodeWithText("尾部第三行").fetchSemanticsNode().boundsInRoot
        val density = rule.density.density
        val bottomGapPx = listBounds.bottom - tailBounds.bottom
        assertTrue("Expected compact bottom gap (< 90dp) without capsule, got ${bottomGapPx / density}dp", bottomGapPx < 90f * density)
        assertTrue("Expected tail still clears composer bar (>= 68dp), got ${bottomGapPx / density}dp", bottomGapPx >= 68f * density)
    }
}
