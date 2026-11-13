package io.github.modao7067.luma.gui.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import io.github.modao7067.luma.gui.LumaTheme
import io.github.modao7067.luma.gui.session.CockpitState
import io.github.modao7067.luma.gui.session.RequestChoice

internal data class ComposerInteractionFrame(val label: String, val expanded: Boolean, val menu: ComposerMenuAnchor?, val active: Boolean = false)
internal class ComposerInteractionProvider : PreviewParameterProvider<ComposerInteractionFrame> {
    override val values = sequenceOf(
        ComposerInteractionFrame("收起 · 文字与内边距同属编辑区", false, null),
        ComposerInteractionFrame("收起 · 打开＋仍不展开", false, ComposerMenuAnchor.Plus),
        ComposerInteractionFrame("收起 · 停止按钮仍独立", false, null, true),
        ComposerInteractionFrame("展开 · ＋菜单", true, ComposerMenuAnchor.Plus),
        ComposerInteractionFrame("展开 · 权限菜单", true, ComposerMenuAnchor.Permission),
        ComposerInteractionFrame("展开 · 模型菜单", true, ComposerMenuAnchor.Model),
    )
}

@Preview(name = "Composer input and menu hit regions", widthDp = 393, heightDp = 650)
@Composable
internal fun ComposerInteractionPreview(@PreviewParameter(ComposerInteractionProvider::class) frame: ComposerInteractionFrame) =
    LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) { SessionTypography { ConversationTypography {
        val view = sampleView().copy(activeTurnId = if (frame.active) "active" else null, draft = "", queue = emptyList(),
            permissions = listOf(RequestChoice(":read-only", "只读"), RequestChoice(":workspace", "工作区"), RequestChoice(":danger-full-access", "完整访问")))
        LiquidScaffold(
            topBar = {}, wrapTop = false, wrapBottom = false,
            bottomBar = {
                ComposerGlass(Modifier.fillMaxWidth()) {
                    CompactComposer(CockpitState(selected = view), view, previewActions, {}, {},
                        previewExpanded = frame.expanded, previewMenuAnchor = frame.menu,
                        previewMotionFraction = if (frame.expanded) 1f else 0f)
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding).padding(18.dp)) { Text(frame.label, style = MaterialTheme.typography.bodyLarge) }
        }
    } } }

@Preview(name = "Composer menu in a short usable window", widthDp = 393, heightDp = 380)
@Composable
internal fun ComposerMenuShortWindowPreview() = ComposerInteractionPreview(
    ComposerInteractionFrame("短窗口 · 菜单不遮挡锚点", true, ComposerMenuAnchor.Plus),
)

@Preview(name = "DSH actual native permission presets", widthDp = 393, heightDp = 760)
@Composable internal fun DshPermissionsPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) {
    SessionTypography { ConversationTypography {
        val view = sampleView().let { it.copy(summary = it.summary.copy(cli = "DeepSeek Harness", model = "deepseek-v4-flash", modelLabel = "deepseek-v4-flash"),
            activeTurnId = null, draft = "", queue = emptyList(), permission = "workspace-write",
            permissions = listOf(RequestChoice("read-only", "read-only", sandbox = "read-only", approval = "ask"),
                RequestChoice("workspace-write", "workspace-write", sandbox = "workspace-write", approval = "ask"),
                RequestChoice("danger-full-access", "danger-full-access", sandbox = "danger-full-access", approval = "never"))) }
        LiquidScaffold(topBar = {}, wrapTop = false, wrapBottom = false, bottomBar = {
            ComposerGlass(Modifier.fillMaxWidth()) {
                CompactComposer(CockpitState(selected = view), view, previewActions, {}, {}, previewExpanded = true,
                    previewMenuAnchor = ComposerMenuAnchor.Permission, previewMotionFraction = 1f)
            }
        }) { }
    } }
}

@Preview(name = "Claude native permission modes under Root", widthDp = 393, heightDp = 760)
@Composable internal fun ClaudePermissionsPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) {
    SessionTypography { ConversationTypography {
        val view = sampleView().let { it.copy(summary = it.summary.copy(cli = "Claude Code"),
            activeTurnId = null, draft = "", queue = emptyList(), permission = "default",
            permissions = listOf(RequestChoice("default", "逐次审批"), RequestChoice("acceptEdits", "自动接受编辑"),
                RequestChoice("auto", "自动审批"), RequestChoice("plan", "规划模式"), RequestChoice("dontAsk", "不询问（自动拒绝）"))) }
        LiquidScaffold(topBar = {}, wrapTop = false, wrapBottom = false, bottomBar = {
            ComposerGlass(Modifier.fillMaxWidth()) {
                CompactComposer(CockpitState(selected = view), view, previewActions, {}, {}, previewExpanded = true,
                    previewMenuAnchor = ComposerMenuAnchor.Permission, previewMotionFraction = 1f)
            }
        }) { }
    } }
}
