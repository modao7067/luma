package io.github.modao7067.luma.gui.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.modao7067.luma.gui.LumaTheme
import io.github.modao7067.luma.gui.session.*

internal fun mediaFixtureBitmap(): Bitmap = Bitmap.createBitmap(600, 1200, Bitmap.Config.ARGB_8888).also { bitmap ->
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    canvas.drawColor(0xFFECE7DE.toInt())
    paint.color = 0xFF37645B.toInt(); canvas.drawRect(0f, 0f, 600f, 360f, paint)
    paint.color = 0xFFE5EBB7.toInt(); canvas.drawCircle(445f, 130f, 62f, paint)
    paint.color = 0xFF79988A.toInt(); canvas.drawRoundRect(40f, 260f, 560f, 400f, 30f, 30f, paint)
    paint.color = 0xFF243D35.toInt(); paint.textSize = 42f
    canvas.drawText("A quieter workspace", 40f, 478f, paint)
    paint.textSize = 25f; paint.color = 0xFF56665F.toInt()
    canvas.drawText("Notes, sketches and a little room to think.", 40f, 530f, paint)
    repeat(5) { i ->
        paint.color = 0xFFD7DCD0.toInt(); canvas.drawRoundRect(40f, 590f+i*110, 560f, 674f+i*110, 20f, 20f, paint)
        paint.color = 0xFF51695C.toInt(); canvas.drawCircle(78f, 632f+i*110, 15f, paint)
        paint.textSize = 24f; canvas.drawText(listOf("Review the small details", "Keep the useful parts", "Make room for the image", "A clear way back", "Ready for the next step")[i], 112f, 640f+i*110, paint)
    }
}
private val screenshotResource = MediaResource("fixture.png", "preview-image.png", "image/png")
private val packageResource = MediaResource("/workspace/example-app-0.4.2-debug.apk", "example-app-0.4.2-debug.apk", "application/vnd.android.package-archive", sizeBytes = 24_956_928)

@Composable
private fun MediaPreviewTheme(dark: Boolean, content: @Composable () -> Unit) = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = dark) {
    val bitmap = remember { mediaFixtureBitmap() }
    SessionTypography { CompositionLocalProvider(LocalMediaFixtureFrame provides MediaFrame(bitmap)) {
        MediaViewerHost("fixture", previewActions, MediaContext("fixture", "/workspace")) { content() }
    } }
}

@Composable
private fun MediaCardsFixture(dark: Boolean) = MediaPreviewTheme(dark) {
    Surface(color = MaterialTheme.colorScheme.background) {
        LiquidScaffold(wrapTop = false, wrapBottom = false,
            topBar = { ConversationHeader(sampleView().copy(summary = sampleView().summary.copy(title = "图片与文件")), previewActions, {}, {}) },
            bottomBar = {},
        ) { padding ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                UserMessageBubble(ConversationItem("user-media", "user", "这张图直接全屏看。", media = listOf(screenshotResource)), previewActions)
                ConversationMessage(ConversationItem("agent-file", "assistant", "文件已整理好。", media = listOf(packageResource)), previewActions, "Codex")
                ConversationMessage(ConversationItem("agent-image", "assistant", "这是调整后的预览。", media = listOf(screenshotResource.copy(name = "页面预览.png"))), previewActions, "Codex")
            }
        }
    }
}
@Preview(name = "Media cards light", widthDp = 393, heightDp = 1040)
@Composable internal fun MediaCardsLightPreview() = MediaCardsFixture(false)
@Preview(name = "Media cards dark", widthDp = 393, heightDp = 1040)
@Composable internal fun MediaCardsDarkPreview() = MediaCardsFixture(true)
@Preview(name = "Full-screen portrait light", widthDp = 393, heightDp = 852)
@Composable internal fun MediaViewerLightPreview() = MediaPreviewTheme(false) { MediaViewerScreen(screenshotResource) {} }
@Preview(name = "Full-screen portrait dark", widthDp = 393, heightDp = 852)
@Composable internal fun MediaViewerDarkPreview() = MediaPreviewTheme(true) { MediaViewerScreen(screenshotResource) {} }
@Preview(name = "Files light", widthDp = 393, heightDp = 560)
@Composable internal fun MediaFilesLightPreview() = MediaFilesFixture(false)
@Preview(name = "Files dark", widthDp = 393, heightDp = 560)
@Composable internal fun MediaFilesDarkPreview() = MediaFilesFixture(true)
@Composable private fun MediaFilesFixture(dark: Boolean) = MediaPreviewTheme(dark) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DetailPageHeader("交付文件", {})
            MessageMedia(listOf(packageResource,
                MediaResource("report.pdf", "设计验收报告.pdf", "application/pdf", sizeBytes = 458752),
                MediaResource("preview.mp4", "交互动效.mp4", "video/mp4", sizeBytes = 3200000),
                MediaResource("notes.zip", "项目资料.zip", "application/zip", sizeBytes = 819200),
                MediaResource("audio.wav", "音频记录.wav", "audio/wav")))
        }
    }
}

