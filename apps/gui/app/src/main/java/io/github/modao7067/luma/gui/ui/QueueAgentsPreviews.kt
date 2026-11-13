package io.github.modao7067.luma.gui.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import io.github.modao7067.luma.gui.LumaTheme
import io.github.modao7067.luma.gui.session.*

internal fun queueAgentsFixture(queue: Boolean = true, agents: Boolean = true): SessionView = sampleView().copy(
    summary = sampleView().summary.copy(title = "检查会话与并发执行"), draft = "", activeTurnId = "turn-1",
    messages = listOf(
        ConversationItem("request", "user", "并行检查协议和界面，保留正在阅读的位置。", turnId = "turn-1"),
        ConversationItem("intro", "assistant", "我会分别核对原生状态与界面，完成后一起汇总。", turnId = "turn-1"),
        ConversationItem("spawn", "tool", "启动子代理", kind = "collabAgentToolCall", outcome = "succeeded", turnId = "turn-1"),
    ),
    queue = if (queue) listOf(
        QueueEntry("q1", "完成后补充窄屏和长消息验收", "排队 · 下一轮"),
        QueueEntry("q2", "先保留日志，不要安装到手机", "插话 · 等待接收", canEdit = false, canSteer = false, canRemove = false),
    ) else emptyList(),
    subAgents = if (agents) listOf(
        SubAgentState("agent-protocol", "协议检查", "explorer", "turn-1", "spawn", "核对真实队列、插话与子代理原生状态。", "running"),
        SubAgentState("agent-ui", "界面验收", "worker", "turn-1", "spawn", "检查窄屏、详情展开和阅读锚点。", "running"),
        SubAgentState("agent-review", "结果复核", "reviewer", "turn-1", "spawn", "等待独立检查结果。", "pending"),
    ) else emptyList(),
)

@Composable
private fun QueueAgentsFixture(queue: Boolean = true, agents: Boolean = true, dark: Boolean = false) =
    LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = dark) {
        CockpitScreen(CockpitState(selected = queueAgentsFixture(queue, agents)), previewActions)
    }

@Preview(name = "Queue and steering", widthDp = 393, heightDp = 820)
@Composable internal fun QueueOnlyPreview() = QueueAgentsFixture(agents = false)
@Preview(name = "Concurrent agents", widthDp = 393, heightDp = 820)
@Composable internal fun AgentsOnlyPreview() = QueueAgentsFixture(queue = false)
@Preview(name = "Queue and concurrent agents", widthDp = 393, heightDp = 820)
@Composable internal fun QueueAgentsPreview() = QueueAgentsFixture(dark = true)
@Preview(name = "Narrow long queue and agents", widthDp = 320, heightDp = 760, fontScale = 1.3f)
@Composable internal fun QueueAgentsNarrowPreview() = QueueAgentsFixture()
@Preview(name = "Local queue menu edit", widthDp = 393, heightDp = 820)
@Composable internal fun LocalQueueEditPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) {
    val view = queueAgentsFixture(agents = false).copy(queue = listOf(
        QueueEntry("submission-local", "修改这条本地待发消息，回填原文和附件后再自行发送", "本地待发",
            canSteer = false, localSubmission = true, clientUserMessageId = "local", canDismiss = true),
        QueueEntry("submission-unknown", "这条已交给后台，仍需核对送达状态", "发送中",
            canEdit = false, canSteer = false, canRemove = false, localSubmission = true, canDismiss = true),
    ))
    CockpitScreen(CockpitState(selected = view), previewActions)
}
@Preview(name = "Agent results and unresolved states", widthDp = 393, heightDp = 860)
@Composable internal fun AgentsMixedPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) {
    SessionTypography { ConversationTypography { Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            val base = queueAgentsFixture().subAgents.first()
            SubAgentGroup(listOf(
                base.copy(id = "ok", name = "协议检查", status = "succeeded", detail = "队列内容与原生状态一致。"),
                base.copy(id = "fail", name = "界面验收 · 长名称应自然省略", status = "failed", detail = "无法读取预览资源：Permission denied。请检查资源路径。"),
                base.copy(id = "closed", name = "独立复核", status = "closed"),
                base.copy(id = "019a4100-7b31-75dd-bb19-26344622aa53", name = "", role = "worker", status = "unknown"),
            ), initiallyExpanded = true, initiallyOpenItem = "fail")
        }
    } } }
}

@Preview(name = "Agent group close and rapid reverse", widthDp = 393, heightDp = 780)
@Composable internal fun AgentsMotionPreview(@PreviewParameter(ProcessMotionProvider::class) frame: ProcessMotionFrame) =
    LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) { SessionTypography { ConversationTypography {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.Text(frame.label, style = MaterialTheme.typography.labelSmall)
                SubAgentGroup(queueAgentsFixture().subAgents.mapIndexed { index, agent ->
                    if (index < 2) agent.copy(status = "succeeded", detail = "已完成原生状态核对，结果保持一致。") else agent
                }, initiallyExpanded = frame.expanded, initiallyOpenItem = "agent-protocol", previewGroupFraction = frame.fraction)
                androidx.compose.material3.Text("汇总回复接续过程，不另建底部状态栏。", style = MaterialTheme.typography.bodyLarge)
            }
        }
    } } }
