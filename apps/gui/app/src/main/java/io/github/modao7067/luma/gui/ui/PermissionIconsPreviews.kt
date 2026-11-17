package io.github.modao7067.luma.gui.ui

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
import io.github.modao7067.luma.gui.session.RequestChoice

internal data class PermissionIconPreviewCase(
    val cli: String,
    val selected: String,
    val options: List<RequestChoice>,
    val planModeSupported: Boolean = false,
    val planMode: Boolean = false,
    val skipPermissionsActive: Boolean = false,
)
internal val permissionIconPreviewCases = listOf(
    PermissionIconPreviewCase("Codex", ":workspace", listOf(RequestChoice(":read-only", "Read-only"), RequestChoice(":workspace", "Workspace"), RequestChoice(":danger-full-access", "Full access")), planModeSupported = true),
    PermissionIconPreviewCase("OpenCode", "build", listOf(RequestChoice("build", "Build"), RequestChoice("plan", "Plan"))),
    PermissionIconPreviewCase("pi", "", emptyList()),
    PermissionIconPreviewCase("DeepSeek Harness", "workspace-write", listOf(
        RequestChoice("read-only", "Read-only", sandbox = "read-only", approval = "ask"),
        RequestChoice("workspace-write", "Workspace write", sandbox = "workspace-write", approval = "ask"),
        RequestChoice("auto", "Auto", sandbox = "danger-full-access", approval = "ask"),
        RequestChoice("danger-full-access", "Full access", sandbox = "danger-full-access", approval = "never")), planModeSupported = true),
    PermissionIconPreviewCase("Kimi Code", "auto", listOf(RequestChoice("default", "Default"), RequestChoice("plan", "Plan"), RequestChoice("auto", "Auto"), RequestChoice("yolo", "YOLO"))),
    PermissionIconPreviewCase("Claude Code", "plan", listOf(RequestChoice("default", "Default"), RequestChoice("acceptEdits", "Accept edits"), RequestChoice("auto", "Auto"), RequestChoice("plan", "Plan"), RequestChoice("dontAsk", "Don't ask"), RequestChoice("bypassPermissions", "Bypass permissions"))),
    PermissionIconPreviewCase("Antigravity", "always-proceed", listOf(
        RequestChoice("default", "Default"),
        RequestChoice("accept-edits", "Accept edits"),
        RequestChoice("always-proceed", "Always proceed")), planModeSupported = true, skipPermissionsActive = true),
    PermissionIconPreviewCase("Qoder CLI", "dontAsk", listOf(RequestChoice("default", "Default"), RequestChoice("acceptEdits", "Accept edits"), RequestChoice("auto", "Auto"), RequestChoice("dontAsk", "Don't ask"), RequestChoice("yolo", "YOLO"))),
    PermissionIconPreviewCase("Grok Build", "always-approve", listOf(RequestChoice("default", "Default"), RequestChoice("auto", "Auto"), RequestChoice("always-approve", "Always approve")), planModeSupported = true),
    PermissionIconPreviewCase("Future CLI", "opaque-policy", listOf(RequestChoice("opaque-policy", "Native policy"))),
)

internal class PermissionIconCaseProvider : PreviewParameterProvider<PermissionIconPreviewCase> {
    override val values = permissionIconPreviewCases.asSequence() + sequenceOf(
        permissionIconPreviewCases.first { it.cli == "Claude Code" }.copy(selected = "dontAsk"),
        permissionIconPreviewCases.first { it.cli == "Grok Build" }.copy(selected = "default", planMode = true),
    )
}

@Preview(name = "All CLI permission · actual menu and current button", widthDp = 393, heightDp = 760)
@Composable
internal fun AllCliPermissionComposerPreview(@PreviewParameter(PermissionIconCaseProvider::class) case: PermissionIconPreviewCase) =
    LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) { SessionTypography { ConversationTypography {
        val view = sampleView().let { it.copy(summary = it.summary.copy(cli = case.cli), activeTurnId = null,
            queue = emptyList(), draft = "", permission = case.selected, permissions = case.options,
            planModeSupported = case.planModeSupported, planMode = case.planMode, skipPermissionsActive = case.skipPermissionsActive) }
        LiquidScaffold(topBar = {}, wrapTop = false, wrapBottom = false, bottomBar = {
            ComposerGlass(Modifier.fillMaxWidth()) {
                CompactComposer(CockpitState(selected = view), view, previewActions, {}, {}, previewExpanded = true,
                    previewMenuAnchor = ComposerMenuAnchor.Permission, previewMotionFraction = 1f)
            }
        }) { padding -> Text(case.cli, Modifier.padding(padding).padding(18.dp)) }
    } } }

@Preview(name = "Permission semantic symbols · light and dark", widthDp = 320, heightDp = 660)
@Composable
internal fun PermissionSemanticSymbolsPreview(@PreviewParameter(IconThemeProvider::class) dark: Boolean) =
    LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = dark) { SessionTypography {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest) {
            Column(Modifier.padding(vertical = 8.dp)) {
                listOf("逐次审批" to MenuSymbol.HAND, "仅接受编辑" to MenuSymbol.EDIT, "自动审查" to MenuSymbol.SHIELD,
                    "规划" to MenuSymbol.PLAN, "拒绝未获批操作" to MenuSymbol.BLOCK, "原生规则下自动批准" to MenuSymbol.AUTO_ALLOW, "危险跳过审批" to MenuSymbol.WARNING,
                    "只读" to MenuSymbol.LOCK, "工作区边界" to MenuSymbol.FOLDER, "原生复合规则" to MenuSymbol.RULES,
                    "未知原生策略" to MenuSymbol.UNKNOWN).forEach { (label, icon) ->
                    ActionMenuItem(label, icon, warning = icon in setOf(MenuSymbol.WARNING, MenuSymbol.AUTO_ALLOW), iconContainer = true, onClick = {})
                }
            }
        }
    } }

@Preview(name = "Current permission buttons · plan, deny, manual and automatic", widthDp = 393, heightDp = 680)
@Composable
internal fun PermissionCurrentButtonsPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) {
    SessionTypography { ConversationTypography {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest) {
            Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                val claude = permissionIconPreviewCases.first { it.cli == "Claude Code" }
                val grok = permissionIconPreviewCases.first { it.cli == "Grok Build" }
                listOf(claude, claude.copy(selected = "dontAsk"), grok.copy(planMode = true), grok.copy(selected = "default", planMode = true)).forEach { case ->
                    val view = sampleView().let { it.copy(summary = it.summary.copy(cli = case.cli), activeTurnId = null,
                        queue = emptyList(), draft = "", permission = case.selected, permissions = case.options,
                        planModeSupported = case.planModeSupported, planMode = case.planMode, skipPermissionsActive = case.skipPermissionsActive) }
                    Text("${case.cli} · ${permissionPresentation(case.selected, cli = case.cli, option = case.options.find { it.id == case.selected }).title}",
                        Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelLarge)
                    CompactComposer(CockpitState(selected = view), view, previewActions, {}, {},
                        previewExpanded = true, previewMotionFraction = 1f)
                }
            }
        }
    } }
}