@Composable private fun MediaMotionFixture(fraction: Float) = MediaPreviewTheme(false) {
    MediaViewerHost("motion", previewActions, MediaContext("fixture", "/workspace"), previewResource = screenshotResource, previewFraction = fraction) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(top = 70.dp, start = 18.dp, end = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                UserMessageBubble(ConversationItem("motion-user", "user", "这张图直接全屏看。", media = listOf(screenshotResource)), previewActions)
                MediaFileCard(packageResource)
            }
        }
    }
}
@Preview(name = "Media entry 20 percent", widthDp = 393, heightDp = 852)
@Composable internal fun MediaMotionEarlyPreview() = MediaMotionFixture(.2f)
@Preview(name = "Media reverse 60 percent", widthDp = 393, heightDp = 852)
@Composable internal fun MediaMotionMiddlePreview() = MediaMotionFixture(.6f)

internal val historyFixtureAgents = listOf(
    SubAgentState("old-success", name = "整理材料", status = "succeeded"),
    SubAgentState("running-ui", name = "界面实现", status = "running"),
    SubAgentState("waiting", name = "等待资源", status = "pending"),
    SubAgentState("old-failed", name = "验证脚本", status = "failed"),
    SubAgentState("running-protocol", name = "协议读取", status = "running"),
    SubAgentState("unknown", name = "外部代理", status = "unknown"),
)
@Composable private fun AgentHistoryFixture(dark: Boolean, expanded: Boolean, phase: Float = 0f) = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = dark) {
    SessionTypography { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SubAgentDetailHeader(SubAgentDetailView("fixture", historyFixtureAgents[1], modelLabel = "GPT-6-Astra"), {})
            Surface(shape = LumaCardShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, shadowElevation = 6.dp) {
                Column(Modifier.width(300.dp).padding(vertical = 8.dp)) {
                    CompositionLocalProvider(LocalProcessDotsPhase provides phase) { SubAgentPopupItems(historyFixtureAgents, "running-ui", expanded, {}, true, onSelect = {}) }
                }
            }
        }
    } }
}
@Preview(name = "Agent history collapsed light", widthDp = 393, heightDp = 620)
@Composable internal fun AgentHistoryLightPreview() = AgentHistoryFixture(false, false)
@Preview(name = "Agent history expanded dark", widthDp = 393, heightDp = 720)
@Composable internal fun AgentHistoryDarkPreview() = AgentHistoryFixture(true, true)
@Composable private fun AgentCapsuleFixture(fraction: Float) = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) {
    SessionTypography {
        LiquidScaffold(wrapTop = false, wrapBottom = false,
            topBar = { ConversationHeader(sampleView(), previewActions, {}, {}) }, bottomBar = {},
            overlay = { padding -> ConversationScrollOverlay(true, padding, historyFixtureAgents, previewRunningFraction = fraction, onJump = {}) },
        ) { padding -> Column(Modifier.padding(padding).padding(18.dp)) { Text("保留原来的阅读位置。", style = MaterialTheme.typography.bodyLarge) } }
    }
}
@Preview(name = "Agent capsule running", widthDp = 393, heightDp = 260)
@Composable internal fun AgentCapsuleRunningPreview() = AgentCapsuleFixture(1f)
@Preview(name = "Agent capsule mid-collapse", widthDp = 393, heightDp = 260)
@Composable internal fun AgentCapsuleMiddlePreview() = AgentCapsuleFixture(.5f)
@Preview(name = "Agent capsule at rest", widthDp = 393, heightDp = 260)
@Composable internal fun AgentCapsuleRestPreview() = AgentCapsuleFixture(0f)

