package io.github.modao7067.luma.gui.ui

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import io.github.modao7067.luma.gui.LumaTheme
import io.github.modao7067.luma.gui.session.*

internal val previewActions = object : CockpitActions {
    override fun createSession(title: String, environment: String, cwd: String, model: String) {}
    override fun attachSession(title: String, environment: String, cwd: String, threadId: String) {}
    override fun openSession(id: String) {}
    override fun showSessions() {}
    override fun renameSession(id: String, title: String) {}
    override fun draft(text: String) {}
    override fun addImage(uri: String) {}
    override fun removeImage(id: String) {}
    override fun send() {}
    override fun steer() {}
    override fun stop() {}
    override fun reconnect() {}
    override fun discardUncertainSubmission() {}
    override fun configure(model: String, effort: String) {}
    override fun removeQueued(id: String) {}
    override fun answer(requestId: String, choice: String, answers: Map<String, List<String>>) {}
    override fun openTui() {}
    override fun openShell(environment: String, cwd: String) {}
    override fun browseFiles(path: String) {}
    override fun closeFiles() {}
    override fun referenceFile(path: String) {}
    override fun previewFile(path: String) {}
    override fun previewDiff() {}
    override fun closePreview() {}
    override fun dismissError() {}
}
private val sampleSession = SessionSummary("preview", "修复终端里的链接预览", "noroot", "/workspace/luma", "gpt-6-astra", "执行中", "preview-thread", modelLabel = "GPT-6-Astra")
private val sampleMessages = listOf(
    ConversationItem("u1", "user", "检查链接预览，先解释原因，再给出最小修复。"),
    ConversationItem("t1", "tool", "读取 TerminalLinkOpener.java", "sed -n '1,180p' TerminalLinkOpener.java", "已完成", kind = "commandExecution", input = "sed -n '1,180p' TerminalLinkOpener.java", outcome = "succeeded"),
    ConversationItem("a1", "assistant", "### 问题出在路径映射\n\nGUI 与终端应共享**同一个工作目录**，不要把容器路径当成 App 路径。\n\n- 保留原会话\n- 只修改预览入口\n\n```kotlin\nval path = runtime.resolve(reference)\nopenPreview(path)\n```"),
)
internal fun sampleView() = SessionView(sampleSession, effort = "xhigh", connection = "已连接", connected = true, activeTurnId = "preview-turn", messages = sampleMessages, draft = "完成后检查中文文件名", queue = listOf(QueueEntry("q1", "随后检查图片预览", "尚未执行")), canOpenTui = true)

@Preview(name = "Sessions", widthDp = 393, heightDp = 852)
@Composable
internal fun SessionsPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = true) {
    CockpitScreen(CockpitState(sessions = listOf(sampleSession, SessionSummary("second", "整理脚本", "noroot", "/workspace", status = "空闲"))), previewActions)
}

@Preview(name = "Sessions narrow large type", widthDp = 320, heightDp = 700, fontScale = 1.5f)
@Composable
internal fun SessionsNarrowLargePreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = true) {
    CockpitScreen(CockpitState(sessions = listOf(sampleSession, SessionSummary("waiting", "确认文件访问", "noroot", "/workspace", status = "等待处理"))), previewActions)
}

@Preview(name = "Sessions wide single column", widthDp = 840, heightDp = 700)
@Composable
internal fun SessionsWidePreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = true) {
    CockpitScreen(CockpitState(sessions = listOf(sampleSession, SessionSummary("second", "整理脚本", "noroot", "/workspace", status = "空闲"))), previewActions)
}

@Preview(name = "Conversation", widthDp = 393, heightDp = 852)
@Composable
internal fun ConversationPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = true) { CockpitScreen(CockpitState(selected = sampleView()), previewActions) }

