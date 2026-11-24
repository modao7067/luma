package io.github.modao7067.luma.gui.session

import io.github.modao7067.luma.gui.i18n.tr

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.github.modao7067.luma.gui.agent.*
import io.github.modao7067.luma.gui.runtime.AgentRuntime
import io.github.modao7067.luma.gui.runtime.RuntimeEndpoint
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID

internal class OpenCodeSession(
    private val record: SessionRecord,
    private val runtime: AgentRuntime,
    private val persist: () -> Unit,
    private val bindBrowser: (String) -> String,
    private val changed: (SessionView, List<ModelOption>) -> Unit,
    private val approval: () -> Unit = {},
    private val childChanged: (String) -> Unit = {},
    private val persistCheckpoint: () -> Unit = persist,
) {
    private var lifecycle = 0
    private var job = SupervisorJob()
    private var scope = CoroutineScope(job + Dispatchers.Main.immediate)
    private var client: OpenCodeClient? = null
    private var synchronizer: OpenCodeSync? = null
    private var connectionNotice: String? = null
    private var deliveryNotice: Pair<String, String>? = null
    val recovering get() = synchronizer?.recovering == true
    var endpoint: RuntimeEndpoint? = null; private set
    private var view = SessionView(SessionSummary(record.id, record.title, record.environment, record.cwd, cli = "OpenCode"))
    private var models = emptyList<ModelOption>()
    private var nativeModels = JSONArray()
    private var nativeAgents = JSONArray()
    private var catalogInitialized = false
    private var nativeSession = JSONObject()
    private val history = linkedMapOf<String, JSONObject>()
    private var queue = JSONArray()
    private val childSnapshots = linkedMapOf<String, JSONObject>()
    private val dirtyChildren = linkedSetOf<String>()
    private class ChildRead {
        val messages = linkedMapOf<String, JSONObject>()
        val dirty = linkedSetOf<String>()
        var full = true
        var updated: Long? = null
        var model = ""
        var effort = ""
    }
    private val childReads = linkedMapOf<String, ChildRead>()
    private var childHistoryLoaded = false
    private var activeIds = emptySet<String>()
    private val forms = linkedMapOf<String, JSONObject>()
    private val answering = mutableSetOf<String>()
    private val admitting = mutableSetOf<String>()
    private val sync = Mutex()
    private data class PartDeltaKey(val sessionId: String, val messageId: String, val kind: String, val ordinal: Int)
    private class PartDeltaState(val buffer: StringBuilder = StringBuilder(), var ended: Boolean = false)
    private data class DeltaSnapshot(val text: String, val ended: Boolean)
    private data class CachedProjection(val rawJson: String, val overlaySignature: Int, val items: List<ConversationItem>)
    private val deltaLock = Any()
    private val liveDeltas = linkedMapOf<PartDeltaKey, PartDeltaState>()
    private val pendingDeltaIds = linkedSetOf<String>()
    private val knownHistoryIds = HashSet<String>()
    private val subAgentToolMessageIds = HashSet<String>()
    private val projectedCache = linkedMapOf<String, CachedProjection>()
    private var deltaPublishJob: Job? = null
    private val path get() = "/api/session/${record.threadId ?: error(tr("会话尚未建立", "Session is not created yet"))}"
    private fun publish() { changed(view, models) }
    private fun clearLiveDeltas() {
        deltaPublishJob?.cancel()
        deltaPublishJob = null
        synchronized(deltaLock) {
            liveDeltas.clear()
            pendingDeltaIds.clear()
        }
    }
    private fun hasSubAgentContent(raw: JSONObject?): Boolean {
        if (raw == null) return false
        if (raw.optJSONObject("metadata")?.optString("source") == "subagent") return true
        return raw.optJSONArray("content")?.objects()?.any { it.optString("type") == "tool" && it.optString("name") == "subagent" } == true
    }
    private fun syncHistoryIndexLocked() {
        val ids = history.keys.toList()
        val subIds = history.entries.filter { hasSubAgentContent(it.value) }.map { it.key }
        synchronized(deltaLock) {
            knownHistoryIds.clear()
            knownHistoryIds.addAll(ids)
            subAgentToolMessageIds.clear()
            subAgentToolMessageIds.addAll(subIds)
        }
    }
    private fun consumePendingDeltaIds(): List<String> = synchronized(deltaLock) {
        if (pendingDeltaIds.isEmpty()) emptyList()
        else pendingDeltaIds.toList().also { pendingDeltaIds.clear() }
    }
    private fun scheduleDeltaPublish(messageId: String) {
        synchronized(deltaLock) { pendingDeltaIds.add(messageId) }
        if (deltaPublishJob?.isActive == true) return
        val attempt = lifecycle
        deltaPublishJob = scope.launch {
            delay(50)
            var updated = false
            sync.withLock {
                if (lifecycle != attempt || client == null) return@withLock
                val parentId = record.threadId ?: return@withLock
                val targetIds = consumePendingDeltaIds().filter { history.containsKey(it) }
                if (targetIds.isEmpty()) return@withLock
                val targetSnapshots = targetIds.associateWith { history.getValue(it) }
                val orderedIds = history.keys.toList()
                val missingIds = orderedIds.filter { it !in projectedCache && it !in targetSnapshots }.associateWith { history.getValue(it) }
                val cwd = record.cwd
                val sessionId = record.id
                val updates = withContext(Dispatchers.Default) {
                    (targetSnapshots + missingIds).mapValues { (_, raw) -> projectSingleMessage(sessionId, cwd, parentId, raw) }
                }
                updates.forEach { (id, cached) -> projectedCache[id] = cached }
                projectedCache.keys.retainAll(history.keys)
                val projectedMessages = flattenProjected(orderedIds)
                if (projectedMessages != view.messages) {
                    view = view.copy(messages = projectedMessages)
                    updated = true
                }
            }
            if (updated) publish()
        }
    }
    private fun flattenProjected(orderedIds: List<String>): List<ConversationItem> {
        var total = 0
        for (id in orderedIds) total += projectedCache[id]?.items?.size ?: 0
        val out = ArrayList<ConversationItem>(total)
        for (id in orderedIds) projectedCache[id]?.items?.let(out::addAll)
        return out
    }
    private fun projectSingleMessage(localSessionId: String, cwd: String, nativeSessionId: String?, raw: JSONObject): CachedProjection {
        val (effective, overlaySig) = if (nativeSessionId != null) overlayMessageDeltas(nativeSessionId, raw) else (raw to 0)
        val items = OpenCodeProjection.message(effective).map { it.withMediaContext(localSessionId, cwd) }
        return CachedProjection(raw.toString(), overlaySig, items)
    }
    private fun overlayMessageDeltas(sessionId: String, raw: JSONObject): Pair<JSONObject, Int> {
        if (raw.optString("type") != "assistant") return raw to 0
        val messageId = raw.stringOrNull("id") ?: return raw to 0
        val snapshot: Map<Pair<String, Int>, DeltaSnapshot> = synchronized(deltaLock) {
            var matched: MutableMap<Pair<String, Int>, DeltaSnapshot>? = null
            for ((key, state) in liveDeltas) {
                if (key.sessionId == sessionId && key.messageId == messageId) {
                    if (matched == null) matched = linkedMapOf()
                    matched[key.kind to key.ordinal] = DeltaSnapshot(state.buffer.toString(), state.ended)
                }
            }
            matched ?: return raw to 0
        }
        val originalContent = raw.optJSONArray("content") ?: JSONArray()
        val messageCompleted = raw.optJSONObject("time")?.has("completed") == true
        val kindOrdinals = hashMapOf<String, Int>()
        val prunedKeys = mutableListOf<PartDeltaKey>()
        val mergedContent = JSONArray()
        var changed = false
        var signature = 1
        for (i in 0 until originalContent.length()) {
            val part = originalContent.optJSONObject(i)
            if (part == null) {
                mergedContent.put(originalContent.opt(i))
                continue
            }
            val type = part.optString("type")
            if (type != "reasoning" && type != "text") {
                mergedContent.put(part)
                continue
            }
            val ord = kindOrdinals[type] ?: 0
            kindOrdinals[type] = ord + 1
            val delta = snapshot[type to ord]
            if (delta == null) {
                mergedContent.put(part)
                continue
            }
            val canonicalText = part.optString("text")
            val partCompleted = messageCompleted || part.optJSONObject("time")?.has("completed") == true
            if (canonicalText.isNotEmpty() && (partCompleted || canonicalText.length >= delta.text.length)) {
                if (partCompleted || canonicalText == delta.text) {
                    prunedKeys.add(PartDeltaKey(sessionId, messageId, type, ord))
                }
                mergedContent.put(part)
                continue
            }
            if (delta.text.isEmpty() && !delta.ended) {
                mergedContent.put(part)
                continue
            }
            val copyPart = JSONObject()
            part.keys().forEach { k -> copyPart.put(k, part.opt(k)) }
            if (delta.text.isNotEmpty()) copyPart.put("text", delta.text)
            if (delta.ended && !partCompleted) {
                val copyTime = JSONObject()
                part.optJSONObject("time")?.let { t -> t.keys().forEach { k -> copyTime.put(k, t.opt(k)) } }
                copyTime.put("completed", 1L)
                copyPart.put("time", copyTime)
            }
            mergedContent.put(copyPart)
            changed = true
            signature = 31 * signature + type.hashCode()
            signature = 31 * signature + ord
            signature = 31 * signature + delta.text.length
            signature = 31 * signature + delta.text.hashCode()
            signature = 31 * signature + if (delta.ended) 1 else 0
        }
        for ((pair, delta) in snapshot) {
            val (kind, ord) = pair
            val seen = kindOrdinals[kind] ?: 0
            if (ord >= seen && delta.text.isNotEmpty()) {
                val synth = JSONObject().put("type", kind).put("text", delta.text)
                if (delta.ended) synth.put("time", JSONObject().put("completed", 1L))
                mergedContent.put(synth)
                kindOrdinals[kind] = ord + 1
                changed = true
                signature = 31 * signature + kind.hashCode()
                signature = 31 * signature + ord
                signature = 31 * signature + delta.text.length
                signature = 31 * signature + delta.text.hashCode()
                signature = 31 * signature + if (delta.ended) 1 else 0
            }
        }
        if (prunedKeys.isNotEmpty()) {
            synchronized(deltaLock) { prunedKeys.forEach(liveDeltas::remove) }
        }
        if (!changed) return raw to 0
        val copyMessage = JSONObject()
        raw.keys().forEach { k -> if (k != "content") copyMessage.put(k, raw.opt(k)) }
        copyMessage.put("content", mergedContent)
        return copyMessage to (if (signature == 0) 1 else signature)
    }
    private fun run(block: suspend () -> Unit) {
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e is IOException && e !is OpenCodeRejected) synchronizer?.invalidate(all = true)
                view = view.copy(error = e.message ?: tr("OpenCode 操作失败", "OpenCode operation failed"))
            }
            finally { publish() }
        }
    }
    suspend fun connect() {
        val previous = job
        close(); val attempt = lifecycle
        previous.join()
        if (attempt != lifecycle) throw CancellationException(tr("界面连接已取消", "UI connection cancelled"))
        job = SupervisorJob(); scope = CoroutineScope(job + Dispatchers.Main.immediate)
        view = view.copy(loading = true, connected = false, canOpenTui = false, error = null, connection = tr("连接 OpenCode…", "Connecting to OpenCode…"))
        publish()
        withContext(scope.coroutineContext) {
            try {
                val target = runtime.connectOpenCode(record.environment); endpoint = target
                val api = OpenCodeClient(target.url, target.authToken!!); client = api
                if (record.threadId == null) {
                    record.threadId = "ses_" + UUID.randomUUID().toString().replace("-", "")
                    record.creationUnknown = true; persist()
                    try {
                        api.call("/api/session", "POST", obj("id" to record.threadId, "title" to record.initialNativeTitle.takeUnless { record.useNativeTitle },
                            "location" to obj("directory" to record.cwd)))
                        record.creationUnknown = false; record.needsName = false; persist()
                    } catch (e: OpenCodeRejected) {
                        if (e.status in listOf(400, 401, 403, 404, 422)) {
                            record.threadId = null; record.creationUnknown = false; persist()
                        }
                        throw e
                    }
                }
                bindBrowser(record.threadId!!)
                var initialSubscribe = true
                suspend fun rebindIfBridgeDead(failed: OpenCodeClient): OpenCodeClient {
                    val active = client
                    if (active != null && active !== failed) return active
                    if (active != null) {
                        val reachable = try { active.probe("/api/info", 3_000L); true }
                        catch (e: CancellationException) { throw e }
                        catch (_: OpenCodeRejected) { true }
                        catch (_: IOException) { false }
                        if (reachable) return active
                    }
                    val target = runtime.connectOpenCode(record.environment)
                    if (lifecycle != attempt) throw CancellationException(tr("界面连接已取消", "UI connection cancelled"))
                    client?.close()
                    catalogInitialized = false
                    return OpenCodeClient(target.url, target.authToken!!).also { endpoint = target; client = it }
                }
                synchronizer = OpenCodeSync(scope, streamConnected = { client?.connected == true },
                    subscribe = { disconnected ->
                        val active = if (initialSubscribe) { initialSubscribe = false; checkNotNull(client) }
                            else rebindIfBridgeDead(client ?: api)
                        active.subscribe(scope, { event -> scope.launch {
                            if (client === active) invalidate(event)
                        } }, disconnected)
                    },
                    refresh = { all, ids, catalog ->
                        val active = checkNotNull(client)
                        try {
                            if (catalog) loadModels()
                            refresh(all, ids)
                            publish()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (e is IOException && e !is OpenCodeRejected) {
                                try { rebindIfBridgeDead(active) }
                                catch (ce: CancellationException) { throw ce }
                                catch (_: Exception) { }
                            }
                            throw e
                        }
                    },
                    changed = { state ->
                        if (!state.connected || state.recovering) clearLiveDeltas()
                        if (state.connected && !view.connected) childReads.values.forEach { it.full = true }
                        val error = if (view.error == null || view.error == connectionNotice) state.notice else view.error
                        connectionNotice = state.notice
                        view = view.copy(connected = state.connected, canOpenTui = state.connected, loading = false,
                            connection = if (state.connected) tr("已连接", "Connected") else if (state.recovering) tr("连接中断 · 正在自动同步", "Disconnected · Auto-resyncing") else tr("连接中断 · 请检查配置后重连", "Disconnected · Check config & reconnect"),
                            error = error)
                        if (state.connected && !state.recovering) reconcileUnconfirmedIdle()
                        publish()
                    }).also { it.start() }
            } catch (e: CancellationException) {
                client?.close(); client = null; job.cancel(); throw e
            } catch (e: Exception) {
                view = view.copy(connected = false, loading = false, canOpenTui = false, error = e.message, connection = tr("未连接", "Disconnected"))
                client?.close(); client = null; job.cancel(); publish(); throw e
            }
        }
    }
    private suspend fun loadModels() {
        nativeModels = client!!.call("/api/model?location[directory]=" + encode(record.cwd)).getJSONArray("data")
        models = OpenCodeProjection.models(nativeModels)
        loadAgents()
        catalogInitialized = true
    }
    private suspend fun loadAgents() {
        nativeAgents = client!!.call("/api/agent?location[directory]=" + encode(record.cwd)).getJSONArray("data")
    }
    private fun invalidate(event: JSONObject) {
        val type = event.optString("type")
        if (type in listOf("model.updated", "provider.updated")) {
            synchronizer?.invalidate(models = true); return
        }
        val data = event.optJSONObject("data") ?: return
        val sessionId = data.optString("sessionID")
        val messageId = data.stringOrNull("assistantMessageID") ?: data.stringOrNull("messageID")
        val canOverlayDeltas = view.connected && !recovering
        if (sessionId != record.threadId) {
            val childId = sessionId
            childReads[childId]?.let { read ->
                when {
                    canOverlayDeltas && messageId != null && (type == "session.reasoning.started" || type == "session.text.started") -> {
                        val kind = if (type.startsWith("session.reasoning.")) "reasoning" else "text"
                        val ordinal = data.optInt("ordinal", 0)
                        synchronized(deltaLock) { liveDeltas.remove(PartDeltaKey(childId, messageId, kind, ordinal)) }
                        read.dirty.add(messageId)
                    }
                    canOverlayDeltas && messageId != null && (type == "session.reasoning.delta" || type == "session.text.delta") && data.has("delta") -> {
                        val kind = if (type.startsWith("session.reasoning.")) "reasoning" else "text"
                        val ordinal = data.optInt("ordinal", 0)
                        val piece = data.optString("delta")
                        synchronized(deltaLock) {
                            val state = liveDeltas.getOrPut(PartDeltaKey(childId, messageId, kind, ordinal)) { PartDeltaState() }
                            if (state.ended) { state.buffer.setLength(0); state.ended = false }
                            state.buffer.append(piece)
                        }
                        if (messageId !in read.messages) read.dirty.add(messageId)
                    }
                    canOverlayDeltas && messageId != null && (type == "session.reasoning.ended" || type == "session.text.ended") -> {
                        val kind = if (type.startsWith("session.reasoning.")) "reasoning" else "text"
                        val ordinal = data.optInt("ordinal", 0)
                        synchronized(deltaLock) {
                            val state = liveDeltas.getOrPut(PartDeltaKey(childId, messageId, kind, ordinal)) { PartDeltaState() }
                            if (data.has("text")) { state.buffer.setLength(0); state.buffer.append(data.optString("text")) }
                            state.ended = true
                        }
                        read.dirty.add(messageId)
                    }
                    messageId != null && (type.endsWith(".delta") || type == "session.tool.progress" || type == "session.step.streamed" ||
                        type == "session.tool.input.started" || type == "session.tool.input.ended" ||
                        type == "session.tool.called" || type == "session.tool.success" || type == "session.tool.error") -> read.dirty.add(messageId)
                    else -> read.full = true
                }
                childChanged(childId)
            }
            if (OpenCodeProjection.childLifecycleEvent(event, view.subAgents.map { it.id }.toSet(), record.threadId)) {
                if (childId.isNotBlank()) dirtyChildren.add(childId)
                synchronizer?.invalidate(all = true)
            }
            return
        }
        if (canOverlayDeltas && messageId != null) {
            when (type) {
                "session.reasoning.started", "session.text.started" -> {
                    val kind = if (type.startsWith("session.reasoning.")) "reasoning" else "text"
                    val ordinal = data.optInt("ordinal", 0)
                    synchronized(deltaLock) { liveDeltas.remove(PartDeltaKey(sessionId, messageId, kind, ordinal)) }
                    synchronizer?.invalidate(ids = listOf(messageId))
                    return
                }
                "session.reasoning.delta", "session.text.delta" -> if (data.has("delta")) {
                    val kind = if (type.startsWith("session.reasoning.")) "reasoning" else "text"
                    val ordinal = data.optInt("ordinal", 0)
                    val piece = data.optString("delta")
                    val known = synchronized(deltaLock) {
                        val state = liveDeltas.getOrPut(PartDeltaKey(sessionId, messageId, kind, ordinal)) { PartDeltaState() }
                        if (state.ended) { state.buffer.setLength(0); state.ended = false }
                        state.buffer.append(piece)
                        messageId in knownHistoryIds
                    }
                    if (known) scheduleDeltaPublish(messageId)
                    else synchronizer?.invalidate(ids = listOf(messageId))
                    return
                }
                "session.reasoning.ended", "session.text.ended" -> {
                    val kind = if (type.startsWith("session.reasoning.")) "reasoning" else "text"
                    val ordinal = data.optInt("ordinal", 0)
                    val known = synchronized(deltaLock) {
                        val state = liveDeltas.getOrPut(PartDeltaKey(sessionId, messageId, kind, ordinal)) { PartDeltaState() }
                        if (data.has("text")) { state.buffer.setLength(0); state.buffer.append(data.optString("text")) }
                        state.ended = true
                        messageId in knownHistoryIds
                    }
                    if (known) scheduleDeltaPublish(messageId)
                    synchronizer?.invalidate(ids = listOf(messageId))
                    return
                }
            }
        }
        if (type.endsWith(".delta") || type == "session.tool.progress" || type == "session.step.streamed" ||
            type == "session.tool.input.started" || type == "session.tool.input.ended") {
            if (messageId != null) synchronizer?.invalidate(ids = listOf(messageId))
            else synchronizer?.invalidate(all = true)
            return
        }
        if (type in listOf("session.tool.called", "session.tool.success", "session.tool.error") &&
            messageId != null && data.optString("name") != "subagent" &&
            synchronized(deltaLock) { messageId !in subAgentToolMessageIds }) {
            synchronizer?.invalidate(ids = listOf(messageId))
            return
        }
        synchronizer?.invalidate(all = true)
    }
    private suspend fun refresh(all: Boolean, ids: List<String> = emptyList()) = sync.withLock {
        val api = client ?: return@withLock
        if (all) {
            val rows = linkedMapOf<String, JSONObject>(); var cursor: String? = null
            val seenCursors = hashSetOf<String>()
            do {
                val page = api.call(path + "/message?limit=100" + if (cursor == null) "&order=asc" else "&cursor=${encode(cursor)}")
                page.getJSONArray("data").objects().forEach { rows[it.getString("id")] = it }
                val nextCursor = page.optJSONObject("cursor")?.stringOrNull("next")
                cursor = if (nextCursor != null && seenCursors.add(nextCursor)) nextCursor else null
            } while (cursor != null)
            history.clear(); history.putAll(rows)
        } else ids.forEach { id ->
            try { history[id] = api.call(path + "/message/" + encode(id)).getJSONObject("data") }
            catch (e: OpenCodeRejected) { if (e.status == 404) history.remove(id) else throw e }
        }
        syncHistoryIndexLocked()
        if (all) {
            if (!catalogInitialized) loadModels() else loadAgents()
            val native = api.call(path).getJSONObject("data")
            check(native.getString("id") == record.threadId) { tr("OpenCode 返回了不同会话", "OpenCode returned a different session") }
            nativeSession = native
            record.creationUnknown = false; record.requireLoaded = false; record.needsName = false
            val nativeUpdated = native.optJSONObject("time")?.optLong("updated", 0L) ?: 0L
            if (record.lastActiveAt < MIN_SESSION_EPOCH_MS && nativeUpdated >= MIN_SESSION_EPOCH_MS) {
                record.lastActiveAt = nativeUpdated
            }
            SessionTitlePolicy.nativeTitle(record, native.stringOrNull("title"))
            native.optJSONObject("model")?.let { record.model = OpenCodeProjection.modelKey(it); record.effort = it.stringOrNull("variant").orEmpty() }
            queue = api.call(path + "/inbox").getJSONArray("data")
            val active = api.call("/api/session/active").getJSONObject("data")
            activeIds = active.keys().asSequence().toSet()
            val running = record.threadId in activeIds
            val previous = view.requests.map { it.id }.toSet()
            val requests = api.call(path + "/permission").getJSONArray("data").objects().map(OpenCodeProjection::permission).toMutableList()
            forms.clear(); api.call(path + "/form").getJSONArray("data").objects().forEach { forms[it.getString("id")] = it; requests += OpenCodeProjection.form(it) }
            answering.retainAll(requests.map { it.id }.toSet())
            val rules = native.optJSONArray("permissions")
            val permissions = OpenCodeProjection.agents(nativeAgents)
            val activeAgent = native.stringOrNull("agent")?.takeIf { it.isNotBlank() }
            val selectedPermission = activeAgent ?: if (permissions.any { it.id == "build" }) "build" else permissions.firstOrNull()?.id.orEmpty()
            val rulesText = if (rules != null && rules.length() > 0) rules.objects().joinToString("\n") { "${it.optString("action")} · ${it.optString("resource")} → ${it.optString("effect")}" } else null
            view = view.copy(activeTurnId = if (running) record.threadId else null, stopping = if (running) view.stopping else false,
                queue = OpenCodeProjection.queue(queue),
                requests = requests.map { it.copy(answering = it.id in answering) },
                permission = selectedPermission, permissions = permissions,
                capabilityNotice = if (permissions.isEmpty()) rulesText else null)
            if (requests.any { it.id !in previous }) approval()
            persist()
        }
        val parentId = record.threadId
        val messages = history.values.toList()
        if (all) {
            val listedChildren = linkedMapOf<String, JSONObject>()
            if (!parentId.isNullOrBlank()) {
                var childCursor: String? = null
                val seenChildCursors = mutableSetOf<String>()
                do {
                    val page = api.call("/api/session?parentID=" + encode(parentId) + "&limit=100" +
                        if (childCursor == null) "&order=asc" else "&cursor=${encode(childCursor)}")
                    page.optJSONArray("data")?.objects().orEmpty().forEach { child ->
                        val cid = child.stringOrNull("id")
                        if (cid != null && child.optString("parentID") == parentId) {
                            listedChildren[cid] = child
                        }
                    }
                    val nextCursor = page.optJSONObject("cursor")?.stringOrNull("next")
                    childCursor = if (nextCursor != null && seenChildCursors.add(nextCursor)) nextCursor else null
                } while (childCursor != null)
            }
            val children = withContext(Dispatchers.Default) { OpenCodeProjection.subAgents(messages, listedChildren, activeIds, parentId) }
            childSnapshots.keys.retainAll(children.map { it.id }.toSet())
            childSnapshots.putAll(listedChildren)
            val changed = dirtyChildren.toSet(); dirtyChildren.removeAll(changed)
            for (child in children.filter { it.id !in listedChildren && (!childHistoryLoaded || it.status in setOf("pending", "running", "unknown") || it.id in activeIds || it.id in changed) }) {
                try {
                    val native = api.call("/api/session/" + encode(child.id)).getJSONObject("data")
                    if (native.optString("id") == child.id && native.optString("parentID") == record.threadId) childSnapshots[child.id] = native
                    else childSnapshots.remove(child.id)
                } catch (e: OpenCodeRejected) {
                    if (e.status == 404) childSnapshots.remove(child.id) else throw e
                }
            }
            childHistoryLoaded = true
        }
        val pendingDeltas = consumePendingDeltaIds()
        val orderedIds = history.keys.toList()
        val sessionId = record.id
        val cwd = record.cwd
        val targetIds = if (all) orderedIds else (ids + pendingDeltas).distinct().filter { history.containsKey(it) }
        val recomputeMetrics = all || view.metrics.isEmpty() || targetIds.any { id -> history[id]?.has("tokens") == true }
        val recomputeSubAgents = all || targetIds.any { id -> hasSubAgentContent(history[id]) }
        val existingCache = HashMap(projectedCache)
        val historyEntries = if (all) {
            history.toMap()
        } else {
            val needed = linkedMapOf<String, JSONObject>()
            for (id in targetIds) history[id]?.let { needed[id] = it }
            for (id in orderedIds) if (id !in existingCache) history[id]?.let { needed[id] = it }
            needed
        }
        val childSnapshotCopy = if (recomputeSubAgents) LinkedHashMap(childSnapshots) else emptyMap()
        val activeIdsCopy = activeIds
        val nativeSessionCopy = nativeSession
        val nativeModelsCopy = nativeModels
        val (updatedEntries, nextMetrics, nextSubAgents) = withContext(Dispatchers.Default) {
            val built = LinkedHashMap<String, CachedProjection>(historyEntries.size)
            for ((id, raw) in historyEntries) {
                val (effective, overlaySig) = if (parentId != null) overlayMessageDeltas(parentId, raw) else (raw to 0)
                val rawStr = raw.toString()
                val prev = existingCache[id]
                built[id] = if (prev != null && prev.rawJson == rawStr && prev.overlaySignature == overlaySig) {
                    prev
                } else {
                    CachedProjection(rawStr, overlaySig, OpenCodeProjection.message(effective).map { it.withMediaContext(sessionId, cwd) })
                }
            }
            val metricsOut = if (recomputeMetrics) OpenCodeProjection.metrics(nativeSessionCopy, messages, nativeModelsCopy) else null
            val subAgentsOut = if (recomputeSubAgents) OpenCodeProjection.subAgents(messages, childSnapshotCopy, activeIdsCopy, parentId) else null
            Triple(built, metricsOut, subAgentsOut)
        }
        projectedCache.keys.retainAll(history.keys)
        projectedCache.putAll(updatedEntries)
        val projectedMessages = flattenProjected(orderedIds)
        if (SessionTitlePolicy.history(record, projectedMessages, ready = all || view.connected)) persist()
        record.submissions.toList().forEach { message ->
            val inQueue = queue.objects().any { it.optString("id") == message.id }
            if (inQueue) message.nativeQueued = true
            if (history.containsKey(message.id) || inQueue) {
                if (history.containsKey(message.id) && !inQueue) SubmissionLedger.consumed(record, message.id)
                SubmissionLedger.accepted(record, id = message.id); persist()
                deliveryNotice?.takeIf { it.first == message.id }?.let { notice ->
                    if (view.error == notice.second) view = view.copy(error = null)
                    deliveryNotice = null
                }
            }
        }
        view = view.copy(
            metrics = nextMetrics ?: view.metrics,
            subAgents = nextSubAgents ?: view.subAgents,
            messages = projectedMessages,
        )
        if (all) reconcileUnconfirmedIdle()
    }
    private fun reconcileUnconfirmedIdle() {
        if (!view.connected || recovering || view.activeTurnId != null) return
        val queuedIds = queue.objects().mapNotNullTo(HashSet()) { it.stringOrNull("id") }
        val rejectedIds = reconcileOpenCodeIdleSubmissions(record, history.keys, queuedIds, admitting)
        if (rejectedIds.isNotEmpty()) {
            deliveryNotice?.takeIf { it.first in rejectedIds }?.let { notice ->
                if (view.error == notice.second) view = view.copy(error = null)
                deliveryNotice = null
            }
            persist()
        }
    }
    fun closeSubAgentRead(id: String) {
        childReads.remove(id)
        synchronized(deltaLock) { liveDeltas.keys.removeAll { it.sessionId == id } }
    }

    suspend fun readSubAgent(id: String): SubAgentDetailView {
        val api = client ?: error(tr("OpenCode 会话尚未连接", "OpenCode session is not connected"))
        val attempt = lifecycle
        val parent = record.threadId ?: error(tr("OpenCode 父会话尚未建立", "OpenCode parent session is not created yet"))
        val agent = view.subAgents.firstOrNull { it.id == id } ?: error(tr("子代理不属于当前父会话", "Sub-agent does not belong to current parent session"))
        val childPath = "/api/session/" + encode(id)
        val native = api.call(childPath).getJSONObject("data")
        check(native.optString("id") == id && native.optString("parentID") == parent) { tr("OpenCode 子代理父会话不匹配", "OpenCode sub-agent parent session mismatch") }
        val read = childReads.getOrPut(id) { ChildRead() }
        val updated = native.optJSONObject("time")?.let { if (it.has("updated")) it.optLong("updated") else null }
        val full = read.full || (updated != read.updated && read.dirty.isEmpty())
        val dirty = read.dirty.toList()
        read.full = false; read.dirty.clear()
        try {
            if (full) {
                val rows = linkedMapOf<String, JSONObject>()
                var cursor: String? = null
                val seenCursors = hashSetOf<String>()
                do {
                    val page = api.call(childPath + "/message?limit=100" + if (cursor == null) "&order=asc" else "&cursor=${encode(cursor)}")
                    page.getJSONArray("data").objects().forEach { rows[it.getString("id")] = it }
                    val nextCursor = page.optJSONObject("cursor")?.stringOrNull("next")
                    cursor = if (nextCursor != null && seenCursors.add(nextCursor)) nextCursor else null
                } while (cursor != null)
                read.messages.clear(); read.messages.putAll(rows)
            } else dirty.forEach { messageId ->
                read.messages[messageId] = api.call(childPath + "/message/" + encode(messageId)).getJSONObject("data")
            }
        } catch (failure: Exception) { read.full = true; throw failure }
        if (lifecycle != attempt || client !== api) throw CancellationException(tr("子代理观察连接已更新", "Sub-agent observer superseded"))
        read.updated = updated
        val ref = native.optJSONObject("model")
        read.model = ref?.let(OpenCodeProjection::modelKey).orEmpty()
        read.effort = ref?.stringOrNull("variant").orEmpty()
        val childCwd = OpenCodeProjection.cwd(native, record.cwd)
        val localSessionId = record.id
        val childRawMessages = read.messages.values.toList()
        val childMessages = withContext(Dispatchers.Default) {
            childRawMessages.flatMap { raw ->
                val (effective, _) = overlayMessageDeltas(id, raw)
                OpenCodeProjection.message(effective)
            }.map { item -> item.withMediaContext(localSessionId, childCwd) }
        }
        return SubAgentDetailView(record.id, agent,
            messages = childMessages,
            modelLabel = models.firstOrNull { it.id == read.model }?.label ?: read.model,
            effort = read.effort, cwd = childCwd)
    }
    fun submit(steer: Boolean) {
        if (!view.connected || view.configuring) return
        run { submitInput(steer) }
    }
    suspend fun submitManaged(input: ManagedSubmission) = submitInput(false, input)
    suspend fun submitFirstDraft() = submitInput(false)
    private suspend fun submitInput(steer: Boolean, external: ManagedSubmission? = null) {
        check(view.connected && !view.configuring) { tr("会话尚未连接或正在更新设置", "Session is disconnected or updating settings") }
        val text = external?.text ?: record.draft
        val images = external?.images ?: record.images.toList()
        val skills = external?.skills ?: record.skills.toList()
        check(text.isNotBlank() || images.isNotEmpty() || skills.isNotEmpty()) { tr("请输入消息", "Enter a message") }
        if (steer) check(view.activeTurnId != null) { tr("当前没有运行任务", "No active turn running") }
        if (images.isNotEmpty()) check(models.find { it.id == record.model }?.supportsImages == true) { tr("当前模型未声明图片能力", "Current model does not support images") }
        val id = external?.id ?: ("msg_" + UUID.randomUUID().toString().replace("-", ""))
        val files = OpenCodeProjection.promptFiles(text, images, record.cwd)
        val body = obj("id" to id, "text" to text, "files" to files, "skills" to JSONArray(skills.map { obj("id" to it.id) }), "delivery" to if (view.activeTurnId != null && view.capabilities.steer) "steer" else "queue")
        val attempt = lifecycle
        val api = checkNotNull(client)
        try {
            SubmissionLedger.admit(
                record = record,
                id = id,
                persist = persistCheckpoint,
                input = external,
                busy = view.activeTurnId != null,
                setSending = { sending ->
                    if (sending) {
                        admitting.add(id)
                        view = view.copy(sending = true, awaitingHandshake = view.activeTurnId == null, error = null)
                        publish()
                    } else {
                        admitting.remove(id)
                        view = view.copy(sending = false, awaitingHandshake = false)
                    }
                },
                isRejected = { it is OpenCodeRejected && it.status in listOf(400, 401, 403, 404, 422) },
                onUnknown = { e ->
                    val detail = if (e is OpenCodeRejected) "HTTP ${e.status}" else e.javaClass.simpleName
                    val cause = if (e is OpenCodeRejected) "HTTP_${e.status}" else e.javaClass.simpleName.replace(Regex("[^A-Za-z0-9_]"), "").take(80)
                    val notice = tr("OpenCode 发送结果待同步（$detail）", "OpenCode send result awaiting sync ($detail)")
                    deliveryNotice = id to notice; view = view.copy(error = notice)
                    runtime.recordOpenCodeDelivery(record.environment, "event=admission-unknown cause=$cause")
                },
            ) {
                val response = api.call(path + "/prompt", "POST", body)
                val result = response.getJSONObject("data")
                check(result.getString("id") == id) { tr("提交标识不匹配", "Submission ID mismatch") }
                SubmissionLedger.accepted(record, id = id); persist()
            }
        } finally {
            if (currentCoroutineContext().isActive && lifecycle == attempt && client === api) synchronizer?.invalidate(all = true)
        }
    }
    fun stop(onFailed: () -> Unit = {}) { if (!view.connected || view.stopping || view.activeTurnId == null) return
        run {
            view = view.copy(stopping = true); publish()
            try { client!!.call(path + "/interrupt", "POST"); synchronizer?.invalidate(all = true) }
            catch (e: Exception) { if (e !is CancellationException && view.activeTurnId != null) onFailed(); throw e }
            finally { view = view.copy(stopping = false) }
        }
    }
    fun configure(model: String, effort: String) {
        if (view.configuring) return
        run {
            check(view.connected && view.activeTurnId == null && view.queue.isEmpty() && !SubmissionLedger.pending(record)) { tr("请在任务与队列结束后修改模型", "Wait for active turn and queue to finish before changing model") }
            val option = models.find { it.id == model } ?: error(tr("模型不在原生列表中", "Model is not in native catalog"))
            check(effort.isEmpty() || effort in option.efforts)
            view = view.copy(configuring = true); publish()
            try { client!!.call(path + "/model", "POST", obj("model" to OpenCodeProjection.modelRef(model, effort))); synchronizer?.invalidate(all = true) }
            finally { view = view.copy(configuring = false) }
        }
    }
    fun configurePermission(agentId: String) {
        if (view.configuring) return
        run {
            check(view.connected && view.activeTurnId == null && view.queue.isEmpty() && !SubmissionLedger.pending(record)) { tr("请在任务与队列结束后修改权限模式", "Wait for active turn and queue to finish before changing permission mode") }
            check(view.permissions.any { it.id == agentId }) { tr("CLI 未允许此权限配置", "Permission mode not allowed by CLI") }
            view = view.copy(configuring = true); publish()
            try { client!!.call(path + "/agent", "POST", obj("agent" to agentId)); synchronizer?.invalidate(all = true) }
            finally { view = view.copy(configuring = false) }
        }
    }
    internal val nativeQueue: List<QueueEntry> get() = view.queue
    internal suspend fun removePending(id: String) {
        check(view.connected && view.queue.any { it.id == id && it.canRemove }) { tr("原生消息已离开可撤回队列", "Message is no longer in the removable native queue") }
        try { client!!.call(path + "/inbox/" + encode(id), "DELETE") }
        finally { synchronizer?.invalidate(all = true) }
    }
    internal suspend fun reconcileQueue() { refresh(all = true); publish() }
    fun queueChange(id: String, steer: Boolean) {
        if (!view.connected || view.queueBusy) return
        run {
            check(queue.objects().any { it.getString("id") == id && it.optString("type") == "user" }) { tr("该消息已离开队列", "Message is no longer in queue") }
            view = view.copy(queueBusy = true); publish()
            try { client!!.call(path + "/inbox/" + encode(id), if (steer) "PATCH" else "DELETE", if (steer) obj("delivery" to "steer") else null); synchronizer?.invalidate(all = true) }
            finally { view = view.copy(queueBusy = false) }
        }
    }
    fun answer(id: String, choice: String, answers: Map<String, List<String>>) {
        if (!view.connected || id in answering || view.requests.none { it.id == id }) return
        run {
            val nativeId = id.substringAfter(':')
            val isForm = id.startsWith("form:")
            val skip = isForm && choice == "skip"
            val body = if (skip) null else if (isForm) OpenCodeProjection.formReply(forms[nativeId] ?: error(tr("提问已结束", "Prompt has already ended")), answers)
                else { check(choice in listOf("once", "always", "reject")); obj("decision" to choice) }
            answering.add(id); view = view.copy(requests = view.requests.map { it.copy(answering = it.id in answering) }); publish()
            val currentSync = synchronizer
            try {
                client!!.call(path + (if (isForm) "/form/" else "/permission/") + encode(nativeId) + (if (skip) "" else "/reply"), if (skip) "DELETE" else "POST", body)
            } catch (e: CancellationException) {
                answering.remove(id)
                view = view.copy(requests = view.requests.map { it.copy(answering = it.id in answering) })
                publish()
                throw e
            } catch (e: Exception) {
                answering.remove(id)
                view = view.copy(requests = view.requests.map { it.copy(answering = it.id in answering) })
                publish()
                throw e
            } finally {
                if (currentCoroutineContext().isActive && synchronizer === currentSync) currentSync?.invalidate(all = true)
            }
        }
    }
    fun rename(title: String) = run { check(view.connected) { tr("请先连接会话", "Connect the session first") }; require(title.isNotBlank()); client!!.call(path, "PATCH", obj("title" to title.trim())); synchronizer?.invalidate(all = true) }
    fun compact() = run { check(view.connected && view.activeTurnId == null && view.queue.isEmpty()); client!!.call(path + "/compact", "POST", obj("delivery" to "queue")); synchronizer?.invalidate(all = true) }
    fun refreshDashboard() { synchronizer?.invalidate(all = true) }
    suspend fun plugins(): PluginCatalog {
        val rows = client!!.call("/api/skill?location[directory]=" + encode(record.cwd)).getJSONArray("data").objects()
        val skills = rows.map { DraftSkill(it.getString("id"), it.getString("name"), it.getString("path")) }
        return try { PluginCatalog(skills, inventory("mcp")) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { PluginCatalog(skills = skills, error = tr("MCP 读取失败：${e.message}", "Failed to load MCP: ${e.message}")) }
    }
    suspend fun inventory(kind: String): List<ReadOnlyEntry> {
        val rows = client!!.call((if (kind == "skill") "/api/skill" else "/api/mcp") + "?location[directory]=" + encode(record.cwd)).getJSONArray("data").objects()
        return rows.map { if (kind == "skill") ReadOnlyEntry(it.getString("name"), it.optString("description"), it.getString("path"))
            else ReadOnlyEntry(it.getString("name"), CodexCapabilities.redact(it.optJSONObject("status")?.toString(2).orEmpty())) }
    }
    suspend fun diff(): String = client!!.call(path + "/diff").getJSONArray("data").objects().joinToString("\n\n") { CodexCapabilities.redact(it.toString(2)) }.take(524288)
    fun dismissError() { synchronizer?.dismissNotice(); connectionNotice = null; view = view.copy(error = null); publish() }
    internal fun onLocaleChanged() {
        val updated = when (view.connection) {
            "已连接", "Connected" -> tr("已连接", "Connected")
            "连接 OpenCode…", "Connecting to OpenCode…" -> tr("连接 OpenCode…", "Connecting to OpenCode…")
            "连接中断 · 正在自动同步", "Disconnected · Auto-resyncing" -> tr("连接中断 · 正在自动同步", "Disconnected · Auto-resyncing")
            "连接中断 · 请检查配置后重连", "Disconnected · Check config & reconnect" -> tr("连接中断 · 请检查配置后重连", "Disconnected · Check config & reconnect")
            "未连接", "Disconnected" -> tr("未连接", "Disconnected")
            "界面已断开 · 后端任务不受影响", "UI detached · Backend tasks unaffected" -> tr("界面已断开 · 后端任务不受影响", "UI detached · Backend tasks unaffected")
            else -> null
        }
        if (updated != null && updated != view.connection) {
            view = view.copy(connection = updated)
            publish()
        }
        if (view.connected) synchronizer?.invalidate(all = true)
    }
    fun setBackgroundIdlePaused(paused: Boolean) {
        synchronizer?.setPaused(paused)
    }
    fun close() {
        lifecycle++
        clearLiveDeltas()
        projectedCache.clear()
        synchronized(deltaLock) {
            knownHistoryIds.clear()
            subAgentToolMessageIds.clear()
        }
        synchronizer?.close(); synchronizer = null; connectionNotice = null
        catalogInitialized = false; nativeAgents = JSONArray()
        client?.close(); client = null; job.cancel(); childReads.clear(); childSnapshots.clear(); dirtyChildren.clear(); childHistoryLoaded = false; activeIds = emptySet(); answering.clear(); admitting.clear()
        view = view.copy(connected = false, canOpenTui = false, loading = false, sending = false, configuring = false, queueBusy = false, requests = emptyList(), connection = tr("界面已断开 · 后端任务不受影响", "UI detached · Backend tasks unaffected"))
    }
    private fun encode(text: String) = URLEncoder.encode(text, "UTF-8")
}

internal fun reconcileOpenCodeIdleSubmissions(
    record: SessionRecord,
    historyIds: Set<String>,
    queuedIds: Set<String>,
    admitting: Set<String>,
): Set<String> {
    val rejected = LinkedHashSet<String>()
    for (message in record.submissions) {
        if (message.state in setOf("pending", "unknown") &&
            !message.queuePresentation &&
            !message.dismissed &&
            message.id !in admitting &&
            message.id !in record.receiptRecoveryRequired &&
            message.id !in historyIds &&
            message.id !in queuedIds
        ) {
            SubmissionLedger.rejected(record, message.id)
            rejected.add(message.id)
        }
    }
    return rejected
}
