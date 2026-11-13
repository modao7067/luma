package io.github.modao7067.luma.gui.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.CompletableDeferred
import io.github.modao7067.luma.gui.session.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="w393dp-h852dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class MediaExternalOpenInteractionTest {
    @get:Rule val rule=createComposeRule()
    private val original=MediaResource("sandbox:/work/luma-release.apk","luma-release.apk","application/vnd.android.package-archive","owner","/work",105_412_213)
    @Test fun unsupportedLargeFilesOnlyOpenOnExplicitClickAndKeepTheirCapturedOwner() {
        val calls=mutableListOf<MediaResource>()
        val actions=object:CockpitActions by previewActions {
            override suspend fun openMediaExternally(resource:MediaResource) {calls+=resource}
            override suspend fun resolveMedia(resource:MediaResource):ResolvedMedia = error("unsupported files must not run preview decoding")
        }
        rule.setContent {MaterialTheme {FilePreviewScreen(FilePreview(original.name,"",media=original),actions)}}
        rule.onNodeWithText("打开").assertIsEnabled()
        rule.onNodeWithText("用其他应用打开").assertIsEnabled()
        rule.runOnIdle {assertTrue(calls.isEmpty())}
        rule.onNodeWithText("打开").performClick()
        rule.runOnIdle {assertEquals(listOf(original),calls)}
    }
    @Test fun apkPreviewOffersDocumentPreviewerStyleOpenWithOtherAppButton() {
        val chooserCalls=mutableListOf<MediaResource>()
        val directCalls=mutableListOf<MediaResource>()
        val completion=CompletableDeferred<Unit>()
        val actions=object:CockpitActions by previewActions {
            override suspend fun openMediaExternally(resource:MediaResource) {directCalls+=resource}
            override suspend fun openMediaWithChooser(resource:MediaResource) {chooserCalls+=resource;completion.await()}
        }
        rule.setContent {MaterialTheme {FilePreviewScreen(FilePreview(original.name,"",media=original),actions)}}
        rule.onNodeWithText("用其他应用打开").assertIsDisplayed().assertIsEnabled().performClick()
        rule.onNodeWithText("用其他应用打开").assertIsNotEnabled()
        rule.onNodeWithText("正在打开…").assertIsNotEnabled()
        rule.onNodeWithText("取消").assertIsEnabled()
        rule.runOnIdle {
            assertEquals(listOf(original),chooserCalls)
            assertTrue(directCalls.isEmpty())
            completion.complete(Unit)
        }
        rule.onNodeWithText("用其他应用打开").assertIsEnabled()
        rule.onNodeWithText("打开").assertIsEnabled()
    }
    @Test fun previewErrorsForPdfAndImagesOfferExternalOpenWithoutReplacingTheirError() {
        var resource by mutableStateOf(original.copy(source="large.pdf",name="large.pdf",mimeType="application/pdf"))
        val calls=mutableListOf<MediaResource>()
        val actions=object:CockpitActions by previewActions {override suspend fun openMediaExternally(resource:MediaResource) {calls+=resource}}
        rule.setContent {MaterialTheme {MediaViewerHost("owner",actions,MediaContext("owner","/work")) {
            MediaViewerScreen(resource,previewError="附件超过 64 MiB") {}
        }}}
        rule.onNodeWithText("附件超过 64 MiB").assertIsDisplayed()
        rule.onNodeWithText("重试").assertIsDisplayed()
        rule.onNodeWithText("用其他应用打开").performClick()
        rule.runOnIdle {assertEquals(listOf(resource),calls);resource=resource.copy(source="broken.png",name="broken.png",mimeType="image/png")}
        rule.onNodeWithText("用其他应用打开").performClick()
        rule.runOnIdle {assertEquals(resource,calls.last());assertEquals(2,calls.size)}
    }
    @Test fun sourceAndHandlerErrorsRemainVisibleAndPermitAnImmediateRetry() {
        var attempts=0
        val actions=object:CockpitActions by previewActions {
            override suspend fun openMediaExternally(resource:MediaResource) {
                if(++attempts==1)throw java.io.IOException("没有可打开此文件的应用")
                throw java.io.FileNotFoundException("找不到文件：original.apk")
            }
        }
        rule.setContent {MaterialTheme {FilePreviewScreen(FilePreview(original.name,"",media=original),actions)}}
        rule.onNodeWithText("打开").performClick().assertIsEnabled()
        rule.onNodeWithText("没有可打开此文件的应用").assertIsDisplayed()
        rule.onNodeWithText("打开").performClick().assertIsEnabled()
        rule.onNodeWithText("找不到文件：original.apk").assertIsDisplayed()
        rule.runOnIdle {assertEquals(2,attempts)}
    }
    @Test fun pendingTransferCannotBeDuplicatedAndCanBeCancelledBeforeSystemLaunch() {
        val completion=CompletableDeferred<Unit>();var attempts=0;var cancelled=false
        val actions=object:CockpitActions by previewActions {
            override suspend fun openMediaExternally(resource:MediaResource) {
                attempts++;try {completion.await()}finally {cancelled=true}
            }
        }
        rule.setContent {MaterialTheme {FilePreviewScreen(FilePreview(original.name,"",media=original),actions)}}
        rule.onNodeWithText("打开").performClick()
        rule.onNodeWithText("正在打开…").assertIsNotEnabled()
        rule.onNodeWithText("取消").performClick()
        rule.onNodeWithText("打开").assertIsEnabled()
        rule.runOnIdle {assertEquals(1,attempts);assertTrue(cancelled)}
    }
    @Test fun directoryMediaResourceOffersBrowseFolderAndNavigatesDirectly() {
        var browsedPath: String? = null
        var previewClosed = false
        val dir = MediaResource("file:///workspace/luma/shared/terminal-emulator", "terminal-emulator", "inode/directory", "owner", "/workspace")
        val actions = object : CockpitActions by previewActions {
            override fun browseFiles(path: String) { browsedPath = path }
            override fun closePreview() { previewClosed = true }
        }
        rule.setContent { MaterialTheme { FilePreviewScreen(FilePreview(dir.name, "", media = dir), actions) } }
        rule.onNodeWithText("浏览文件夹").assertIsDisplayed().performClick()
        rule.runOnIdle {
            assertTrue(previewClosed)
            assertEquals("/workspace/luma/shared/terminal-emulator", browsedPath)
        }
    }
    @Test fun directoryMediaCardDirectlyBrowsesFilesOnClick() {
        var browsedPath: String? = null
        val dir = MediaResource("file:///workspace/luma/shared/terminal-emulator", "terminal-emulator", "inode/directory", "owner", "/workspace")
        val actions = object : CockpitActions by previewActions {
            override fun browseFiles(path: String) { browsedPath = path }
        }
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalSessionInteractive provides true,
                    LocalOpenMedia provides { _, _ -> },
                    LocalMediaActions provides actions
                ) {
                    MediaFileCard(dir)
                }
            }
        }
        rule.onNodeWithText("文件夹").assertIsDisplayed()
        rule.onNodeWithTag(mediaCardTag(dir)).performClick()
        rule.runOnIdle {
            assertEquals("/workspace/luma/shared/terminal-emulator", browsedPath)
        }
    }
    @Test fun codeFilePreviewShowsLineNumbersAndCopyAction() {
        val codeText = "fun main() {\n    println(\"hello\")\n}"
        val preview = FilePreview("Main.kt", codeText, sourceResource = MediaResource("Main.kt", "Main.kt", "text/plain"))
        rule.setContent { MaterialTheme { FilePreviewScreen(preview, previewActions) } }
        rule.onNodeWithText("复制全文").assertIsDisplayed()
        rule.onNodeWithText("1\n2\n3").assertIsDisplayed()
        rule.onNodeWithText(codeText, substring = true).assertIsDisplayed()
    }
}
