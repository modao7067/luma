package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.session.ConversationItem

internal fun reasoningCaption(outcome: String): String = if (outcome == "running") "思考中" else "思考"

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
    val completed = reasoning.count { it.outcome == "succeeded" }
    val running = reasoning.any { it.outcome == "running" }
    return buildList {
        if (tools.isNotEmpty()) add(ProcessSummaryPart("${tools.size} 次 ", tools.map(::processToolName).distinct().joinToString("、")))
        if (completed > 0) add(ProcessSummaryPart("$completed 次 ", "思考"))
        if (running) add(ProcessSummaryPart(label = "思考中"))
        if (reasoning.isNotEmpty() && completed == 0 && !running) add(ProcessSummaryPart(label = "思考"))
        if (failedTools > 0) add(ProcessSummaryPart("$failedTools 次 ", "失败"))
        if (subAgentEntries > 0) add(ProcessSummaryPart("$subAgentEntries 次 ", "子代理"))
        if (isEmpty() && entries.isNotEmpty()) add(ProcessSummaryPart(label = "执行过程"))
    }
}

internal fun processGroupCaption(entries: List<ConversationItem>, cli: String = "Codex"): String =
    processGroupSummary(entries, cli).joinToString(" · ") { it.text }