@Preview(name = "Conversation Large", widthDp = 393, heightDp = 852, fontScale = 1.6f)
@Composable
internal fun ConversationLargePreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = true) { CockpitScreen(CockpitState(selected = sampleView().copy(activeTurnId = null, queue = emptyList(), draft = "")), previewActions) }

@Preview(name = "Approval", widthDp = 393, heightDp = 852)
@Composable
internal fun ApprovalPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = true) {
    CockpitScreen(CockpitState(selected = sampleView().copy(requests = listOf(UserRequest("approval", "命令需要批准", "在 /workspace/luma 执行构建", listOf(RequestChoice("accept", "批准本次"), RequestChoice("decline", "拒绝")))))), previewActions)
}

@Preview(name = "Markdown", widthDp = 393, heightDp = 852)
@Composable
internal fun MarkdownPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = true) {
    androidx.compose.material3.Surface {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            NativeMarkdown("## 计算与检查\n\n行内公式 $" + "x^2 + y^2 = z^2$。\n\n| 项目 | 结果 |\n| --- | --- |\n| Root | 通过 |\n| NoRoot | 沿用当前实现 |\n\n$$\n\\frac{1}{n}\\sum_{i=1}^{n}x_i\n$$\n\n```python\ndef mean(values):\n    return sum(values) / len(values)\n```")
        }
    }
}

@Preview(name = "Approval Dialog", widthDp = 393, heightDp = 852)
@Composable
internal fun ApprovalDialogPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = true) { SessionTypography {
    RequestDialog(UserRequest("approval", "命令需要批准", "在 `/workspace/luma` 执行：\n\n```sh\n./gradlew :apps:gui:assembleDebug --offline\n```", listOf(RequestChoice("accept", "批准本次"), RequestChoice("decline", "拒绝"))), previewActions) {}
} }

@Preview(name = "Model preferences", widthDp = 393, heightDp = 852)
@Composable
internal fun ModelPreferencesPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = true) { SessionTypography {
    CockpitModelMenuPreview()
} }

@Preview(name = "Model preferences busy", widthDp = 393, heightDp = 852)
@Composable
internal fun ModelPreferencesBusyPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = true) { SessionTypography {
    CockpitModelMenuPreview()
} }

private val processFixture = listOf(
    ConversationItem("reason", "tool", "思考", "先确认路径映射，再检查文件 Provider 的访问权限。", kind = "reasoning", outcome = "succeeded"),
    ConversationItem("read", "tool", "读取 TerminalLinkOpener.java", "文件路径通过 runtime 映射。", kind = "commandExecution", input = "sed -n '1,180p' TerminalLinkOpener.java", outcome = "succeeded"),
    ConversationItem("patch", "tool", "TerminalLinkOpener.java", "@@ -42,1 +42,1 @@\n-openPreview(rawPath)\n+openPreview(runtime.resolve(rawPath))", kind = "fileChange", input = "TerminalLinkOpener.java", outcome = "succeeded"),
)
@Composable
private fun ProcessFixture(items: List<ConversationItem>, expanded: Boolean, openItem: String? = null, detailFraction: Float? = null, closing: Boolean = false) = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = true) { SessionTypography { ConversationTypography {
    LiquidScaffold(topBar = {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            androidx.compose.material3.Text("检查路径映射", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
            androidx.compose.material3.Text("Codex · Ubuntu / Root", style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = androidx.compose.material3.MaterialTheme.colorScheme.primary)
        }
    }, bottomBar = {
        val view = sampleView().copy(activeTurnId = null, draft = "", queue = emptyList())
        CompactComposer(CockpitState(selected = view), view, previewActions, onPickImages = {}, onModels = {})
    }) { padding ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            ProcessGroup(items, initiallyExpanded = expanded, initiallyOpenItem = openItem, previewDetailFraction = detailFraction, previewDetailClosing = closing)
            NativeMarkdown("### 下一步\n保留原会话，确认预览入口收到的是**环境内文件路径**。")
        }
    }
} } }
@Preview(name = "Process Collapsed", widthDp = 393, heightDp = 852)
@Composable
internal fun ProcessCollapsedPreview() = ProcessFixture(processFixture, false)
@Preview(name = "Process Expanded", widthDp = 393, heightDp = 852)
@Composable
internal fun ProcessExpandedPreview() = ProcessFixture(processFixture, true, "patch")
@Preview(name = "Process Running Failed", widthDp = 393, heightDp = 852)
@Composable
internal fun ProcessRunningFailedPreview() = ProcessFixture(processFixture + listOf(
    ConversationItem("build", "tool", "编译 GUI", "> Task :apps:gui:compileDebugKotlin\n正在编译 Kotlin…", kind = "commandExecution", input = "./gradlew :apps:gui:compileDebugKotlin --offline", outcome = "running"),
    ConversationItem("check", "tool", "读取预览文件", "Permission denied: /workspace/private/preview.txt", kind = "commandExecution", input = "cat /workspace/private/preview.txt", outcome = "failed"),
), false, "check")

