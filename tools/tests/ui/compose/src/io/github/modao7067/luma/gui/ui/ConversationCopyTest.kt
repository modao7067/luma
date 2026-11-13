package io.github.modao7067.luma.gui.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.modao7067.luma.gui.session.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ConversationCopyTest {
    @get:Rule val rule = createComposeRule()

    @Test fun emptyConversationHasNoInstructionRowsButKeepsErrorFeedback() {
        val summary = SessionSummary("empty", "未命名会话", "root", "/workspace", cli = "DeepSeek Harness")
        val view = SessionView(summary, capabilityNotice = "原生 ACP 能力说明", error = "连接失败")
        rule.setContent { MaterialTheme { CompositionLocalProvider(LocalInspectionMode provides true) {
            SessionDetail(CockpitState(selected = view), view, previewActions, {})
        } } }
        rule.onNodeWithText("原生 ACP 能力说明").assertDoesNotExist()
        rule.onNodeWithText("开始这段对话").assertDoesNotExist()
        rule.onNodeWithText("发送任务，或先引用文件。").assertDoesNotExist()
        rule.onNodeWithText("连接失败").assertExists()
        rule.onNodeWithContentDescription("更多").assertExists()
    }
}
