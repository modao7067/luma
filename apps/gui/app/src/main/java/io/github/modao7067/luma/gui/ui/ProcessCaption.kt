package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.i18n.LumaI18n
import io.github.modao7067.luma.gui.i18n.tr
import io.github.modao7067.luma.gui.session.ConversationItem
import io.github.modao7067.luma.gui.session.SubAgentState
import java.util.LinkedHashMap

internal fun reasoningCaption(outcome: String): String = if (outcome == "running") tr("思考中", "Thinking") else tr("思考", "Thought")

internal fun processEntryActive(item: ConversationItem): Boolean =
    item.outcome == "running" || (item.kind == "subAgent" && item.outcome == "pending")

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

internal fun formatProcessDuration(durationMs: Long): String {
    val totalSeconds = ((durationMs.coerceAtLeast(0L) + 500L) / 1000L).coerceAtLeast(1L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0L -> buildString {
            append(hours).append('h')
            if (minutes > 0L) append(minutes).append('m')
            if (seconds > 0L) append(seconds).append('s')
        }
        minutes > 0L -> buildString {
            append(minutes).append('m')
            if (seconds > 0L) append(seconds).append('s')
        }
        else -> "${seconds}s"
    }
}

internal fun monotonicElapsedMs(): Long {
    val elapsed = try {
        android.os.SystemClock.elapsedRealtime()
    } catch (_: Throwable) {
        0L
    }
    return if (elapsed > 0L) elapsed else System.nanoTime() / 1_000_000L
}

internal object ProcessDurationStore {
    private const val MAX_ENTRIES = 512
    private class Entry(val firstStartedMs: Long, var completedDurationMs: Long? = null, var wasActive: Boolean = true)
    private val lock = Any()
    private val map = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > MAX_ENTRIES
    }

    fun observe(key: String, active: Boolean, nowMs: Long = monotonicElapsedMs()): Long? {
        if (key.isBlank()) return null
        synchronized(lock) {
            val existing = map[key]
            if (active) {
                if (existing == null) {
                    map[key] = Entry(firstStartedMs = nowMs, completedDurationMs = null, wasActive = true)
                } else {
                    existing.wasActive = true
                }
                return null
            }
            if (existing == null) return null
            if (existing.wasActive) {
                existing.completedDurationMs = (nowMs - existing.firstStartedMs).coerceAtLeast(1L)
                existing.wasActive = false
            }
            return existing.completedDurationMs
        }
    }

    fun clearForTest() = synchronized(lock) { map.clear() }
}

internal class SessionProcessDurationTracker(
    private val clockMs: () -> Long = ::monotonicElapsedMs,
    private val maxPersisted: Int = 256,
) {
    private val activeStarts = LinkedHashMap<String, Long>()

    fun reconcile(
        persisted: MutableMap<String, Long>,
        connected: Boolean,
        loading: Boolean,
        hydrating: Boolean,
        activeTurnId: String?,
        messages: List<ConversationItem>,
        subAgents: List<SubAgentState>,
    ): Boolean {
        if (!connected) {
            activeStarts.clear()
            return false
        }
        if (loading || hydrating) return false
        val (rows, openProcessRowId) = sessionConversationRows(messages, subAgents, loading = false, activeTurnId = activeTurnId)
        val slotKeys = processGroupSlotKeys(rows)
        val now = clockMs()
        val seenSlots = HashSet<String>(slotKeys.size * 2)
        var changed = false
        rows.forEach { row ->
            if (!row.process) return@forEach
            val slot = slotKeys[row.id] ?: return@forEach
            seenSlots.add(slot)
            val entries = processGroupEntries(row)
            val turnOpen = row.id == openProcessRowId
            if (entries.isEmpty() && !turnOpen) return@forEach
            val active = processGroupHasActive(entries, turnOpen = turnOpen)
            if (active) {
                if (!activeStarts.containsKey(slot)) {
                    activeStarts[slot] = now
                }
            } else {
                val started = activeStarts.remove(slot)
                if (started != null) {
                    val elapsed = (now - started).coerceAtLeast(1L)
                    if (!persisted.containsKey(slot) && persisted.size >= maxPersisted) {
                        val oldest = persisted.keys.firstOrNull()
                        if (oldest != null) persisted.remove(oldest)
                    }
                    if (persisted[slot] != elapsed) {
                        persisted[slot] = elapsed
                        changed = true
                    }
                }
            }
        }
        activeStarts.keys.retainAll(seenSlots)
        return changed
    }
}

internal data class ProcessSummaryPart(val text: String, val emphasized: Boolean = false)