@Preview(name = "Conversation API 31 Frosted", widthDp = 393, heightDp = 852, apiLevel = 31)
@Composable
internal fun ConversationApi31Preview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = true) { CockpitScreen(CockpitState(selected = sampleView()), previewActions) }

@Preview(name = "Liquid Optics", widthDp = 393, heightDp = 480, apiLevel = 35)
@Composable
internal fun LiquidOpticsPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = true) { SessionTypography {
    LiquidScaffold(topBar = { androidx.compose.material3.Text("玻璃取样检查", Modifier.padding(24.dp), style = androidx.compose.material3.MaterialTheme.typography.titleLarge) }, bottomBar = { androidx.compose.material3.Text("正文和控件不参与滤镜", Modifier.padding(24.dp)) }) { _ ->
        Column(Modifier.fillMaxSize()) {
            repeat(20) { index ->
                androidx.compose.material3.Surface(color = if (index % 2 == 0) androidx.compose.ui.graphics.Color(0xFF358A82) else androidx.compose.ui.graphics.Color(0xFF414E77), modifier = Modifier.fillMaxWidth().height(24.dp)) {
                    androidx.compose.material3.Text("BACKGROUND  $index   •   0123456789", color = androidx.compose.ui.graphics.Color.White)
                }
            }
        }
    }
} }

