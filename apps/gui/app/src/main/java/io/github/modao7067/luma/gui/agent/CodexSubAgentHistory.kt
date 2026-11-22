package io.github.modao7067.luma.gui.agent

import io.github.modao7067.luma.gui.i18n.tr

import io.github.modao7067.luma.gui.session.ConversationItem
import org.json.JSONObject

internal class CodexSubAgentHistory(
    private val parentThreadId: String,
    private val childThreadId: String,
    private val request: suspend (String, JSONObject) -> JSONObject,
    private val imageUri: (String) -> String?,
) {
    data class Snapshot(val thread: JSONObject, val lastTurn: JSONObject?, val messages: List<ConversationItem>, val model: String, val effort: String, val statusFresh: Boolean)
    private val items = linkedMapOf<String, ConversationItem>()
    private val nativeToClientId = HashMap<String, String>()
    private val liveItems = mutableMapOf<String, Long>()
    private var streamRevision = 0L
    private var turnCursor: String? = null
    private val buffered = mutableListOf<JSONObject>()
    private val boundary = HydrationBoundary()
    private var hydrating = true
    private var statusRevision = 0
    private var model = ""
    private var effort = ""
    private var cwd = ""
    val messages get() = items.values.toList()
    val modelId get() = model
    val reasoningEffort get() = effort

    suspend fun refresh(): Snapshot {
        val revision = statusRevision
        val streamAtRead = streamRevision
        val thread = request("thread/read", obj("threadId" to childThreadId, "includeTurns" to false)).getJSONObject("thread")
        checkIdentity(thread)
        cwd = thread.optString("cwd")
        model = thread.stringOrNull("model").orEmpty()
        effort = thread.stringOrNull("reasoningEffort").orEmpty()
        val rows = linkedMapOf<String, ConversationItem>()
        val turnsBefore = buffered.size
        val tail = request("thread/turns/list", obj("threadId" to childThreadId, "limit" to 1, "sortDirection" to "desc", "itemsView" to "notLoaded"))
        if (hydrating) boundary.turns(turnsBefore, childThreadId)
        val lastTurn = tail.optJSONArray("data")?.optJSONObject(0)
        val nextAnchor = tail.stringOrNull("backwardsCursor")
        fun add(item: JSONObject, turnId: String, completed: Boolean?, pageBefore: Int, pageAfter: Int) {
            val nativeId = item.stringOrNull("id")
            val row = project(item, turnId, if (!item.isNull("completedAtMs")) true else completed)
            putCanonical(rows, nativeId, row.id, row, migrateLive = false)
            if (hydrating) {
                boundary.item(row.id, pageBefore, pageAfter)
                if (!nativeId.isNullOrBlank() && nativeId != row.id) {
                    boundary.item(nativeId, pageBefore, pageAfter)
                }
            }
        }
        if (turnCursor != null) {
            var cursor = turnCursor
            val seenCursors = hashSetOf<String>()
            cursor?.let(seenCursors::add)
            do {
                val pageBefore = buffered.size
                val page = request("thread/turns/list", obj("threadId" to childThreadId, "cursor" to cursor, "limit" to 100, "sortDirection" to "asc", "itemsView" to "full"))
                val pageAfter = buffered.size
                page.optJSONArray("data")?.objects().orEmpty().forEach { turn ->
                    turn.optJSONArray("items")?.objects().orEmpty().forEach { add(it, turn.optString("id"), turn.optString("status") != "inProgress", pageBefore, pageAfter) }
                }
                val nextCursor = page.stringOrNull("nextCursor")
                cursor = if (nextCursor != null && seenCursors.add(nextCursor)) nextCursor else null
            } while (cursor != null)
        } else if (thread.optString("historyMode") == "legacy") {
            val pageBefore = buffered.size
            val full = request("thread/read", obj("threadId" to childThreadId, "includeTurns" to true)).getJSONObject("thread")
            val pageAfter = buffered.size
            checkIdentity(full)
            full.optJSONArray("turns")?.objects().orEmpty().forEach { turn ->
                turn.optJSONArray("items")?.objects().orEmpty().forEach { add(it, turn.optString("id"), turn.optString("status") != "inProgress", pageBefore, pageAfter) }
            }
        } else {
            var cursor: String? = null
            val seenCursors = hashSetOf<String>()
            do {
                val pageBefore = buffered.size
                val page = request("thread/items/list", obj("threadId" to childThreadId, "limit" to 100, "sortDirection" to "asc", "cursor" to cursor))
                val pageAfter = buffered.size
                page.optJSONArray("data")?.objects().orEmpty().forEach { entry ->
                    add(entry.getJSONObject("item"), entry.optString("turnId"), !entry.isNull("completedAtMs"), pageBefore, pageAfter)
                }
                val nextCursor = page.stringOrNull("nextCursor")
                cursor = if (nextCursor != null && seenCursors.add(nextCursor)) nextCursor else null
            } while (cursor != null)
        }
        turnCursor = nextAnchor ?: turnCursor
        rows.forEach { (id, row) ->
            val reverseNative = nativeToClientId.entries.firstOrNull { it.value == id }?.key
            val current = items[id] ?: reverseNative?.let(items::get)
            val live = liveItems[id] ?: reverseNative?.let(liveItems::get)
            val terminalSnapshot = row.outcome in setOf("succeeded", "failed", "cancelled")
            val longerSnapshot = current != null && (row.text.length > current.text.length || row.detail.length > current.detail.length)
            if (live == null || (live <= streamAtRead && (terminalSnapshot || longerSnapshot))) {
                putCanonical(items, reverseNative, id, row, migrateLive = true)
                if (terminalSnapshot || live == null) {
                    liveItems.remove(id)
                    if (reverseNative != null) liveItems.remove(reverseNative)
                }
            } else if (reverseNative != null && items.containsKey(reverseNative) && current != null) {
                putCanonical(items, reverseNative, id, current.copy(id = id), migrateLive = true)
            }
        }
        if (hydrating) {
            hydrating = false
            val events = buffered.toList(); buffered.clear()
            val toReplay = boundary.replayEvents(events) { itemId, toolDetail ->
                val snap = items[nativeToClientId[itemId] ?: itemId]
                if (toolDetail) snap?.detail.orEmpty() else snap?.text.orEmpty()
            }
            toReplay.forEach(::apply)
            boundary.clear()
        }
        return Snapshot(thread, lastTurn, messages, model, effort, revision == statusRevision)
    }

    private fun checkIdentity(thread: JSONObject) {
        check(thread.optString("id") == childThreadId) { tr("Codex 返回了不同子代理", "Codex returned a different sub-agent") }
        val sourceParent = thread.optJSONObject("source")?.optJSONObject("subAgent")?.optJSONObject("thread_spawn")?.stringOrNull("parent_thread_id")
        val parents = listOfNotNull(thread.stringOrNull("parentThreadId"), sourceParent)
        check(parents.isNotEmpty() && parents.all { it == parentThreadId }) { tr("Codex 子代理父会话不匹配", "Codex sub-agent parent session mismatch") }
    }

    fun event(message: JSONObject): Boolean {
        if (message.has("id") || message.optJSONObject("params")?.stringOrNull("threadId") != childThreadId) return false
        if (message.optString("method") in setOf("turn/started", "turn/completed", "thread/status/changed")) statusRevision++
        if (hydrating) buffered += message else apply(message)
        return true
    }
    private fun putCanonical(
        target: LinkedHashMap<String, ConversationItem>,
        nativeId: String?,
        canonicalId: String,
        value: ConversationItem,
        migrateLive: Boolean,
    ) {
        if (!nativeId.isNullOrBlank() && nativeId != canonicalId && target.containsKey(nativeId)) {
            val rebuilt = LinkedHashMap<String, ConversationItem>(target.size)
            for ((k, v) in target) {
                when (k) {
                    nativeId -> if (!target.containsKey(canonicalId)) rebuilt[canonicalId] = value
                    canonicalId -> rebuilt[canonicalId] = value
                    else -> rebuilt[k] = v
                }
            }
            target.clear()
            target.putAll(rebuilt)
            if (migrateLive) {
                liveItems.remove(nativeId)?.let { prev ->
                    liveItems[canonicalId] = maxOf(prev, liveItems[canonicalId] ?: 0L)
                }
            }
        } else {
            target[canonicalId] = value
        }
    }
    private fun project(item: JSONObject, turnId: String, completed: Boolean? = null): ConversationItem {
        val nativeId = item.stringOrNull("id")
        val explicitClient = if (item.optString("type") == "userMessage") {
            CodexProjection.clientMessageId(item)?.takeIf { it.isNotBlank() }
        } else null
        if (!nativeId.isNullOrBlank() && explicitClient != null) {
            nativeToClientId[nativeId] = explicitClient
        }
        val canonicalHint = if (item.optString("type") == "userMessage") {
            explicitClient ?: nativeId?.let(nativeToClientId::get)
        } else null
        val previous = canonicalHint?.let(items::get) ?: nativeId?.let(items::get)
        val raw = CodexProjection.item(item, previous)
        val canonicalId = canonicalHint ?: raw.id
        val content = if (item.optString("type") == "reasoning") item.optJSONArray("content")?.strings().orEmpty().filter { it.isNotBlank() } else emptyList()
        return raw.copy(id = canonicalId, turnId = turnId, media = raw.withMediaContext(parentThreadId, cwd, childThreadId).media,
            detail = (listOf(raw.detail).filter { it.isNotBlank() } + content).distinct().joinToString("\n"),
            outcome = if (raw.outcome != "unknown" || completed == null) raw.outcome else if (completed) "succeeded" else "running",
            asyncQuestions = emptyList())
    }
    private fun put(item: JSONObject, turnId: String, completed: Boolean? = null) {
        val nativeId = item.stringOrNull("id")
        val row = project(item, turnId, if (!item.isNull("completedAtMs")) true else completed)
        putCanonical(items, nativeId, row.id, row, migrateLive = true)
        liveItems[row.id] = ++streamRevision
    }
    private fun apply(message: JSONObject) {
        val p = message.optJSONObject("params") ?: return
        when (val method = message.optString("method")) {
            "item/started", "item/completed" -> p.optJSONObject("item")?.let { put(it, p.optString("turnId"), method == "item/completed") }
            "turn/started", "turn/completed" -> p.optJSONObject("turn")?.let { turn ->
                turn.optJSONArray("items")?.objects().orEmpty().forEach { put(it, turn.optString("id"), method == "turn/completed") }
            }
            "item/agentMessage/delta", "item/plan/delta", "item/commandExecution/outputDelta", "item/reasoning/summaryTextDelta", "item/reasoning/textDelta" -> {
                val id = p.optString("itemId")
                val assistant = method in setOf("item/agentMessage/delta", "item/plan/delta")
                val old = items[id] ?: ConversationItem(id, if (assistant) "assistant" else "tool", text = if (method.contains("reasoning")) tr("推理摘要", "Reasoning summary") else "", turnId = p.optString("turnId"), outcome = "running")
                val delta = p.optString("delta")
                items[id] = if (assistant) old.copy(text = old.text + delta) else old.copy(detail = old.detail + delta)
                liveItems[id] = ++streamRevision
            }
            "thread/settings/updated" -> p.optJSONObject("threadSettings")?.let {
                model = it.stringOrNull("model").orEmpty(); effort = it.stringOrNull("effort").orEmpty()
            }
        }
    }
}