internal fun processActiveVerb(item: ConversationItem, cli: String = "Codex"): String {
    val zh = LumaI18n.isZh()
    return when {
        item.kind == "reasoning" -> if (zh) "正在思考…" else "Thinking…"
        item.kind == "subAgent" || item.kind == "subAgentActivity" || item.kind == "collabAgentToolCall" ->
            if (zh) "正在召唤子代理…" else "Summoning sub-agent…"
        item.kind == "compaction" -> if (zh) "正在整理上下文…" else "Compacting context…"
        else -> when (processCategory(item, cli)) {
            ProcessCategory.Terminal -> if (zh) "正在执行命令…" else "Running command…"
            ProcessCategory.Read -> if (zh) "正在读取文件…" else "Reading files…"
            ProcessCategory.Search -> if (zh) "正在搜索代码…" else "Searching code…"
            ProcessCategory.WebSearch -> if (zh) "正在搜索网页…" else "Searching web…"
            ProcessCategory.Browse -> if (zh) "正在浏览页面…" else "Browsing page…"
            ProcessCategory.Edit -> if (zh) "正在修改文件…" else "Editing files…"
            ProcessCategory.Image -> if (zh) "正在处理图片…" else "Processing image…"
            ProcessCategory.Tool -> if (zh) "正在调用工具…" else "Calling tool…"
        }
    }
}

internal fun processMergedSummaryEntries(entries: List<ConversationItem>): List<ConversationItem> {
    if (entries.isEmpty()) return emptyList()
    val merged = linkedMapOf<String, ConversationItem>()
    entries.forEachIndexed { index, item ->
        if (item.role != "tool") return@forEachIndexed
        val uniqueIdx = if (item.id.isBlank()) index else -1
        val key = when {
            item.kind in processInvocationKinds -> "call:${item.turnId}:${item.id}:$uniqueIdx"
            item.kind == "reasoning" -> "reasoning:${item.turnId}:${item.id}:$uniqueIdx"
            item.kind == "subAgentActivity" -> "subAct:${item.turnId}:${item.id}:$uniqueIdx"
            item.kind == "subAgent" -> {
                val identity = item.toolKind.ifBlank { item.id }
                "subAgent:$identity:${if (identity.isBlank()) index else -1}"
            }
            item.kind == "compaction" -> "compaction:${item.turnId}:${item.id}:$uniqueIdx"
            else -> null
        } ?: return@forEachIndexed
        merged[key] = item
    }
    return merged.values.toList()
}

private fun processSummaryActiveItem(
    entries: List<ConversationItem>,
    merged: List<ConversationItem>,
): ConversationItem? =
    if (merged.isNotEmpty()) {
        merged.lastOrNull(::processEntryActive)
    } else {
        entries.lastOrNull { it.role == "tool" && processEntryActive(it) }
    }

internal fun processGroupHasActive(entries: List<ConversationItem>, turnOpen: Boolean = false): Boolean {
    if (turnOpen) return true
    if (entries.isEmpty()) return false
    val merged = processMergedSummaryEntries(entries)
    return processSummaryActiveItem(entries, merged) != null
}

internal fun processGroupSummary(
    entries: List<ConversationItem>,
    cli: String = "Codex",
    durationMs: Long? = null,
    turnOpen: Boolean = false,
): List<ProcessSummaryPart> {
    val zh = LumaI18n.isZh()
    val working = ProcessSummaryPart(if (zh) "工作中…" else "Working…", emphasized = true)
    if (entries.isEmpty()) {
        return if (turnOpen) listOf(working) else emptyList()
    }
    val merged = processMergedSummaryEntries(entries)
    val activeItem = processSummaryActiveItem(entries, merged)
    if (activeItem != null) {
        return listOf(ProcessSummaryPart(processActiveVerb(activeItem, cli), emphasized = true))
    }
    if (turnOpen) {
        return listOf(working)
    }
    val duration = durationMs?.let(::formatProcessDuration)
    if (merged.isEmpty()) return listOf(ProcessSummaryPart(if (zh) "执行过程" else "Steps"))
    if (merged.all { it.kind == "reasoning" }) {
        return listOf(ProcessSummaryPart(when {
            duration == null -> if (zh) "已思考" else "Thought"
            zh -> "思考了 $duration"
            else -> "Thought for $duration"
        }))
    }
    val steps = merged.size
    val edits = merged.count { processCategory(it, cli) == ProcessCategory.Edit }
    return listOfNotNull(
        ProcessSummaryPart(if (zh) "$steps 步" else if (steps == 1) "1 step" else "$steps steps"),
        edits.takeIf { it > 0 }?.let { ProcessSummaryPart(if (zh) "修改 $it 处" else if (it == 1) "1 edit" else "$it edits", emphasized = true) },
        duration?.let { ProcessSummaryPart(it) },
    )
}

internal fun processGroupCaption(
    entries: List<ConversationItem>,
    cli: String = "Codex",
    durationMs: Long? = null,
    turnOpen: Boolean = false,
): String = processGroupSummary(entries, cli, durationMs, turnOpen).joinToString(" · ") { it.text }