@Composable
private fun CompactComposerFixture(expanded: Boolean = false, menu: Boolean = false, motionFraction: Float? = null, menuFraction: Float? = null, darkTheme: Boolean = true) = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = darkTheme) { SessionTypography {
    val view = sampleView().copy(activeTurnId = null, draft = if (expanded || motionFraction != null) "先检查路径映射，保留现有会话。" else "", queue = emptyList())
    LiquidScaffold(topBar = {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            androidx.compose.material3.Text("路径映射检查", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            androidx.compose.material3.Text(if (expanded || menu || motionFraction != null) "展开态 fixture · 非真机 IME" else "Codex · 已连接", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
        }
    }, bottomBar = {
        CompactComposer(CockpitState(selected = view), view, previewActions, onPickImages = {}, onModels = {}, previewExpanded = expanded, previewMenu = menu, previewMotionFraction = motionFraction, onTakePhoto = {})
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NativeMarkdown("### 保留同一会话\n先检查环境内路径，再读取文件。")
        }
    }
} }
@Preview(name = "Composer Compact", widthDp = 393, heightDp = 520)
@Composable
internal fun ComposerCompactPreview() = CompactComposerFixture()
@Preview(name = "Composer Expanded Fixture (not device IME)", widthDp = 393, heightDp = 520)
@Composable
internal fun ComposerExpandedPreview() = CompactComposerFixture(expanded = true)
@Preview(name = "Composer Plus Menu Fixture", widthDp = 393, heightDp = 520)
@Composable
internal fun ComposerPlusMenuPreview() = CompactComposerFixture(menu = true)

@Preview(name = "Composer Spring 60ms", widthDp = 393, heightDp = 520)
@Composable
internal fun ComposerMotion060Preview() = CompactComposerFixture(expanded = true, motionFraction = spatialSample(60))
@Preview(name = "Composer Spring 120ms", widthDp = 393, heightDp = 520)
@Composable
internal fun ComposerMotion120Preview() = CompactComposerFixture(expanded = true, motionFraction = spatialSample(120))
@Preview(name = "Composer Spring 240ms", widthDp = 393, heightDp = 520)
@Composable
internal fun ComposerMotion240Preview() = CompactComposerFixture(expanded = true, motionFraction = spatialSample(240))
@Preview(name = "Composer Spring collapse 120ms", widthDp = 393, heightDp = 520)
@Composable
internal fun ComposerMotionClosePreview() = CompactComposerFixture(expanded = true, motionFraction = spatialSample(120, from = 1f, to = 0f))
@Preview(name = "Plus anchor spring 120ms", widthDp = 393, heightDp = 520)
@Composable
internal fun ComposerMenuMotionPreview() = CompactComposerFixture(menu = true, menuFraction = spatialSample(120))

internal data class SpatialFrame(val opening: Boolean, val milliseconds: Long, val fraction: Float)
internal class SpatialFrameProvider : PreviewParameterProvider<SpatialFrame> {
    override val values = sequence {
        for (ms in 0L..480L step 60L) yield(SpatialFrame(true, ms, spatialSample(ms)))
        for (ms in 0L..480L step 60L) yield(SpatialFrame(false, ms, spatialSample(ms, 1f, 0f)))
    }
}
internal class SpatialReversalProvider : PreviewParameterProvider<SpatialFrame> {
    override val values = sequence {
        for (ms in 0L..120L step 30L) yield(SpatialFrame(true, ms, spatialSample(ms)))
        val opening = TargetBasedAnimation(spatialSpring(), Float.VectorConverter, 0f, 1f)
        val value = opening.getValueFromNanos(120_000_000L)
        val velocity = opening.getVelocityVectorFromNanos(120_000_000L).value
        for (ms in 0L..480L step 60L) yield(SpatialFrame(false, ms, spatialSample(ms, value, 0f, velocity)))
    }
}
@Preview(name = "Composer enter-exit samples", widthDp = 393, heightDp = 520)
@Composable
internal fun ComposerMotionSequencePreview(@PreviewParameter(SpatialFrameProvider::class) frame: SpatialFrame) =
    CompactComposerFixture(expanded = frame.opening, motionFraction = frame.fraction)
@Preview(name = "Plus anchor enter-exit samples", widthDp = 393, heightDp = 520)
@Composable
internal fun ComposerMenuSequencePreview(@PreviewParameter(SpatialFrameProvider::class) frame: SpatialFrame) =
    CompactComposerFixture(expanded = true, menu = frame.opening, menuFraction = frame.fraction)
@Preview(name = "Tool title enter-exit samples", widthDp = 393, heightDp = 600)
@Composable
internal fun ProcessDetailSequencePreview(@PreviewParameter(SpatialFrameProvider::class) frame: SpatialFrame) =
    ProcessFixture(listOf(processFixture[1]), true, "read", detailFraction = frame.fraction, closing = !frame.opening)
@Preview(name = "Live tool excerpt-detail enter-exit samples", widthDp = 393, heightDp = 600)
@Composable
internal fun ProcessLiveDetailSequencePreview(@PreviewParameter(SpatialFrameProvider::class) frame: SpatialFrame) =
    ProcessFixture(listOf(processFixture[1].copy(outcome = "running", detail = "路径映射已读取。\n正在检查文件 Provider。\n等待权限检查结果…")), true, "read", detailFraction = frame.fraction, closing = !frame.opening)
@Preview(name = "Composer reversal at 120ms", widthDp = 393, heightDp = 520)
@Composable
internal fun ComposerReversalSequencePreview(@PreviewParameter(SpatialReversalProvider::class) frame: SpatialFrame) =
    CompactComposerFixture(expanded = frame.opening, motionFraction = frame.fraction)
@Preview(name = "Plus anchor reversal at 120ms", widthDp = 393, heightDp = 520)
@Composable
internal fun ComposerMenuReversalSequencePreview(@PreviewParameter(SpatialReversalProvider::class) frame: SpatialFrame) =
    CompactComposerFixture(expanded = true, menu = frame.opening, menuFraction = frame.fraction)

internal data class ThemeFixture(val seed: Int, val dark: Boolean)
internal class ThemeFixtureProvider : PreviewParameterProvider<ThemeFixture> {
    override val values = sequenceOf(
        ThemeFixture(0xFF6750A4.toInt(), false),
        ThemeFixture(0xFF6750A4.toInt(), true),
        ThemeFixture(0xFF006C70.toInt(), false),
        ThemeFixture(0xFF006C70.toInt(), true),
    )
}

@Preview(name = "SPEC 2025 · conversation · seed × day/night", widthDp = 393, heightDp = 852)
@Composable
internal fun Spec2025ConversationPreview(@PreviewParameter(ThemeFixtureProvider::class) theme: ThemeFixture) =
    LumaTheme(seedArgb = theme.seed, darkTheme = theme.dark) {
        CockpitScreen(CockpitState(selected = sampleView()), previewActions)
    }

@Preview(name = "SPEC 2025 · light attachments", widthDp = 393, heightDp = 520)
@Composable
internal fun Spec2025MenuLightPreview() = CompactComposerFixture(menu = true, darkTheme = false)

@Preview(name = "SPEC 2025 · light session list", widthDp = 320, heightDp = 700, fontScale = 1.5f)
@Composable
internal fun Spec2025SessionsLightPreview() = LumaTheme(seedArgb = 0xFF006C70.toInt(), darkTheme = false) {
    CockpitScreen(CockpitState(sessions = listOf(sampleSession, SessionSummary("waiting", "确认文件访问", "noroot", "/workspace", status = "等待处理"))), previewActions)
}

@Preview(name = "SPEC 2025 · light model preferences", widthDp = 393, heightDp = 852)
@Composable
internal fun Spec2025PreferencesLightPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) { SessionTypography {
    CockpitModelMenuPreview()
} }

