package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.i18n.LumaI18n
import io.github.modao7067.luma.gui.i18n.tr

import io.github.modao7067.luma.gui.session.ConversationItem

internal fun reasoningCaption(outcome: String): String = if (outcome == "running") tr("思考中", "Thinking") else tr("思考", "Thought")

internal fun processEntryRetained(item: ConversationItem): Boolean =
    item.outcome != "succeeded" && item.outcome != "cancelled" && item.outcome != "failed" && !(item.kind == "subAgent" && item.outcome == "closed")

internal data class ProcessRevealRun(val retained: Boolean, val entries: List<ConversationItem>)

internal fun processRevealRuns(entries: List<ConversationItem>): List<ProcessRevealRun> {
    val runs = mutableListOf<ProcessRevealRun>()
    entries.forEach { entry ->
        val retained = processEntryRetained(entry)
        val previous = runs.lastOrNull()
        if (previous?.retained == retained) {
            runs[runs.lastIndex] = previous.copy(entries = previous.entries + entry)
        } else {
            runs += ProcessRevealRun(retained, listOf(entry))
        }
    }
    return runs
}

internal data class ProcessSummaryPart(val prefix: String = "", val label: String) {
    val text: String get() = prefix + label
}

internal fun processGroupSummary(entries: List<ConversationItem>, cli: String = "Codex"): List<ProcessSummaryPart> {
    val tools = processToolInvocations(entries)
    val failedTools = tools.count { it.outcome == "failed" }
    val activityCount = entries.withIndex().filter { it.value.role == "tool" && it.value.kind == "subAgentActivity" }
        .distinctBy { (index, item) -> Triple(item.turnId, item.id, if (item.id.isBlank()) index else -1) }.size
    val agentCount = entries.withIndex().filter { it.value.role == "tool" && it.value.kind == "subAgent" }
        .distinctBy { (index, item) ->
            val identity = item.toolKind.ifBlank { item.id }
            identity to if (identity.isBlank()) index else -1
        }.size
    val subAgentEntries = activityCount + agentCount
    val reasoning = entries.filter { it.kind == "reasoning" }.distinctBy { it.turnId to it.id }
    return buildList {
        val zh = LumaI18n.isZh()
        if (tools.isNotEmpty()) add(ProcessSummaryPart(if (zh) "${tools.size} 次 " else "${tools.size}× ", tools.map(::processToolName).distinct().joinToString(if (zh) "、" else ", ")))
        if (reasoning.isNotEmpty()) add(ProcessSummaryPart(if (zh) "${reasoning.size} 次 " else "${reasoning.size}× ", if (zh) "思考" else "thought"))
        if (failedTools > 0) add(ProcessSummaryPart(if (zh) "$failedTools 次 " else "$failedTools ", if (zh) "失败" else "failed"))
        if (subAgentEntries > 0) add(ProcessSummaryPart(if (zh) "$subAgentEntries 次 " else "$subAgentEntries× ", if (zh) "子代理" else "sub-agent"))
        if (isEmpty() && entries.isNotEmpty()) add(ProcessSummaryPart(label = if (zh) "执行过程" else "Steps"))
    }
}

internal fun processGroupCaption(entries: List<ConversationItem>, cli: String = "Codex"): String =
    processGroupSummary(entries, cli).joinToString(" · ") { it.text }
