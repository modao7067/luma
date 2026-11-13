package io.github.modao7067.luma.gui.ui

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import io.github.modao7067.luma.gui.LumaTheme
import io.github.modao7067.luma.gui.agent.OpenCodeProjection
import io.github.modao7067.luma.gui.session.*
import org.json.JSONArray
import org.json.JSONObject

@Preview(name = "OpenCode dashboard · light", widthDp = 393, heightDp = 852)
@Composable
internal fun OpenCodeDashboardLightPreview() = OpenCodeDashboardPreview(false)

@Preview(name = "OpenCode dashboard · dark", widthDp = 393, heightDp = 852)
@Composable
internal fun OpenCodeDashboardDarkPreview() = OpenCodeDashboardPreview(true)

@Composable
private fun OpenCodeDashboardPreview(dark: Boolean) = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = dark) { SessionTypography {
    val session = JSONObject("""{"cost":0.012345,"tokens":{"input":4000,"output":6000,"reasoning":1000,"cache":{"read":16000,"write":0}}}""")
    val step = JSONObject("""{"id":"step","type":"assistant","model":{"providerID":"fixture","id":"fixture"},"tokens":{"input":2000,"output":3000,"reasoning":500,"cache":{"read":8000,"write":0}}}""")
    val models = JSONArray("""[{"id":"fixture","providerID":"fixture","limit":{"context":20000}}]""")
    val view = sampleView().copy(summary = SessionSummary("opencode-metrics", "检查缓存与上下文", "noroot", "/workspace", model = "fixture/fixture", cli = "OpenCode"),
        effort = "", activeTurnId = null, queue = emptyList(), draft = "",
        metrics = OpenCodeProjection.metrics(session, listOf(step), models))
    LiquidScaffold(wrapTop = false, topBar = { ConversationHeader(view, previewActions, {}, {}, previewDashboard = true) }, bottomBar = {
        CompactComposer(CockpitState(selected = view), view, previewActions, {}, {})
    }) { padding -> Column(Modifier.padding(padding).padding(20.dp)) {} }
} }