@Preview(name = "SPEC 2025 · native content · seed × day/night", widthDp = 393, heightDp = 760)
@Composable
internal fun Spec2025ContentPreview(@PreviewParameter(ThemeFixtureProvider::class) theme: ThemeFixture) =
    LumaTheme(seedArgb = theme.seed, darkTheme = theme.dark) { SessionTypography {
        androidx.compose.material3.Surface {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                NativeMarkdown("## 原生内容\n\n检查 `runtime.resolve` 与**当前会话**。\n\n```kotlin\n// 保留原路径语义\nval path = runtime.resolve(\"中文文件.md\")\nif (path != null) openPreview(path)\n```\n\n```diff\n@@ 路径映射 @@\n-openPreview(rawPath)\n+openPreview(runtime.resolve(rawPath))\n```\n\n| 检查 | 状态 |\n| --- | --- |\n| 文件路径 | 已映射 |")
                ErrorNotice("无法读取文件：请检查当前环境中的访问权限。")
            }
        }
    } }

internal class NoticeThemeFixtureProvider : PreviewParameterProvider<ThemeFixture> {
    override val values = sequenceOf(ThemeFixture(0xFF6750A4.toInt(), false), ThemeFixture(0xFF6750A4.toInt(), true))
}
@Preview(name = "SPEC 2025 · actionable notices · day/night", widthDp = 393, heightDp = 852)
@Composable
internal fun Spec2025NoticesPreview(@PreviewParameter(NoticeThemeFixtureProvider::class) theme: ThemeFixture) =
    LumaTheme(seedArgb = theme.seed, darkTheme = theme.dark) {
        CockpitScreen(CockpitState(selected = sampleView().copy(
            error = "无法读取文件，请检查当前环境的访问权限。",
            uncertainSubmission = true,
            requests = listOf(UserRequest("approval", "命令需要批准", "在 /workspace/luma 执行构建", listOf(RequestChoice("accept", "批准本次"), RequestChoice("decline", "拒绝")))),
        )), previewActions)
    }

