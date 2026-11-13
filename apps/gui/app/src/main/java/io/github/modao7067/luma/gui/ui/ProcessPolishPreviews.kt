package io.github.modao7067.luma.gui.ui

import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import io.github.modao7067.luma.gui.LumaTheme
import io.github.modao7067.luma.gui.session.CockpitState
import io.github.modao7067.luma.gui.session.ConversationItem

internal data class ComposerReadabilityFrame(val dark: Boolean, val draft: String)
internal class ComposerReadabilityProvider : PreviewParameterProvider<ComposerReadabilityFrame> {
    override val values = sequence {
        for (dark in listOf(false, true)) for (draft in listOf("", "检查输出，然后继续。")) yield(ComposerReadabilityFrame(dark, draft))
    }
}

@Preview(name = "Editor glass over dense chat", widthDp = 393, heightDp = 540)
@Composable
internal fun ComposerReadabilityPreview(@PreviewParameter(ComposerReadabilityProvider::class) frame: ComposerReadabilityFrame) =
    LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = frame.dark) { SessionTypography { ConversationTypography {
        val view = sampleView().copy(activeTurnId = null, queue = emptyList(), draft = frame.draft)
        LiquidScaffold(
            wrapTop = false, wrapBottom = false,
            topBar = { ConversationHeader(view, previewActions, {}, {}) },
            bottomBar = { ComposerGlass(Modifier.fillMaxWidth()) { CompactComposer(CockpitState(selected = view), view, previewActions, {}, {}) } },
            overlay = { padding -> ConversationScrollOverlay(true, padding) {} },
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                repeat(32) { index ->
                    Text("BACKGROUND $index · 底部正文不可穿透输入", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    } } }

private val completedProcess = listOf(
    ConversationItem("think", "tool", detail = "先检查路径映射和权限。", kind = "reasoning", outcome = "succeeded"),
    ConversationItem("shell", "tool", text = "./gradlew :apps:gui:testDebugUnitTest", kind = "commandExecution", input = "./gradlew :apps:gui:testDebugUnitTest", detail = "BUILD SUCCESSFUL\n6 actionable tasks: 2 executed", outcome = "succeeded"),
    ConversationItem("edit", "tool", text = "NativeMarkdown.kt", kind = "fileChange", outcome = "succeeded"),
)
private val activeProcess = listOf(
    ConversationItem("live", "tool", text = "检查预览", kind = "commandExecution", detail = "正在渲染当前组件…", outcome = "running"),
    ConversationItem("failure", "tool", text = "读取受限文件", kind = "commandExecution", detail = "Permission denied", outcome = "failed"),
)

internal data class ProcessMotionFrame(val label: String, val expanded: Boolean, val fraction: Float)
internal class ProcessMotionProvider : PreviewParameterProvider<ProcessMotionFrame> {
    override val values = sequence {
        yield(ProcessMotionFrame("open 120 ms", true, spatialSample(120)))
        for (ms in listOf(0L, 120L, 320L, 800L)) yield(ProcessMotionFrame("close $ms ms", false, spatialSample(ms, 1f, 0f)))
        val closing = TargetBasedAnimation(spatialSpring(), Float.VectorConverter, 1f, 0f)
        val value = closing.getValueFromNanos(120_000_000L)
        val velocity = closing.getVelocityVectorFromNanos(120_000_000L).value
        for (ms in listOf(0L, 120L, 800L)) yield(ProcessMotionFrame("reverse $ms ms", true, spatialSample(ms, value, 1f, velocity)))
    }
}

@Composable
private fun ProcessMotionFixture(frame: ProcessMotionFrame, partial: Boolean) =
    LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) { SessionTypography { ConversationTypography {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(frame.label, style = MaterialTheme.typography.labelMedium)
                ProcessGroup(
                    completedProcess + if (partial) activeProcess else emptyList(),
                    initiallyExpanded = frame.expanded,
                    initiallyOpenItem = "shell",
                    previewGroupFraction = frame.fraction,
                )
                Text("下一条回复固定接续当前过程高度。", style = MaterialTheme.typography.bodyLarge)
            }
        }
    } } }

@Preview(name = "Group close and reverse", widthDp = 393, heightDp = 640)
@Composable
internal fun ProcessGroupMotionPreview(@PreviewParameter(ProcessMotionProvider::class) frame: ProcessMotionFrame) = ProcessMotionFixture(frame, false)

@Preview(name = "Partial group close preserves live and failed entries", widthDp = 393, heightDp = 800)
@Composable
internal fun ProcessPartialMotionPreview(@PreviewParameter(ProcessMotionProvider::class) frame: ProcessMotionFrame) = ProcessMotionFixture(frame, true)

@Preview(name = "Single entries use the same summary", widthDp = 393, heightDp = 750)
@Composable
internal fun ProcessSinglePreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) { SessionTypography { ConversationTypography {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            ProcessGroup(listOf(completedProcess[0]))
            ProcessGroup(listOf(completedProcess[1]))
            ProcessGroup(listOf(completedProcess[0].copy(id = "thinking", outcome = "running")))
            ProcessGroup(listOf(activeProcess[1]))
            ProcessGroup(listOf(completedProcess[1].copy(id = "expanded-shell")), initiallyExpanded = true, initiallyOpenItem = "expanded-shell")
        }
    }
} } }

@Preview(name = "Real operation categories", widthDp = 393, heightDp = 680)
@Composable
internal fun ProcessCategoriesPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = true) { SessionTypography { ConversationTypography {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            val pi = listOf("bash", "bash", "edit", "read", "read", "read").mapIndexed { index, name ->
                ConversationItem("pi-$index", "tool", text = name, kind = "tool", toolName = name, outcome = "succeeded")
            } + completedProcess[0]
            ProcessGroup(pi, cli = "pi")
            val acp = listOf("read", "fetch", "search", "other").mapIndexed { index, kind ->
                ConversationItem("acp-$index", "tool", text = "原生操作 · $kind", kind = "tool", toolKind = kind, outcome = "succeeded")
            }
            ProcessGroup(acp, cli = "DeepSeek Harness", initiallyExpanded = true)
            ProcessGroup(listOf(ConversationItem("image", "tool", text = "image.png", kind = "imageView", outcome = "succeeded")))
        }
    }
} } }


internal data class ProcessStatusFrame(val phase: Float, val outcome: String = "running")
internal class ProcessStatusFrames : PreviewParameterProvider<ProcessStatusFrame> {
    override val values = sequenceOf(0f, .15f, .3f, .45f, .65f, 1f).map { ProcessStatusFrame(it) } +
        sequenceOf(ProcessStatusFrame(0f, "succeeded"), ProcessStatusFrame(0f, "failed"))
}
@Preview(name = "Fixed process status", widthDp = 393, heightDp = 320)
@Composable internal fun ProcessStatusPreview(@PreviewParameter(ProcessStatusFrames::class) frame: ProcessStatusFrame) =
    LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = true) { SessionTypography { ConversationTypography {
        androidx.compose.runtime.CompositionLocalProvider(LocalProcessDotsPhase provides frame.phase) {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val entries = listOf(completedProcess[0], activeProcess[0].copy(outcome = frame.outcome), activeProcess[1].copy(outcome = frame.outcome))
                    ProcessGroup(entries, initiallyExpanded = true)
                }
            }
        }
    } } }
