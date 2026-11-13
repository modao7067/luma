package io.github.modao7067.luma.gui.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalInspectionMode
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
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SessionRenameInteractionTest {
    @get:Rule val rule = createComposeRule()
    private var summary by mutableStateOf(SessionSummary("local", "First message title", "noroot", "/workspace", cli = "Claude Code"))
    private val calls = mutableListOf<Pair<String, String>>()
    private val actions = object : CockpitActions by previewActions {
        override fun renameSession(id: String, title: String) { calls += id to title; summary = summary.copy(title = title) }
    }
    private fun fixture() {
        rule.setContent { MaterialTheme { CompositionLocalProvider(LocalInspectionMode provides true) {
            ConversationHeader(SessionView(summary, connected = false), actions, {}, {})
        } } }
        rule.waitForIdle()
    }
    private fun openRename() {
        rule.onNodeWithContentDescription("更多").performClick()
        rule.onNodeWithText("重命名", substring = false).performClick()
        rule.onNodeWithText("重命名会话").assertExists()
    }

    @Test fun renameDialogRendersWithoutAMenuWindow() {
        rule.setContent { MaterialTheme { SessionRenameDialog(summary, actions::renameSession, {}) } }
        rule.onNodeWithText("重命名会话").assertExists()
        rule.onNode(hasSetTextAction()).performTextReplacement("Direct name")
        rule.onNodeWithText("保存").performClick()
        rule.runOnIdle { assertEquals(listOf("local" to "Direct name"), calls) }
    }

    @Test fun disconnectedSessionCanRenameWithoutCliCapabilityAndBlankInputCannotSave() {
        fixture(); openRename()
        rule.onNode(hasSetTextAction()).performTextReplacement("   ")
        rule.onNodeWithText("保存").assertIsNotEnabled()
        rule.runOnIdle { assertTrue(calls.isEmpty()) }
        rule.onNode(hasSetTextAction()).performTextReplacement("  我的任务  ")
        rule.onNodeWithText("保存").assertIsEnabled().performClick()
        rule.onNodeWithText("重命名会话").assertDoesNotExist()
        rule.onNodeWithText("我的任务").assertExists()
        rule.runOnIdle { assertEquals(listOf("local" to "我的任务"), calls) }
        openRename()
        rule.onNode(hasSetTextAction()).performTextReplacement("Cancelled")
        rule.onNodeWithText("取消").performClick()
        rule.onNodeWithText("我的任务").assertExists()
        rule.runOnIdle { assertEquals(1, calls.size) }
    }

    @Test fun sidebarLongPressUsesTheSameRenameDialog() {
        rule.setContent { MaterialTheme { CompositionLocalProvider(LocalInspectionMode provides true) {
            SessionSidebar(listOf(summary), summary.id, {}, {}, enabledClis = setOf("Claude Code"),
                onRename = actions::renameSession)
        } } }
        rule.onNodeWithText("Claude Code", substring = false).performClick()
        rule.onNodeWithText("First message title").performTouchInput { longClick() }
        rule.onNodeWithText("重命名", substring = false).performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement("侧栏名称")
        rule.onNodeWithText("保存").performClick()
        rule.onNodeWithText("侧栏名称").assertExists()
        rule.runOnIdle { assertEquals(listOf("local" to "侧栏名称"), calls) }
    }

    @Test fun changingSessionsClosesTheOldRenameDialog() {
        fixture(); openRename()
        rule.onNode(hasSetTextAction()).performTextReplacement("Unsaved old name")
        rule.runOnIdle { summary = summary.copy(id = "other", title = "Other session") }
        rule.onNodeWithText("重命名会话").assertDoesNotExist()
        rule.onNodeWithText("Other session").assertExists()
        rule.runOnIdle { assertTrue(calls.isEmpty()) }
    }
}