@Preview(name = "Cockpit · native dashboard", widthDp = 393, heightDp = 852)
@Composable
internal fun CockpitDashboardPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) { SessionTypography {
    val view = sampleView().copy(effort = "high", metrics = listOf(Metric("最近请求 / 上下文窗口", "35000 / 200000 · 17.5%"), Metric("累计输入缓存命中", "21000 / 30000 · 70.0%")))
    LiquidScaffold(wrapTop = false, topBar = { ConversationHeader(view, previewActions, {}, {}, previewDashboard = true) }, bottomBar = {
        CompactComposer(CockpitState(selected = view), view, previewActions, {}, {})
    }) { padding -> Column(Modifier.padding(padding).padding(20.dp)) { NativeMarkdown(sampleMessages.last().text, {}) } }
} }

@Preview(name = "Cockpit · collapsed model submenus", widthDp = 393, heightDp = 852)
@Composable
internal fun CockpitModelMenuPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = true) { SessionTypography {
    val view = sampleView().copy(activeTurnId = null, queue = emptyList(), effort = "high")
    LiquidScaffold(topBar = {}, bottomBar = {
        Column { CompactComposer(CockpitState(selected = view), view, previewActions, {}, {}, previewExpanded = true)
            ModelMenu(true, {}, CockpitState(models = listOf(ModelOption("gpt-6-astra", "GPT-6-Astra", listOf("medium", "high"), speeds = listOf(RequestChoice("priority", "Fast"))))), view, previewActions)
        }
    }) { padding -> Column(Modifier.padding(padding).padding(20.dp)) { NativeMarkdown(sampleMessages.last().text, {}) } }
} }

@Preview(name = "Cockpit · seven line editor", widthDp = 393, heightDp = 852)
@Preview(name = "Cockpit · narrow editor", widthDp = 320, heightDp = 700, fontScale = 1.3f)
@Composable
internal fun CockpitSevenLinePreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) { SessionTypography { ConversationTypography {
    val view = sampleView().copy(draft = (1..10).joinToString("\n") { "第 $it 行：检查输入框滚动" })
    LiquidScaffold(wrapTop = false, topBar = { ConversationHeader(view, previewActions, {}, {}) }, bottomBar = {
        CompactComposer(CockpitState(selected = view), view, previewActions, {}, {}, previewExpanded = true)
    }) { padding -> Column(Modifier.padding(padding).padding(20.dp)) { NativeMarkdown(sampleMessages.last().text, {}) } }
} } }

@Preview(name = "Cockpit · CLI drawer", widthDp = 393, heightDp = 852)
@Composable
internal fun CockpitDrawerPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) {
    CockpitScreen(CockpitState(selected = sampleView(), sessions = listOf(sampleSession, SessionSummary("second", "整理脚本", "noroot", "/workspace"), SessionSummary("legacy-root", "旧 Root 记录不可作为 NoRoot 显示", "root", "/workspace"))), previewActions, previewDrawerOpen = true)
}

@Preview(name = "Resting empty", widthDp = 365, heightDp = 796)
@Composable
internal fun RestingEmptyPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) {
    val view = sampleView().copy(activeTurnId = null, queue = emptyList(), draft = "", permission = ":danger-full-access")
    CockpitScreen(CockpitState(selected = view), previewActions)
}

