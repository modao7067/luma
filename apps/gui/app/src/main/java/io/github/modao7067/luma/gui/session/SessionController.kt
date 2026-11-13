package io.github.modao7067.luma.gui.session

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import io.github.modao7067.luma.gui.GuiProduct
import io.github.modao7067.luma.gui.agent.*
import io.github.modao7067.luma.gui.runtime.AgentRuntime
import io.github.modao7067.luma.gui.runtime.DebianRootMode
import io.github.modao7067.luma.gui.runtime.NativeCliRuntimePolicy
import io.github.modao7067.luma.gui.runtime.NoRootRuntime
import io.github.modao7067.luma.gui.runtime.RootBackgroundNotifications
import io.github.modao7067.luma.gui.runtime.RuntimeEndpoint
import io.github.modao7067.luma.gui.runtime.WatchedRootSession
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class SessionController(private val context: Context) : CockpitActions {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runtime = AgentRuntime(context)
    private val store = SessionStore(context)
    private val diagnostics = DeliveryDiagnostics(java.io.File(context.filesDir, "${io.github.modao7067.luma.gui.GuiProduct.agentRuntimePath(environment)}/delivery-events.jsonl"))
    private val commands = SessionCommandStore(java.io.File(context.filesDir, "session-commands.json"))
    private val preferences = context.getSharedPreferences("cockpit-navigation", Context.MODE_PRIVATE)
    private val blank get() = store.navigation.blank
    private val blankImports = mutableListOf<Attachment>()
    private val browser get() = (context.applicationContext as io.github.modao7067.luma.gui.LumaApplication).browser
    private var inventoryGeneration = 0
    private val sessions = linkedMapOf<String, Live>()
    private val mutableState = MutableStateFlow(CockpitState())
    val state: StateFlow<CockpitState> = mutableState
    private var selected: String? = null
    private var storeError: String? = null
    private var saveJob: Job? = null
    private var fileGeneration = 0
    private var previewGeneration = 0
    private var cliMaintenanceJob: Job? = null
    private class ChildObservation(val parentId: String, val childId: String, val epoch: Int, var detail: SubAgentDetailView) {
        var history: CodexSubAgentHistory? = null
        var job: Job? = null
        var started = false
        val signals = Channel<Unit>(Channel.CONFLATED)
    }
    private var childObservation: ChildObservation? = null
    private var foreground = false
    private var idleGraceMillis = SessionIdlePolicy.graceMillis
    private var openCodeIdleJob: Job? = null
    private var lastOpenCodeEndpoint: RuntimeEndpoint? = null
    private val activityClock = SessionActivityClock()
    internal var notificationSink: SessionNotificationSink = AndroidSessionNotificationSink(context)
    private var lastKeepAliveSummary: String? = null
    private var lastArmedRootSessions: List<WatchedRootSession> = emptyList()
    private var rootWatcherArmed = false
    private var wakeEpoch = 0
    @Suppress("unused")
    private val rootModeObserver = DebianRootMode.observe {
        scope.launch {
            disconnectAll()
            if (!GuiProduct.usesRootExecution(context)) {
                lastKeepAliveSummary = null
            }
            publish()
        }
    }
    private class CachedItemsMap : MutableMap<String, ConversationItem> {
        private val backing = LinkedHashMap<String, ConversationItem>()
        private var cachedList: List<ConversationItem>? = emptyList()
        fun snapshot(): List<ConversationItem> = cachedList ?: backing.values.toList().also { cachedList = it }
        override val size: Int get() = backing.size
        override fun containsKey(key: String) = backing.containsKey(key)
        override fun containsValue(value: ConversationItem) = backing.containsValue(value)
        override fun get(key: String) = backing[key]
        override fun isEmpty() = backing.isEmpty()
        override val entries: MutableSet<MutableMap.MutableEntry<String, ConversationItem>>
            get() { cachedList = null; return backing.entries }
        override val keys: MutableSet<String>
            get() { cachedList = null; return backing.keys }
        override val values: MutableCollection<ConversationItem>
            get() = backing.values
        override fun clear() { if (backing.isNotEmpty()) { backing.clear(); cachedList = emptyList() } }
        override fun put(key: String, value: ConversationItem): ConversationItem? {
            val prev = backing[key]
            if (prev != null && prev == value) return prev
            cachedList = null
            return backing.put(key, value)
        }
        override fun putAll(from: Map<out String, ConversationItem>) {
            if (from.isEmpty()) return
            cachedList = null
            backing.putAll(from)
        }
        override fun remove(key: String): ConversationItem? {
            val removed = backing.remove(key)
            if (removed != null) cachedList = null
            return removed
        }
        fun replaceKeyInPlace(oldKey: String, newKey: String, value: ConversationItem) {
            if (oldKey == newKey || !backing.containsKey(oldKey)) {
                put(newKey, value)
                return
            }
            cachedList = null
            val rebuilt = LinkedHashMap<String, ConversationItem>(backing.size)
            for ((k, v) in backing) {
                when (k) {
                    oldKey -> if (!backing.containsKey(newKey)) rebuilt[newKey] = value
                    newKey -> rebuilt[newKey] = value
                    else -> rebuilt[k] = v
                }
            }
            backing.clear()
            backing.putAll(rebuilt)
        }
    }
    private class Live(val record: SessionRecord) {
        val activity = SessionActivityState(record)
        val notificationTracker = SessionNotificationTracker()
        var userStoppedTurn = false
        var view = SessionView(summary(record), draft = record.draft, uncertainSubmission = record.submissionId != null)
        val items = CachedItemsMap()
        val nativeToClientId = HashMap<String, String>()
        val admitting = mutableSetOf<String>()
        val deliveryItems = linkedMapOf<String, ConversationItem>()
        val subAgents = CodexSubAgents { record.threadId }
        val subAgentMetadataReads = mutableSetOf<String>()
        val requests = linkedMapOf<String, PendingApproval>()
        var rpc: CodexRpc? = null
        var openCode: OpenCodeSession? = null
        var nativeCli: NativeCliSession? = null
        var endpoint: RuntimeEndpoint? = null
        var generation = 0
        var hydrating = false
        val buffered = mutableListOf<JSONObject>()
        val hydrationBoundary = HydrationBoundary()
        var models = emptyList<ModelOption>()
        var queueRaw = emptyList<JSONObject>()
        var queueNeedsExplicitStart = false
        var queueRefresh: Job? = null
        var deltaPublishJob: Job? = null
        var lastCodexPublishNs = 0L
        var deliveryTimer: Job? = null
        var deliveryTimerId: String? = null
        var settingsWaiter: CompletableDeferred<JSONObject>? = null
        var settingsExpected: JSONObject? = null
        var connectJob: Job? = null
        var reconnectJob: Job? = null
        var firstSendJob: Job? = null
        var dispatchJob: Job? = null
        var dismissalJob: Job? = null
        var dismissing = false
        var observing = false
        var dormant = false
        var idleConfirmed = false
        var historyResumable = false
        var idleJob: Job? = null
        var releasing = false
        var reconnectDelay = 1000L
        var dispatchingLocal = false
        var dispatchStorageBlocked = false
        var takingBack = false
        var firstSendPending = false
        var firstSendScheduled = false
        var tokens = emptyList<Metric>()
        var limits = emptyList<Metric>()
        var rolloutPath: String? = null
        var rawScopedSource: List<ConversationItem>? = null
        var rawScopedSessionId: String? = null
        var rawScopedCwd: String? = null
        var rawScopedThreadId: String? = null
        var cachedScopedMessages: List<ConversationItem> = emptyList()
        companion object {
            fun summary(r: SessionRecord) = SessionSummary(
                r.id, r.title, r.environment, r.cwd, r.model,
                threadId = r.threadId, cli = r.cli,
                lastActiveAt = r.lastActiveAt, hasUnreadDot = r.unread,
            )
        }
    }
    init {
        context.getSystemService(NotificationManager::class.java)?.let {
            it.deleteNotificationChannel("luma-agent-connection")
            it.deleteNotificationChannel("luma-agent-approval")
        }
        try { store.load().forEach { activityClock.seed(it.lastActiveAt); sessions[it.id] = Live(it) } }
        catch (failure: Exception) { storeError = "无法读取会话索引，原文件已保留：${failure.message}" }
        selected = restoredSelection(store.navigation, sessions.values.map { it.record }, preferences.getString("session", null), environment)
        store.navigation.initialized = true
        store.navigation.selectedId = selected
        publish()
        current()?.let { live -> startObservation(live) }
        val managedStates = commands.entries.values.groupBy({ it.sessionId }, { it.state })
        sessions.values.filter {
            it !== current() && it.record.environment == environment &&
                SessionIdlePolicy.restoreObservation(it.record, managedStates[it.record.id].orEmpty())
        }.forEach { live -> startObservation(live) }
    }
    private fun scopeMedia(s: Live, item: ConversationItem) = item.withMediaContext(s.record.id, s.record.cwd, s.record.threadId)
    private fun scopeSessionMessages(s: Live, messages: List<ConversationItem>): List<ConversationItem> {
        if (messages === s.rawScopedSource &&
            s.record.id == s.rawScopedSessionId &&
            s.record.cwd == s.rawScopedCwd &&
            s.record.threadId == s.rawScopedThreadId
        ) {
            return s.cachedScopedMessages
        }
        var changed = false
        val scoped = ArrayList<ConversationItem>(messages.size)
        for (item in messages) {
            val mapped = scopeMedia(s, item)
            if (mapped !== item) changed = true
            scoped.add(mapped)
        }
        val result = if (changed) scoped else messages
        s.rawScopedSource = messages
        s.rawScopedSessionId = s.record.id
        s.rawScopedCwd = s.record.cwd
        s.rawScopedThreadId = s.record.threadId
        s.cachedScopedMessages = result
        return result
    }
    private fun mediaOwner(resource: MediaResource): Pair<Live?, MediaResource> {
        val owner = if (resource.sessionId == null) current() else sessions[resource.sessionId]
        check(resource.sessionId == null || owner != null) { "来源会话已关闭，请重新打开附件" }
        return owner to resource.copy(sessionId = resource.sessionId ?: owner?.record?.id,
            cwd = resource.cwd ?: owner?.record?.cwd ?: blank.cwd)
    }
    private suspend fun dereferenceMedia(owner: Live?, resource: MediaResource): MediaResource {
        val params = MediaProjection.mcpParams(resource.source) ?: return resource
        val connection = owner?.rpc ?: error("请重连来源会话后查看 MCP 资源")
        if (!params.has("threadId")) params.put("threadId", owner.record.threadId ?: error("来源会话尚未建立"))
        val result = connection.request("mcpServer/resource/read", params)
        val uri = params.getString("uri")
        val content = result.optJSONArray("contents")?.objects().orEmpty().firstOrNull { it.optString("uri") == uri }
            ?: error("MCP 服务未返回所选资源")
        val embedded = MediaProjection.blocks(obj("type" to "resource", "resource" to content)).singleOrNull()
            ?: error("MCP 资源没有可读取内容")
        check(embedded.source != uri) { "MCP 资源未提供文件内容" }
        return resource.copy(source = embedded.source, mimeType = embedded.mimeType.ifBlank { resource.mimeType })
    }
    override suspend fun inspectMedia(resource: MediaResource): MediaResource {
        val (owner, captured) = withContext(Dispatchers.Main.immediate) { mediaOwner(resource) }
        if (captured.sizeBytes != null) return captured
        return runtime.inspectMedia(owner?.record?.environment ?: environment, captured)
    }
    override suspend fun resolveMedia(resource: MediaResource): ResolvedMedia = resolveMedia(resource, forceRefresh = false)
    override suspend fun resolveMedia(resource: MediaResource, forceRefresh: Boolean): ResolvedMedia {
        val (owner, captured) = withContext(Dispatchers.Main.immediate) { mediaOwner(resource) }
        return runtime.resolveMedia(owner?.record?.environment ?: environment, dereferenceMedia(owner, captured), forceRefresh)
    }
    override suspend fun openMediaExternally(resource: MediaResource) = openMediaExternalInternal(resource, chooseApp = false)
    override suspend fun openMediaWithChooser(resource: MediaResource) = openMediaExternalInternal(resource, chooseApp = true)
    private suspend fun openMediaExternalInternal(resource: MediaResource, chooseApp: Boolean) {
        val (owner, captured) = withContext(Dispatchers.Main.immediate) { mediaOwner(resource) }
        val env = owner?.record?.environment ?: environment
        val inspected = try { runtime.inspectMedia(env, captured) } catch (_: Exception) { captured }
        if (inspected.mimeType == "inode/directory") {
            val guestPath = try { io.github.modao7067.luma.gui.runtime.MediaSourcePolicy.guestPath(captured.source, captured.cwd ?: owner?.record?.cwd ?: "/workspace") } catch (_: Exception) { captured.source }
            withContext(Dispatchers.Main.immediate) {
                closePreview()
                browseFiles(guestPath)
            }
            return
        }
        runtime.openMediaExternally(env, dereferenceMedia(owner, captured), chooseApp = chooseApp)
    }
    override suspend fun saveMedia(resource: MediaResource): String {
        val (owner, captured) = withContext(Dispatchers.Main.immediate) { mediaOwner(resource) }
        return runtime.saveMedia(owner?.record?.environment ?: environment, dereferenceMedia(owner, captured))
    }
    private fun persist() {
        check(storeError == null) { storeError!! }
        store.navigation.selectedId = selected
        store.save(sessions.values.map { it.record })
        sessions.values.forEach { it.dispatchStorageBlocked = false }
    }
    private fun persistDurable() {
        check(storeError == null) { storeError!! }
        store.navigation.selectedId = selected
        store.saveDurable(sessions.values.map { it.record })
        sessions.values.forEach { it.dispatchStorageBlocked = false }
    }
    private fun canDispatchLocal(s: Live) = !s.releasing && s.observing && s.view.connected && !s.view.loading && !s.view.configuring && !s.dispatchStorageBlocked &&
        (s.record.cli !in CliCatalog.bridged || s.nativeCli?.canDispatchLocal == true)
    private fun cliServiceUpdateImpact(s: Live): String {
        val task = NativeCliRuntimePolicy.task(s.record.cli)
        val clis = CliCatalog.ids.filter { it in CliCatalog.bridged && NativeCliRuntimePolicy.task(it) == task }
        val running = sessions.values.filter { it.record.environment == s.record.environment && it.record.cli in clis &&
            it.view.connected && !it.view.loading && it.view.activeTurnId != null }.map { it.record.cli }
        return "更新目标：${s.record.environment} · $task。\n" + NativeCliStatePolicy.serviceUpdateImpact(clis, running)
    }
    private fun publish() {
        var consumptionChanged = false
        var observationChanged = false
        var activityChanged = false
        sessions.values.forEach { s ->
            val returned = s.record.submissions.firstOrNull { HostQueueTakeback.ready(it) && it.takebackDraftRevision == s.record.draftRevision }
            if (returned != null && !s.dispatchStorageBlocked && !s.firstSendPending && !s.view.attachments.any { it.busy } &&
                s.record.draft.isEmpty() && s.record.images.isEmpty() && s.record.skills.isEmpty()) {
                try { SubmissionLedger.restoreLocalToComposer(s.record, returned.id, ::persistDurable) }
                catch (failure: SubmissionPersistenceFailure) { s.dispatchStorageBlocked = true; s.view = s.view.copy(error = "取回内容已保留，但无法保存到输入框；请稍后点击编辑") }
            }
            val consumedBefore = s.record.submissions.count { it.nativeConsumed }
            if (s.firstSendPending && !s.view.loading && !s.view.connected && s.view.error != null) s.firstSendPending = false
            if (s.firstSendPending && s.observing && s.view.connected && !s.view.loading && s.connectJob?.isActive != true && !s.firstSendScheduled) {
                s.firstSendScheduled = true
                val epoch = s.generation
                s.firstSendJob = scope.launch {
                    yield()
                    try {
                        if (sessions[s.record.id] !== s || !s.firstSendPending || !s.observing || s.generation != epoch || !s.view.connected) return@launch
                        s.firstSendPending = false
                        when {
                            s.record.cli in CliCatalog.bridged -> s.nativeCli!!.submitFirstDraft()
                            s.record.cli == "OpenCode" -> s.openCode!!.submitFirstDraft()
                            else -> submitCodex(s, false)
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { s.view = s.view.copy(error = e.message ?: "首次发送失败，输入已保留") }
                    finally {
                        if (s.firstSendJob === currentCoroutineContext()[Job]) { s.firstSendScheduled = false; s.firstSendJob = null }
                        publish()
                    }
                }
            }
            if (s.view.connected && s.observing) {
                if (!s.view.loading) s.reconnectDelay = 1000L
                if (canDispatchLocal(s) && s.connectJob?.isActive != true && !s.dispatchingLocal && s.record.submissions.any(SubmissionLedger::isLocal)) {
                    s.dispatchingLocal = true
                    val epoch = s.generation
                    s.dispatchJob = scope.launch {
                        yield()
                        try {
                            for (id in s.record.submissions.filter(SubmissionLedger::isLocal).map { it.id }) {
                                if (s.generation != epoch || !canDispatchLocal(s)) break
                                val message = SubmissionLedger.find(s.record, id)?.takeIf(SubmissionLedger::isLocal) ?: continue
                                if (message.settings.any { (key, value) -> settings(s)[key] != value }) {
                                    SubmissionLedger.rejectLocal(s.record, id, ::persistDurable)
                                    s.view = s.view.copy(error = "待发消息的会话设置已改变"); continue
                                }
                                val input = ManagedSubmission(message.id, message.text, message.images, message.skills, true)
                                try {
                                    when {
                                        s.record.cli in CliCatalog.bridged -> s.nativeCli!!.submitManaged(input)
                                        s.record.cli == "OpenCode" -> s.openCode!!.submitManaged(input)
                                        else -> submitCodex(s, false, input)
                                    }
                                } catch (e: CancellationException) { throw e }
                                catch (e: SubmissionPersistenceFailure) { throw e }
                                catch (e: Exception) {
                                    if (SubmissionLedger.find(s.record, id)?.let(SubmissionLedger::isLocal) == true) SubmissionLedger.rejectLocal(s.record, id, ::persistDurable)
                                    else throw e
                                    s.view = s.view.copy(error = e.message)
                                }
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            s.dispatchStorageBlocked = true
                            s.view = s.view.copy(error = e.message ?: "待发未能保存，消息仍保留")
                        } finally {
                            if (s.dispatchJob === currentCoroutineContext()[Job]) { s.dispatchingLocal = false; s.dispatchJob = null }
                            publish()
                        }
                    }
                }
            } else if (!s.releasing && s.observing && !s.view.loading && s.record.threadId != null && s.nativeCli?.retryable != false && s.reconnectJob?.isActive != true && s.connectJob?.isActive != true && s.openCode?.recovering != true) {
                s.reconnectJob = scope.launch {
                    val intent = s.generation
                    delay(s.reconnectDelay)
                    s.reconnectDelay = (s.reconnectDelay * 2).coerceAtMost(30_000L)
                    try { if (sessions[s.record.id] === s && s.observing && s.generation == intent && !s.view.connected && !s.view.loading) connect(s) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { if (s.observing && s.connectJob === currentCoroutineContext()[Job]) s.view = s.view.copy(loading = false, error = s.view.error ?: e.message) }
                    finally {
                        if (s.connectJob === currentCoroutineContext()[Job]) s.connectJob = null
                        if (s.reconnectJob === currentCoroutineContext()[Job]) s.reconnectJob = null
                        publish()
                    }
                }
            }
            scheduleDismissals(s)
            settleCommands(s)
            val codexRequests = if (s.record.cli == "Codex") codexRequests(s) else emptyList()
            val effectiveRequests = if (s.record.cli != "Codex") s.view.requests else codexRequests
            val effectiveMessages = if (s.record.cli != "Codex") s.view.messages else s.items.snapshot()
            val viewingInForeground = foreground && selected == s.record.id && childObservation?.parentId != s.record.id
            if (s.activity.reconcile(
                    s.record, s.view.connected, s.view.loading, s.hydrating,
                    s.view.activeTurnId, effectiveMessages, effectiveRequests, viewingInForeground, activityClock,
                )
            ) activityChanged = true
            when (val decision = s.notificationTracker.reconcile(
                connected = s.view.connected,
                loading = s.view.loading,
                hydrating = s.hydrating,
                turnActive = s.view.activeTurnId != null,
                turnCompleted = s.activity.lastTurnCompleted,
                consumeUserStop = { val stopped = s.userStoppedTurn; s.userStoppedTurn = false; stopped },
                requests = effectiveRequests,
                viewingInForeground = viewingInForeground,
            )) {
                is SessionNotificationDecision.Post -> notificationSink.post(s.record.id, s.record.title, s.record.cli, decision.kind)
                SessionNotificationDecision.Cancel -> notificationSink.cancel(s.record.id)
                SessionNotificationDecision.None -> {}
            }
            diagnostics.observe(s.record.id, s.view.error, s.record.submissions, s.record.queueDismissals)
            if (s.dormant) s.view = s.view.copy(summary = Live.summary(s.record).copy(status = "休眠"), draft = s.record.draft)
            else s.view = SubmissionLedger.project(s.record, s.view.copy(preparingFirstSend = s.firstSendPending,
                cliServiceUpdateImpact = if (s.view.cliServiceUpdateRequired || s.view.cliServiceUpdateAvailable) cliServiceUpdateImpact(s) else "",
                summary = Live.summary(s.record).copy(
                modelLabel = s.models.find { it.id == s.record.model }?.label.orEmpty(),
                hasUnreadDot = s.record.unread || effectiveRequests.isNotEmpty(),
                status = when {
                (if (s.record.cli != "Codex") s.view.requests.isNotEmpty() else s.requests.isNotEmpty()) -> "等待处理"
                s.view.activeTurnId != null -> if (s.view.connected) "执行中" else "执行状态待同步"
                s.view.connected && codexRequests.isNotEmpty() -> "待回答"
                s.view.connected -> "空闲"
                s.dormant -> "休眠"
                else -> "未连接"
            }), effort = s.record.effort,
                messages = effectiveMessages,
                subAgents = if (s.record.cli != "Codex") s.view.subAgents else s.subAgents.values(), requests = effectiveRequests,
                skills = s.record.skills.toList(),
                queueRecovery = s.record.queueTransfer?.let { raw -> val t = JSONObject(raw); QueueRecovery(CodexProjection.inputText(t.getJSONArray("input")), t.getString("phase")) },
                attachments = s.record.images.map { Attachment(it.id, it.uri, it.name) } + s.view.attachments.filter { it.busy }), s.deliveryItems).let { view -> view.copy(messages = scopeSessionMessages(s, view.messages)) }
            val observation = SessionIdlePolicy.observationIntent(s.record, s.view, when {
                s.record.cli == "Codex" -> s.idleConfirmed
                s.record.cli == "OpenCode" -> s.openCode != null
                else -> s.nativeCli?.idleConfirmed == true
            })
            if (s.record.observationRequired != observation) {
                s.record.observationRequired = observation
                observationChanged = true
            }
            scheduleIdleRelease(s)
            consumptionChanged = consumptionChanged || s.record.submissions.count { it.nativeConsumed } != consumedBefore
        }
        if (consumptionChanged || observationChanged || activityChanged) try { persist() } catch (failure: Exception) {
            current()?.let { it.view = it.view.copy(error = "无法保存会话同步状态：${failure.message}") }
        }
        if (!GuiProduct.usesRootExecution(context)) {
            var runningCount = 0
            var waitingCount = 0
            for (s in sessions.values) {
                if (s.record.environment != environment) continue
                if (s.view.requests.isNotEmpty()) {
                    waitingCount++
                } else if (s.view.connected && s.view.activeTurnId != null) {
                    runningCount++
                }
            }
            val summaryText = SessionNotifications.keepAliveSummaryText(runningCount, waitingCount)
            if (summaryText != lastKeepAliveSummary) {
                lastKeepAliveSummary = summaryText
                NoRootRuntime.updateKeepAliveStatus(summaryText)
            }
        } else if (!foreground) {
            syncRootBackgroundWatcher(forceSpawn = false)
        }
        mutableState.value = mutableState.value.copy(sessions = sortSessionsByActivity(sessions.values.filter { it.record.environment == environment }.map { it.view.summary }),
            subAgentDetail = projectSubAgentDetail(),
            selected = sessions[selected]?.view, models = sessions[selected]?.models.orEmpty(),
            blank = BlankSessionView(blank.key, blank.cli, blank.cwd, blank.text,
                blank.images.map { Attachment(it.id, it.uri, it.name) } + blankImports, blank.skills.toList()),
            error = storeError ?: mutableState.value.error)
    }
    private fun canReleaseIdle(s: Live, backgroundOnly: Boolean = true): Boolean =
        (!backgroundOnly || selected != s.record.id) && s.observing && !s.dormant &&
            s.connectJob?.isActive != true && !s.firstSendPending && !s.dispatchingLocal && !s.dismissing &&
            childObservation?.parentId != s.record.id && SessionIdlePolicy.eligible(s.record, s.view) &&
            when {
                s.record.cli in CliCatalog.bridged -> s.nativeCli?.releasable == true
                s.record.cli == "Codex" -> s.idleConfirmed && s.historyResumable
                s.record.cli == "OpenCode" -> s.openCode != null
                else -> false
            }

    private fun scheduleIdleRelease(s: Live) {
        if (s.releasing) return
        if (!canReleaseIdle(s)) { s.idleJob?.cancel(); s.idleJob = null; return }
        if (s.idleJob?.isActive == true) return
        val epoch = s.generation
        s.idleJob = scope.launch {
            delay(idleGraceMillis)
            try {
                if (sessions[s.record.id] !== s || s.generation != epoch || !canReleaseIdle(s)) return@launch
                s.releasing = true
                when {
                    s.record.cli in CliCatalog.bridged -> s.nativeCli!!.releaseIdle()
                    s.record.cli == "Codex" && s.record.externalSocket == null -> {
                        val rpc = s.rpc ?: return@launch
                        val thread = rpc.request("thread/read", obj("threadId" to s.record.threadId, "includeTurns" to false)).getJSONObject("thread")
                        if (thread.getString("id") != s.record.threadId || thread.optJSONObject("status")?.optString("type") != "idle") return@launch
                        if (!canReleaseIdle(s)) return@launch
                        try { rpc.request("thread/unsubscribe", obj("threadId" to s.record.threadId)) }
                        catch (unsupported: RpcRejected) { if (unsupported.code != -32601) throw unsupported }
                    }
                }
                if (sessions[s.record.id] !== s || s.generation != epoch) return@launch
                dropIdleCache(s)
                if (SubmissionLedger.pending(s.record)) startObservation(s)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {  }
            finally {
                s.releasing = false
                if (s.idleJob === currentCoroutineContext()[Job]) s.idleJob = null
                publish()
            }
        }
    }

    private fun syncOpenCodeDaemonIdleTimer() {
        val hasActiveOpenCode = sessions.values.any {
            it.record.environment == environment && it.record.cli == "OpenCode" && (it.observing || it.openCode != null)
        }
        if (hasActiveOpenCode) {
            openCodeIdleJob?.cancel()
            openCodeIdleJob = null
            return
        }
        if (openCodeIdleJob?.isActive == true) return
        val knownEndpoint = lastOpenCodeEndpoint
        openCodeIdleJob = scope.launch {
            delay(idleGraceMillis)
            val stillIdle = sessions.values.none {
                it.record.environment == environment && it.record.cli == "OpenCode" && (it.observing || it.openCode != null)
            }
            if (stillIdle) {
                runtime.stopIdleOpenCode(environment, knownEndpoint)
            }
            if (openCodeIdleJob === currentCoroutineContext()[Job]) openCodeIdleJob = null
        }
    }

    private fun dropIdleCache(s: Live) {
        val wasOpenCode = s.record.cli == "OpenCode"
        if (wasOpenCode && s.endpoint != null) lastOpenCodeEndpoint = s.endpoint
        s.observing = false; s.dormant = true; s.idleConfirmed = false; s.generation++
        s.reconnectJob?.cancel(); s.reconnectJob = null
        s.queueRefresh?.cancel(); s.queueRefresh = null
        s.deliveryTimer?.cancel(); s.deliveryTimer = null; s.deliveryTimerId = null
        s.rpc?.close(); s.rpc = null
        s.openCode?.close(); s.openCode = null
        s.nativeCli?.close(); s.nativeCli = null
        s.endpoint = null
        s.hydrating = false
        s.items.clear(); s.nativeToClientId.clear(); s.admitting.clear(); s.deliveryItems.clear(); s.requests.clear(); s.buffered.clear()
        s.subAgents.clear(); s.subAgentMetadataReads.clear(); s.hydrationBoundary.clear()
        s.models = emptyList(); s.queueRaw = emptyList(); s.tokens = emptyList(); s.limits = emptyList(); s.rolloutPath = null
        s.settingsWaiter?.cancel(); s.settingsWaiter = null; s.settingsExpected = null
        s.idleJob = null
        s.view = SessionView(Live.summary(s.record), connection = "休眠", draft = s.record.draft, effort = s.record.effort)
        if (wasOpenCode) syncOpenCodeDaemonIdleTimer()
    }

    private fun persistDelivery(s: Live) {
        try { persist() }
        catch (failure: Exception) { s.view = s.view.copy(error = "无法保存发送状态，输入已保留：${failure.message}") }
    }
    private fun current() = sessions[selected]
    private fun asyncQuestions(s: Live) = CodexAsyncQuestions.pending(s.items.values,
        s.queueRaw.map { CodexProjection.inputText(it.optJSONArray("input") ?: JSONArray()) },
        s.record.submissions.map { CodexAsyncQuestions.Submission(it.text, it.state) })
    private fun codexRequests(s: Live) = s.requests.values.map { it.view() } + asyncQuestions(s)
    private fun launch(s: Live? = current(), block: suspend () -> Unit): Job {
        val epoch = s?.generation
        return scope.launch {
            try { block() }
            catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) {
                val message = failure.message ?: "操作失败，请重试"
                if (s != null) { if (s.generation == epoch) s.view = s.view.copy(error = message, sending = false, loading = false) }
                else mutableState.value = mutableState.value.copy(error = message, creating = false)
            }
            finally { publish() }
        }
    }

    private fun startObservation(s: Live, recoverExternal: Boolean = false, beforeConnect: () -> Unit = {}) {
        val restoreExternal = recoverExternal || s.dormant
        s.dormant = false
        s.observing = true
        val previousIdle = s.idleJob
        previousIdle?.cancel(); s.idleJob = null
        val intent = ++s.generation
        s.reconnectJob?.cancel(); s.reconnectJob = null
        val previous = s.connectJob
        previous?.cancel()
        s.rpc?.close(); s.rpc = null; s.openCode?.close(); s.nativeCli?.close()
        s.requests.clear()
        s.view = s.view.copy(connected = false, loading = true, sending = false, canOpenTui = false, requests = emptyList())
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var prepared = false
            try {
                if (!s.observing || s.generation != intent || sessions[s.record.id] !== s) return@launch
                previous?.join(); previousIdle?.join()
                if (!s.observing || s.generation != intent || sessions[s.record.id] !== s) return@launch
                beforeConnect()
                prepared = true
                connect(s, restoreExternal)
            } catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) {
                if (s.connectJob === currentCoroutineContext()[Job]) {
                    if (!prepared) s.observing = false
                    s.view = s.view.copy(error = s.view.error ?: failure.message ?: "连接失败", sending = false, loading = false)
                }
            } finally {
                if (s.connectJob === currentCoroutineContext()[Job]) s.connectJob = null
                publish()
            }
        }
        s.connectJob = job; job.start()
    }

    internal suspend fun manage(request: JSONObject): JSONObject {
        SessionManagementProtocol.validate(request)
        return when (request.getString("action")) {
            "list" -> obj("ok" to true, "environment" to environment, "clis" to JSONArray(CliCatalog.ids),
                "nativeProtocols" to JSONObject(CliCatalog.nativeProtocols),
                "sessions" to JSONArray(sessions.values.filter { it.record.environment == environment }.map(::managedInfo)))
            "create" -> {
                commands.existing(request)?.let { return commandInfo(it) }
                check(storeError == null) { storeError!! }
                val id = UUID.randomUUID().toString()
                val entry = commands.reserve(request, id)
                try {
                    val s = addManagedSession(request, id, null)
                    commands.update(entry, "starting")
                    startObservation(s)
                    commandInfo(entry)
                } catch (e: Exception) { commands.update(entry, "rejected", e.message); throw e }
            }
            "attach" -> {
                val cli = request.getString("cli"); val nativeId = request.getString("nativeId")
                val externalSocket = request.stringOrNull("externalSocket")
                io.github.modao7067.luma.gui.runtime.ExternalCodexPolicy.validateAttachment(cli, environment, nativeId, externalSocket)
                val pattern = when (cli) { "Codex" -> "[A-Za-z0-9-]+"; "OpenCode" -> "ses[a-zA-Z0-9_-]{1,128}"; else -> null }
                require(pattern == null || nativeId.matches(Regex(pattern))) { "请输入 $cli 原生会话 ID" }
                require(cli !in CliCatalog.bridged || nativeId.matches(Regex("[a-zA-Z0-9_-]{1,128}"))) { "当前 CLI bridge 仅接收原生 session ID，不接收 sessionFile 路径；请使用 CLI 返回的 nativeId" }
                val repair = request.stringOrNull("id")?.let { id ->
                    val target = sessions[id]?.takeIf { it.record.environment == environment } ?: error("attach.id 不在当前 App 中")
                    ManagedSessionPolicy.validateExternalRepair(target.record, cli, nativeId, checkNotNull(externalSocket), target.view.connected, target.view.loading)
                    target
                }
                val existing = sessions.values.firstOrNull { it.record.environment == environment && it.record.cli == cli && it.record.threadId == nativeId && it.record.externalSocket == externalSocket &&
                    (cli != "Antigravity" || (it.record.nativeProtocol ?: "antigravity-acp") == request.getString("nativeProtocol")) }
                val s = existing ?: repair?.also { target ->
                    val previousSocket = target.record.externalSocket; val previousNeedsName = target.record.needsName
                    target.record.externalSocket = externalSocket; target.record.needsName = false
                    try { persist() } catch (error: Exception) {
                        target.record.externalSocket = previousSocket; target.record.needsName = previousNeedsName; throw error
                    }
                } ?: addManagedSession(request, UUID.randomUUID().toString(), nativeId)
                if (existing != null || repair != null) request.optString("title").takeIf { it.isNotBlank() }?.let { renameLocal(s, it) }
                if (!s.view.connected && !s.view.loading && s.openCode?.recovering != true) startObservation(s)
                obj("ok" to true, "session" to managedInfo(s), "reused" to (existing != null || repair != null))
            }
            "send" -> {
                commands.existing(request)?.let { return commandInfo(it) }
                val s = managedTarget(request)
                check(!s.view.configuring && !s.firstSendPending) { "会话正在更新设置" }
                if (s.view.activeTurnId == null) s.userStoppedTurn = false
                val submissionId = if (s.record.cli == "OpenCode") "msg_" + UUID.randomUUID().toString().replace("-", "") else UUID.randomUUID().toString()
                val entry = commands.reserve(request, s.record.id, submissionId)
                try {
                    SubmissionLedger.checkpoint(s.record, submissionId, ::persistDurable) {
                        SubmissionLedger.begin(s.record, submissionId, externalText = request.getString("text"), settings = settings(s), busy = true)
                        SubmissionLedger.state(s.record, submissionId, "local")
                    }
                } catch (e: SubmissionPersistenceFailure) { commands.update(entry, "rejected", e.message); throw e }
                commands.update(entry, "pending")
                if (!s.view.connected && !s.view.loading && !s.observing) startObservation(s)
                publish()
                commandInfo(entry)
            }
            "status" -> if (request.has("requestId")) {
                check(commands.error == null) { commands.error!! }
                commandInfo(commands.entries[request.getString("requestId")] ?: error("requestId 不存在；不会自动执行"))
            } else obj("ok" to true, "session" to managedInfo(managedTarget(request)))
            "read" -> {
                val s = managedTarget(request); val rows = s.view.messages
                val end = if (request.has("before")) rows.indexOfFirst { it.id == request.getString("before") }.also { require(it >= 0) { "before 消息不在当前已同步历史中" } } else rows.size
                val start = (end - request.optInt("limit", 20)).coerceAtLeast(0)
                obj("ok" to true, "session" to managedInfo(s), "messages" to JSONArray(rows.subList(start, end).map { message ->
                    obj("id" to message.id, "role" to message.role, "text" to message.text.take(16384), "detail" to message.detail.take(16384),
                        "truncated" to (message.text.length > 16384 || message.detail.length > 16384), "status" to message.status,
                        "delivery" to message.delivery, "outcome" to message.outcome, "turnId" to message.turnId)
                }), "before" to rows.getOrNull(start)?.id?.takeIf { start > 0 },
                    "requests" to JSONArray(s.view.requests.take(100).map { obj("id" to it.id, "title" to it.title.take(1024), "body" to it.body.take(1024)) }),
                    "queue" to JSONArray(s.view.queue.take(100).map { obj("id" to it.id, "text" to it.text.take(1024), "status" to it.status) }),
                    "agents" to JSONArray(s.view.subAgents.take(100).map { obj("id" to it.id, "name" to it.name, "status" to it.status, "task" to it.task.take(1024), "detail" to it.detail.take(1024)) }))
            }
            "rename" -> {
                val s = managedTarget(request)
                renameLocal(s, request.getString("title"))
                obj("ok" to true, "session" to managedInfo(s))
            }
            else -> error("未知 action")
        }
    }
    private fun managedTarget(request: JSONObject): Live = sessions[request.getString("id")]?.takeIf { it.record.environment == environment }
        ?: error("当前 App 中不存在此本地 id；先用 list 获取目标")
    private fun managedInfo(s: Live): JSONObject = obj("id" to s.record.id, "nativeId" to s.record.threadId, "cli" to s.record.cli,
        "externalSocket" to s.record.externalSocket,
        "nativeProtocol" to (s.record.nativeProtocol ?: "antigravity-acp".takeIf { s.record.cli == "Antigravity" && s.record.threadId != null }),
        "title" to s.record.title, "cwd" to s.record.cwd, "model" to s.record.model, "connected" to s.view.connected,
        "loading" to s.view.loading, "activeTurnId" to s.view.activeTurnId, "status" to s.view.summary.status,
        "approvalCount" to s.view.requests.size, "queueCount" to s.view.queue.size, "error" to s.view.error,
        "creationUnknown" to s.record.creationUnknown)
    private fun addManagedSession(request: JSONObject, id: String, nativeId: String?): Live {
        check(storeError == null) { storeError!! }
        val record = ManagedSessionPolicy.newRecord(id, request.getString("cli"), "", environment, request.getString("cwd"), request.optString("model"), nativeId,
            request.stringOrNull("nativeProtocol"), request.stringOrNull("externalSocket"))
        record.lastActiveAt = activityClock.next()
        request.optString("title").takeIf { it.isNotBlank() }?.let { SessionTitlePolicy.rename(record, it) }
        val s = Live(record); sessions[id] = s
        try { persist() } catch (e: Exception) { sessions.remove(id); throw e }
        publish(); return s
    }
    private fun settleCommands(s: Live) {
        for (entry in commands.entries.values.filter { it.sessionId == s.record.id && it.state !in setOf("accepted", "ready", "rejected") }) {
            val result = if (entry.submissionId != null) when {
                s.record.deliveryResults.containsKey(entry.submissionId) -> when (s.record.deliveryResults[entry.submissionId]) { "" -> "accepted"; "rejected" -> "rejected"; else -> "unknown" }
                SubmissionLedger.find(s.record, entry.submissionId) != null -> when (SubmissionLedger.find(s.record, entry.submissionId)!!.state) {
                    "accepted" -> "accepted"; "rejected", "cancelled" -> "rejected"; "unknown" -> "unknown"; else -> "pending"
                }
                else -> if (entry.state == "pending") "unknown" else entry.state
            } else when {
                s.view.connected && s.record.threadId != null -> "ready"
                s.view.loading -> "starting"
                s.record.creationUnknown -> "unknown"
                s.view.error != null -> "error"
                else -> entry.state
            }
            try { commands.update(entry, result, if (result in setOf("error", "rejected")) s.view.error else null) }
            catch (e: Exception) { s.view = s.view.copy(error = "无法保存命令状态：${e.message}") }
        }
    }
    private fun commandInfo(entry: SessionCommandStore.Entry): JSONObject {
        val s = sessions[entry.sessionId]?.takeIf { it.record.environment == environment }
        if (s != null) settleCommands(s)
        val state = if (entry.state == "reserved") "unknown" else entry.state
        return obj("ok" to true, "requestId" to entry.requestId, "id" to entry.sessionId, "submissionId" to entry.submissionId,
            "state" to state, "accepted" to (state == "accepted"), "error" to entry.error, "session" to s?.let(::managedInfo))
    }
    override fun createCliSession(cli: String, title: String, environment: String, cwd: String, model: String) {
        addSession(title, environment, cwd, model, null, cli)
    }
    override fun attachCliSession(cli: String, title: String, environment: String, cwd: String, threadId: String) {
        if (cli == "Codex") { attachSession(title, environment, cwd, threadId); return }
        val pattern = if (cli == "OpenCode") "ses[a-zA-Z0-9_-]{1,128}" else "[a-zA-Z0-9_-]{1,128}"
        if (cli !in CliCatalog.ids || !threadId.matches(Regex(pattern))) { mutableState.value = mutableState.value.copy(error = "请输入 $cli 原生 session ID"); return }
        addSession(title, environment, cwd, "", threadId, cli)
    }
    override fun createSession(title: String, environment: String, cwd: String, model: String) {
        addSession(title, environment, cwd, model, null)
    }
    override fun attachSession(title: String, environment: String, cwd: String, threadId: String) {
        if (!threadId.matches(Regex("[A-Za-z0-9-]+"))) { mutableState.value = mutableState.value.copy(error = "请输入原 Codex thread ID"); return }
        addSession(title, environment, cwd, "", threadId)
    }
    private fun isEmptySession(s: Live): Boolean =
        !s.hydrating && s.items.isEmpty() && s.deliveryItems.isEmpty() && s.requests.isEmpty() &&
            EmptySessionPolicy.canDiscard(s.record, s.view,
                historyReady = s.record.cli != "DeepSeek Harness" || s.nativeCli?.historyReady == true)

    private fun disposeSession(s: Live, destroyBackend: Boolean = false) {
        val cli = s.record.cli
        val env = s.record.environment
        val sessionId = s.record.id
        val knownEndpoint = s.endpoint ?: s.nativeCli?.endpoint ?: s.openCode?.endpoint
        if (cli == "OpenCode" && knownEndpoint != null) lastOpenCodeEndpoint = knownEndpoint
        s.observing = false
        s.firstSendPending = false
        s.userStoppedTurn = false
        s.notificationTracker.clear()
        notificationSink.cancel(s.record.id)
        s.generation++
        s.connectJob?.cancel(); s.firstSendJob?.cancel(); s.dispatchJob?.cancel(); s.dismissalJob?.cancel()
        s.deliveryTimer?.cancel()
        s.idleJob?.cancel()
        s.reconnectJob?.cancel()
        s.queueRefresh?.cancel()
        s.deltaPublishJob?.cancel(); s.deltaPublishJob = null
        s.rpc?.close()
        s.openCode?.close(); s.openCode = null
        s.nativeCli?.close(); s.nativeCli = null
        sessions.remove(s.record.id)
        if (destroyBackend && cli in CliCatalog.bridged) {
            scope.launch(Dispatchers.IO) {
                runtime.destroyBridgedSessionIfRunning(cli, env, sessionId, knownEndpoint, deleteState = true)
            }
        }
        if (cli == "OpenCode") syncOpenCodeDaemonIdleTimer()
    }

    private fun prunePreviousIfEmpty(nextId: String?): Boolean {
        val previous = selected?.takeIf { it != nextId }?.let(sessions::get) ?: return true
        if (!isEmptySession(previous)) return true
        val previousSelection = store.navigation.selectedId
        try {
            check(storeError == null) { storeError!! }
            store.navigation.selectedId = nextId?.takeIf { it in sessions }
            store.save(sessions.values.filter { it !== previous }.map { it.record })
        } catch (failure: Exception) {
            store.navigation.selectedId = previousSelection
            previous.view = previous.view.copy(error = "无法清理空会话，原会话已保留：${CodexCapabilities.redact(failure.message ?: "保存失败")}")
            publish()
            return false
        }
        disposeSession(previous, destroyBackend = true)
        return true
    }

    private fun addSession(title: String, environment: String, cwd: String, model: String, threadId: String?, cli: String = "Codex") {
        if (storeError != null) return
        if (cli !in CliCatalog.ids || environment != this.environment || !cwd.startsWith('/') || cwd.contains('\u0000')) {
            mutableState.value = mutableState.value.copy(error = "请输入内置环境中的绝对工作目录"); return
        }
        val existing = sessions.values.firstOrNull { it.record.cli == cli && it.record.environment == environment && threadId != null && it.record.threadId == threadId && it.record.externalSocket == null &&
            (cli != "Antigravity" || it.record.nativeProtocol == null || it.record.nativeProtocol == "antigravity-acp") }
        if (existing != null) { openSession(existing.record.id); return }
        val r = ManagedSessionPolicy.newRecord(UUID.randomUUID().toString(), cli, title, environment, cwd, model, threadId)
        r.lastActiveAt = activityClock.next()
        if (!prunePreviousIfEmpty(r.id)) return
        val s = Live(r); sessions[r.id] = s; selected = r.id
        mutableState.value = mutableState.value.copy(error = null, browserOpen = false)
        preferences.edit().putString("session", r.id).apply()
        startObservation(s, beforeConnect = ::persist); publish()
    }
    override fun openSession(id: String) {
        val s = sessions[id]?.takeIf { it.record.environment == environment } ?: return
        if (!prunePreviousIfEmpty(id)) return
        clearSubAgentObservation()
        fileGeneration++; previewGeneration++; inventoryGeneration++
        mutableState.value = mutableState.value.copy(fileBrowser = null, filePreview = null, inventory = null, plugins = PluginCatalog(), browserOpen = false)
        selected = id; preferences.edit().putString("session", id).apply(); saveNavigation(); publish()
        if (!s.view.connected && !s.view.loading && s.openCode?.recovering != true) startObservation(s)
    }
    override fun openSubAgent(parentSessionId: String, agentId: String) {
        val parent = current()?.takeIf { it.record.id == parentSessionId } ?: return
        val agent = parent.view.subAgents.firstOrNull { it.id == agentId } ?: return
        val current = childObservation
        if (current?.parentId == parentSessionId && current.childId == agentId && current.epoch == parent.generation) return
        clearSubAgentObservation()
        childObservation = ChildObservation(parentSessionId, agentId, parent.generation, SubAgentDetailView(parentSessionId, agent))
        publish()
    }
    override fun closeSubAgent() { clearSubAgentObservation(); publish() }
    private fun clearSubAgentObservation() {
        val previous = childObservation; childObservation = null
        previous?.let { sessions[it.parentId]?.openCode?.closeSubAgentRead(it.childId) }
        previous?.job?.cancel()
        previous?.signals?.close()
    }
    private fun ownsChildObservation(observation: ChildObservation, parent: Live) =
        childObservation === observation && selected == observation.parentId && sessions[observation.parentId] === parent && parent.generation == observation.epoch

    private fun projectSubAgentDetail(): SubAgentDetailView? {
        var observation = childObservation ?: return null
        val parent = sessions[observation.parentId]
        if (parent == null || selected != observation.parentId) { clearSubAgentObservation(); return null }
        val agent = parent.view.subAgents.firstOrNull { it.id == observation.childId }
        if (agent == null && !parent.view.loading && !parent.hydrating) { clearSubAgentObservation(); return null }
        if (parent.generation != observation.epoch) {
            observation.job?.cancel(); observation.signals.close()
            observation = ChildObservation(observation.parentId, observation.childId, parent.generation, observation.detail)
            childObservation = observation
        }
        if (agent != null && agent != observation.detail.agent) observation.signals.trySend(Unit)
        val streamedChild = agent != null && ((parent.record.cli == "Antigravity" &&
            parent.record.nativeProtocol in setOf("antigravity-cli-stream-json", "antigravity-agy-acp")) ||
            parent.record.cli == "DeepSeek Harness" || parent.record.cli == "Grok Build")
        observation.detail = if (streamedChild) NativeCliProjection.streamedSubAgentDetail(agent, observation.detail, parent.models)
            else observation.detail.copy(agent = agent ?: observation.detail.agent,
                loading = if (!parent.view.connected) parent.view.loading else observation.detail.loading)
        if (!streamedChild && foreground && !observation.started && parent.view.connected && parent.record.cli in setOf("Codex", "OpenCode")) startSubAgentRead(parent, observation)
        return observation.detail
    }
    private fun startSubAgentRead(parent: Live, observation: ChildObservation) {
        val rpc = parent.rpc
        if (parent.record.cli == "Codex" && rpc == null) return
        if (parent.record.cli == "OpenCode" && parent.openCode == null) return
        observation.started = true
        observation.detail = observation.detail.copy(loading = true)
        if (parent.record.cli == "Codex" && observation.history == null) observation.history = CodexSubAgentHistory(checkNotNull(parent.record.threadId), observation.childId, rpc!!::request,
            { runtime.imagePreviewUri(parent.record.environment, it) })
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                while (foreground && ownsChildObservation(observation, parent) && parent.view.connected) {
                    try {
                        val detail = if (parent.record.cli == "OpenCode") parent.openCode!!.readSubAgent(observation.childId) else {
                            val snapshot = observation.history!!.refresh()
                            if (!foreground || !ownsChildObservation(observation, parent)) return@launch
                            if (!parent.subAgents.metadata(snapshot.thread)) {
                                parent.requests.entries.removeAll { it.value.threadId == observation.childId }
                            }
                            if (snapshot.statusFresh) {
                                val turn = snapshot.lastTurn
                                if (snapshot.thread.optJSONObject("status")?.optString("type") == "active") parent.subAgents.event(obj("method" to "thread/status/changed", "params" to obj("threadId" to observation.childId, "status" to obj("type" to "active"))))
                                else if (turn != null && parent.subAgents.values().firstOrNull { it.id == observation.childId }?.status != "closed") parent.subAgents.event(obj("method" to if (turn.optString("status") == "inProgress") "turn/started" else "turn/completed", "params" to obj("threadId" to observation.childId, "turn" to turn)))
                            }
                            SubAgentDetailView(observation.parentId, observation.detail.agent, snapshot.messages.map { item -> item.copy(media = item.media.map { it.copy(sessionId = parent.record.id) }) },
                                parent.models.firstOrNull { it.id == snapshot.model }?.label ?: snapshot.model, snapshot.effort, cwd = snapshot.thread.optString("cwd", parent.record.cwd))
                        }
                        if (!foreground || !ownsChildObservation(observation, parent)) return@launch
                        observation.detail = detail.copy(loading = false)
                        publish()
                    } catch (failure: CancellationException) { throw failure }
                    catch (failure: Exception) {
                        if (ownsChildObservation(observation, parent)) {
                            observation.detail = observation.detail.copy(loading = false)
                            parent.view = parent.view.copy(error = "子代理读取失败：${CodexCapabilities.redact(failure.message ?: "未知错误")}")
                            publish()
                        }
                        return@launch
                    }
                    if (observation.detail.agent.status in setOf("succeeded", "failed", "cancelled", "closed")) observation.signals.receive()
                    else withTimeoutOrNull(3_000) { observation.signals.receive() }
                    delay(150)

                }
            } finally {
                if (ownsChildObservation(observation, parent) && !parent.view.connected) observation.started = false
            }
        }
        observation.job = job; job.start()
    }
    private fun observeChildEvent(parent: Live, message: JSONObject) {
        val observation = childObservation ?: return
        if (!ownsChildObservation(observation, parent)) return
        val history = observation.history ?: return
        if (history.event(message)) {
            observation.detail = observation.detail.copy(messages = history.messages,
                modelLabel = parent.models.firstOrNull { it.id == history.modelId }?.label ?: history.modelId, effort = history.reasoningEffort)
            if (message.optString("method") in setOf("turn/started", "turn/completed", "thread/settings/updated")) observation.signals.trySend(Unit)
        }
    }
    fun setForeground(visible: Boolean) {
        if (foreground == visible) return
        foreground = visible
        if (visible) {
            runtime.onAppForegrounded()
            if (GuiProduct.usesRootExecution(context)) {
                rootWatcherArmed = false
                lastArmedRootSessions = emptyList()
                RootBackgroundNotifications.disarmLocal(context)
            }
        } else {
            childObservation?.let { it.job?.cancel(); it.job = null; it.started = false; it.detail = it.detail.copy(loading = false) }
        }
        publish()
        if (!visible && GuiProduct.usesRootExecution(context)) {
            syncRootBackgroundWatcher(forceSpawn = true)
        }
    }

    private fun buildWatchedRootSessions(): List<WatchedRootSession> {
        if (!GuiProduct.usesRootExecution(context)) return emptyList()
        val expectedEnv = if (GuiProduct.isRoot) "root" else environment
        return sessions.values.mapNotNull { s ->
            if (s.record.environment != expectedEnv) return@mapNotNull null
            val activeObserver = s.observing || s.view.connected || s.view.loading || s.view.activeTurnId != null || s.view.requests.isNotEmpty()
            if (!activeObserver) return@mapNotNull null
            val proto = when {
                s.record.cli in CliCatalog.bridged -> "bridged"
                s.record.cli == "OpenCode" -> "opencode"
                s.record.cli == "Codex" -> "codex"
                else -> return@mapNotNull null
            }
            if (proto != "bridged" && s.record.threadId.isNullOrBlank()) return@mapNotNull null
            WatchedRootSession(
                id = s.record.id,
                epoch = s.generation,
                protocol = proto,
                task = if (proto == "bridged") NativeCliRuntimePolicy.task(s.record.cli) else "",
                threadId = s.record.threadId.orEmpty(),
                endpointUrl = (s.openCode?.endpoint?.url ?: s.nativeCli?.endpoint?.url ?: s.endpoint?.url).orEmpty().takeUnless { it.startsWith("unix://") }.orEmpty(),
                externalSocket = s.record.externalSocket.orEmpty(),
                running = s.view.activeTurnId != null,
                userStopped = s.userStoppedTurn,
                requestIds = s.view.requests.map { it.id },
            )
        }
    }

    private fun syncRootBackgroundWatcher(forceSpawn: Boolean) {
        if (!GuiProduct.usesRootExecution(context) || foreground) return
        val watched = buildWatchedRootSessions()
        if (watched.isEmpty()) {
            if (rootWatcherArmed || lastArmedRootSessions.isNotEmpty()) {
                rootWatcherArmed = false
                lastArmedRootSessions = emptyList()
                RootBackgroundNotifications.disarmLocal(context)
            }
            return
        }
        val shouldSpawn = forceSpawn || !rootWatcherArmed
        if (!shouldSpawn && watched == lastArmedRootSessions) return
        rootWatcherArmed = true
        lastArmedRootSessions = watched
        RootBackgroundNotifications.armInBackground(context, scope, watched, spawnLauncher = shouldSpawn)
    }

    internal fun onBackgroundWake(sessionId: String, kind: SessionNotificationKind, onComplete: () -> Unit) {
        onBackgroundWakeBatch(listOf(sessionId to kind), onComplete)
    }

    internal fun onBackgroundWakeBatch(
        events: List<Pair<String, SessionNotificationKind>>,
        onComplete: () -> Unit,
    ) {
        val epoch = wakeEpoch
        scope.launch {
            try {
                val targets = ArrayList<Pair<Live, SessionNotificationKind>>(events.size)
                for ((sessionId, kind) in events) {
                    if (wakeEpoch != epoch) break
                    val s = sessions[sessionId]?.takeIf { it.record.environment == environment } ?: continue
                    if (kind == SessionNotificationKind.COMPLETED && s.userStoppedTurn) {
                        s.userStoppedTurn = false
                        continue
                    }
                    when {
                        !s.view.connected && !s.view.loading && s.openCode?.recovering != true -> startObservation(s)
                        s.record.cli in CliCatalog.bridged -> s.nativeCli?.pullLatest()
                        s.record.cli == "OpenCode" -> s.openCode?.refreshDashboard()
                    }
                    targets += s to kind
                }
                if (targets.isEmpty() || wakeEpoch != epoch) return@launch
                withTimeoutOrNull(1000L) {
                    while (wakeEpoch == epoch && targets.any { (s, kind) -> s.observing && s.notificationTracker.activePostedKind != kind }) {
                        delay(80L)
                    }
                }
                if (wakeEpoch != epoch) return@launch
                for ((s, kind) in targets) {
                    if (sessions[s.record.id] !== s || !s.observing) continue
                    val viewingNow = foreground && selected == s.record.id && childObservation?.parentId != s.record.id
                    when (val decision = s.notificationTracker.onExternalWake(kind, viewingNow)) {
                        is SessionNotificationDecision.Post -> notificationSink.post(s.record.id, s.record.title, s.record.cli, decision.kind)
                        SessionNotificationDecision.Cancel -> notificationSink.cancel(s.record.id)
                        SessionNotificationDecision.None -> {}
                    }
                }
            } finally {
                onComplete()
            }
        }
    }
    override fun showSessions() {
        current()?.record?.let { blank.cli = it.cli; blank.cwd = it.cwd }
        if (!prunePreviousIfEmpty(null)) return
        enterBlank(); saveNavigation(); publish()
    }
    private fun enterBlank() {
        selected = null; blank.key = UUID.randomUUID().toString()
        fileGeneration++; previewGeneration++; inventoryGeneration++
        mutableState.value = mutableState.value.copy(fileBrowser = null, filePreview = null, inventory = null, plugins = PluginCatalog(), browserOpen = false)
    }
    private fun saveNavigation() {
        try { persist() } catch (e: Exception) { mutableState.value = mutableState.value.copy(error = "无法保存会话选择：${e.message}") }
    }
    override fun discardUncertainSubmission() {
        val s = current() ?: return
        launch(s) { if (SubmissionLedger.uncertain(s.record)) { SubmissionLedger.discard(s.record); persist() } }
    }
    override fun reconnect() {
        val s = current() ?: return
        if (cliMaintenanceJob?.isActive == true) return
        startObservation(s, recoverExternal = true) { if (s.dispatchStorageBlocked) persist() }
    }

    override fun restartCliBridge() {
        val target = current()?.takeIf { it.view.cliServiceUpdateRequired || it.view.cliServiceUpdateAvailable } ?: return
        if (cliMaintenanceJob?.isActive == true) return
        try { persist() } catch (failure: Exception) {
            target.view = target.view.copy(error = "无法保存会话，CLI 服务未停止：${failure.message}"); publish(); return
        }
        val task = NativeCliRuntimePolicy.task(target.record.cli)
        val affected = sessions.values.filter { it.record.environment == target.record.environment && it.record.cli in CliCatalog.bridged && NativeCliRuntimePolicy.task(it.record.cli) == task }
        val restore = affected.filter { it.observing || it === target }.map { it.record.id }.toSet()
        val maintenance = scope.launch(start = CoroutineStart.LAZY) {
            val pending = affected.flatMap { s ->
                s.observing = false; s.generation++; s.firstSendPending = false
                s.releasing = true
                val jobs = listOfNotNull(s.connectJob, s.firstSendJob, s.dispatchJob, s.dismissalJob, s.deliveryTimer, s.idleJob, s.reconnectJob, s.queueRefresh)
                jobs.forEach { it.cancel() }
                s.connectJob = null; s.firstSendJob = null; s.dispatchJob = null; s.dismissalJob = null
                s.deliveryTimer = null; s.idleJob = null; s.reconnectJob = null; s.queueRefresh = null
                s.dispatchingLocal = false; s.dismissing = false; s.firstSendScheduled = false
                s.nativeCli?.close()
                s.view = s.view.copy(connected = false, loading = true, canOpenTui = false, requests = emptyList(),
                    connection = "正在更新 CLI 服务…", error = null)
                jobs
            }
            publish()
            try {
                pending.distinct().forEach { it.join() }
                affected.forEach { s ->
                    s.record.receiptRecoveryRequired.addAll(s.record.submissions.filter { it.state in setOf("pending", "unknown") }.map { it.id })
                }
                persist()
                runtime.restartNativeCliBridge(target.record.cli, target.record.environment, confirmed = true)
                affected.forEach { s ->
                    s.releasing = false
                    if (sessions[s.record.id] === s) {
                        s.view = s.view.copy(loading = false, cliServiceUpdateRequired = false, cliServiceUpdateAvailable = false, connection = "CLI 服务已更新 · 待同步")
                        if (s.record.id in restore) startObservation(s, recoverExternal = true)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                affected.forEach { s -> if (sessions[s.record.id] === s) s.view = s.view.copy(loading = false,
                    error = "CLI 服务更新失败：${failure.message}；记录与草稿保留，请回到更新入口再次确认") }
            } finally {
                affected.forEach { it.releasing = false }
                if (cliMaintenanceJob === currentCoroutineContext()[Job]) cliMaintenanceJob = null
                publish()
            }
        }
        cliMaintenanceJob = maintenance; maintenance.start()
    }

    private suspend fun connect(s: Live, recoverExternal: Boolean = false) {
        if (!s.observing || sessions[s.record.id] !== s) throw CancellationException("界面观察已停止")
        currentCoroutineContext().ensureActive()
        s.connectJob = currentCoroutineContext()[Job]
        val epoch = ++s.generation
        s.idleConfirmed = false
        s.view = s.view.copy(loading = true, connected = false, connection = "连接运行环境…", error = null, canOpenTui = false)
        publish()
        if (s.record.cli in CliCatalog.bridged) {
            val cachedModels = s.models.takeIf { s.nativeCli?.catalogIdentity == NativeCliCatalogIdentity.of(s.record) }.orEmpty()
            s.nativeCli?.close()
            val adapter = NativeCliSession(s.record, runtime, ::persist,
                bindBrowser = { browser.bind(s.record.id, it) },
                changed = { view, models -> if (s.observing && s.generation == epoch) { s.nativeCli?.endpoint?.let { s.endpoint = it }; s.view = view; s.models = models; publish() } },
                initialModels = cachedModels, resetCatalogOnSnapshot = recoverExternal,
                persistCheckpoint = ::persistDurable).also { s.nativeCli = it }
            adapter.connect()
            currentCoroutineContext().ensureActive()
            if (!s.observing || s.generation != epoch) throw CancellationException("界面观察已停止")
            s.endpoint = adapter.endpoint
            return
        }
        check(s.record.cli in CliCatalog.ids) { "此 CLI 适配尚未启用" }
        if (s.record.cli == "OpenCode") {
            openCodeIdleJob?.cancel(); openCodeIdleJob = null
            sessions.values.filter { it.record.environment == environment }.forEach { browser.bind(it.record.id, it.record.threadId) }
            s.openCode?.close()
            val adapter = OpenCodeSession(s.record, runtime, ::persist,
                bindBrowser = { browser.bind(s.record.id, it) },
                changed = { view, models -> if (s.observing && s.generation == epoch) { s.openCode?.endpoint?.let { s.endpoint = it; lastOpenCodeEndpoint = it }; s.view = view; s.models = models; publish() } },
                childChanged = { id -> childObservation?.takeIf { it.parentId == s.record.id && it.childId == id }?.signals?.trySend(Unit) },
                persistCheckpoint = ::persistDurable).also { s.openCode = it }
            adapter.connect()
            currentCoroutineContext().ensureActive()
            if (!s.observing || s.generation != epoch) throw CancellationException("界面观察已停止")
            s.endpoint = adapter.endpoint
            adapter.endpoint?.let { lastOpenCodeEndpoint = it }
            return
        }
        val endpoint = s.record.externalSocket?.let {
            if (recoverExternal) runtime.recoverExternalCodex(s.record.environment, it)
            runtime.externalCodex(s.record.environment, it)
        }
            ?: runtime.connect(s.record.environment)
        currentCoroutineContext().ensureActive()
        if (!s.observing || s.generation != epoch) throw CancellationException("界面观察已停止")
        s.endpoint = endpoint
        s.rpc?.close(); s.deltaPublishJob?.cancel(); s.deltaPublishJob = null; s.requests.clear(); s.buffered.clear(); s.hydrationBoundary.clear(); s.subAgentMetadataReads.clear()
        val rpc = CodexRpc(endpoint.url, endpoint.authToken, transport = runtime.codexTransport(endpoint),
            onEvent = { message -> scope.launch { if (s.observing && s.generation == epoch) {
                if (s.hydrating) { s.buffered.add(message); return@launch }
                var failed = false
                try { event(s, message) }
                catch (failure: Exception) { failed = true; s.view = s.view.copy(error = "无法应用服务事件，请重新连接同步：${failure.message}") }
                publishCodexEvent(s, message, forceImmediate = failed)
            } } },
            onDisconnect = { failure -> scope.launch { if (s.observing && s.generation == epoch) {
                s.deltaPublishJob?.cancel(); s.deltaPublishJob = null
                s.view = s.view.copy(connected = false, connection = "连接中断 · 任务状态待同步", loading = false, sending = false, error = failure.message)
                s.requests.clear(); publish()
            } } })
        s.rpc = rpc; s.hydrating = true
        try {
            rpc.connect()
            sessions.values.filter { it.record.environment == environment }.forEach { browser.bind(it.record.id, it.record.threadId) }
            val browserOverrides = CodexConnectionSetup.prepare(s.record, runtime.skillsPath(s.record.environment),
                browserConfig = { browser.bind(s.record.id, s.record.threadId) }, request = rpc::request, persist = ::persist,
                recoverExternal = recoverExternal)
            var startedThread: JSONObject? = null
            if (s.record.threadId == null) {
                if (s.record.creationUnknown) {
                    val known = sessions.values.filter { it.record.environment == s.record.environment }.mapNotNull { it.record.threadId }.toSet()
                    val candidates = loadedThreads(rpc) - known
                    error("上次创建结果尚未确认，不会自动再建。当前服务未关联的 ID：${candidates.joinToString().ifBlank { "无；可能服务已重启" }}。请保留这些 ID，由支持人员核对托管服务与本地索引；确需另建时再点新建。")
                }
                s.record.creationUnknown = true
                try { persist() } catch (e: Exception) { s.record.creationUnknown = false; throw e }
                val params = obj("cwd" to s.record.cwd, "approvalPolicy" to "on-request", "sandbox" to "workspace-write", "historyMode" to "paginated", "config" to browserOverrides)
                if (s.record.model.isNotBlank()) params.put("model", s.record.model)
                try {
                    val result = rpc.request("thread/start", params)
                    startedThread = result.getJSONObject("thread")
                    s.record.threadId = startedThread.getString("id")
                    s.record.creationUnknown = false
                    s.record.model = result.optString("model", s.record.model)
                    s.record.effort = result.stringOrNull("reasoningEffort").orEmpty()
                    persist()
                } catch (rejected: RpcRejected) { s.record.creationUnknown = false; persist(); throw rejected }
            }
            val id = s.record.threadId!!
            browser.bind(s.record.id, id)
            if (s.record.externalSocket == null) CodexThreadInitialization.finish(s.record, startedThread, rpc::request, ::persist)
            val resumed = rpc.request("thread/resume", CodexConnectionSetup.resumeParams(s.record, browserOverrides))
            check(resumed.getJSONObject("thread").getString("id") == id) { "服务返回了不同会话，已停止接入" }
            s.record.requireLoaded = false
            s.idleConfirmed = resumed.getJSONObject("thread").optJSONObject("status")?.optString("type") == "idle"
            s.rolloutPath = resumed.getJSONObject("thread").stringOrNull("path")
            s.historyResumable = !resumed.getJSONObject("thread").optBoolean("ephemeral") &&
                !s.rolloutPath.isNullOrBlank()
            ManagedSessionPolicy.nativeTitle(s.record, resumed.getJSONObject("thread").stringOrNull("name"))
            s.record.model = resumed.optString("model", s.record.model)
            s.record.effort = resumed.stringOrNull("reasoningEffort").orEmpty()
            persist()
            val tier = resumed.stringOrNull("serviceTier").orEmpty()
            s.view = s.view.copy(permission = resumed.optJSONObject("activePermissionProfile")?.optString("id").orEmpty(), speed = tier)
            hydrate(s)
            val models = mutableListOf<ModelOption>(); var cursor: String? = null
            do {
                val page = rpc.request("model/list", obj("limit" to 100, "cursor" to cursor))
                models += CodexProjection.models(page.optJSONArray("data") ?: JSONArray()); cursor = page.stringOrNull("nextCursor")
            } while (cursor != null)
            s.models = models
            try {
                val options = mutableListOf<RequestChoice>(); var next: String? = null
                do {
                    val page = rpc.request("permissionProfile/list", obj("cwd" to s.record.cwd, "cursor" to next, "limit" to 100))
                    options += CodexCapabilities.permissions(page); next = page.stringOrNull("nextCursor")
                } while (next != null)
                s.view = s.view.copy(permissions = options, capabilityNotice = null)
            } catch (e: RpcRejected) { s.view = s.view.copy(permissions = emptyList(), capabilityNotice = "当前 CLI 未提供可选择的权限配置") }
            refreshQueue(s)
            currentCoroutineContext().ensureActive()
            if (!s.observing || s.generation != epoch) throw CancellationException("界面观察已停止")
            s.view = s.view.copy(connected = true, loading = false, connection = "已连接", canOpenTui = true, error = null)
        } finally {
            if (s.observing && s.generation == epoch) {
                s.hydrating = false
                val events = s.buffered.toList(); s.buffered.clear()
                val replay = s.hydrationBoundary.replayEvents(events) { itemId, toolDetail ->
                    val snap = s.items[s.nativeToClientId[itemId] ?: itemId]
                    if (toolDetail) snap?.detail.orEmpty() else snap?.text.orEmpty()
                }
                replay.forEach { e -> event(s, e) }
                s.hydrationBoundary.clear()
                reconcileCodexUnconfirmedIdle(s)
            }
            publish()
        }
    }

    private suspend fun loadedThreads(rpc: CodexRpc): Set<String> = CodexConnectionSetup.loadedThreads(rpc::request)

    private fun reconcileCodexUnconfirmedIdle(s: Live) {
        if (!s.view.connected || s.view.loading || s.hydrating || s.view.activeTurnId != null || s.queueRefresh?.isActive == true) return
        if (reconcileCodexIdleSubmissions(s.record, s.historyResumable, s.items, s.nativeToClientId, s.queueRaw, s.admitting)) {
            persist()
        }
    }
    private suspend fun hydrate(s: Live) {
        val rpc = s.rpc ?: return; val id = s.record.threadId ?: return
        s.subAgents.clear(); s.nativeToClientId.clear()
        val hydrated = CachedItemsMap(); var cursor: String? = null
        do {
            val pageBefore = s.buffered.size
            val page = rpc.request("thread/items/list", obj("threadId" to id, "limit" to 100, "sortDirection" to "asc", "cursor" to cursor))
            val pageAfter = s.buffered.size
            page.optJSONArray("data")?.objects()?.forEach { row ->
                val item = row.getJSONObject("item"); val turnId = row.optString("turnId")
                val nativeId = item.stringOrNull("id")
                val isUser = item.optString("type") == "userMessage"
                val explicitClient = if (isUser) CodexProjection.clientMessageId(item)?.takeIf { it.isNotBlank() } else null
                if (!nativeId.isNullOrBlank() && explicitClient != null) s.nativeToClientId[nativeId] = explicitClient
                val canonicalHint = if (isUser) (explicitClient ?: nativeId?.let(s.nativeToClientId::get)) else null
                val previous = canonicalHint?.let(hydrated::get) ?: nativeId?.let(hydrated::get)
                val raw = CodexProjection.item(item, previous)
                val canonicalId = canonicalHint ?: raw.id
                s.hydrationBoundary.item(canonicalId, pageBefore, pageAfter)
                if (!nativeId.isNullOrBlank() && nativeId != canonicalId) {
                    s.hydrationBoundary.item(nativeId, pageBefore, pageAfter)
                }
                val projected = raw.copy(
                    id = canonicalId,
                    turnId = turnId,
                    outcome = if (raw.outcome == "unknown" && !row.isNull("completedAtMs")) "succeeded" else raw.outcome,
                )
                if (!nativeId.isNullOrBlank() && nativeId != canonicalId && hydrated.containsKey(nativeId)) {
                    hydrated.replaceKeyInPlace(nativeId, canonicalId, projected)
                } else {
                    hydrated[canonicalId] = projected
                }
                s.subAgents.item(item, turnId); confirmSubmission(s, item)
            }
            cursor = page.stringOrNull("nextCursor")
        } while (cursor != null)
        s.items.clear(); s.items.putAll(hydrated)
        if (SessionTitlePolicy.history(s.record, s.items.values, ready = true)) persist()
        val turnsBefore = s.buffered.size
        val turns = rpc.request("thread/turns/list", obj("threadId" to id, "limit" to 1, "sortDirection" to "desc", "itemsView" to "notLoaded"))
        s.hydrationBoundary.turns(turnsBefore, id)
        val last = turns.optJSONArray("data")?.optJSONObject(0)
        s.queueNeedsExplicitStart = last?.optString("status") in setOf("interrupted", "failed")
        s.view = s.view.copy(activeTurnId = last?.takeIf { it.optString("status") == "inProgress" }?.optString("id"), stopping = false)
        if (s.tokens.isEmpty() && !s.rolloutPath.isNullOrBlank()) {
            runtime.readCodexRolloutTokenUsage(s.record.environment, s.rolloutPath!!)?.let { info ->
                s.tokens = CodexCapabilities.tokenMetrics(info, s.record.model.ifBlank { s.view.summary.model }, s.view.speed)
                s.view = s.view.copy(metrics = s.tokens)
            }
        }
        for (agent in s.subAgents.values()) {
            try {
                val thread = rpc.request("thread/read", obj("threadId" to agent.id, "includeTurns" to false)).optJSONObject("thread")
                if (thread != null && !s.subAgents.metadata(thread)) {
                    s.requests.entries.removeAll { it.value.threadId == agent.id }
                    continue
                }
                val childTurnsBefore = s.buffered.size
                val turn = rpc.request("thread/turns/list", obj("threadId" to agent.id, "limit" to 1, "sortDirection" to "desc", "itemsView" to "notLoaded"))
                    .optJSONArray("data")?.optJSONObject(0)
                s.hydrationBoundary.turns(childTurnsBefore, agent.id)
                if (turn != null) s.subAgents.event(obj("method" to if (turn.optString("status") == "inProgress") "turn/started" else "turn/completed",
                    "params" to obj("threadId" to agent.id, "turn" to turn)))
            } catch (_: RpcRejected) {  }
        }
    }
    private suspend fun refreshQueue(s: Live) {
        val rpc = s.rpc ?: return; val id = s.record.threadId ?: return
        val queue = mutableListOf<JSONObject>(); var cursor: String? = null
        do {
            val page = rpc.request("thread/queue/list", obj("threadId" to id, "limit" to 100, "cursor" to cursor))
            queue += page.optJSONArray("data")?.objects().orEmpty(); cursor = page.stringOrNull("nextCursor")
        } while (cursor != null)
        applyQueue(s, queue)
        publish()
    }
    private suspend fun reconcileCodexSubmission(s: Live, id: String, rpc: CodexRpc, epoch: Int) {
        var cursor: String? = null
        do {
            if (!s.observing || s.generation != epoch || s.rpc !== rpc || !rpc.connected) return
            val page = rpc.request("thread/items/list", obj("threadId" to s.record.threadId, "limit" to 100, "sortDirection" to "desc", "cursor" to cursor))
            if (!s.observing || s.generation != epoch || s.rpc !== rpc) return
            val row = page.optJSONArray("data")?.objects()?.firstOrNull { value -> value.optJSONObject("item")?.let {
                it.optString("type") == "userMessage" && (CodexProjection.clientMessageId(it) ?: s.nativeToClientId[it.optString("id")] ?: it.optString("id")) == id
            } == true }
            if (row != null) { putItem(s, row.getJSONObject("item"), completed = !row.isNull("completedAtMs"), turnId = row.optString("turnId")); publish(); return }
            cursor = page.stringOrNull("nextCursor")
        } while (cursor != null)
    }
    private fun applyQueue(s: Live, queue: List<JSONObject>) {
        val linked = queue.map { queued ->
            if (queued.stringOrNull("clientUserMessageId") != null) queued else JSONObject(queued.toString()).also { row ->
                s.record.submissions.firstOrNull { it.nativeQueueId == queued.optString("id") }?.let { row.put("clientUserMessageId", it.id) }
            }
        }
        val idleDirectIds = if (s.view.activeTurnId == null && !s.queueNeedsExplicitStart) {
            linked.mapNotNull { it.stringOrNull("clientUserMessageId") }.filter { id ->
                SubmissionLedger.find(s.record, id)?.let { !it.queuePresentation && !it.nativeQueued } == true
            }.toSet()
        } else emptySet()
        val waiting = linked.filterNot { queued ->
            val cid = queued.stringOrNull("clientUserMessageId")
            cid != null && (s.items[cid]?.role == "user" || cid in idleDirectIds)
        }
        s.queueRaw = waiting
        s.view = s.view.copy(queue = waiting.map { QueueEntry(it.getString("id"), CodexAsyncQuestions.displayReply(CodexProjection.inputText(it.optJSONArray("input") ?: JSONArray())), "尚未执行", it.optJSONArray("input")?.objects().orEmpty().filter { input -> input.optString("type") == "text" }.joinToString("\n") { input -> input.optString("text") },
            clientUserMessageId = it.stringOrNull("clientUserMessageId")) })
        linked.mapNotNull { it.stringOrNull("clientUserMessageId") }.forEach { id ->
            SubmissionLedger.find(s.record, id)?.let {
                if (id !in idleDirectIds) {
                    linked.firstOrNull { row -> row.stringOrNull("clientUserMessageId") == id }?.let { SubmissionLedger.bindQueue(s.record, id, it.getString("id")) }
                }
                clearSubmission(s, id)
            }
        }
    }
    private fun confirmSubmission(s: Live, item: JSONObject) {
        val nativeId = item.stringOrNull("id")
        val explicitClient = CodexProjection.clientMessageId(item)?.takeIf { it.isNotBlank() }
        if (item.optString("type") == "userMessage" && !nativeId.isNullOrBlank() && explicitClient != null) {
            s.nativeToClientId[nativeId] = explicitClient
        }
        val mappedClient = explicitClient ?: nativeId?.let(s.nativeToClientId::get)
        s.record.queueTransfer?.let { raw ->
            val id = JSONObject(raw).getString("clientId")
            if (mappedClient == id || nativeId == id) { s.record.queueTransfer = null; persist() }
        }
        val id = mappedClient ?: nativeId.orEmpty()
        if (id.isNotEmpty() && SubmissionLedger.find(s.record, id) != null) {
            if (item.optString("type") == "userMessage") SubmissionLedger.consumed(s.record, id)
            clearSubmission(s, id)
        }
    }
    private fun clearSubmission(s: Live, id: String) {
        SubmissionLedger.accepted(s.record, titleEligible = !s.hydrating, id = id); persist()
    }
    private fun publishCodexEvent(s: Live, message: JSONObject, forceImmediate: Boolean = false) {
        val method = message.optString("method")
        val isDelta = !forceImmediate && !message.has("id") && method.endsWith("Delta", ignoreCase = true)
        if (!isDelta) {
            s.deltaPublishJob?.cancel()
            s.deltaPublishJob = null
            s.lastCodexPublishNs = System.nanoTime()
            publish()
            return
        }
        val now = System.nanoTime()
        val elapsedMs = if (s.lastCodexPublishNs == 0L) Long.MAX_VALUE else (now - s.lastCodexPublishNs) / 1_000_000L
        if (elapsedMs >= 40L && s.deltaPublishJob?.isActive != true) {
            s.lastCodexPublishNs = now
            publish()
        } else if (s.deltaPublishJob?.isActive != true) {
            val epoch = s.generation
            val waitMs = (40L - elapsedMs).coerceIn(1L, 40L)
            s.deltaPublishJob = scope.launch {
                delay(waitMs)
                s.deltaPublishJob = null
                s.lastCodexPublishNs = System.nanoTime()
                if (sessions[s.record.id] === s && s.observing && s.generation == epoch) publish()
            }
        }
    }
    private fun event(s: Live, message: JSONObject) {
        val method = message.optString("method"); val p = message.optJSONObject("params") ?: JSONObject()
        val thread = p.stringOrNull("threadId") ?: p.optJSONObject("thread")?.stringOrNull("id")
        s.subAgents.event(message)
        if (thread != null && thread != s.record.threadId && !s.subAgents.acceptsRequestsFrom(thread)) {
            s.requests.entries.removeAll { it.value.threadId == thread }
        }
        observeChildEvent(s, message)
        refreshSubAgentMetadata(s)
        if (method == "serverRequest/resolved") {
            if (p.has("requestId") && !p.isNull("requestId")) {
                s.requests.remove(requestKey(p.get("requestId")))
            }
            return
        }
        if (message.has("id")) {
            val approval = PendingApproval(message.get("id"), method, p)
            if (acceptCodexRequest(s.record.threadId, s.subAgents, approval)) {
                s.requests[approval.id] = approval
            }
            return
        }
        if (thread != null && thread != s.record.threadId) return
        when (method) {
            "thread/settings/updated" -> p.optJSONObject("threadSettings")?.let { settings ->
                s.record.model = settings.getString("model"); s.record.effort = settings.stringOrNull("effort").orEmpty()
                s.view = s.view.copy(permission = settings.optJSONObject("activePermissionProfile")?.optString("id").orEmpty(), speed = settings.stringOrNull("serviceTier").orEmpty())
                persist()
                if (NativeSettings.matches(s.settingsExpected, settings)) s.settingsWaiter?.complete(settings)
            }
            "thread/tokenUsage/updated" -> p.optJSONObject("tokenUsage")?.let {
                s.tokens = CodexCapabilities.tokenMetrics(it, s.record.model.ifBlank { s.view.summary.model }, s.view.speed); s.view = s.view.copy(metrics = s.tokens)
            }
            "account/rateLimits/updated" -> {
                s.limits = CodexCapabilities.limitMetrics(p)
            }
            "thread/queue/changed" -> {
                s.queueRefresh?.cancel(); s.queueRefresh = launch(s) { delay(80); refreshQueue(s) }
            }
            "thread/status/changed" -> s.idleConfirmed = p.optJSONObject("status")?.optString("type") == "idle"
            "turn/started" -> {
                s.idleConfirmed = false
                s.userStoppedTurn = false
                s.queueNeedsExplicitStart = false
                val turn = p.getJSONObject("turn"); s.view = s.view.copy(activeTurnId = turn.getString("id"), stopping = false)
                turn.optJSONArray("items")?.objects()?.forEach { putItem(s, it, turnId = turn.getString("id")) }
            }
            "turn/completed" -> {
                s.idleConfirmed = true
                val turn = p.getJSONObject("turn")
                turn.optJSONArray("items")?.objects()?.forEach { putItem(s, it, turnId = turn.getString("id")) }
                if (s.view.activeTurnId == turn.optString("id")) s.view = s.view.copy(activeTurnId = null, stopping = false)
                if (s.view.activeTurnId == null) s.queueNeedsExplicitStart = turn.optString("status") in setOf("interrupted", "failed")
                turn.optJSONObject("error")?.let { s.view = s.view.copy(error = CodexProjection.error(it)) }
            }
            "item/started", "item/completed" -> p.optJSONObject("item")?.let { putItem(s, it, method == "item/completed", p.optString("turnId")) }
            "item/agentMessage/delta", "item/commandExecution/outputDelta", "item/reasoning/summaryTextDelta", "item/reasoning/textDelta", "item/plan/delta" -> {
                val rawId = p.optString("itemId")
                val id = s.nativeToClientId[rawId] ?: rawId
                val assistant = method.contains("agentMessage") || method.contains("plan")
                val reasoning = method.contains("reasoning")
                val old = s.items[id]
                    ?: ConversationItem(id, if (assistant) "assistant" else "tool",
                        text = if (reasoning) "推理摘要" else "",
                        kind = when { reasoning -> "reasoning"; method.contains("commandExecution") -> "commandExecution"; method.contains("plan") -> "plan"; else -> "agentMessage" },
                        turnId = p.optString("turnId"), outcome = "running")
                val delta = p.optString("delta")
                if (delta.isNotEmpty()) {
                    s.items[id] = if (old.role == "tool") old.copy(detail = old.detail + delta) else old.copy(text = old.text + delta)
                }
            }
            "thread/name/updated" -> if (p.has("threadName")) {
                ManagedSessionPolicy.nativeTitle(s.record, p.stringOrNull("threadName")); persist()
            }
            "error" -> s.view = s.view.copy(error = CodexProjection.error(p.optJSONObject("error") ?: p))
        }
    }
    private fun putItem(s: Live, item: JSONObject, completed: Boolean? = null, turnId: String = "") {
        val nativeId = item.stringOrNull("id")
        val isUser = item.optString("type") == "userMessage"
        val explicitClient = if (isUser) CodexProjection.clientMessageId(item)?.takeIf { it.isNotBlank() } else null
        if (!nativeId.isNullOrBlank() && explicitClient != null) {
            s.nativeToClientId[nativeId] = explicitClient
        }
        val canonicalHint = if (isUser) (explicitClient ?: nativeId?.let(s.nativeToClientId::get)) else null
        val previous = canonicalHint?.let(s.items::get) ?: nativeId?.let(s.items::get)
        val raw = CodexProjection.item(item, previous)
        val canonicalId = canonicalHint ?: raw.id
        val entry = raw.copy(
            id = canonicalId,
            outcome = if (raw.outcome != "unknown" || completed == null) raw.outcome else if (completed) "succeeded" else "running",
        )
        val ownerTurn = turnId.ifEmpty { previous?.turnId.orEmpty() }
        val finalEntry = entry.copy(turnId = ownerTurn)
        if (!nativeId.isNullOrBlank() && nativeId != canonicalId && s.items.containsKey(nativeId)) {
            s.items.replaceKeyInPlace(nativeId, canonicalId, finalEntry)
        } else {
            s.items[canonicalId] = finalEntry
        }
        s.subAgents.item(item, ownerTurn); refreshSubAgentMetadata(s); confirmSubmission(s, item)
        if (entry.role == "user") applyQueue(s, s.queueRaw)
        if (SessionTitlePolicy.history(s.record, s.items.values, ready = !s.hydrating)) persist()
    }

    private fun refreshSubAgentMetadata(s: Live) {
        val rpc = s.rpc ?: return
        val epoch = s.generation
        for (agent in s.subAgents.values().filter { (it.name.isEmpty() || !s.subAgents.isVerified(it.id)) && s.subAgentMetadataReads.add(it.id) }) {
            scope.launch {
                try {
                    val thread = rpc.request("thread/read", obj("threadId" to agent.id, "includeTurns" to false)).optJSONObject("thread")
                    if (s.generation == epoch && thread != null) {
                        if (!s.subAgents.metadata(thread)) {
                            s.requests.entries.removeAll { it.value.threadId == agent.id }
                        }
                        publish()
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: RpcRejected) {  }
                catch (e: Exception) {
                    if (s.generation == epoch && rpc.connected) {
                        s.view = s.view.copy(error = "子代理名称读取失败，请重连同步：${e.message}"); publish()
                    }
                }
            }
        }
    }

    override fun recoverSubmission(id: String) {
        val s = current() ?: return
        val msg = SubmissionLedger.find(s.record, id) ?: return
        if (msg.state == "unknown" && (s.view.loading || s.view.activeTurnId != null || s.view.awaitingHandshake || id in s.admitting)) return
        launch(s) { SubmissionLedger.recover(s.record, id, ::persistDurable) }
    }
    override fun draft(text: String) {
        val s = current()
        if (s?.firstSendPending == true) return
        if (s == null) blank.text = text else {
            if (s.record.draft != text) s.record.draftRevision++
            s.record.draft = text
        }
        saveJob?.cancel(); saveJob = scope.launch {
            delay(250)
            try { persist() } catch (e: Exception) {
                if (s != null) s.view = s.view.copy(error = e.message) else mutableState.value = mutableState.value.copy(error = e.message)
                publish()
            }
        }
        publish()
    }
    override fun addImage(uri: String) {
        val s = current()
        if (s == null) {
            val id = UUID.randomUUID().toString(); val key = blank.key
            blankImports += Attachment(id, uri, "导入图片…", true); publish()
            launch(null) {
                try {
                    val path = runtime.importImage(environment, Uri.parse(uri))
                    val preview = runtime.imagePreviewUri(environment, path) ?: error("导入图片不可读取")
                    check(blank.key == key) { "导入期间已切换空白会话，图片未添加，请重新选择" }
                    blank.images += StoredImage(id, preview, path, path.substringAfterLast('/')); persist()
                } finally { blankImports.removeAll { it.id == id } }
            }
            return
        }
        if (s.firstSendPending) return
        val id = UUID.randomUUID().toString()
        s.view = s.view.copy(attachments = s.view.attachments + Attachment(id, uri, "导入图片…", true)); publish()
        launch(s) {
            try {
                val path = runtime.importImage(s.record.environment, Uri.parse(uri))
                val preview = runtime.imagePreviewUri(s.record.environment, path) ?: error("导入图片的私有副本不可读取")
                s.record.draftRevision++; s.record.images += StoredImage(id, preview, path, path.substringAfterLast('/')); persist()
            } finally { s.view = s.view.copy(attachments = s.view.attachments.filterNot { it.id == id }) }
        }
    }
    override fun removeImage(id: String) {
        val s = current()
        if (s?.firstSendPending == true) return
        launch(s) { if (s == null) blank.images.removeAll { it.id == id } else { s.record.draftRevision++; s.record.images.removeAll { it.id == id } }; persist() }
    }
    override fun send() = submit(false)
    override fun steer() = submit(true)
    private fun submit(steer: Boolean) {
        val s = current() ?: run { if (!steer) sendFromBlank(); return }
        if (s.firstSendPending) return
        if (s.nativeCli?.retryable == false && !s.view.connected) {
            s.view = s.view.copy(error = s.view.error ?: "CLI 尚未就绪，请在原生 CLI 处理后重新连接；输入已保留")
            publish(); return
        }
        if (s.record.cli == "Grok Build" && s.record.threadId == null && !s.view.connected && !s.view.loading && s.view.error != null) {
            if (!s.view.error.orEmpty().contains("重新连接")) s.view = s.view.copy(error = "${s.view.error}\n请处理后重新连接；未发送的输入仍保留。")
            publish(); return
        }
        if (!s.view.connected || s.view.loading) {
            if (s.view.attachments.any { it.busy } || (s.record.draft.isBlank() && s.record.images.isEmpty() && s.record.skills.isEmpty())) return
            if (s.view.activeTurnId == null) s.userStoppedTurn = false
            val id = if (s.record.cli == "OpenCode") "msg_" + UUID.randomUUID().toString().replace("-", "") else UUID.randomUUID().toString()
            try {
                SubmissionLedger.checkpoint(s.record, id, ::persistDurable) {
                    SubmissionLedger.begin(s.record, id, settings = settings(s), busy = true)
                    SubmissionLedger.state(s.record, id, "local")
                }
            } catch (e: SubmissionPersistenceFailure) { s.view = s.view.copy(error = e.message); publish(); return }
            if (!s.view.loading && !s.observing) startObservation(s)
            publish(); return
        }
        if (s.view.activeTurnId == null) s.userStoppedTurn = false
        if (s.record.cli in CliCatalog.bridged) { if (!s.view.attachments.any { it.busy }) s.nativeCli?.submit(steer); return }
        if (s.record.cli == "OpenCode") { if (!s.view.attachments.any { it.busy }) s.openCode?.submit(steer); return }
        if (!s.view.connected || s.view.configuring || s.view.attachments.any { it.busy }) return
        launch(s) { submitCodex(s, steer) }
    }
    private fun settings(s: Live) = mapOf("model" to s.record.model, "effort" to s.record.effort, "permission" to s.view.permission, "speed" to s.view.speed).filterValues { it.isNotBlank() }
    private fun sendFromBlank() {
        if (storeError != null || blankImports.isNotEmpty() || (blank.text.isBlank() && blank.images.isEmpty() && blank.skills.isEmpty())) return
        val record = ManagedSessionPolicy.newRecord(UUID.randomUUID().toString(), blank.cli, "", environment, blank.cwd, "", null)
        record.lastActiveAt = activityClock.next()
        record.draft = blank.text; record.images.addAll(blank.images); record.skills.addAll(blank.skills)
        val s = Live(record).apply { firstSendPending = true }
        sessions[record.id] = s; selected = record.id
        mutableState.value = mutableState.value.copy(error = null)
        blank.text = ""; blank.images.clear(); blank.skills.clear()
        try { persist() } catch (e: Exception) {
            sessions.remove(record.id); selected = null
            store.navigation.selectedId = null
            blank.text = record.draft; blank.images.addAll(record.images); blank.skills.addAll(record.skills)
            mutableState.value = mutableState.value.copy(error = "无法创建会话，输入已保留：${e.message}"); publish(); return
        }
        preferences.edit().putString("session", record.id).apply()
        startObservation(s); publish()
    }
    private suspend fun submitCodex(s: Live, steer: Boolean, external: ManagedSubmission? = null) {
        check(s.view.connected && !s.view.configuring) { "会话尚未连接或正在更新设置" }
        val text = external?.text ?: s.record.draft
        val images = external?.images?.map { it.path } ?: s.record.images.map { it.path }
        val skills = external?.skills ?: s.record.skills.toList()
        check(text.isNotBlank() || images.isNotEmpty() || skills.isNotEmpty()) { "请输入消息或添加图片" }
        if (images.isNotEmpty()) check(s.models.firstOrNull { it.id == s.record.model }?.supportsImages == true) { "当前模型未声明图片能力，请选择支持图片的模型" }
        val turn = s.view.activeTurnId
        val doSteer = turn != null && !s.queueNeedsExplicitStart
        val resumeStoppedQueue = !doSteer && turn == null && s.queueNeedsExplicitStart
        val id = external?.id ?: UUID.randomUUID().toString()
        val input = if (CodexAsyncQuestions.replies(text).isNotEmpty()) JSONArray().put(obj("type" to "text", "text" to text))
            else CodexProjection.inputs(text, images, s.record.cwd)
        skills.forEach { input.put(obj("type" to "skill", "name" to it.name, "path" to it.path)) }
        val carrier = s.rpc!!; val epoch = s.generation; var reconcile = false
        try {
            SubmissionLedger.admit(
                record = s.record,
                id = id,
                persist = ::persistDurable,
                input = external,
                busy = turn != null,
                setSending = { sending ->
                    if (sending) {
                        s.admitting.add(id)
                        s.view = s.view.copy(sending = true, awaitingHandshake = s.view.activeTurnId == null, error = null)
                        publish()
                    } else {
                        s.admitting.remove(id)
                        s.view = s.view.copy(sending = false, awaitingHandshake = false)
                    }
                },
                isRejected = { it is RpcRejected },
                rethrowAcceptedRejection = true,
                onUnknown = { reconcile = true },
                onResponse = { response ->
                    reconcile = true
                    clearSubmission(s, id)
                    val idleDirect = turn == null && !resumeStoppedQueue && !s.queueNeedsExplicitStart &&
                        SubmissionLedger.find(s.record, id)?.queuePresentation == false
                    response.optJSONObject("queuedSubmission")?.let { queued ->
                        check(queued.stringOrNull("clientUserMessageId").let { it == null || it == id }) { "原生队列返回了不同提交身份" }
                        if (!idleDirect) {
                            SubmissionLedger.bindQueue(s.record, id, queued.getString("id")); persist()
                            if (s.items.values.none { it.role == "user" && it.id == id }) {
                                applyQueue(s, s.queueRaw.filterNot { it.optString("id") == queued.optString("id") } + queued)
                            }
                        }
                        publish()
                    }
                    if (resumeStoppedQueue && s.queueNeedsExplicitStart && s.view.activeTurnId == null &&
                        SubmissionLedger.find(s.record, id)?.dismissed != true) {
                        val queuedId = response.optJSONObject("queuedSubmission")?.stringOrNull("id")
                            ?: error("CLI 已受理消息，但未返回可启动的队列 ID；不会重新发送")
                        try {
                            s.rpc!!.request("thread/queue/start", obj("threadId" to s.record.threadId, "queuedSubmissionId" to queuedId))
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            throw java.io.IOException("CLI 已受理消息，但启动排队消息${if (e is RpcRejected) "失败" else "的结果尚未确认"}：${e.message}", e)
                        }
                    }
                },
            ) {
                val params = obj("threadId" to s.record.threadId, "clientUserMessageId" to id, "input" to input)
                if (doSteer) params.put("expectedTurnId", turn)
                s.rpc!!.request(if (doSteer) "turn/steer" else "thread/queue/add", params)
            }
        } finally {
            if (reconcile && currentCoroutineContext().isActive && s.observing && s.generation == epoch && s.rpc === carrier && carrier.connected) {
                try { refreshQueue(s) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { s.view = s.view.copy(error = "发送结果待同步，请重新连接核对") }
                try { reconcileCodexSubmission(s, id, carrier, epoch) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { s.view = s.view.copy(error = "发送结果待同步，请重新连接核对") }
            }
        }
    }
    override fun stop() {
        val s = current() ?: return
        if (s.record.cli in CliCatalog.bridged) {
            if (s.view.connected && !s.view.stopping && s.view.activeTurnId != null) {
                s.userStoppedTurn = true
                s.nativeCli?.stop(onFailed = { s.userStoppedTurn = false })
            }
            return
        }
        if (s.record.cli == "OpenCode") {
            if (s.view.connected && !s.view.stopping && s.view.activeTurnId != null) {
                s.userStoppedTurn = true
                s.openCode?.stop(onFailed = { s.userStoppedTurn = false })
            }
            return
        }
        val turn = s.view.activeTurnId ?: return
        if (s.view.stopping) return
        s.userStoppedTurn = true
        launch(s) {
            s.view = s.view.copy(stopping = true); publish()
            try {
                s.rpc!!.request("turn/interrupt", obj("threadId" to s.record.threadId, "turnId" to turn))
            } catch (e: Exception) {
                if (e !is CancellationException && s.view.activeTurnId != null) s.userStoppedTurn = false
                if (e is RpcRejected) s.view = s.view.copy(stopping = false)
                throw e
            }
        }
    }
    private fun queueMutation(s: Live, block: suspend () -> Unit) {
        if (!s.view.connected || s.view.queueBusy || s.record.queueTransfer != null) return
        s.view = s.view.copy(queueBusy = true); publish()
        launch(s) { try { block() } finally { s.view = s.view.copy(queueBusy = false) } }
    }
    private fun nativeQueue(s: Live): List<QueueEntry> = SubmissionLedger.queue(s.record, when {
        s.record.cli in CliCatalog.bridged -> s.nativeCli?.nativeQueue.orEmpty()
        s.record.cli == "OpenCode" -> s.openCode?.nativeQueue.orEmpty()
        else -> s.queueRaw.map { QueueEntry(it.getString("id"), "", "", clientUserMessageId = it.stringOrNull("clientUserMessageId")) }
    })
    private fun dismissalCandidate(s: Live, intent: StoredQueueDismissal) = nativeQueue(s).firstOrNull { it.canRemove && SubmissionLedger.matches(intent, it) }
    private fun scheduleDismissals(s: Live) {
        if (!s.observing || !s.view.connected || s.view.loading || s.dispatchStorageBlocked || s.dismissing ||
            s.connectJob?.isActive == true || s.record.queueDismissals.none { it.state == "waiting" && dismissalCandidate(s, it) != null }) return
        s.dismissing = true
        val epoch = s.generation
        s.dismissalJob = scope.launch {
            yield()
            try {
                for (key in s.record.queueDismissals.filter { it.state == "waiting" }.map { it.id }) {
                    if (!s.observing || s.generation != epoch || !s.view.connected) break
                    val intent = s.record.queueDismissals.firstOrNull { it.id == key && it.state == "waiting" } ?: continue
                    val entry = dismissalCandidate(s, intent) ?: continue
                    SubmissionLedger.checkpoint(s.record, intent.clientUserMessageId ?: key, ::persistDurable) {
                        intent.nativeQueueId = entry.id; intent.hostOwned = entry.hostOwned; intent.state = "unknown"
                    }
                    try {
                        when {
                            s.record.cli in CliCatalog.bridged -> s.nativeCli!!.removePending(entry.id)
                            s.record.cli == "OpenCode" -> s.openCode!!.removePending(entry.id)
                            else -> s.rpc!!.request("thread/queue/delete", obj("threadId" to s.record.threadId, "queuedSubmissionId" to entry.id))
                        }
                        intent.state = "confirmed"; persist()
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        if (e is RpcRejected || e is NativeCliRejected || (e is OpenCodeRejected && e.status in setOf(400, 401, 403, 404, 422))) intent.state = "rejected"
                        s.view = s.view.copy(error = if (intent.state == "rejected") "待发已移除，原生撤回未确认" else "待发已移除，撤回结果待核对")
                        persist()
                    } finally {
                        if (currentCoroutineContext().isActive && s.observing && s.generation == epoch) try {
                            when {
                                s.record.cli in CliCatalog.bridged -> s.nativeCli?.reconcileQueue()
                                s.record.cli == "OpenCode" -> s.openCode?.reconcileQueue()
                                else -> refreshQueue(s)
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { s.view = s.view.copy(error = "待发已移除，原生状态待同步") }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { s.dispatchStorageBlocked = true; s.view = s.view.copy(error = "无法保存删除状态，原生操作已暂停") }
            finally {
                if (s.dismissalJob === currentCoroutineContext()[Job]) { s.dismissalJob = null; s.dismissing = false }
                publish()
            }
        }
    }
    private fun changeLocalQueue(s: Live, id: String, text: String? = null, cancel: Boolean = false): Boolean {
        val message = s.record.submissions.firstOrNull { "submission-${it.id}" == id } ?: return false
        try { SubmissionLedger.changeLocal(s.record, message.id, text, cancel, ::persistDurable) }
        catch (e: SubmissionPersistenceFailure) { s.dispatchStorageBlocked = true; s.view = s.view.copy(error = e.message) }
        catch (e: Exception) { s.view = s.view.copy(error = e.message) }
        publish(); return true
    }
    override fun removeQueued(id: String) {
        val s = current() ?: return
        val entry = s.view.queue.firstOrNull { it.id == id } ?: return
        try { SubmissionLedger.dismiss(s.record, entry, ::persistDurable) }
        catch (e: SubmissionPersistenceFailure) { s.dispatchStorageBlocked = true; s.view = s.view.copy(error = "无法保存删除状态，待发仍保留") }
        publish()
    }
    override fun editQueued(id: String, text: String) {
        val s = current() ?: return
        if (changeLocalQueue(s, id, text)) return
        if (s.record.cli != "Codex") { s.view = s.view.copy(error = "此 CLI 原生队列不支持编辑正文"); publish(); return }
        queueMutation(s) {
            val raw = s.queueRaw.find { it.optString("id") == id } ?: error("消息已离开队列")
            val input = QueueEdits.replaceText(raw.getJSONArray("input"), text, s.record.cwd)
            check(input.length() > 0) { "消息不能为空" }
            s.rpc!!.request("thread/queue/update", obj("threadId" to s.record.threadId, "queuedSubmissionId" to id, "input" to input))
            refreshQueue(s)
        }
    }
    override fun restoreQueuedToComposer(id: String) {
        val s = current() ?: return
        try {
            check(!s.firstSendPending && !s.view.attachments.any { it.busy }) {
                "输入框正在准备内容，请先处理当前输入，再编辑待发消息；两边内容均保留"
            }
            val host = s.view.queue.firstOrNull { it.id == id && it.hostOwned && it.canEdit && !it.localRecovery }
            if (host != null) {
                check(!s.takingBack) { "正在取回待发消息，请稍候" }
                check(!s.view.loading) { "请先等待 CLI 观察同步后取回待发消息" }
                val clientId = host.clientUserMessageId ?: error("后台待发未提供精确身份，不能取回")
                check(host.id == clientId) { "后台待发身份不一致，不能取回" }
                val operation = HostQueueTakeback.begin(s.record, clientId, UUID.randomUUID().toString(), ::persistDurable)
                val expected = HostQueueTakeback.payload(checkNotNull(SubmissionLedger.find(s.record, clientId)))
                val adapter = s.nativeCli ?: error("CLI 观察尚未就绪")
                val epoch = s.generation
                s.takingBack = true; s.view = s.view.copy(queueBusy = true, error = null); publish()
                launch(s) {
                    try { adapter.takeBack(clientId, operation, expected) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (rejected: NativeCliRejected) {
                        SubmissionLedger.checkpoint(s.record, clientId, ::persistDurable) {
                            SubmissionLedger.find(s.record, clientId)?.takeIf { it.takebackState == "pending" }?.takebackState = "failed"
                        }
                        try { adapter.reconcileQueue() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) {  }
                        val current = SubmissionLedger.find(s.record, clientId)
                        if (s.generation == epoch && current != null && !current.dismissed && current.state in setOf("pending", "unknown", "accepted")) {
                            check(!s.view.attachments.any { it.busy }) { "输入框正在准备内容，请先处理当前输入，再编辑待发消息；两边内容均保留" }
                            SubmissionLedger.recoverStoredToComposer(s.record, clientId, host, ::persistDurable)
                            s.view = s.view.copy(error = null)
                        } else if (s.generation == epoch) s.view = s.view.copy(error = rejected.message)
                    } catch (_: Exception) {
                        if (s.generation == epoch) s.view = s.view.copy(error = "取回结果待核对；不会返回输入框或重发，请重新同步")
                    } finally { s.takingBack = false; if (s.generation == epoch) s.view = s.view.copy(queueBusy = false) }
                }
                return
            }
            val entry = s.view.queue.firstOrNull { it.id == id } ?: error("消息已离开待发队列，未返回输入框")
            val message = entry.clientUserMessageId?.let { SubmissionLedger.find(s.record, it) }
                ?: s.record.submissions.firstOrNull { "submission-${it.id}" == id }
                ?: error("原待发正文和附件不在本地索引中，无法无损恢复")
            if (SubmissionLedger.isLocal(message) || HostQueueTakeback.ready(message))
                SubmissionLedger.restoreLocalToComposer(s.record, message.id, ::persistDurable)
            else SubmissionLedger.recoverStoredToComposer(s.record, message.id, entry, ::persistDurable)
            s.view = s.view.copy(error = null)
        } catch (e: SubmissionPersistenceFailure) {
            s.dispatchStorageBlocked = true
            s.view = s.view.copy(error = "无法保存编辑状态，待发消息与当前输入均保留")
        } catch (e: Exception) { s.view = s.view.copy(error = e.message) }
        publish()
    }
    override fun steerQueued(id: String) {
        val s = current() ?: return
        if (s.record.cli in CliCatalog.bridged) { s.nativeCli?.queueChange(id, true); return }
        if (s.record.cli == "OpenCode") { s.openCode?.queueChange(id, true); return }
        queueMutation(s) {
            val turn = s.view.activeTurnId ?: error("当前任务已结束，消息保留在队列中")
            val raw = s.queueRaw.find { it.optString("id") == id } ?: error("消息已离开队列")
            val transfer = obj("id" to id, "input" to raw.getJSONArray("input"), "turn" to turn,
                "clientId" to UUID.randomUUID().toString(), "phase" to "deleting")
            s.record.queueTransfer = transfer.toString(); persist(); publish()
            try { s.rpc!!.request("thread/queue/delete", obj("threadId" to s.record.threadId, "queuedSubmissionId" to id)) }
            catch (e: RpcRejected) { s.record.queueTransfer = null; persist(); throw e }
            transfer.put("phase", "removed"); s.record.queueTransfer = transfer.toString(); persist()
            check(s.view.activeTurnId == turn) { "当前任务已结束，原消息已保存，可恢复到草稿" }
            transfer.put("phase", "steering"); s.record.queueTransfer = transfer.toString(); persist()
            try {
                s.rpc!!.request("turn/steer", obj("threadId" to s.record.threadId, "expectedTurnId" to turn,
                    "clientUserMessageId" to transfer.getString("clientId"), "input" to transfer.getJSONArray("input")))
                s.record.queueTransfer = null; persist()
            } catch (e: RpcRejected) {
                transfer.put("phase", "removed"); s.record.queueTransfer = transfer.toString(); persist(); throw e
            }
            refreshQueue(s)
        }
    }
    override fun resolveQueueRecovery(restore: Boolean) {
        val s = current() ?: return
        if (s.view.queueBusy) return
        launch(s) {
            val transfer = s.record.queueTransfer?.let(::JSONObject) ?: return@launch
            if (restore) {
                check(transfer.getString("phase") == "removed") { "请先核对后端执行结果，不能自动重发" }
                val input = transfer.getJSONArray("input").objects()
                val text = input.filter { it.optString("type") == "text" }.joinToString("\n") { it.optString("text") }
                val paths = input.filter { it.optString("type") == "localImage" }.map { it.getString("path") }
                val images = paths.map { path -> StoredImage(UUID.randomUUID().toString(), runtime.imagePreviewUri(s.record.environment, path) ?: error("原图片不可读取，恢复未执行"), path, path.substringAfterLast('/')) }
                check(input.all { it.optString("type") in listOf("text", "mention", "localImage", "skill") }) { "消息包含无法还原为草稿的内容，请保留记录并人工核对" }
                val mentions = input.filter { it.optString("type") == "mention" }.joinToString(" ") { "@\"${it.getString("path")}\"" }
                s.record.draft = listOf(s.record.draft, text, mentions).filter { it.isNotBlank() }.joinToString("\n")
                s.record.images += images
                input.filter { it.optString("type") == "skill" }.forEach { skill ->
                    val path = skill.getString("path")
                    if (s.record.skills.none { it.id == path }) s.record.skills += DraftSkill(path, skill.getString("name"), path)
                }
            }
            s.record.queueTransfer = null; persist(); if (s.view.connected) refreshQueue(s)
        }
    }
    override fun configure(model: String, effort: String) {
        val s = current() ?: return
        if (s.record.cli in CliCatalog.bridged) { s.nativeCli?.configure(obj("model" to model, "effort" to effort)); return }
        if (s.record.cli == "OpenCode") { s.openCode?.configure(model, effort); return }
        configureNative(obj("model" to model).apply { if (effort.isNotEmpty()) put("effort", effort) }) { live ->
            val option = live.models.firstOrNull { it.id == model } ?: error("该模型不在服务提供的列表中")
            check(effort.isEmpty() || effort in option.efforts) { "该模型不支持此推理强度" }
        }
    }
    override fun refreshDashboard() {
        val s = current() ?: return
        if (s.dispatchStorageBlocked) try { persist() }
        catch (e: Exception) { s.view = s.view.copy(error = "无法保存发送状态，待发仍保留"); publish(); return }
        if (s.record.cli in CliCatalog.bridged) { s.nativeCli?.refreshDashboard(); return }
        if (s.record.cli == "OpenCode") { s.openCode?.refreshDashboard(); return }
        if (!s.view.connected) return
        launch(s) {
            try { s.limits = CodexCapabilities.limitMetrics(s.rpc!!.request("account/rateLimits/read", JSONObject())) }
            catch (e: RpcRejected) { s.limits = emptyList() }
            if (s.tokens.isEmpty() && !s.rolloutPath.isNullOrBlank()) {
                runtime.readCodexRolloutTokenUsage(s.record.environment, s.rolloutPath!!)?.let { info ->
                    s.tokens = CodexCapabilities.tokenMetrics(info, s.record.model.ifBlank { s.view.summary.model }, s.view.speed)
                }
            }
            s.view = s.view.copy(metrics = s.tokens)
        }
    }
    private fun configureNative(params: JSONObject, validate: (Live) -> Unit) {
        val s = current() ?: return
        if (s.record.cli in CliCatalog.bridged) {
            val body = JSONObject()
            if (params.has("permissions")) body.put("permission", params.getString("permissions"))
            if (params.has("serviceTier")) body.put("speed", params.optString("serviceTier", "default"))
            s.nativeCli?.configure(body); return
        }
        if (s.record.cli == "OpenCode") { s.view = s.view.copy(error = "OpenCode 未提供此设置选项"); publish(); return }
        if (s.view.configuring) return
        s.view = s.view.copy(configuring = true); publish()
        launch(s) {
            try {
                check(s.view.connected && s.view.activeTurnId == null && nativeQueue(s).isEmpty() && !SubmissionLedger.pending(s.record)) { "请在任务与队列结束后修改设置" }
                validate(s)
                val waiter = CompletableDeferred<JSONObject>()
                s.settingsWaiter = waiter; s.settingsExpected = params
                s.rpc!!.request("thread/settings/update", params.put("threadId", s.record.threadId))
                val settings = withTimeoutOrNull(10000) { waiter.await() }
                check(settings != null) { "设置结果尚未同步，请重新连接核对；不会自动重试" }
            } finally {
                s.settingsWaiter = null; s.settingsExpected = null
                s.view = s.view.copy(configuring = false)
            }
        }
    }
    override fun configurePermission(id: String) = configureNative(obj("permissions" to id)) { s ->
        check(s.view.permissions.any { it.id == id }) { "CLI 未允许此权限配置" }
    }
    override fun configureSpeed(id: String) = configureNative(obj("serviceTier" to if (id == "default") JSONObject.NULL else id)) { s ->
        check(s.models.find { it.id == s.record.model }?.speeds?.any { it.id == id } == true) { "该模型未声明此速度选项" }
    }
    override fun command(id: String) {
        val s = current() ?: return
        when (id) {
            "compact" -> if (s.record.cli in CliCatalog.bridged) s.nativeCli?.compact() else if (s.record.cli == "OpenCode") s.openCode?.compact() else launch(s) { check(s.view.connected && s.view.activeTurnId == null && nativeQueue(s).isEmpty() && !SubmissionLedger.pending(s.record)) { "请等待当前任务与队列结束" }; s.rpc!!.request("thread/compact/start", obj("threadId" to s.record.threadId)) }
            "diff" -> previewDiff()
            "steer" -> steer()
            "stop" -> stop()
            "reconnect" -> reconnect()
        }
    }
    override fun openBrowser() {
        val s = current() ?: return
        runCatching { browser.bind(s.record.id, s.record.threadId) }
        mutableState.value = mutableState.value.copy(browserOpen = true)
    }
    override fun closeBrowser() {
        if (mutableState.value.browserOpen) {
            mutableState.value = mutableState.value.copy(browserOpen = false)
        }
    }
    override fun showInventory(kind: String) {
        val s = current() ?: return
        val generation = ++inventoryGeneration
        val title = if (kind == "skill") "Skill" else "MCP"
        mutableState.value = mutableState.value.copy(inventory = Inventory(title, loading = true))
        scope.launch {
            val result = try {
                check(s.view.connected) { "请先连接会话" }
                val entries = if (s.record.cli in CliCatalog.bridged) s.nativeCli!!.inventory(kind) else if (s.record.cli == "OpenCode") s.openCode!!.inventory(kind) else if (kind == "skill") {
                    val page = s.rpc!!.request("skills/list", obj("cwds" to JSONArray(listOf(s.record.cwd)), "forceReload" to false))
                    page.optJSONArray("data")?.objects().orEmpty().flatMap { group ->
                        group.optJSONArray("skills")?.objects().orEmpty().map { skill ->
                            ReadOnlyEntry(skill.getString("name"), skill.optString("description") + "\n" + if (skill.optBoolean("enabled")) "已启用" else "未启用", skill.getString("path"))
                        } + group.optJSONArray("errors")?.objects().orEmpty().map { ReadOnlyEntry("读取错误", CodexCapabilities.redact(it.toString(2))) }
                    }
                } else {
                    val entries = mutableListOf<ReadOnlyEntry>(); var next: String? = null
                    do {
                        val page = s.rpc!!.request("mcpServerStatus/list", obj("threadId" to s.record.threadId, "cursor" to next, "limit" to 100))
                        entries += page.optJSONArray("data")?.objects().orEmpty().map(CodexCapabilities::mcp)
                        next = page.stringOrNull("nextCursor")
                    } while (next != null)
                    entries
                }
                Inventory(title, entries)
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { Inventory(title, error = e.message ?: "读取失败") }
            if (selected == s.record.id && inventoryGeneration == generation) mutableState.value = mutableState.value.copy(inventory = result)
        }
    }
    override fun loadPlugins() {
        val s = current() ?: return
        val generation = ++inventoryGeneration
        mutableState.value = mutableState.value.copy(plugins = PluginCatalog(loading = true))
        scope.launch {
            val result = try {
                check(s.view.connected) { "请先连接会话" }
                if (s.record.cli in CliCatalog.bridged) s.nativeCli!!.plugins()
                else if (s.record.cli == "OpenCode") s.openCode!!.plugins()
                else {
                    val data = s.rpc!!.request("skills/list", obj("cwds" to JSONArray(listOf(s.record.cwd)), "forceReload" to false))
                    val groups = data.optJSONArray("data")?.objects().orEmpty()
                    val skills = groups.flatMap { it.optJSONArray("skills")?.objects().orEmpty() }.filter { it.optBoolean("enabled") }.map {
                        DraftSkill(it.getString("path"), it.getString("name"), it.getString("path"))
                    }.distinctBy { it.id }
                    val mcp = mutableListOf<ReadOnlyEntry>(); var cursor: String? = null
                    do {
                        val page = s.rpc!!.request("mcpServerStatus/list", obj("threadId" to s.record.threadId, "limit" to 100, "cursor" to cursor))
                        mcp += page.optJSONArray("data")?.objects().orEmpty().map(CodexCapabilities::mcp)
                        cursor = page.stringOrNull("nextCursor")
                    } while (cursor != null)
                    PluginCatalog(skills, mcp, error = groups.flatMap { it.optJSONArray("errors")?.objects().orEmpty() }.takeIf { it.isNotEmpty() }?.joinToString("\n") { CodexCapabilities.redact(it.toString()) })
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { PluginCatalog(error = e.message ?: "读取插件失败") }
            if (selected == s.record.id && inventoryGeneration == generation) mutableState.value = mutableState.value.copy(plugins = result)
        }
    }
    override fun toggleSkill(skill: DraftSkill) {
        val s = current()
        if (s == null) {
            launch(null) {
                if (blank.skills.any { it.id == skill.id }) blank.skills.removeAll { it.id == skill.id }
                else { check(mutableState.value.plugins.skills.any { it == skill }); blank.skills += skill }
                persist()
            }
            return
        }
        if (s.firstSendPending) return
        launch(s) {
            if (s.record.skills.any { it.id == skill.id }) s.record.skills.removeAll { it.id == skill.id }
            else {
                check(mutableState.value.plugins.skills.any { it == skill }) { "该 Skill 不在当前 CLI 提供的列表中" }
                check(s.record.skills.size < s.view.capabilities.maxSkills) { "此 CLI 每条消息最多选择 ${s.view.capabilities.maxSkills} 个 Skill" }
                s.record.skills += skill
            }
            s.record.draftRevision++; persist()
        }
    }
    override fun closeInventory() { inventoryGeneration++; mutableState.value = mutableState.value.copy(inventory = null) }
    override fun previewInventory(entry: ReadOnlyEntry) {
        val s = current() ?: return
        val originalName = io.github.modao7067.luma.gui.runtime.MediaSourcePolicy.name(entry.name)
        val originalMime = io.github.modao7067.luma.gui.runtime.MediaSourcePolicy.mime(originalName)
        val textual = io.github.modao7067.luma.gui.runtime.MediaSourcePolicy.isText(originalMime)
        val name = if (textual) originalName else "$originalName.txt"
        val mime = if (textual) originalMime else "text/plain"
        preview(s, entry.name, snapshot = { redacted ->
            MediaResource("data:$mime;base64," + java.util.Base64.getEncoder().encodeToString(redacted.toByteArray(Charsets.UTF_8)),
                name, mime, s.record.id, s.record.cwd)
        }) { CodexCapabilities.redact(entry.path?.let { runtime.readFile(s.record.environment, it) } ?: entry.detail) }
    }
    override fun answer(requestId: String, choice: String, answers: Map<String, List<String>>) {
        val s = current() ?: return
        if (s.record.cli in CliCatalog.bridged) { s.nativeCli?.answer(requestId, choice, answers); return }
        if (s.record.cli == "OpenCode") { s.openCode?.answer(requestId, choice, answers); return }
        val async = asyncQuestions(s).find { it.id == requestId }
        if (async != null) {
            if (async.answering || !s.view.connected || s.view.loading) return
            launch(s) {
                val current = asyncQuestions(s).find { it.id == requestId } ?: return@launch
                if (current.answering) return@launch
                check(choice != "skip") { "此原生异步提问不支持跳过" }
                val text = CodexAsyncQuestions.reply(current, answers)
                val id = CodexAsyncQuestions.submissionId(text, s.record.submissions.map { CodexAsyncQuestions.Submission(it.text, it.state) })
                submitCodex(s, false, ManagedSubmission(id, text))
            }
            return
        }
        val request = s.requests[requestId] ?: return
        if (request.answering || !s.view.connected) return
        launch(s) {
            val response = request.response(choice, answers)
            request.answering = true; publish()
            s.rpc!!.respond(request.rawId, response)
        }
    }
    override fun renameSession(id: String, title: String) {
        val s = sessions[id]?.takeIf { it.record.environment == environment } ?: return
        try { renameLocal(s, title) } catch (e: Exception) { s.view = s.view.copy(error = e.message); publish() }
    }
    private fun renameLocal(s: Live, title: String) {
        val trimmed = title.trim(); require(trimmed.isNotEmpty()) { "会话标题不能为空" }
        val previous = s.record.manualTitle
        SessionTitlePolicy.rename(s.record, trimmed)
        try { persist() } catch (e: Exception) {
            s.record.manualTitle = previous; SessionTitlePolicy.refresh(s.record)
            throw e
        }
        publish()
    }
    override fun archiveSession(id: String) {
        val s = sessions[id]?.takeIf { it.record.environment == environment } ?: return
        val wasSelected = selected == id
        val previous = sessions.toMap(); val previousState = mutableState.value
        val previousKey = blank.key; val previousCli = blank.cli; val previousCwd = blank.cwd
        sessions.remove(id)
        if (wasSelected) {
            blank.cli = s.record.cli; blank.cwd = s.record.cwd
            enterBlank()
        }
        try { persist() } catch (e: Exception) {
            sessions.clear(); sessions.putAll(previous)
            if (wasSelected) selected = id
            store.navigation.selectedId = selected
            blank.key = previousKey; blank.cli = previousCli; blank.cwd = previousCwd
            mutableState.value = previousState.copy(error = "无法归档会话，原会话已保留：${e.message}")
            publish(); return
        }
        disposeSession(s, destroyBackend = true)
        browser.closeOwned(id)
        if (wasSelected) preferences.edit().remove("session").apply()
        publish()
    }
    override fun openTui() {
        val s = current() ?: return
        launch(s) {
            check(s.view.canOpenTui) { "会话尚未准备好，请重新连接" }
            val nativeTerminal = s.nativeCli?.terminal()
            persist()
            runtime.openTerminal(context, s.record.environment, s.endpoint, s.record.threadId, s.record.cwd, false, s.record.cli, nativeTerminal)
        }
    }
    override fun openShell(environment: String, cwd: String) { launch(null) { require(environment == this@SessionController.environment); runtime.openTerminal(context, environment, null, null, cwd, true) } }
    override fun browseFiles(path: String) = openFiles(path, mutableState.value.fileBrowser?.picking == true)
    override fun pickFiles(path: String) = openFiles(path, picking = true)
    private fun openFiles(path: String, picking: Boolean) {
        val s = current() ?: return
        val generation = ++fileGeneration
        previewGeneration++
        mutableState.value = mutableState.value.copy(filePreview = null, fileBrowser = FileBrowser(path, loading = true, picking = picking))
        scope.launch {
            val browser = try {
                FileBrowser(path, runtime.listFiles(s.record.environment, path).map { FileEntry(it.name, it.path, it.directory, it.size, it.modified) }, picking = picking)
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { FileBrowser(path, error = e.message ?: "读取目录失败", picking = picking) }
            if (selected == s.record.id && generation == fileGeneration) mutableState.value = mutableState.value.copy(fileBrowser = browser)
        }
    }
    override fun closeFiles() { fileGeneration++; mutableState.value = mutableState.value.copy(fileBrowser = null) }
    override fun referenceFile(path: String) = referenceFiles(listOf(path))
    override fun referenceFiles(paths: List<String>) {
        val s = current() ?: return
        if (paths.isNotEmpty()) draft(s.record.draft + (if (s.record.draft.isEmpty() || s.record.draft.endsWith(' ')) "" else " ") + io.github.modao7067.luma.gui.ui.fileReferenceText(paths) + " ")
        closeFiles()
    }
    private fun preview(s: Live, title: String, cwd: String = s.record.cwd, sourceResource: MediaResource? = null, snapshot: ((String) -> MediaResource)? = null,
                        display: (String) -> String = { it }, read: suspend () -> String) {
        val generation = ++previewGeneration
        mutableState.value = mutableState.value.copy(filePreview = FilePreview(title, "", true, sessionId = s.record.id, cwd = cwd, sourceResource = sourceResource))
        scope.launch {
            var original = sourceResource
            val text = try {
                val raw = read(); val shown = display(raw)
                original = snapshot?.invoke(raw) ?: sourceResource
                shown
            } catch (e: CancellationException) { throw e }
                catch (e: Exception) { "无法读取：${e.message ?: "未知错误"}" }
            if (selected == s.record.id && generation == previewGeneration) mutableState.value = mutableState.value.copy(filePreview = FilePreview(title, text, sessionId = s.record.id, cwd = cwd, sourceResource = original))
        }
    }
    override fun previewFile(path: String) {
        val s = current() ?: return
        previewResource(MediaResource(path, sessionId = s.record.id, cwd = s.record.cwd))
    }
    override fun previewResource(resource: MediaResource) {
        val (s, captured) = try { mediaOwner(resource) } catch (e: Exception) {
            mutableState.value = mutableState.value.copy(error = e.message); return
        }
        val owner = s ?: return
        if (captured.mimeType == "inode/directory") {
            val guestPath = try { io.github.modao7067.luma.gui.runtime.MediaSourcePolicy.guestPath(captured.source, captured.cwd ?: owner.record.cwd) } catch (_: Exception) { captured.source }
            browseFiles(guestPath)
            return
        }
        val title = captured.name.ifBlank { io.github.modao7067.luma.gui.runtime.MediaSourcePolicy.name(captured.source) }
        val mime = previewMime(captured, title)
        if (!io.github.modao7067.luma.gui.runtime.MediaSourcePolicy.isText(mime)) {
            val isLocalGuest = !captured.source.startsWith("http:") && !captured.source.startsWith("https:") &&
                !captured.source.startsWith("data:") && !captured.source.startsWith("codex-")
            if (isLocalGuest) {
                scope.launch {
                    val inspected = try { runtime.inspectMedia(owner.record.environment, captured) } catch (_: Exception) { captured }
                    if (inspected.mimeType == "inode/directory") {
                        val guestPath = try { io.github.modao7067.luma.gui.runtime.MediaSourcePolicy.guestPath(captured.source, captured.cwd ?: owner.record.cwd) } catch (_: Exception) { captured.source }
                        browseFiles(guestPath)
                        return@launch
                    }
                    if (io.github.modao7067.luma.gui.runtime.MediaSourcePolicy.isText(inspected.mimeType)) {
                        if (selected == owner.record.id) previewTextResource(owner, title, inspected)
                        return@launch
                    }
                    if (selected == owner.record.id) {
                        previewGeneration++
                        mutableState.value = mutableState.value.copy(filePreview = FilePreview(title, "", media = inspected))
                    }
                }
                return
            }
            previewGeneration++
            mutableState.value = mutableState.value.copy(filePreview = FilePreview(title, "", media = captured))
            return
        }
        previewTextResource(owner, title, captured)
    }
    private fun previewMime(captured: MediaResource, title: String): String {
        val byName = io.github.modao7067.luma.gui.runtime.MediaSourcePolicy.mime(title)
        val declared = captured.mimeType.substringBefore(';').trim()
        return when {
            declared.isBlank() || declared.endsWith("/*") || declared == "application/octet-stream" -> byName
            io.github.modao7067.luma.gui.runtime.MediaSourcePolicy.isText(byName) && !declared.startsWith("image/") && !declared.startsWith("audio/") -> byName
            else -> declared
        }
    }
    private fun previewTextResource(owner: Live, title: String, captured: MediaResource) {
        val documentCwd = try { io.github.modao7067.luma.gui.runtime.MediaSourcePolicy.documentDirectory(captured.source, captured.cwd ?: owner.record.cwd) }
            catch (_: Exception) { captured.cwd ?: owner.record.cwd }
        preview(owner, title, documentCwd, sourceResource = captured) {
            val isLocalGuest = !captured.source.startsWith("http:") && !captured.source.startsWith("https:") &&
                !captured.source.startsWith("data:") && !captured.source.startsWith("codex-")
            if (isLocalGuest) {
                val inspected = try { runtime.inspectMedia(owner.record.environment, captured) } catch (_: Exception) { null }
                if (inspected?.mimeType == "inode/directory") {
                    val guestPath = try { io.github.modao7067.luma.gui.runtime.MediaSourcePolicy.guestPath(captured.source, captured.cwd ?: owner.record.cwd) } catch (_: Exception) { captured.source }
                    withContext(Dispatchers.Main.immediate) { browseFiles(guestPath) }
                    return@preview ""
                }
            }
            val resolved = runtime.resolveMedia(owner.record.environment, dereferenceMedia(owner, captured))
            withContext(Dispatchers.IO) {
                check(resolved.sizeBytes <= 512 * 1024) { "文本超过 512 KiB，请使用外部应用打开" }
                val bytes = java.io.File(Uri.parse(resolved.uri).path!!).readBytes()
                Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            }
        }
    }
    override fun previewDiff() {
        val s = current() ?: return
        var warning = ""
        preview(s, "工作区 Diff", snapshot = { raw ->
            MediaResource("data:text/x-diff;base64," + java.util.Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8)),
                "changes.patch", "text/x-diff", s.record.id, s.record.cwd)
        }, display = { raw -> (raw + warning).ifBlank { "没有已跟踪文件的变更；未跟踪文件请从文件列表查看。" } + "\n\n预览最多 512 KiB；仅包含已跟踪文件。" }) {
            check(s.view.connected) { "请先连接会话" }
            check(s.record.cli !in CliCatalog.bridged) { "此 CLI 协议不提供 Diff；请在终端使用 git diff" }
            if (s.record.cli == "OpenCode") return@preview s.openCode!!.diff()
            val result = s.rpc!!.request("command/exec", obj("command" to JSONArray(listOf("git", "--no-pager", "diff", "--no-ext-diff", "HEAD")), "cwd" to s.record.cwd, "outputBytesCap" to 524288))
            warning = result.optString("stderr")
            if (result.optInt("exitCode") != 0) throw java.io.IOException("git diff 退出码 ${result.optInt("exitCode")}\n${result.optString("stdout")}$warning")
            result.optString("stdout")
        }
    }
    override fun closePreview() { previewGeneration++; mutableState.value = mutableState.value.copy(filePreview = null) }
    override fun dismissError() { current()?.openCode?.dismissError(); current()?.nativeCli?.dismissError(); current()?.let { it.view = it.view.copy(error = null) }; mutableState.value = mutableState.value.copy(error = null); publish() }
    fun saveDrafts() { saveJob?.cancel(); try { persist(); store.flush() } catch (e: Exception) { mutableState.value = mutableState.value.copy(error = "无法保存草稿：${e.message}"); publish() } }
    fun disconnectAll() {
        wakeEpoch++
        clearSubAgentObservation()
        if (GuiProduct.usesRootExecution(context)) {
            rootWatcherArmed = false
            lastArmedRootSessions = emptyList()
            RootBackgroundNotifications.disarmLocal(context)
        }
        sessions.values.forEach { s ->
            val idle = canReleaseIdle(s, backgroundOnly = false)
            val releaseEndpoint = if (idle && s.record.cli in CliCatalog.bridged) (s.endpoint ?: s.nativeCli?.endpoint) else null
            val releaseThreadId = s.record.threadId
            val releaseProtocol = s.record.nativeProtocol
            s.notificationTracker.clear()
            s.userStoppedTurn = false
            s.idleJob?.cancel(); s.idleJob = null
            s.observing = false; s.generation++
            s.connectJob?.cancel(); s.connectJob = null
            s.reconnectJob?.cancel(); s.reconnectJob = null
            s.firstSendJob?.cancel(); s.firstSendJob = null; s.firstSendScheduled = false
            s.dispatchJob?.cancel(); s.dispatchJob = null; s.dispatchingLocal = false
            s.dismissalJob?.cancel(); s.dismissalJob = null; s.dismissing = false
            s.queueRefresh?.cancel(); s.queueRefresh = null
            s.hydrating = false; s.buffered.clear()
            s.rpc?.close(); s.openCode?.close(); s.nativeCli?.close()
            s.view = s.view.copy(connected = false, loading = false, sending = false, configuring = false, queueBusy = false,
                connection = "界面已断开 · 后端任务不受影响", canOpenTui = false, requests = emptyList())
            s.requests.clear(); s.admitting.clear()
            if (idle) {
                dropIdleCache(s)
                if (s.record.cli in CliCatalog.bridged && !releaseThreadId.isNullOrBlank()) {
                    val cli = s.record.cli; val env = s.record.environment; val sid = s.record.id
                    scope.launch(Dispatchers.IO) {
                        runtime.releaseBridgedSessionIfRunning(cli, env, sid, releaseThreadId, releaseProtocol, releaseEndpoint)
                    }
                }
            }
        }
        syncOpenCodeDaemonIdleTimer()
        notificationSink.cancelAll()
        publish()
        store.flush()
    }
}

internal fun acceptCodexRequest(parentThreadId: String?, subAgents: CodexSubAgents, approval: PendingApproval): Boolean =
    !parentThreadId.isNullOrBlank() && (
        approval.threadId == parentThreadId ||
            subAgents.acceptsRequestsFrom(approval.threadId) ||
            approval.threadId.isEmpty()
    )

internal fun putCanonicalCodexUserItem(
    items: MutableMap<String, ConversationItem>,
    nativeToClientId: MutableMap<String, String>,
    item: JSONObject,
    completed: Boolean? = null,
    turnId: String = "",
): ConversationItem {
    val nativeId = item.stringOrNull("id")
    val isUser = item.optString("type") == "userMessage"
    val explicitClient = if (isUser) CodexProjection.clientMessageId(item)?.takeIf { it.isNotBlank() } else null
    if (!nativeId.isNullOrBlank() && explicitClient != null) {
        nativeToClientId[nativeId] = explicitClient
    }
    val canonicalHint = if (isUser) (explicitClient ?: nativeId?.let(nativeToClientId::get)) else null
    val previous = canonicalHint?.let(items::get) ?: nativeId?.let(items::get)
    val raw = CodexProjection.item(item, previous)
    val canonicalId = canonicalHint ?: raw.id
    val entry = raw.copy(
        id = canonicalId,
        outcome = if (raw.outcome != "unknown" || completed == null) raw.outcome else if (completed) "succeeded" else "running",
        turnId = turnId.ifEmpty { previous?.turnId.orEmpty() },
    )
    if (!nativeId.isNullOrBlank() && nativeId != canonicalId && items.containsKey(nativeId)) {
        val rebuilt = LinkedHashMap<String, ConversationItem>(items.size)
        for ((k, v) in items) {
            when (k) {
                nativeId -> if (!items.containsKey(canonicalId)) rebuilt[canonicalId] = entry
                canonicalId -> rebuilt[canonicalId] = entry
                else -> rebuilt[k] = v
            }
        }
        items.clear()
        items.putAll(rebuilt)
    } else {
        items[canonicalId] = entry
    }
    return entry
}

internal fun reconcileCodexIdleSubmissions(
    record: SessionRecord,
    historyResumable: Boolean,
    items: Map<String, ConversationItem>,
    nativeToClientId: Map<String, String>,
    queueRaw: List<JSONObject>,
    admitting: Set<String>,
): Boolean {
    val submissionIds = record.submissions.mapTo(HashSet()) { it.id }
    val hasUncorrelatedNativeUser = items.values.any { it.role == "user" && it.nativeInputEvidence && it.id !in submissionIds }
    if (historyResumable && hasUncorrelatedNativeUser) return false
    val knownClientIds = HashSet<String>().apply {
        addAll(items.keys)
        addAll(nativeToClientId.values)
        queueRaw.mapNotNullTo(this) { it.stringOrNull("clientUserMessageId") }
        val queuedNativeIds = queueRaw.mapNotNullTo(HashSet()) { it.stringOrNull("id") }
        record.submissions.filter { it.nativeQueueId != null && it.nativeQueueId in queuedNativeIds }.mapTo(this) { it.id }
    }
    var changed = false
    for (message in record.submissions) {
        if (message.state in setOf("pending", "unknown") &&
            !message.queuePresentation &&
            !message.dismissed &&
            message.id !in admitting &&
            message.id !in record.receiptRecoveryRequired &&
            message.id !in knownClientIds
        ) {
            SubmissionLedger.rejected(record, message.id)
            changed = true
        }
    }
    return changed
}
