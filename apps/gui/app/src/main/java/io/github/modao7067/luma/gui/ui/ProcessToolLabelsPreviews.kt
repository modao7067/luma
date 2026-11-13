package io.github.modao7067.luma.gui.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.modao7067.luma.gui.LumaTheme
import io.github.modao7067.luma.gui.session.ConversationItem
import io.github.modao7067.luma.gui.session.SubAgentState

private fun toolLabelsPreviewEntries(long: Boolean = false): List<ConversationItem> {
    val regular = listOf("", "read", "glob", "websearch", "read", "glob", "websearch", "read").mapIndexed { index, name ->
        ConversationItem("call-$index", "tool", text = if (name.isBlank()) "printf synthetic" else name,
            kind = if (name.isBlank()) "commandExecution" else "tool", toolName = name,
            outcome = if (index == 5) "failed" else "succeeded",
            input = if (name.isBlank()) "printf synthetic" else "{\"pattern\":\"synthetic\"}", detail = "synthetic result $index")
    } + (0..1).map { ConversationItem("reason-$it", "tool", kind = "reasoning", outcome = "succeeded", detail = "Synthetic public summary $it") }
    val longName = "mcp__SyntheticServer__" + "ExtremelyLongMixedCaseToolName_".repeat(5)
    return if (!long) regular else regular + regular.first().copy(id = "long", kind = "mcpToolCall", text = longName, toolName = longName)
}

private fun mixedToolLabelsPreviewEntries() = listOf(
    ConversationItem("activity-1", "tool", text = "子代理活动", kind = "subAgentActivity", outcome = "succeeded"),
    ConversationItem("activity-2", "tool", text = "子代理活动", kind = "subAgentActivity", outcome = "succeeded"),
    ConversationItem("reason-1", "tool", kind = "reasoning", outcome = "succeeded", detail = "Synthetic public summary"),
    ConversationItem("wait-1", "tool", text = "等待子代理", kind = "collabAgentToolCall", toolName = "wait", outcome = "succeeded", input = "{\"ids\":[\"synthetic-agent\"]}", detail = "Synthetic subagent result"),
    ConversationItem("wait-2", "tool", text = "等待子代理", kind = "collabAgentToolCall", toolName = "wait", outcome = "succeeded"),
).map { it.copy(turnId = "preview-turn") }.let { messages ->
    val agent = SubAgentState("preview-agent", "Ptolemy", "explorer", "preview-turn", "wait-1", "Synthetic task", "running", "Synthetic result")
    processGroupEntries(conversationRows(messages, listOf(agent)).single())
}

@Composable
private fun ToolLabelsFrame(expanded: Boolean, long: Boolean = false, mixed: Boolean = false) =
    LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) { SessionTypography { ConversationTypography {
        CompositionLocalProvider(LocalProcessDotsPhase provides 0f) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    ProcessGroup(if (mixed) mixedToolLabelsPreviewEntries() else toolLabelsPreviewEntries(long), initiallyExpanded = expanded,
                        initiallyOpenItem = if (expanded && !long) { if (mixed) "wait-1" else "call-1" } else null,
                        previewDetailFraction = if (expanded && !long) 1f else null,
                        previewGroupFraction = if (expanded) 1f else 0f, cli = if (mixed) "Codex" else "OpenCode")
                }
            }
        }
    } } }

@Preview(name = "Tool names · mixed subagent activity and expanded wait", widthDp = 344, heightDp = 660)
@Composable
internal fun ToolLabelsExpandedPreview() = ToolLabelsFrame(expanded = true, mixed = true)

@Preview(name = "Tool names · narrow long MCP name", widthDp = 264, heightDp = 850, fontScale = 1.3f)
@Composable
internal fun ToolLabelsLongNamePreview() = ToolLabelsFrame(expanded = true, long = true)

@Preview(name = "Tool names · collapsed same inputs", widthDp = 264, heightDp = 180, fontScale = 1.3f)
@Composable
internal fun ToolLabelsCollapsedPreview() = ToolLabelsFrame(expanded = false, long = true)

@Preview(name = "Tool names · reasoning and agent-only summaries", widthDp = 344, heightDp = 340)
@Composable
internal fun ToolLabelsStatusFallbackPreview() =
    LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) { SessionTypography { ConversationTypography {
        CompositionLocalProvider(LocalProcessDotsPhase provides 0f) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val reasoning = toolLabelsPreviewEntries().filter { it.kind == "reasoning" }
                    ProcessGroup((0..1).map { ConversationItem("activity-$it", "tool", kind = "subAgentActivity", outcome = "succeeded") }, previewGroupFraction = 0f)
                    ProcessGroup(reasoning, previewGroupFraction = 0f)
                    ProcessGroup(listOf(reasoning.first().copy(id = "running", outcome = "running")), previewGroupFraction = 0f)
                    ProcessGroup((0..1).map { ConversationItem("agent-$it", "tool", kind = "subAgent", outcome = "succeeded") }, previewGroupFraction = 0f)
                    ProcessGroup(listOf(ConversationItem("failed-shell", "tool", kind = "commandExecution", outcome = "failed")), previewGroupFraction = 0f)
                }
            }
        }
    } } }
