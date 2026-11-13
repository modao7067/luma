package io.github.modao7067.luma.gui.ui

import android.app.Application
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.MotionDurationScale
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
@OptIn(ExperimentalTestApi::class)
class MediaImmersiveInteractionTest {
    private val motion = object : MotionDurationScale { override var scaleFactor by mutableFloatStateOf(1f) }
    @get:Rule val rule = createComposeRule(effectContext = motion)
    private lateinit var host: View
    private fun bars() {
        rule.runOnIdle {
            ViewCompat.dispatchApplyWindowInsets(host, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0,24,0,0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0,0,0,24))
                .setVisible(WindowInsetsCompat.Type.systemBars(), true).build())
        }
        rule.waitForIdle()
    }
    @Test fun zoomDrawsBehindChromeAndSystemInsetsWhileHeaderRemainsClickableAtZeroMotion() {
        var saves = 0; var backs = 0
        val resource = MediaResource("original.png", "原图", "image/png", "session", "/work")
        val actions = object : CockpitActions by previewActions {
            override suspend fun saveMedia(resource: MediaResource): String { saves++; return "/sdcard/Download/Luma/original.png" }
        }
        rule.setContent { MaterialTheme {
            host = LocalView.current
            val bitmap = remember { mediaFixtureBitmap() }
            CompositionLocalProvider(LocalMediaFixtureFrame provides MediaFrame(bitmap)) {
                MediaViewerHost("session", actions, MediaContext("session", "/work")) { MediaViewerScreen(resource) { backs++ } }
            }
        } }
        bars()
        val canvas = rule.onNodeWithTag("media-zoom").fetchSemanticsNode().boundsInRoot
        val page = rule.onNodeWithTag("media-viewer").fetchSemanticsNode().boundsInRoot
        assertEquals(page, canvas)
        val header = rule.onNodeWithContentDescription("保存原文件").fetchSemanticsNode().boundsInRoot
        assertTrue(header.top >= 24f)
        val initial = rule.onNodeWithTag("media-image").fetchSemanticsNode().boundsInRoot
        assertTrue("initial=$initial header=$header canvas=$canvas", initial.top > header.bottom)
        assertTrue("initial=$initial canvas=$canvas", initial.bottom < canvas.bottom - 24f)
        rule.runOnIdle { motion.scaleFactor = 0f }
        rule.onNodeWithTag("media-zoom").performTouchInput { doubleClick(center) }
        val zoomed = rule.onNodeWithTag("media-image").fetchSemanticsNode().boundsInRoot
        assertTrue("zoom=$zoomed canvas=$canvas", zoomed.top <= canvas.top + 1f && zoomed.bottom >= canvas.bottom - 1f)
        rule.onNodeWithContentDescription("保存原文件").performClick()
        rule.onNodeWithContentDescription("返回来源").performClick()
        rule.runOnIdle { assertEquals(1, saves); assertEquals(1, backs) }
    }
    @Test fun markdownScrollUsesFullWindowAndMovesTextUnderTheFloatingHeader() {
        val source = MediaResource("notes.md", "记录.md", "text/markdown", "session", "/work")
        rule.setContent { MaterialTheme {
            host = LocalView.current
            FilePreviewScreen(FilePreview(source.name, (1..50).joinToString("\n\n") { "正文记录第${it}行" }, sourceResource = source), previewActions)
        } }
        bars()
        val scroll = rule.onNodeWithTag("file-preview-scroll").fetchSemanticsNode().boundsInRoot
        assertEquals(0f, scroll.top, 1f)
        val header = rule.onNodeWithContentDescription("保存原文件").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithText("正文记录第1行").assertIsDisplayed()
        assertTrue(rule.onNodeWithText("正文记录第1行").fetchSemanticsNode().boundsInRoot.top > header.bottom)
        rule.onNodeWithTag("file-preview-scroll").performTouchInput { swipeUp() }
        val rows = rule.onAllNodes(hasText("正文记录第", substring = true)).fetchSemanticsNodes().map { it.boundsInRoot }
        assertTrue("Some actual text must pass behind chrome: $rows", rows.any { it.bottom > 0f && it.top < header.bottom })
        rule.onNodeWithContentDescription("保存原文件").assertIsDisplayed().assertIsEnabled()
    }
}
