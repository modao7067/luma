package io.github.modao7067.luma.gui.ui

import android.app.Application
import android.graphics.Color
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.view.WindowCompat
import io.github.modao7067.luma.gui.LumaTheme
import io.github.modao7067.luma.gui.session.FilePreview
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PageWindowTest {
    @get:Rule val rule = createComposeRule()

    @Suppress("DEPRECATION")
    private fun assertEdgeToEdge(window: Window, light: Boolean) {
        assertEquals(Color.TRANSPARENT, window.statusBarColor)
        assertEquals(Color.TRANSPARENT, window.navigationBarColor)
        assertFalse(window.isStatusBarContrastEnforced)
        assertFalse(window.isNavigationBarContrastEnforced)
        assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS,
            window.attributes.layoutInDisplayCutoutMode)
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        assertEquals(light, bars.isAppearanceLightStatusBars)
        assertEquals(light, bars.isAppearanceLightNavigationBars)
        assertEquals(WindowManager.LayoutParams.MATCH_PARENT, window.attributes.height)
    }

    @Test fun settingsAndProvidersShareEdgeToEdgeWindowAndBackStack() {
        var open by mutableStateOf(true)
        val toggles = mutableListOf<Pair<String, Boolean>>()
        rule.setContent { LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) {
            if (open) SettingsDialog(setOf("Codex"), { cli, enabled -> toggles += cli to enabled }, { open = false })
        } }
        val initialDialog = ShadowDialog.getLatestDialog() as ComponentDialog
        val card = rule.onNodeWithText("CLI 工具").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        rule.runOnIdle {
            val width = initialDialog.window!!.decorView.width.toFloat()
            assertTrue("Providers card must fit dialog: $card / $width", card.right < width)
            assertEquals("Full-screen card retains equal gutters", card.left, width - card.right, 2f)
        }
        rule.onNodeWithText("CLI 工具").performClick()
        rule.onNodeWithText("管理侧边栏显示的 CLI 列表").assertDoesNotExist()
        rule.onNodeWithText("Profile").assertDoesNotExist()
        rule.onNodeWithText("Codex").assertIsDisplayed().performClick()
        val dialog = ShadowDialog.getLatestDialog() as ComponentDialog
        rule.runOnIdle {
            assertEdgeToEdge(dialog.window!!, true)
            assertEquals(listOf("Codex" to false), toggles)
            dialog.onBackPressedDispatcher.onBackPressed()
        }
        rule.onNodeWithText("设置").assertIsDisplayed()
        rule.onNodeWithText("CLI 工具").assertIsDisplayed()
        rule.runOnIdle { assertTrue(open); dialog.onBackPressedDispatcher.onBackPressed() }
        rule.runOnIdle { assertFalse(open) }
    }

    @Test fun pageSystemIconsFollowThemeWithoutReplacingWindow() {
        var dark by mutableStateOf(false)
        rule.setContent { LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = dark) {
            GuiPageDialog({}) { Box(Modifier.fillMaxSize()) }
        } }
        val window = ShadowDialog.getLatestDialog().window!!
        rule.runOnIdle { assertEdgeToEdge(window, true); dark = true }
        rule.runOnIdle {
            assertSame(window, ShadowDialog.getLatestDialog().window)
            assertEdgeToEdge(window, false)
        }
    }

    @Test fun filePreviewAlsoUsesTransparentSystemBars() {
        rule.setContent { LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = true) {
            FilePreviewDialog(FilePreview("/workspace/readme.md", "# Preview\n\nContent"), previewActions)
        } }
        rule.onNodeWithText("readme.md").assertIsDisplayed()
        rule.runOnIdle { assertEdgeToEdge(ShadowDialog.getLatestDialog().window!!, false) }
    }
}