@Preview(name = "Agent dots first lift", widthDp = 393, heightDp = 620)
@Composable internal fun AgentDotsEarlyPreview() = AgentHistoryFixture(false, false, .15f)
@Preview(name = "Agent dots second lift", widthDp = 393, heightDp = 620)
@Composable internal fun AgentDotsLatePreview() = AgentHistoryFixture(false, false, .3f)

@Preview(name = "Tail clears floating agents after IME", widthDp = 393, heightDp = 852)
@Composable internal fun MediaTailClearancePreview() = MediaPreviewTheme(false) {
    val view = sampleView().copy(draft = "", queue = emptyList(), activeTurnId = null,
        subAgents = listOf(SubAgentState("agent", name = "检查", itemId = "spawn", status = "succeeded")),
        messages = listOf(ConversationItem("spawn", "tool", text = "启动子代理", outcome = "succeeded")) +
            (1..10).map { ConversationItem("row-$it", "assistant", "保持同一会话的阅读位置。") } +
            ConversationItem("tail", "assistant", "尾部第一行。\n\n尾部第二行。\n\n最后一行完整可读。"))
    SessionDetail(CockpitState(selected = view), view, previewActions, {})
}

@Preview(name = "Long filenames", widthDp = 393, heightDp = 360)
@Composable internal fun MediaLongNamesPreview() = MediaPreviewTheme(false) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            DetailPageHeader("portrait-preview-with-a-long-example-filename.png", {})
            MediaFileCard(packageResource.copy(name = "example-app-0.4.2-debug-arm64-v8a-media-preview-verification.apk"))
            MediaFileCard(MediaResource("very-long.pdf", "项目设计与交互验收报告-文件管理与图片预览示例.pdf", "application/pdf", sizeBytes = 458752))
        }
    }
}
@Preview(name = "Media loading", widthDp = 393, heightDp = 852)
@Composable internal fun MediaLoadingPreview() = MediaPreviewTheme(false) { MediaViewerScreen(screenshotResource, previewLoading = true) {} }
@Preview(name = "Media failed retry", widthDp = 393, heightDp = 852)
@Composable internal fun MediaErrorPreview() = MediaPreviewTheme(true) { MediaViewerScreen(screenshotResource, previewError = "文件已移动或无法读取") {} }
private fun syntheticLongScreenshot(): Bitmap {
    val bitmap = Bitmap.createBitmap(540, 5000, Bitmap.Config.RGB_565)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    canvas.drawColor(0xFFF4F1EC.toInt())
    for (row in 0 until 50) {
        val top = row * 100f
        paint.color = if (row % 2 == 0) 0xFFE3DDD3.toInt() else 0xFFD5CEC2.toInt()
        canvas.drawRect(24f, top + 12f, 516f, top + 88f, paint)
        paint.color = 0xFF6750A4.toInt()
        canvas.drawCircle(64f, top + 50f, 22f, paint)
    }
    return bitmap
}
@Preview(name = "Long screenshot decoded fit width", widthDp = 393, heightDp = 852)
@Composable internal fun MediaLongScreenshotPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) {
    val bitmap = remember { syntheticLongScreenshot() }
    SessionTypography { CompositionLocalProvider(LocalMediaFixtureFrame provides MediaFrame(requireNotNull(bitmap))) {
        MediaViewerHost("long-fixture", previewActions, MediaContext("fixture", "/workspace")) {
            MediaViewerScreen(screenshotResource.copy(name = "1080×10000 长截图.png"), previewZoom = 4.8f) {}
        }
    } }
}
