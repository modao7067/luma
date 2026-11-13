package io.github.modao7067.luma.gui.ui

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.github.modao7067.luma.gui.LumaTheme
import io.github.modao7067.luma.gui.agent.NativeCliProjection
import io.github.modao7067.luma.gui.session.*
import org.json.JSONObject

@Preview(name = "Empty native metrics", widthDp = 393, heightDp = 852)
@Composable
internal fun EmptyNativeMetricsPreview() = NativeMetricsPreview("Qoder", true, "{\"metrics\":[]}")

@Preview(name = "Claude native metrics", widthDp = 393, heightDp = 852)
@Composable
internal fun ClaudeNativeMetricsPreview() = NativeMetricsPreview("Claude Code", true, """
{"metrics":[{"label":"上下文","value":"1000 / 200000 · 0.5%"},{"label":"本回合输入缓存命中","value":"600 / 1000 · 60.0%"},{"label":"本回合输入 token","value":"100"},{"label":"本回合输出 token","value":"20"},{"label":"本回合缓存写入 token","value":"300"},{"label":"CLI 累计估算 · USD","value":"0.00635"}]}
""")

@Preview(name = "Pi native metrics", widthDp = 393, heightDp = 852)
@Composable
internal fun PiNativeMetricsPreview() = NativeMetricsPreview("pi", false, """
{"metrics":[{"label":"上下文","value":"8449 / 1000000 · 0.8%"},{"label":"累计 输入缓存命中","value":"30784 / 33153 · 92.9%"},{"label":"CLI 记录费用 · USD","value":"0.00048673520000000004"}]}
""")

@Composable
private fun NativeMetricsPreview(cli: String, dark: Boolean, metrics: String) = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = dark) { SessionTypography {
    val base = sampleView().copy(summary = SessionSummary("metrics", "原生用量", "noroot", "/workspace", model = "fixture", cli = cli), activeTurnId = null, queue = emptyList(), draft = "")
    val view = NativeCliProjection.view(JSONObject(metrics).put("connected", true), base).copy(connection = "已连接")
    LiquidScaffold(wrapTop = false, topBar = { ConversationHeader(view, previewActions, {}, {}, previewDashboard = true) }, bottomBar = {
        CompactComposer(CockpitState(selected = view), view, previewActions, {}, {})
    }) { padding -> Column(Modifier.padding(padding)) {} }
} }
