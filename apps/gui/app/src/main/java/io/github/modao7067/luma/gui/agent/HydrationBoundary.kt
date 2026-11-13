package io.github.modao7067.luma.gui.agent

import org.json.JSONObject

internal class HydrationBoundary {
    private data class ItemWindow(val before: Int, val after: Int)
    private data class DeltaFieldKey(val itemId: String, val toolDetail: Boolean)
    private val items = mutableMapOf<String, ItemWindow>()
    private val turns = mutableMapOf<String?, Int>()
    fun clear() { items.clear(); turns.clear() }
    fun item(id: String, bufferedBefore: Int, bufferedAfter: Int = bufferedBefore) {
        val prev = items[id]
        val start = if (prev != null) minOf(prev.before, bufferedBefore) else bufferedBefore
        val end = if (prev != null) maxOf(prev.after, bufferedAfter) else maxOf(bufferedBefore, bufferedAfter)
        items[id] = ItemWindow(start, end)
    }
    fun turns(bufferedBefore: Int, threadId: String? = null) { turns[threadId] = bufferedBefore }
    fun shouldReplay(index: Int, message: JSONObject): Boolean {
        if (message.has("id")) return true
        val method = message.optString("method")
        val params = message.optJSONObject("params")
        val id = params?.stringOrNull("itemId") ?: params?.optJSONObject("item")?.stringOrNull("id")
        val turnBoundary = turns[params?.stringOrNull("threadId")] ?: turns[null] ?: 0
        val itemBefore = id?.let { items[it]?.before } ?: 0
        return !((method.startsWith("item/") && index < itemBefore) ||
            ((method.startsWith("turn/") || method == "thread/status/changed") && index < turnBoundary))
    }
    fun replayEvents(
        events: List<JSONObject>,
        snapshotField: (itemId: String, toolDetail: Boolean) -> String,
    ): List<JSONObject> {
        if (events.isEmpty()) return emptyList()
        val windowDeltas = LinkedHashMap<DeltaFieldKey, MutableList<Pair<Int, String>>>()
        events.forEachIndexed { index, message ->
            if (message.has("id")) return@forEachIndexed
            val method = message.optString("method")
            if (!isItemDeltaMethod(method)) return@forEachIndexed
            val params = message.optJSONObject("params") ?: return@forEachIndexed
            val id = params.stringOrNull("itemId") ?: return@forEachIndexed
            val window = items[id] ?: return@forEachIndexed
            if (index in window.before until window.after) {
                val delta = params.optString("delta")
                if (delta.isNotEmpty()) {
                    val key = DeltaFieldKey(id, isToolDeltaMethod(method))
                    windowDeltas.getOrPut(key) { mutableListOf() }.add(index to delta)
                }
            }
        }
        if (windowDeltas.isEmpty()) {
            return events.filterIndexed { index, message -> shouldReplay(index, message) }
        }
        val rewrittenByIndex = HashMap<Int, JSONObject?>()
        for ((key, entries) in windowDeltas) {
            val concatenated = buildString { entries.forEach { append(it.second) } }
            val snapshot = snapshotField(key.itemId, key.toolDetail)
            var overlap = concatenated.length
            while (overlap > 0 && !snapshot.endsWith(concatenated.substring(0, overlap))) {
                overlap--
            }
            var skipRemaining = overlap
            for ((idx, delta) in entries) {
                if (delta.length <= skipRemaining) {
                    skipRemaining -= delta.length
                    rewrittenByIndex[idx] = null
                } else if (skipRemaining > 0) {
                    val remainder = delta.substring(skipRemaining)
                    skipRemaining = 0
                    val original = events[idx]
                    val origParams = original.optJSONObject("params") ?: JSONObject()
                    val copyParams = JSONObject(origParams.toString()).put("delta", remainder)
                    val copyEvent = JSONObject(original.toString()).put("params", copyParams)
                    rewrittenByIndex[idx] = copyEvent
                }
            }
        }
        val result = ArrayList<JSONObject>(events.size)
        events.forEachIndexed { index, message ->
            if (rewrittenByIndex.containsKey(index)) {
                rewrittenByIndex[index]?.let(result::add)
            } else if (shouldReplay(index, message)) {
                result.add(message)
            }
        }
        return result
    }
    companion object {
        private fun isItemDeltaMethod(method: String): Boolean = method in setOf(
            "item/agentMessage/delta",
            "item/plan/delta",
            "item/commandExecution/outputDelta",
            "item/reasoning/summaryTextDelta",
            "item/reasoning/textDelta",
        )
        private fun isToolDeltaMethod(method: String): Boolean =
            method != "item/agentMessage/delta" && method != "item/plan/delta"
    }
}
