package io.github.modao7067.luma.gui.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
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
class ProviderIconsInteractionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun profileRowAndSwitchKeepTheirNativeToggleBehavior() {
        var enabled by mutableStateOf(false)
        val calls = mutableListOf<Boolean>()
        rule.setContent { MaterialTheme {
            ProviderSettingsRow("OpenCode", enabled) { enabled = it; calls += it }
        } }
        rule.onNodeWithText("OpenCode").performClick()
        rule.onNode(isToggleable()).assertIsOn().performClick()
        rule.onNode(isToggleable()).assertIsOff()
        rule.runOnIdle { assertEquals(listOf(true, false), calls) }
    }

    @Test fun pluginFailureIsShortAndDoesNotClaimAnEmptyList() {
        rule.setContent { MaterialTheme { Column {
            PluginMenu(PluginCatalog(error = "IOException: socket failed: private-details"), sampleView(), previewActions, {})
        } } }
        rule.onNodeWithText("无法获取插件").assertIsDisplayed()
        rule.onNodeWithText("暂无插件").assertDoesNotExist()
        rule.onNodeWithText("private-details", substring = true).assertDoesNotExist()
    }

    @Test fun actualEmptyPluginCatalogIsNotAFailure() {
        rule.setContent { MaterialTheme { Column { PluginMenu(PluginCatalog(), sampleView(), previewActions, {}) } } }
        rule.onNodeWithText("暂无插件").assertIsDisplayed()
        rule.onNodeWithText("无法获取插件").assertDoesNotExist()
        rule.onNodeWithText("无法获取 MCP").assertDoesNotExist()
    }

    @Test fun mcpFailureLeavesNativeSkillsSelectable() {
        val skill = DraftSkill("review", "code-review", "/skills/review/SKILL.md")
        var selected by mutableStateOf(false)
        val view = sampleView().copy(capabilities = sampleView().capabilities.copy(skills = true, maxSkills = 1))
        val actions = object : CockpitActions by previewActions {
            override fun toggleSkill(value: DraftSkill) { assertEquals(skill.id, value.id); selected = !selected }
        }
        rule.setContent { MaterialTheme { Column {
            PluginMenu(PluginCatalog(skills = listOf(skill), error = "MCP 读取失败：fixture"),
                view.copy(skills = if (selected) listOf(skill) else emptyList()), actions, {})
        } } }
        rule.onNodeWithText("无法获取 MCP").assertIsDisplayed()
        rule.onNodeWithText("code-review").performClick().assertIsSelected()
        rule.runOnIdle { assertTrue(selected) }
    }
}
