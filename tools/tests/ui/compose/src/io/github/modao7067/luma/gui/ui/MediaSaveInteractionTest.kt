package io.github.modao7067.luma.gui.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
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
class MediaSaveInteractionTest {
    @get:Rule val rule = createComposeRule()
    private val source = MediaResource("sandbox:/child/original.png", "display.png", "image/png", "parent", "/work/child")
    @Test fun saveUsesOriginalResourceAndDeduplicatesUntilCompletionAndAfterSuccess() {
        val completion = CompletableDeferred<String>()
        val calls = mutableListOf<MediaResource>()
        val actions = object : CockpitActions by previewActions {
            override suspend fun saveMedia(resource: MediaResource): String { calls += resource; return completion.await() }
        }
        rule.setContent { MaterialTheme {
            MediaViewerHost("parent", actions, MediaContext("parent", "/work")) {
                MediaViewerScreen(source, previewError = "无法预览") {}
            }
        } }
        val save = rule.onNodeWithContentDescription("保存原文件")
        val back = rule.onNodeWithContentDescription("返回来源")
        val a = save.fetchSemanticsNode().boundsInRoot; val b = back.fetchSemanticsNode().boundsInRoot
        assertEquals(b.width, a.width, .5f); assertEquals(b.height, a.height, .5f); assertEquals(b.top, a.top, .5f)
        save.performClick()
        save.assertIsNotEnabled()
        save.performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.runOnIdle { assertEquals(listOf(source), calls); completion.complete("/sdcard/Download/Luma/original.png") }
        save.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已保存"))
        save.assertIsNotEnabled()
        rule.onNodeWithText("已保存").assertExists()
        rule.runOnIdle { assertEquals(1, calls.size) }
    }
    @Test fun aSaveFailureLeavesAnImmediateRetryAndPreservesSourceScope() {
        var attempt = 0
        val feedback = mutableListOf<String>()
        val actions = object : CockpitActions by previewActions {
            override suspend fun saveMedia(resource: MediaResource): String {
                assertEquals(source, resource)
                if (++attempt == 1) throw java.io.IOException("存储空间不足")
                return "/sdcard/Download/Luma/original.png"
            }
        }
        rule.setContent { MaterialTheme { MediaSaveButton(source, actions) { feedback += it } } }
        rule.onNodeWithContentDescription("保存原文件").performClick().assertIsEnabled()
        rule.runOnIdle { assertEquals(listOf("存储空间不足"), feedback) }
        rule.onNodeWithContentDescription("保存原文件").performClick().assertIsNotEnabled()
        rule.runOnIdle { assertEquals(listOf("存储空间不足", "已保存"), feedback); assertEquals(2, attempt) }
    }
    @Test fun textErrorAndVirtualDiffShareTheSameSaveEntryWithoutGuessingAPath() {
        var preview by mutableStateOf(FilePreview("huge.md", "文件过大", sourceResource = source.copy(source = "large.md", mimeType = "text/markdown")))
        val calls = mutableListOf<MediaResource>()
        val actions = object : CockpitActions by previewActions {
            override suspend fun saveMedia(resource: MediaResource): String { calls += resource; return "/sdcard/Download/Luma/large.md" }
        }
        rule.setContent { MaterialTheme { Box(Modifier.width(393.dp).height(852.dp)) { FilePreviewScreen(preview, actions) } } }
        rule.onNodeWithContentDescription("保存原文件").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals("large.md", calls.single().source); preview = FilePreview("changes.diff", "", loading = true) }
        rule.onNodeWithContentDescription("保存原文件").assertIsNotEnabled()
        rule.runOnIdle { preview = preview.copy(loading = false, text = "+new", sourceResource = MediaResource("data:text/plain;base64,K25ldw==", "changes.patch", "text/plain")) }
        rule.onNodeWithContentDescription("保存原文件").assertIsEnabled()
    }
    @Test fun everyMediaKindOffersSavingEvenWhenThePreviewCannotRender() {
        var resource by mutableStateOf(source)
        rule.setContent { MaterialTheme { MediaViewerHost("parent", previewActions, MediaContext("parent", "/work")) { MediaViewerScreen(resource, previewError = "无法预览") {} } } }
        for (mime in listOf("image/png", "image/svg+xml", "application/pdf", "application/octet-stream")) {
            rule.runOnIdle { resource = source.copy(mimeType = mime) }
            rule.onNodeWithContentDescription("保存原文件").assertIsEnabled()
        }
    }
}
