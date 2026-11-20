package io.github.modao7067.luma.gui.ui

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
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
class MediaViewerInteractionTest {
    private val motion = object : MotionDurationScale { override var scaleFactor by mutableFloatStateOf(1f) }
    @get:Rule val rule = createComposeRule(effectContext = motion)
    private var owner by mutableStateOf("A")
    private var draft by mutableStateOf("保留草稿")
    private val calls = mutableListOf<MediaResource>()
    private lateinit var back: OnBackPressedDispatcher
    private var scrollPosition = 0
    private val image = MediaResource("image.png", "长文件名-9ee768c2-37dd-4ce6-a7ed-57cb28f18319.png", "image/png")
    private fun fixture(frame: Boolean = true) {
        rule.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            MaterialTheme {
                val bitmap = remember { mediaFixtureBitmap() }
                CompositionLocalProvider(LocalMediaFixtureFrame provides if (frame) MediaFrame(bitmap) else null,
                    LocalMediaFrameLoader provides if (frame) null else { resource, _, _ -> calls += resource; throw java.io.IOException("文件暂不可读") }) {
                    MediaViewerHost(owner, previewActions, MediaContext(owner, "/workspace/$owner")) {
                        val scroll = rememberScrollState()
                        SideEffect { scrollPosition = scroll.value }
                        Column(Modifier.fillMaxSize().verticalScroll(scroll).testTag("source-page")) {
                            TextField(draft, { draft = it }, Modifier.testTag("draft"))
                            ImageThumbnail(image.source, image.name)
                            UserMessageBubble(ConversationItem("user", "user", "图片下方的文字", media = listOf(image)), previewActions)
                            CompositionLocalProvider(LocalMediaContext provides MediaContext(owner, "/workspace/child")) {
                                NativeMarkdown("![子代理图片](child.png)")
                            }
                            repeat(20) { Text("原来的阅读位置 $it", Modifier.padding(8.dp)) }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }
    @Test fun pendingImageOpensFullScreenAndBothBackPathsPreserveDraftAndSource() {
        fixture()
        rule.onAllNodesWithTag("media-card:image.png")[0].performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.onNodeWithTag("media-viewer").assertIsDisplayed()
        val bounds = rule.onNodeWithTag("media-viewer").fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.width > 380f)
        rule.onNodeWithContentDescription("返回来源").performClick()
        rule.onNodeWithTag("media-viewer").assertDoesNotExist()
        rule.onNodeWithTag("draft").assertTextContains("保留草稿")
        rule.onAllNodesWithTag("media-card:image.png")[1].performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.runOnIdle { back.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithTag("media-viewer").assertDoesNotExist()
        rule.runOnIdle { assertEquals("保留草稿", draft); assertEquals(0, scrollPosition) }
    }
    @Test fun doubleTapZoomAndDragResetToFitWithoutEscapingBounds() {
        fixture()
        rule.onAllNodesWithTag("media-card:image.png")[0].performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.onNodeWithTag("media-zoom").performTouchInput { doubleClick(center) }
        rule.onNodeWithTag("media-zoom").assertContentDescriptionContains("缩放 250%", substring = true)
        rule.onNodeWithTag("media-zoom").performTouchInput { swipe(center, center + Offset(1000f, 1000f)) }
        rule.onNodeWithTag("media-zoom").performTouchInput { doubleClick(center) }
        rule.onNodeWithTag("media-zoom").assertContentDescriptionContains("缩放 100%", substring = true)
        assertEquals(Offset.Zero, clampMediaOffset(Offset(500f, -500f), 1f, Size(393f, 786f), IntSize(393, 800)))
        assertEquals(Offset(196.5f, -386f), clampMediaOffset(Offset(5000f, -5000f), 2f, Size(393f, 786f), IntSize(393, 800)))
    }
    @Test fun closingDuringEntryAndZeroMotionBothEndAtTheSource() {
        fixture()
        rule.mainClock.autoAdvance = false
        rule.onAllNodesWithTag("media-card:image.png")[0].performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.mainClock.advanceTimeByFrame(); rule.waitForIdle()
        rule.mainClock.advanceTimeBy(80); rule.waitForIdle()
        rule.onNodeWithTag("media-host:A").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "正在查看媒体"))
        rule.runOnIdle { back.onBackPressed() }
        rule.mainClock.advanceTimeByFrame(); rule.waitForIdle()
        rule.mainClock.advanceTimeBy(32); rule.waitForIdle()
        rule.onNodeWithTag("media-host:A").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "来源页面"))
        rule.mainClock.advanceTimeBy(1000); rule.waitForIdle()
        rule.onNodeWithTag("media-overlay").assertDoesNotExist()
        rule.runOnIdle { motion.scaleFactor = 0f }
        rule.onAllNodesWithTag("media-card:image.png")[0].performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.mainClock.advanceTimeByFrame(); rule.waitForIdle(); rule.mainClock.advanceTimeByFrame(); rule.waitForIdle()
        rule.onNodeWithTag("media-viewer").assertIsDisplayed()
        rule.runOnIdle { back.onBackPressed() }
        rule.mainClock.advanceTimeByFrame(); rule.waitForIdle(); rule.mainClock.advanceTimeByFrame(); rule.waitForIdle()
        rule.onNodeWithTag("media-overlay").assertDoesNotExist()
    }
    @Test fun switchingSessionDropsOnlyTheViewerAndNeverReassignsItsResource() {
        fixture(frame = false)
        rule.onAllNodesWithTag("media-card:image.png")[0].performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.onNodeWithText("重试").performClick()
        rule.runOnIdle { assertTrue(calls.all { it.sessionId == "A" }); owner = "B" }
        rule.waitForIdle()
        rule.onNodeWithTag("media-viewer").assertDoesNotExist()
        rule.onNodeWithTag("draft").assertTextContains("保留草稿")
    }
    @Test fun imagesAreOutsideTheUserBubbleAndImageOnlyMessagesHaveNoEmptyBubble() {
        rule.setContent { MaterialTheme {
            val bitmap = remember { mediaFixtureBitmap() }
            CompositionLocalProvider(LocalMediaFixtureFrame provides MediaFrame(bitmap)) {
                MediaViewerHost("A", previewActions, MediaContext("A", "/workspace")) {
                    Column {
                        UserMessageBubble(ConversationItem("both", "user", "下方文字", media = listOf(image)), previewActions)
                        UserMessageBubble(ConversationItem("image-only", "user", media = listOf(image.copy(source = "only.png"))), previewActions)
                    }
                }
            }
        } }
        val imageBounds = rule.onNodeWithTag("media-card:image.png").fetchSemanticsNode().boundsInRoot
        val bubbleBounds = rule.onNodeWithTag("user-bubble:both").fetchSemanticsNode().boundsInRoot
        assertTrue(imageBounds.bottom <= bubbleBounds.top)
        rule.onNodeWithTag("user-bubble:image-only").assertDoesNotExist()
    }
    @Test
    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun diagnoseLegacyShapeHitTesting() {
        val clicks = IntArray(3)
        val shapes = listOf<androidx.compose.ui.graphics.Shape>(
            androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
            androidx.compose.foundation.shape.GenericShape { size, _ -> addRect(androidx.compose.ui.geometry.Rect(Offset.Zero, size)) },
            LumaCardShape(18.dp),
        )
        rule.setContent { MaterialTheme { Column {
            shapes.forEachIndexed { index, shape ->
                Surface(onClick = { clicks[index]++ }, shape = shape, modifier = Modifier.size(84.dp).testTag("shape-$index")) { Text("$index") }
            }
        } } }
        for (index in 0..2) rule.onNodeWithTag("shape-$index").performTouchInput { click(center) }
        rule.runOnIdle { println("LEGACY touch counts rounded/generic-rect/miuix = ${clicks.toList()}"); assertEquals(1, clicks[0]) }
    }
    @Test fun headingWithResourceLinkStacksCardBelowHeadingWithoutOverlap() {
        val uri = "file:///workspace/project/apps/gui/app/src/main/assets/browser/luma-browser"
        rule.setContent {
            MaterialTheme {
                MediaViewerHost("A", previewActions, MediaContext("A", "/workspace/project")) {
                    NativeMarkdown("#### 3. 给 [luma-browser]($uri) 加上零转义极简命令")
                }
            }
        }
        rule.waitForIdle()
        val headingBounds = rule.onNodeWithText("3. 给 ", substring = true).fetchSemanticsNode().boundsInRoot
        val cardBounds = rule.onNodeWithTag("media-card:$uri").fetchSemanticsNode().boundsInRoot
        assertTrue("Expected card (${cardBounds.top}) below heading (${headingBounds.bottom})", cardBounds.top >= headingBounds.bottom)
    }
    @Test fun decodeBoundsAndPageFitAreBoundedForHugePortraitAndLandscape() {
        assertEquals(IntSize(1024, 2048), boundedMediaSize(12000, 24000, 2048))
        assertEquals(IntSize(2048, 1024), boundedMediaSize(24000, 12000, 2048))
        assertEquals(Size(350f, 700f), fittedMediaSize(IntSize(600, 1200), IntSize(393, 700)))
        assertEquals("23.8 MB", formatMediaSize(24_956_928))
        val tall = decodedMediaSize(1080, 10000, 2048)
        assertTrue(tall.width >= 950)
        assertTrue(tall.width.toLong() * tall.height <= 8_388_608L)
        assertTrue(decodedMediaSize(100000, 100, 2048).width <= 16384)
    }
    @Test fun attachedBrowserSlideAndPredictiveBackAndPureFloatingPreview() {
        var browserOpen by mutableStateOf(false)
        val summary = SessionSummary("s1", "Browser Session", "noroot", "/workspace", cli = "Codex")
        val sessionView = SessionView(summary = summary, connected = true)
        val actions = object : CockpitActions by previewActions {
            override fun openBrowser() { browserOpen = true }
            override fun closeBrowser() { browserOpen = false }
        }
        rule.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            val previewBmp = remember { android.graphics.Bitmap.createBitmap(360, 670, android.graphics.Bitmap.Config.ARGB_8888) }
            CompositionLocalProvider(LocalBrowserPreviewFixture provides previewBmp) {
                MaterialTheme {
                    CockpitScreen(
                        state = CockpitState(
                            sessions = listOf(summary),
                            selected = sessionView,
                            browserOpen = browserOpen,
                        ),
                        actions = actions,
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("browser-floating-preview").assertIsDisplayed()
        rule.onNodeWithTag("browser-floating-preview-image", useUnmergedTree = true).assertExists()
        assertEquals(
            0,
            rule.onAllNodes(hasAnyAncestor(hasTestTag("browser-floating-preview")), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .count { it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Text) },
        )

        rule.onNodeWithTag("browser-floating-preview").performClick()
        rule.waitForIdle()
        assertTrue(browserOpen)
        rule.onNodeWithTag("attached-browser-pane").assertIsDisplayed()
        rule.onNodeWithTag("browser-back-button").assertIsDisplayed()
        rule.onNodeWithTag("browser-address-field").assertIsDisplayed()
        rule.onNodeWithText("前往").assertDoesNotExist()
        rule.onNodeWithTag("browser-tab-bar").assertDoesNotExist()

        rule.onNodeWithTag("browser-more-button").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("新建标签页").assertIsDisplayed()
        rule.onNodeWithText("后退").assertIsDisplayed()
        rule.onNodeWithText("前进").assertIsDisplayed()
        rule.onNodeWithText("刷新").assertIsDisplayed()
        rule.onNodeWithText("桌面版网站").assertIsDisplayed()
        rule.onNodeWithText("复制链接").assertDoesNotExist()
        rule.onNodeWithText("在系统浏览器打开").assertDoesNotExist()
        rule.onNodeWithText("关闭当前标签页").assertDoesNotExist()

        rule.onNodeWithTag("action-menu-carrier").performTouchInput { click(Offset(2f, 2f)) }
        rule.waitForIdle()
        rule.runOnIdle { back.onBackPressed() }
        rule.waitForIdle()
        assertFalse(browserOpen)
        rule.onNodeWithTag("browser-floating-preview").assertIsDisplayed()
    }

    @Test fun failedMessageImagesCollapseCleanlyWhileComposerThumbnailAndPlaceholderStayIntact() {
        val good = MediaResource("good.png", "good.png", "image/png")
        val bad = MediaResource("bad.png", "bad.png", "image/png")
        val codexOpaque = MediaResource("codex-file:file-1", "opaque.png", "image/png")
        val bitmap = mediaFixtureBitmap()
        MediaFrameCache.clear()
        val cacheKey = MediaFrameKey(
            owner = "${previewActions.environment}:A",
            sessionId = "A",
            cwd = "/workspace/A",
            source = good.source,
            resolvedFileStamp = "file:///tmp/good.png|10|1",
            maxDimension = 256,
            page = 0,
        )
        MediaFrameCache.put(cacheKey, MediaFrame(bitmap))
        try {
            rule.setContent {
                MaterialTheme {
                    CompositionLocalProvider(LocalMediaFrameLoader provides { res, maxDim, _ ->
                        if (res.source == bad.source) throw java.io.IOException("不可读")
                        if (maxDim >= 2048) kotlinx.coroutines.delay(5_000)
                        MediaFrame(bitmap)
                    }) {
                        MediaViewerHost("A", previewActions, MediaContext("A", "/workspace/A")) {
                            Column {
                                ImageThumbnail(bad.source, bad.name)
                                UserMessageBubble(
                                    ConversationItem("u1", "user", "文字说明", media = listOf(bad, good, codexOpaque)),
                                    previewActions,
                                )
                                NativeMarkdown("![坏图](bad.png)")
                            }
                        }
                    }
                }
            }
            rule.waitForIdle()
            rule.onAllNodesWithTag("media-card:bad.png").assertCountEquals(1)
            rule.onNodeWithTag("media-card:codex-file:file-1").assertDoesNotExist()
            rule.onAllNodesWithText("无法预览").assertCountEquals(1)
            rule.onNodeWithTag("media-card:good.png").assertIsDisplayed()

            rule.onNodeWithTag("media-card:good.png").performSemanticsAction(SemanticsActions.OnClick) { it() }
            rule.waitForIdle()
            rule.onNodeWithTag("media-viewer").assertIsDisplayed()
            rule.onNodeWithTag("media-image").assertIsDisplayed()
        } finally {
            MediaFrameCache.clear()
        }
    }

    @Test fun markdownSmallBadgesRenderAtCompactIntrinsicSizeAndFlowHorizontally() {
        rule.setContent {
            MaterialTheme {
                val badgeA = remember { android.graphics.Bitmap.createBitmap(408, 60, android.graphics.Bitmap.Config.ARGB_8888) }
                val badgeB = remember { android.graphics.Bitmap.createBitmap(318, 60, android.graphics.Bitmap.Config.ARGB_8888) }
                CompositionLocalProvider(LocalMediaFrameLoader provides { res, _, _ ->
                    if (res.source.contains("badge-a")) MediaFrame(badgeA, intrinsicWidth = 136, intrinsicHeight = 20)
                    else MediaFrame(badgeB, intrinsicWidth = 106, intrinsicHeight = 20)
                }) {
                    MediaViewerHost("A", previewActions, MediaContext("A", "/workspace/A")) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            NativeMarkdown("![License](badge-a.svg)\n![ABI](badge-b.svg)")
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        val aBounds = rule.onNodeWithTag("media-card:badge-a.svg").fetchSemanticsNode().boundsInRoot
        val bBounds = rule.onNodeWithTag("media-card:badge-b.svg").fetchSemanticsNode().boundsInRoot
        assertTrue("Badge A height (${aBounds.height}) should stay compact", aBounds.height <= 30f)
        assertTrue("Badge A width (${aBounds.width}) should stay near intrinsic", aBounds.width in 120f..150f)
        assertTrue("Badge B height (${bBounds.height}) should stay compact", bBounds.height <= 30f)
        assertTrue("Badge B (${bBounds.left}) should flow beside Badge A (${aBounds.right})", bBounds.left >= aBounds.right)
        assertEquals(aBounds.top, bBounds.top, 2f)
    }
}
