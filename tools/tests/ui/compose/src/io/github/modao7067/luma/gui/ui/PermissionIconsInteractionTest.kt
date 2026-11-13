package io.github.modao7067.luma.gui.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PermissionIconsInteractionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun everyCliUsesNativeCatalogForBothCurrentButtonAndMenu() {
        var case by mutableStateOf(permissionIconPreviewCases.first())
        val calls = mutableListOf<String>()
        val actions = object : CockpitActions by previewActions {
            override fun configurePermission(id: String) { calls += id }
        }
        rule.setContent { MaterialTheme { CompositionLocalProvider(LocalInspectionMode provides true) {
            val view = sampleView().let { it.copy(summary = it.summary.copy(cli = case.cli), activeTurnId = null,
                queue = emptyList(), draft = "", permission = case.selected, permissions = case.options) }
            Box(Modifier.fillMaxSize()) { Box(Modifier.align(Alignment.BottomCenter)) {
                CompactComposer(CockpitState(selected = view), view, actions, {}, {}, previewExpanded = true,
                    previewMotionFraction = 1f)
            } }
        } } }
        permissionIconPreviewCases.forEach { next ->
            rule.runOnIdle { case = next }
            val selected = permissionPresentation(next.selected, cli = next.cli, option = next.options.find { it.id == next.selected })
            val button = rule.onNodeWithContentDescription("权限：${selected.title}")
            button.assertExists().performClick()
            if (next.options.isEmpty()) {
                rule.onNodeWithText("CLI 未提供可选权限").assertIsDisplayed()
            } else {
                rule.onNodeWithText(selected.title).assertIsSelected()
                next.options.forEach { option ->
                    val title = permissionPresentation(option.id, option.label, next.cli, option).title
                    rule.onNodeWithText(title).assertExists()
                }
            }
            button.performClick()
        }
        rule.runOnIdle { assertTrue(calls.isEmpty()) }
    }

    @Test fun grokAutomaticApprovalKeepsExactDispatchAndWaitsForNativeReadback() {
        val fixture = permissionIconPreviewCases.first { it.cli == "Grok Build" }
        var permission by mutableStateOf("default")
        val calls = mutableListOf<String>()
        val actions = object : CockpitActions by previewActions {
            override fun configurePermission(id: String) { calls += id }
        }
        rule.setContent { MaterialTheme { CompositionLocalProvider(LocalInspectionMode provides true) {
            val view = sampleView().let { it.copy(summary = it.summary.copy(cli = fixture.cli), activeTurnId = null,
                queue = emptyList(), draft = "", permission = permission, permissions = fixture.options) }
            Box(Modifier.fillMaxSize()) { Box(Modifier.align(Alignment.BottomCenter)) {
                CompactComposer(CockpitState(selected = view), view, actions, {}, {}, previewExpanded = true,
                    previewMotionFraction = 1f)
            } }
        } } }
        rule.onNodeWithContentDescription("权限：逐次审批").performClick()
        rule.onNodeWithText("跳过审批").performClick()
        rule.runOnIdle { assertEquals(listOf("always-approve"), calls); assertEquals("default", permission) }
        rule.onNodeWithContentDescription("权限：逐次审批").assertExists()
        rule.runOnIdle { permission = "always-approve" }
        rule.onNodeWithContentDescription("权限：跳过审批").assertExists()
    }
}
