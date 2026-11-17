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

    @Test fun groupedModesKeepTwoSelectionsAndDispatchTheNativeGroupWithDuplicateIds() {
        var planned by mutableStateOf(false)
        val groupedCalls = mutableListOf<Pair<String, String>>()
        val legacyCalls = mutableListOf<String>()
        val actions = object : CockpitActions by previewActions {
            override fun configurePermission(id: String) { legacyCalls += id }
            override fun configurePermissionGroup(configId: String, id: String) { groupedCalls += configId to id }
        }
        rule.setContent { MaterialTheme { CompositionLocalProvider(LocalInspectionMode provides true) {
            val options = listOf(
                RequestChoice("off", "Ask", configId = "permissions", configName = "Permissions", selected = true),
                RequestChoice("on", "Allow", configId = "permissions", configName = "Permissions", selected = false),
                RequestChoice("off", "Build", configId = "workflow", configName = "Workflow", selected = !planned),
                RequestChoice("plan", "Plan", configId = "workflow", configName = "Workflow", selected = planned),
            )
            val view = sampleView().let { it.copy(summary = it.summary.copy(cli = "CodeBuddy Code"), activeTurnId = null,
                queue = emptyList(), draft = "", permission = "off", permissions = options) }
            Box(Modifier.fillMaxSize()) { Box(Modifier.align(Alignment.BottomCenter)) {
                CompactComposer(CockpitState(selected = view), view, actions, {}, {}, previewExpanded = true, previewMotionFraction = 1f)
            } }
        } } }
        rule.onNodeWithContentDescription("权限：Ask").performClick()
        rule.onNodeWithTag("permission-group-divider-1").assertIsDisplayed()
        rule.onNodeWithText("Permissions").assertIsDisplayed()
        rule.onNodeWithText("Workflow").assertIsDisplayed()
        rule.onNodeWithText("Ask").assertIsSelected()
        rule.onNodeWithText("Build").assertIsSelected()
        rule.onNodeWithText("Plan").performClick()
        rule.runOnIdle { assertEquals(listOf("workflow" to "plan"), groupedCalls); assertTrue(legacyCalls.isEmpty()) }
        rule.onNodeWithContentDescription("权限：Ask").assertExists()
        rule.runOnIdle { planned = true }
        rule.onNodeWithContentDescription("权限：Plan · Ask").performClick()
        rule.onNodeWithText("Plan").assertIsSelected()
        rule.onNodeWithText("Ask").assertIsSelected()
        rule.onNodeWithText("Build").assertIsNotSelected()
        rule.onNodeWithText("Allow").performClick()
        rule.runOnIdle { assertEquals("permissions" to "on", groupedCalls.last()); assertTrue(legacyCalls.isEmpty()) }
    }

    @Test fun everyCliUsesNativeCatalogForBothCurrentButtonAndMenu() {
        var case by mutableStateOf(permissionIconPreviewCases.first())
        val calls = mutableListOf<String>()
        val actions = object : CockpitActions by previewActions {
            override fun configurePermission(id: String) { calls += id }
        }
        rule.setContent { MaterialTheme { CompositionLocalProvider(LocalInspectionMode provides true) {
            val view = sampleView().let { it.copy(summary = it.summary.copy(cli = case.cli), activeTurnId = null,
                queue = emptyList(), draft = "", permission = case.selected, permissions = case.options,
                planModeSupported = case.planModeSupported, planMode = case.planMode, skipPermissionsActive = case.skipPermissionsActive) }
            Box(Modifier.fillMaxSize()) { Box(Modifier.align(Alignment.BottomCenter)) {
                CompactComposer(CockpitState(selected = view), view, actions, {}, {}, previewExpanded = true,
                    previewMotionFraction = 1f)
            } }
        } } }
        permissionIconPreviewCases.forEach { next ->
            rule.runOnIdle { case = next }
            val selected = permissionPresentation(next.selected, cli = next.cli, option = next.options.find { it.id == next.selected })
            val expectedDescription = if (next.planMode) "权限：Plan · ${selected.title}" else "权限：${selected.title}"
            val button = rule.onNodeWithContentDescription(expectedDescription)
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
            if (next.planModeSupported) {
                if (next.planMode) rule.onNodeWithText("Plan").assertIsSelected() else rule.onNodeWithText("Plan").assertExists().assertIsNotSelected()
                if (next.options.isNotEmpty()) {
                    rule.onNodeWithTag("permission-plan-divider").assertIsDisplayed()
                }
            } else {
                rule.onNodeWithTag("permission-plan-divider").assertDoesNotExist()
                rule.onNodeWithTag("permission-group-divider-1").assertDoesNotExist()
            }
            button.performClick()
        }
        rule.runOnIdle { assertTrue(calls.isEmpty()) }
    }

    @Test fun grokAutomaticApprovalKeepsExactDispatchAndWaitsForNativeReadback() {
        val fixture = permissionIconPreviewCases.first { it.cli == "Grok Build" }
        var permission by mutableStateOf("default")
        var planMode by mutableStateOf(false)
        val calls = mutableListOf<String>()
        val planCalls = mutableListOf<Boolean>()
        val actions = object : CockpitActions by previewActions {
            override fun configurePermission(id: String) { calls += id }
            override fun configurePlanMode(enabled: Boolean) { planCalls += enabled }
        }
        rule.setContent { MaterialTheme { CompositionLocalProvider(LocalInspectionMode provides true) {
            val view = sampleView().let { it.copy(summary = it.summary.copy(cli = fixture.cli), activeTurnId = null,
                queue = emptyList(), draft = "", permission = permission, permissions = fixture.options,
                planModeSupported = fixture.planModeSupported, planMode = planMode) }
            Box(Modifier.fillMaxSize()) { Box(Modifier.align(Alignment.BottomCenter)) {
                CompactComposer(CockpitState(selected = view), view, actions, {}, {}, previewExpanded = true,
                    previewMotionFraction = 1f)
            } }
        } } }
        rule.onNodeWithContentDescription("权限：Default").performClick()
        rule.onNodeWithText("Plan").performClick()
        rule.runOnIdle { assertEquals(listOf(true), planCalls); assertTrue(calls.isEmpty()); planMode = true }
        rule.onNodeWithContentDescription("权限：Plan · Default").performClick()
        rule.onNodeWithText("Always approve").performClick()
        rule.runOnIdle { assertEquals(listOf("always-approve"), calls); assertEquals("default", permission) }
        rule.onNodeWithContentDescription("权限：Plan · Default").assertExists()
        rule.runOnIdle { permission = "always-approve" }
        rule.onNodeWithContentDescription("权限：Plan · Always approve").assertExists()
    }
}