@Preview(name = "Resting draft", widthDp = 365, heightDp = 796)
@Composable
internal fun RestingDraftPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) {
    val view = sampleView().copy(activeTurnId = null, queue = emptyList(), draft = "输入框", permission = ":danger-full-access")
    CockpitScreen(CockpitState(selected = view), previewActions)
}

@Preview(name = "Opaque more menu", widthDp = 365, heightDp = 796)
@Composable
internal fun OpaqueMorePreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) { SessionTypography {
    LiquidScaffold(wrapTop = false, topBar = { ConversationHeader(sampleView(), previewActions, {}, {}, previewMore = true) }, bottomBar = {}) { padding ->
        Column(Modifier.padding(padding).padding(20.dp)) { NativeMarkdown(sampleMessages.last().text, {}) }
    }
} }

@Preview(name = "Native permission menu", widthDp = 365, heightDp = 796)
@Composable
internal fun NativePermissionPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) { SessionTypography {
    LiquidScaffold(topBar = {}, bottomBar = {
        Column { androidx.compose.material3.Text("权限")
            OriginMenu(true, {}, width = 300.dp) {
                listOf(":workspace", ":read-only", ":danger-full-access", "team-policy").forEach { id ->
                    val row = permissionPresentation(id, "团队自定义权限")
                    ActionMenuItem(row.title, row.icon, row.description, selected = id == ":danger-full-access", warning = row.warning, onClick = {})
                }
            }
        }
    }) { padding -> Column(Modifier.padding(padding).padding(20.dp)) { NativeMarkdown(sampleMessages.last().text, {}) } }
} }

@Preview(name = "Per-message plugins", widthDp = 393, heightDp = 620)
@Composable
internal fun ComposerPluginsPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) { SessionTypography {
    val skill = DraftSkill("luma-browser", "luma-browser", "/opt/luma/agent/skills/luma-browser/SKILL.md")
    val view = sampleView().copy(activeTurnId = null, queue = emptyList(), draft = "检查页面", skills = listOf(skill))
    val state = CockpitState(selected = view, plugins = PluginCatalog(listOf(skill, DraftSkill("review", "code-review", "/skills/review/SKILL.md")), listOf(ReadOnlyEntry("docs", "connected"))))
    LiquidScaffold(topBar = { ConversationHeader(view, previewActions, {}, {}) }, wrapTop = false,
        bottomBar = { CompactComposer(state, view, previewActions, {}, {}, previewExpanded = true, previewMenu = true, previewPlugins = true) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding))
    }
} }

@Preview(name = "SukiSU glass · centered return control", widthDp = 393, heightDp = 852)
@Composable
internal fun CenteredGlassConversationPreview(@PreviewParameter(NoticeThemeFixtureProvider::class) theme: ThemeFixture) =
    LumaTheme(seedArgb = theme.seed, darkTheme = theme.dark) { SessionTypography { ConversationTypography {
        val view = sampleView().copy(activeTurnId = null, queue = emptyList(), draft = "")
        LiquidScaffold(
            wrapTop = false,
            topBar = { ConversationHeader(view, previewActions, {}, {}) },
            bottomBar = { CompactComposer(CockpitState(selected = view), view, previewActions, {}, {}) },
            overlay = { padding -> ConversationScrollOverlay(true, padding) {} },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp).padding(top = padding.calculateTopPadding()), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                repeat(8) { NativeMarkdown("### 阅读较早的消息\n保留列表的位置，正文从玻璃后方经过。只在悬浮层取样，正文和按钮图标保持清晰。") }
            }
        }
    } } }

@Preview(name = "Reasoning completed and running captions", widthDp = 393, heightDp = 852)
@Composable
internal fun ReasoningStateLabelsPreview() = ProcessFixture(
    processFixture.take(2) + ConversationItem("thinking-live", "tool", "思考", "继续核对文件路径和访问权限。", kind = "reasoning", outcome = "running"),
    expanded = true,
)
