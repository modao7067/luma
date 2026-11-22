package io.github.modao7067.luma.gui.session

import io.github.modao7067.luma.gui.i18n.tr

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
    private val basePricingCatalog by lazy { CodexPricingCatalog.fromAssets(context) }
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
    private var storeLoadFailed = false
    private var storeLoadFailureDetail: String? = null
    private val storeError: String?
        get() = if (!storeLoadFailed) null else tr(
            "无法读取会话索引，原文件已保留：${storeLoadFailureDetail.orEmpty()}",
            "Unable to read session index (original file preserved): ${storeLoadFailureDetail.orEmpty()}",
        )
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
    private val foregroundTracker = ForegroundActivityTracker()
    private val defaultForegroundToken = Any()
    private var foreground = false
    private var backgrounded = false
    private var idleGraceMillis = SessionIdlePolicy.graceMillis
    private var openCodeIdleJob: Job? = null
    private var codexIdleJob: Job? = null
    private var lastOpenCodeEndpoint: RuntimeEndpoint? = null
    private data class ForegroundTerminalSnapshot(
        val sessionId: String = "",
        val instanceId: String = "",
        val updatedAt: Long = -1L,
    ) {
        fun isFresh(now: Long = System.currentTimeMillis()): Boolean =
            updatedAt > 0L && now - updatedAt <= 180_000L

        fun matches(s: Live, now: Long = System.currentTimeMillis()): Boolean {
            if (!isFresh(now)) return false
            val sessionMatch = sessionId == s.record.id
            val instanceIdOwned = s.nativeCli?.ownerInstanceId
            val instanceMatch = !instanceIdOwned.isNullOrBlank() && instanceId == instanceIdOwned
            return sessionMatch || instanceMatch
        }
    }
    @Volatile private var foregroundTerminalSnapshot: ForegroundTerminalSnapshot? = null
    private var foregroundTerminalRefreshJob: Job? = null
    private var lastForegroundTerminalRefreshMs = 0L
    private val activityClock = SessionActivityClock()
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
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
        val durationTracker = io.github.modao7067.luma.gui.ui.SessionProcessDurationTracker()
        val notificationTracker = SessionNotificationTracker()
        var userStoppedTurn = false
        var view = SessionView(
            summary(record),
            draft = record.draft,
            uncertainSubmission = record.submissionId != null,
            processDurations = if (record.processDurations.isEmpty()) emptyMap() else LinkedHashMap(record.processDurations),
        )
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
        val endedTurns = LinkedHashMap<String, String>()
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
        val tokenPricer = CodexSessionPricer(CodexPricingCatalog.default())
        val observedQueueIds = hashSetOf<String>()
        var rawScopedSource: List<ConversationItem>? = null
        var rawScopedSessionId: String? = null
        var rawScopedCwd: String? = null
        var rawScopedThreadId: String? = null
        var cachedScopedMessages: List<ConversationItem> = emptyList()
        companion object {
            fun summary(r: SessionRecord) = SessionSummary(
                r.id, if (SessionTitlePolicy.isUnnamed(r.title)) SessionTitlePolicy.unnamed else r.title, r.environment, r.cwd, r.model,
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
        catch (failure: Exception) { storeLoadFailed = true; storeLoadFailureDetail = failure.message }
        selected = restoredSelection(store.navigation, sessions.values.map { it.record }, preferences.getString("session", null), environment)
        store.navigation.initialized = true
        store.navigation.selectedId = selected
        publish()
        refreshForegroundTerminalSnapshot(force = true)
        current()?.let { live -> startObservation(live) }
        val managedStates = commands.entries.values.groupBy({ it.sessionId }, { it.state })
        sessions.values.filter {
            it !== current() && it.record.environment == environment &&
                SessionIdlePolicy.restoreObservation(it.record, managedStates[it.record.id].orEmpty())
        }.forEach { live -> startObservation(live) }
    }
    fun onLocaleChanged() {
        scope.launch {
            context.getSystemService(NotificationManager::class.java)?.let(SessionNotifications::ensureChannels)
            sessions.values.forEach { s ->
                SessionTitlePolicy.refresh(s.record)
                val updatedConnection = when {
                    s.dormant -> tr("休眠", "Dormant")
                    s.view.connection == "界面已断开 · 后端任务不受影响" || s.view.connection == "UI detached · Backend tasks unaffected" ->
                        tr("界面已断开 · 后端任务不受影响", "UI detached · Backend tasks unaffected")
                    s.view.connection == "已连接" || s.view.connection == "Connected" ->
                        tr("已连接", "Connected")
                    s.view.connection == "连接中断 · 任务状态待同步" || s.view.connection == "Disconnected · Task state syncing" ->
                        tr("连接中断 · 任务状态待同步", "Disconnected · Task state syncing")
                    s.view.connection == "连接运行环境…" || s.view.connection == "Connecting to runtime…" ->
                        tr("连接运行环境…", "Connecting to runtime…")
                    else -> null
                }
                if (updatedConnection != null && updatedConnection != s.view.connection) {
                    s.view = s.view.copy(connection = updatedConnection)
                }
                s.nativeCli?.onLocaleChanged()
                s.openCode?.onLocaleChanged()
            }
            lastKeepAliveSummary = null
            publish()
        }
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
        check(resource.sessionId == null || owner != null) { tr("来源会话已关闭，请重新打开附件", "Source session was closed; reopen the attachment") }
        return owner to resource.copy(sessionId = resource.sessionId ?: owner?.record?.id,
            cwd = resource.cwd ?: owner?.record?.cwd ?: blank.cwd)
    }
    private suspend fun dereferenceMedia(owner: Live?, resource: MediaResource): MediaResource {
        val params = MediaProjection.mcpParams(resource.source) ?: return resource
        val connection = owner?.rpc ?: error(tr("请重连来源会话后查看 MCP 资源", "Reconnect source session to view MCP resource"))
        if (!params.has("threadId")) params.put("threadId", owner.record.threadId ?: error(tr("来源会话尚未建立", "Source session is not created yet")))
        val result = connection.request("mcpServer/resource/read", params)
        val uri = params.getString("uri")
        val content = result.optJSONArray("contents")?.objects().orEmpty().firstOrNull { it.optString("uri") == uri }
            ?: error(tr("MCP 服务未返回所选资源", "MCP server did not return the requested resource"))
        val embedded = MediaProjection.blocks(obj("type" to "resource", "resource" to content)).singleOrNull()
            ?: error(tr("MCP 资源没有可读取内容", "MCP resource has no readable content"))
        check(embedded.source != uri) { tr("MCP 资源未提供文件内容", "MCP resource did not provide file content") }
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
        store.save(sessions.values.map { it.record }) {
            scope.launch {
                var cleared = false
                sessions.values.forEach {
                    if (it.dispatchStorageBlocked) {
                        it.dispatchStorageBlocked = false
                        cleared = true
                    }
                }
                if (cleared) publish()
            }
        }
    }
    private fun persistDurable() {
        check(storeError == null) { storeError!! }
        store.navigation.selectedId = selected
        store.saveDurable(sessions.values.map { it.record })
        sessions.values.forEach { it.dispatchStorageBlocked = false }
    }
    private suspend fun persistDurableAsync() {
        check(storeError == null) { storeError!! }
        store.navigation.selectedId = selected
        store.saveDurableAsync(sessions.values.map { it.record }, ioDispatcher)
        sessions.values.forEach { it.dispatchStorageBlocked = false }
    }
    private fun inCliMaintenance(s: Live): Boolean = cliMaintenanceJob?.isActive == true && s.record.cli in CliCatalog.bridged
    private fun canDispatchLocal(s: Live) = !s.releasing && s.observing && s.view.connected && !s.view.loading && !s.view.configuring && !s.dispatchStorageBlocked &&
        (s.record.cli !in CliCatalog.bridged || s.nativeCli?.canDispatchLocal == true)
    private fun cliServiceUpdateImpact(s: Live): String {
        val task = NativeCliRuntimePolicy.task(s.record.cli)
        val clis = CliCatalog.ids.filter { it in CliCatalog.bridged && NativeCliRuntimePolicy.task(it) == task }
        val running = sessions.values.filter { it.record.environment == s.record.environment && it.record.cli in clis &&
            it.view.connected && !it.view.loading && it.view.activeTurnId != null }.map { it.record.cli }
        return tr("更新目标：${s.record.environment} · $task。\n", "Update target: ${s.record.environment} · $task.\n") + NativeCliStatePolicy.serviceUpdateImpact(clis, running)
    }
    private fun publish() {
        var consumptionChanged = false
        var observationChanged = false
        var activityChanged = false
        var durationsChanged = false
        sessions.values.forEach { s ->
            val returned = s.record.submissions.firstOrNull { HostQueueTakeback.ready(it) && it.takebackDraftRevision == s.record.draftRevision }
            if (returned != null && !s.dispatchStorageBlocked && !s.firstSendPending && !s.view.attachments.any { it.busy } &&
                s.record.draft.isEmpty() && s.record.images.isEmpty() && s.record.skills.isEmpty()) {
                try { SubmissionLedger.restoreLocalToComposer(s.record, returned.id, ::persistDurable) }
                catch (failure: SubmissionPersistenceFailure) { s.dispatchStorageBlocked = true; s.view = s.view.copy(error = tr("取回内容已保留，但无法保存到输入框；请稍后点击编辑", "Taken-back content is preserved, but could not be saved to composer; tap Edit shortly")) }
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
                    catch (e: Exception) { s.view = s.view.copy(error = e.message ?: tr("首次发送失败，输入已保留", "Initial send failed; input preserved")) }
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
                                    if (s.generation == epoch) s.view = s.view.copy(error = tr("待发消息的会话设置已改变", "Session settings changed for queued message")); continue
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
                                    if (s.generation == epoch) s.view = s.view.copy(error = e.message)
                                }
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            if (s.generation == epoch) {
                                s.dispatchStorageBlocked = true
                                s.view = s.view.copy(error = e.message ?: tr("待发未能保存，消息仍保留", "Failed to save queue state; message preserved"))
                            }
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
            if (s.dormant) {
                s.durationTracker.reconcile(s.record.processDurations, false, false, false, null, emptyList(), emptyList())
                val durationsSnapshot = if (s.record.processDurations.isEmpty()) emptyMap() else LinkedHashMap(s.record.processDurations)
                s.view = s.view.copy(
                    summary = Live.summary(s.record).copy(status = tr("休眠", "Dormant")),
                    draft = s.record.draft,
                    processDurations = if (s.view.processDurations == durationsSnapshot) s.view.processDurations else durationsSnapshot,
                )
            } else {
                val projectedView = SubmissionLedger.project(s.record, s.view.copy(preparingFirstSend = s.firstSendPending,
                    cliServiceUpdateImpact = if (s.view.cliServiceUpdateRequired || s.view.cliServiceUpdateAvailable) cliServiceUpdateImpact(s) else "",
                    summary = Live.summary(s.record).copy(
                    modelLabel = s.models.find { it.id == s.record.model }?.label.orEmpty(),
                    hasUnreadDot = s.record.unread || effectiveRequests.isNotEmpty(),
                    status = when {
                    (if (s.record.cli != "Codex") s.view.requests.isNotEmpty() else s.requests.isNotEmpty()) -> tr("等待处理", "Action required")
                    s.view.activeTurnId != null -> if (s.view.connected) tr("执行中", "Running") else tr("执行状态待同步", "Run state syncing")
                    s.view.connected && codexRequests.isNotEmpty() -> tr("待回答", "Awaiting reply")
                    s.view.connected -> tr("空闲", "Idle")
                    s.dormant -> tr("休眠", "Dormant")
                    else -> tr("未连接", "Disconnected")
                }), effort = s.record.effort,
                    messages = effectiveMessages,
                    subAgents = if (s.record.cli != "Codex") s.view.subAgents else s.subAgents.values(), requests = effectiveRequests,
                    skills = s.record.skills.toList(),
                    queueRecovery = s.record.queueTransfer?.let { raw -> val t = JSONObject(raw); QueueRecovery(CodexProjection.inputText(t.getJSONArray("input")), t.getString("phase")) },
                    attachments = s.record.images.map { Attachment(it.id, it.uri, it.name) } + s.view.attachments.filter { it.busy }), s.deliveryItems).let { view -> view.copy(messages = scopeSessionMessages(s, view.messages)) }
                if (s.durationTracker.reconcile(
                        s.record.processDurations,
                        projectedView.connected,
                        projectedView.loading,
                        s.hydrating,
                        projectedView.activeTurnId,
                        projectedView.messages,
                        projectedView.subAgents,
                    )
                ) durationsChanged = true
                val durationsSnapshot = if (s.record.processDurations.isEmpty()) emptyMap() else LinkedHashMap(s.record.processDurations)
                s.view = if (projectedView.processDurations == durationsSnapshot) projectedView else projectedView.copy(processDurations = durationsSnapshot)
            }
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
        if (consumptionChanged || observationChanged || activityChanged || durationsChanged) try { persist() } catch (failure: Exception) {
            current()?.let { it.view = it.view.copy(error = tr("无法保存会话同步状态：${failure.message}", "Failed to save session sync state: ${failure.message}")) }
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
    private fun readForegroundTerminalSnapshot(): ForegroundTerminalSnapshot? {
        val file = java.io.File(java.io.File(context.filesDir, "runtime"), "foreground-terminal.json")
        if (!file.isFile) return null
        val info = try { JSONObject(file.readText()) } catch (_: Exception) { return null }
        val updatedAt = info.optLong("updatedAt", -1L)
        if (updatedAt <= 0L || System.currentTimeMillis() - updatedAt > 180_000L) return null
        val pid = info.optInt("pid", 0)
        val expectedStart = info.optString("processStart", "")
        if (pid > 0 && expectedStart.isNotEmpty()) {
            val statFile = java.io.File("/proc/$pid/stat")
            if (statFile.exists()) {
                val actualStart = try {
                    val raw = statFile.readText()
                    val end = raw.lastIndexOf(')')
                    if (end < 0) "" else raw.substring(end + 1).trim().split(Regex("\\s+")).let {
                        if (it.size > 19 && it[0] != "Z") it[19] else ""
                    }
                } catch (_: Exception) { null }
                if (actualStart != null && actualStart != expectedStart) return null
            } else if (java.io.File("/proc/self/stat").exists()) {
                return null
            }
        }
        return ForegroundTerminalSnapshot(
            sessionId = info.optString("sessionId", ""),
            instanceId = info.optString("instanceId", ""),
            updatedAt = updatedAt,
        )
    }

    private fun refreshForegroundTerminalSnapshot(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && foregroundTerminalRefreshJob?.isActive == true) return
        if (!force && now - lastForegroundTerminalRefreshMs < 2_000L) return
        lastForegroundTerminalRefreshMs = now
        if (force) foregroundTerminalRefreshJob?.cancel()
        foregroundTerminalRefreshJob = scope.launch {
            val fresh = withContext(ioDispatcher) { readForegroundTerminalSnapshot() }
            if (fresh != foregroundTerminalSnapshot) {
                foregroundTerminalSnapshot = fresh
                publish()
                syncOpenCodeDaemonIdleTimer()
                syncCodexDaemonIdleTimer()
            }
        }
    }

    private fun isDisplayedInForegroundTerminal(s: Live): Boolean =
        foregroundTerminalSnapshot?.matches(s) == true

    private fun canReleaseIdle(
        s: Live,
        backgroundOnly: Boolean = true,
        allowForegroundTerminal: Boolean = false,
    ): Boolean =
        (allowForegroundTerminal || !isDisplayedInForegroundTerminal(s)) &&
            (!backgroundOnly || backgrounded || (selected != s.record.id && !foregroundTracker.isPinned(s.record.id))) &&
            s.observing && !s.dormant &&
            s.connectJob?.isActive != true && !s.firstSendPending && !s.dispatchingLocal && !s.dismissing &&
            childObservation?.parentId != s.record.id && SessionIdlePolicy.canAutoRelease(s.record, s.view) &&
            when {
                s.record.cli in CliCatalog.bridged -> s.nativeCli?.releasable == true
                s.record.cli == "Codex" -> s.record.externalSocket == null && s.idleConfirmed && s.historyResumable
                s.record.cli == "OpenCode" -> s.openCode != null
                else -> false
            }

    private fun canDeferIdleInForegroundTerminal(s: Live): Boolean =
        (backgrounded || (selected != s.record.id && !foregroundTracker.isPinned(s.record.id))) &&
            isDisplayedInForegroundTerminal(s) &&
            canReleaseIdle(s, backgroundOnly = false, allowForegroundTerminal = true)

    private fun scheduleIdleRelease(s: Live) {
        if (s.releasing) return
        if (backgrounded || foregroundTerminalSnapshot != null) refreshForegroundTerminalSnapshot()
        if (!canReleaseIdle(s) && !canDeferIdleInForegroundTerminal(s)) { s.idleJob?.cancel(); s.idleJob = null; return }
        if (s.idleJob?.isActive == true) return
        val epoch = s.generation
        s.idleJob = scope.launch {
            while (true) {
                delay(idleGraceMillis)
                if (sessions[s.record.id] !== s || s.generation != epoch) return@launch
                foregroundTerminalSnapshot = withContext(ioDispatcher) { readForegroundTerminalSnapshot() }
                if (canDeferIdleInForegroundTerminal(s)) continue
                if (!canReleaseIdle(s)) return@launch
                break
            }
            try {
                s.releasing = true
                when {
                    s.record.cli in CliCatalog.bridged -> s.nativeCli!!.releaseIdle()
                    s.record.cli == "Codex" && s.record.externalSocket == null -> {
                        val rpc = s.rpc ?: return@launch
                        val thread = rpc.request("thread/read", obj("threadId" to s.record.threadId, "includeTurns" to false)).getJSONObject("thread")
                        if (thread.getString("id") != s.record.threadId || thread.optJSONObject("status")?.optString("type") != "idle") return@launch
                        foregroundTerminalSnapshot = withContext(ioDispatcher) { readForegroundTerminalSnapshot() }
                        if (!canReleaseIdle(s)) return@launch
                        try { rpc.request("thread/unsubscribe", obj("threadId" to s.record.threadId)) }
                        catch (unsupported: RpcRejected) { if (unsupported.code != -32601) throw unsupported }
                    }
                }
                if (sessions[s.record.id] !== s || s.generation != epoch) return@launch
                dropIdleCache(s)
                s.releasing = false
                if (SubmissionLedger.pending(s.record) || (!backgrounded && (selected == s.record.id || foregroundTracker.isPinned(s.record.id)))) {
                    startObservation(s)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {  }
            finally {
                s.releasing = false
                if (s.idleJob === currentCoroutineContext()[Job]) s.idleJob = null
                publish()
            }
        }
    }

    private fun isObservingOpenCode(s: Live): Boolean =
        s.record.environment == environment && s.record.cli == "OpenCode" && (s.observing || s.openCode != null)

    private fun isManagedCodex(s: Live): Boolean =
        s.record.environment == environment && s.record.cli == "Codex" && s.record.externalSocket == null

    private fun hasForegroundTerminalSession(
        terminal: ForegroundTerminalSnapshot?,
        predicate: (Live) -> Boolean,
    ): Boolean = terminal != null && sessions.values.any { predicate(it) && terminal.matches(it) }

    private fun launchDaemonIdleTimer(
        currentJob: Job?,
        isObserving: () -> Boolean,
        matchesDaemon: (Live) -> Boolean,
        onClear: (Job?) -> Unit,
        stopDaemon: suspend () -> Unit,
    ): Job? {
        if (isObserving()) {
            currentJob?.cancel()
            return null
        }
        if (hasForegroundTerminalSession(foregroundTerminalSnapshot, matchesDaemon)) {
            refreshForegroundTerminalSnapshot()
        }
        if (currentJob?.isActive == true) return currentJob
        return scope.launch {
            try {
                while (true) {
                    delay(idleGraceMillis)
                    if (isObserving()) return@launch
                    val terminal = withContext(ioDispatcher) { readForegroundTerminalSnapshot() }
                    foregroundTerminalSnapshot = terminal
                    if (hasForegroundTerminalSession(terminal, matchesDaemon)) continue
                    stopDaemon()
                    return@launch
                }
            } finally {
                onClear(currentCoroutineContext()[Job])
            }
        }
    }

    private fun syncOpenCodeDaemonIdleTimer() {
        val knownEndpoint = lastOpenCodeEndpoint
        openCodeIdleJob = launchDaemonIdleTimer(
            currentJob = openCodeIdleJob,
            isObserving = { sessions.values.any(::isObservingOpenCode) },
            matchesDaemon = { it.record.environment == environment && it.record.cli == "OpenCode" },
            onClear = { finished -> if (openCodeIdleJob === finished) openCodeIdleJob = null },
            stopDaemon = { runtime.stopIdleOpenCode(environment, knownEndpoint ?: lastOpenCodeEndpoint) },
        )
    }

    private fun syncCodexDaemonIdleTimer() {
        codexIdleJob = launchDaemonIdleTimer(
            currentJob = codexIdleJob,
            isObserving = { sessions.values.any { isManagedCodex(it) && (it.observing || it.rpc != null) } },
            matchesDaemon = ::isManagedCodex,
            onClear = { finished -> if (codexIdleJob === finished) codexIdleJob = null },
            stopDaemon = { runtime.stopIdleCodex(environment) },
        )
    }

    private fun dropIdleCache(s: Live) {
        val wasOpenCode = s.record.cli == "OpenCode"
        val wasManagedCodex = s.record.cli == "Codex" && s.record.externalSocket == null
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
        s.activity.forgetHistory()
        s.items.clear(); s.nativeToClientId.clear(); s.admitting.clear(); s.deliveryItems.clear(); s.requests.clear(); s.buffered.clear()
        s.subAgents.clear(); s.subAgentMetadataReads.clear(); s.hydrationBoundary.clear(); s.endedTurns.clear()
        s.observedQueueIds.clear()
        s.tokenPricer.reset(); s.tokenPricer.updateCatalog(basePricingCatalog)
        s.models = emptyList(); s.queueRaw = emptyList(); s.tokens = emptyList(); s.limits = emptyList(); s.rolloutPath = null
        s.settingsWaiter?.cancel(); s.settingsWaiter = null; s.settingsExpected = null
        s.idleJob = null
        s.view = SessionView(Live.summary(s.record), connection = tr("休眠", "Dormant"), draft = s.record.draft, effort = s.record.effort)
        if (wasOpenCode) syncOpenCodeDaemonIdleTimer()
        if (wasManagedCodex) syncCodexDaemonIdleTimer()
    }

    private fun persistDelivery(s: Live) {
        try { persist() }
        catch (failure: Exception) { s.view = s.view.copy(error = tr("无法保存发送状态，输入已保留：${failure.message}", "Failed to save send state (input preserved): ${failure.message}")) }
    }
    private fun current() = sessions[selected]
    private fun asyncQuestions(s: Live) = CodexAsyncQuestions.pending(
        s.items.values,
        s.queueRaw.map { CodexProjection.inputText(it.optJSONArray("input") ?: JSONArray()) },
        s.record.submissions.map { CodexAsyncQuestions.Submission(it.text, it.state) },
        s.record.dismissedAsyncQuestionItems,
    )
    private fun codexRequests(s: Live) = s.requests.values.map { it.view() } + asyncQuestions(s)
    private fun launch(s: Live? = current(), block: suspend () -> Unit): Job {
        val epoch = s?.generation
        return scope.launch {
            try { block() }
            catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) {
                val message = failure.message ?: tr("操作失败，请重试", "Action failed; please try again")
                if (s != null) { if (s.generation == epoch) s.view = s.view.copy(error = message, sending = false, loading = false) }
                else mutableState.value = mutableState.value.copy(error = message, creating = false)
            }
            finally { publish() }
        }
    }

    private fun resetConnectionTransientState(s: Live) {
        s.settingsWaiter?.cancel()
        s.settingsWaiter = null
        s.settingsExpected = null
        s.takingBack = false
    }

    private fun startObservation(s: Live, recoverExternal: Boolean = false, beforeConnect: suspend () -> Unit = {}) {
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
        resetConnectionTransientState(s)
        s.requests.clear()
        s.view = resetConnectionView(s.view, loading = true)
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
                    s.view = s.view.copy(error = s.view.error ?: failure.message ?: tr("连接失败", "Connection failed"), sending = false, loading = false)
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
                val entry = commands.reserveDurable(request, id, dispatcher = ioDispatcher)
                try {
                    val s = addManagedSession(request, id, null)
                    commands.updateDurable(entry, "starting", dispatcher = ioDispatcher)
                    startObservation(s)
                    commandInfo(entry)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    commands.updateDurable(entry, "rejected", e.message, dispatcher = ioDispatcher)
                    throw e
                }
            }
            "attach" -> {
                val cli = request.getString("cli"); val nativeId = request.getString("nativeId")
                val externalSocket = request.stringOrNull("externalSocket")
                io.github.modao7067.luma.gui.runtime.ExternalCodexPolicy.validateAttachment(cli, environment, nativeId, externalSocket)
                val pattern = when (cli) { "Codex" -> "[A-Za-z0-9-]+"; "OpenCode" -> "ses[a-zA-Z0-9_-]{1,128}"; else -> null }
                require(pattern == null || nativeId.matches(Regex(pattern))) { tr("请输入 $cli 原生会话 ID", "Enter a valid $cli native session ID") }
                require(cli !in CliCatalog.bridged || nativeId.matches(Regex("[a-zA-Z0-9_-]{1,128}"))) { tr("当前 CLI bridge 仅接收原生 session ID，不接收 sessionFile 路径；请使用 CLI 返回的 nativeId", "CLI bridge expects a native session ID rather than a sessionFile path; pass nativeId") }
                val repair = request.stringOrNull("id")?.let { id ->
                    val target = sessions[id]?.takeIf { it.record.environment == environment } ?: error(tr("attach.id 不在当前 App 中", "attach.id not found in current app"))
                    ManagedSessionPolicy.validateExternalRepair(target.record, cli, nativeId, checkNotNull(externalSocket), target.view.connected, target.view.loading)
                    target
                }
                val existing = sessions.values.firstOrNull { it.record.environment == environment && it.record.cli == cli && it.record.threadId == nativeId && it.record.externalSocket == externalSocket &&
                    (cli != "Antigravity" || (it.record.nativeProtocol ?: "antigravity-acp") == request.getString("nativeProtocol")) &&
                    (cli != "Kimi Code" || !request.has("nativeProtocol") || (it.record.nativeProtocol ?: "kimi-web-v1") == request.getString("nativeProtocol")) }
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
                check(!s.view.configuring && !s.firstSendPending) { tr("会话正在更新设置", "Session is updating settings") }
                if (s.view.activeTurnId == null) s.userStoppedTurn = false
                val submissionId = if (s.record.cli == "OpenCode") "msg_" + UUID.randomUUID().toString().replace("-", "") else UUID.randomUUID().toString()
                val entry = commands.reserveDurable(request, s.record.id, submissionId, dispatcher = ioDispatcher)
                try {
                    check(sessions[s.record.id] === s) { tr("会话已不存在", "Session no longer exists") }
                    SubmissionLedger.checkpoint(s.record, submissionId, ::persistDurable) {
                        SubmissionLedger.begin(s.record, submissionId, externalText = request.getString("text"), settings = settings(s), busy = true)
                        SubmissionLedger.state(s.record, submissionId, "local")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    commands.updateDurable(entry, "rejected", e.message, dispatcher = ioDispatcher)
                    throw e
                }
                commands.updateDurable(entry, "pending", dispatcher = ioDispatcher)
                if ((!s.view.connected && !s.view.loading && !s.observing) || (s.releasing && !inCliMaintenance(s))) startObservation(s)
                publish()
                commandInfo(entry)
            }
            "status" -> if (request.has("requestId")) {
                check(commands.error == null) { commands.error!! }
                commandInfo(commands.entries[request.getString("requestId")] ?: error(tr("requestId 不存在；不会自动执行", "requestId not found; will not auto-execute")))
            } else obj("ok" to true, "session" to managedInfo(managedTarget(request)))
            "read" -> {
                val s = managedTarget(request); val rows = s.view.messages
                val end = if (request.has("before")) rows.indexOfFirst { it.id == request.getString("before") }.also { require(it >= 0) { tr("before 消息不在当前已同步历史中", "before message ID not found in synced history") } } else rows.size
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
            else -> error(tr("未知 action", "Unknown action"))
        }
    }
    private fun managedTarget(request: JSONObject): Live = sessions[request.getString("id")]?.takeIf { it.record.environment == environment }
        ?: error(tr("当前 App 中不存在此本地 id；先用 list 获取目标", "Local session ID not found; run list first"))
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
            try { commands.updateAsync(entry, result, if (result in setOf("error", "rejected")) s.view.error else null) }
            catch (e: Exception) { s.view = s.view.copy(error = tr("无法保存命令状态：${e.message}", "Failed to save command state: ${e.message}")) }
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
        if (cli !in CliCatalog.ids || !threadId.matches(Regex(pattern))) { mutableState.value = mutableState.value.copy(error = tr("请输入 $cli 原生 session ID", "Enter a $cli native session ID")); return }
        addSession(title, environment, cwd, "", threadId, cli)
    }
    override fun createSession(title: String, environment: String, cwd: String, model: String) {
        addSession(title, environment, cwd, model, null)
    }
    override fun attachSession(title: String, environment: String, cwd: String, threadId: String) {
        if (!threadId.matches(Regex("[A-Za-z0-9-]+"))) { mutableState.value = mutableState.value.copy(error = tr("请输入原 Codex thread ID", "Enter the Codex thread ID")); return }
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
        if (cli == "Codex" && s.record.externalSocket == null) syncCodexDaemonIdleTimer()
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
            previous.view = previous.view.copy(error = tr("无法清理空会话，原会话已保留：${CodexCapabilities.redact(failure.message ?: "保存失败")}", "Failed to clean up empty session (preserved): ${CodexCapabilities.redact(failure.message ?: "save failed")}"))
            publish()
            return false
        }
        disposeSession(previous, destroyBackend = true)
        return true
    }

    private fun addSession(title: String, environment: String, cwd: String, model: String, threadId: String?, cli: String = "Codex") {
        if (storeError != null) return
        if (cli !in CliCatalog.ids || environment != this.environment || !cwd.startsWith('/') || cwd.any { it.isISOControl() }) {
            mutableState.value = mutableState.value.copy(error = tr("请输入内置环境中的绝对工作目录", "Enter an absolute working directory inside the environment")); return
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
        if (!inCliMaintenance(s) && (s.releasing || (!s.view.connected && !s.view.loading && s.openCode?.recovering != true))) startObservation(s)
    }
    override fun openSubAgent(parentSessionId: String, agentId: String) {
        val parent = current()?.takeIf { it.record.id == parentSessionId } ?: return
        val agent = parent.view.subAgents.firstOrNull { it.id == agentId }
            ?: if (parent.view.loading || parent.hydrating || !parent.view.connected) SubAgentState(id = agentId) else return
        val current = childObservation
        if (current?.parentId == parentSessionId && current.childId == agentId && current.epoch == parent.generation) return
        clearSubAgentObservation()
        childObservation = ChildObservation(
            parentSessionId,
            agentId,
            parent.generation,
            SubAgentDetailView(parentSessionId, agent, loading = !parent.view.connected || parent.view.loading || parent.hydrating),
        )
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
        if (agent == null && parent.view.connected && !parent.view.loading && !parent.hydrating) { clearSubAgentObservation(); return null }
        if (parent.generation != observation.epoch) {
            observation.job?.cancel(); observation.signals.close()
            observation = ChildObservation(observation.parentId, observation.childId, parent.generation, observation.detail)
            childObservation = observation
        }
        if (agent != null && agent != observation.detail.agent) observation.signals.trySend(Unit)
        val streamedChild = agent != null && ((parent.record.cli == "Antigravity" &&
            parent.record.nativeProtocol in setOf("antigravity-cli-stream-json", "antigravity-agy-acp")) ||
            parent.record.cli in setOf("Claude Code", "DeepSeek Harness", "Grok Build", "Kimi Code"))
        observation.detail = if (streamedChild) NativeCliProjection.streamedSubAgentDetail(agent, observation.detail, parent.models)
            else observation.detail.copy(agent = agent ?: observation.detail.agent,
                loading = if (!parent.view.connected) parent.view.loading else if (agent == null) true else observation.detail.loading)
        if (!streamedChild && foreground && !observation.started && agent != null && parent.view.connected && !parent.view.loading && !parent.hydrating && parent.record.cli in setOf("Codex", "OpenCode")) startSubAgentRead(parent, observation)
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
                            parent.view = parent.view.copy(error = tr("子代理读取失败：${CodexCapabilities.redact(failure.message ?: "未知错误")}", "Failed to load sub-agent: ${CodexCapabilities.redact(failure.message ?: "unknown error")}"))
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
    fun onActivityStarted(token: Any, pinnedSessionId: String? = null) {
        applyForegroundState(foregroundTracker.start(token, pinnedSessionId), forcePublish = true)
    }

    fun onActivityStopped(token: Any) {
        applyForegroundState(foregroundTracker.stop(token), forcePublish = true)
    }

    fun setForeground(visible: Boolean) {
        if (visible) onActivityStarted(defaultForegroundToken) else onActivityStopped(defaultForegroundToken)
    }

    private fun applyForegroundState(visible: Boolean, forcePublish: Boolean = false) {
        val changed = foreground != visible
        if (changed) {
            foreground = visible
            backgrounded = !visible
            refreshForegroundTerminalSnapshot(force = true)
            if (visible) {
                runtime.onAppForegrounded()
                if (RootBackgroundNotifications.isEnabled(context)) {
                    rootWatcherArmed = false
                    lastArmedRootSessions = emptyList()
                    RootBackgroundNotifications.disarmLocal(context)
                }
                current()?.let { if ((it.dormant && !it.observing) || (it.releasing && !inCliMaintenance(it))) startObservation(it) }
            } else {
                childObservation?.let { it.job?.cancel(); it.job = null; it.started = false; it.detail = it.detail.copy(loading = false) }
            }
        }
        if (changed || forcePublish) {
            publish()
        }
        if (changed && !visible && RootBackgroundNotifications.isEnabled(context)) {
            syncRootBackgroundWatcher(forceSpawn = true)
        }
    }

    private fun buildWatchedRootSessions(): List<WatchedRootSession> {
        if (!RootBackgroundNotifications.isEnabled(context)) return emptyList()
        val expectedEnv = if (GuiProduct.isRoot) "root" else environment
        return sessions.values.mapNotNull { s ->
            if (s.record.environment != expectedEnv) return@mapNotNull null
            val activeSubAgent = s.view.subAgents.any {
                it.status !in setOf("succeeded", "completed", "failed", "cancelled", "closed", "aborted")
            }
            val activeTool = s.view.messages.any {
                it.kind == "tool" && it.status in setOf("running", "in_progress", "pending")
            }
            val needsWatch = s.view.activeTurnId != null ||
                s.view.requests.isNotEmpty() ||
                s.view.loading ||
                s.connectJob?.isActive == true ||
                s.firstSendPending ||
                s.dispatchingLocal ||
                SubmissionLedger.pending(s.record) ||
                activeSubAgent ||
                activeTool
            if (!needsWatch) return@mapNotNull null
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
        if (!RootBackgroundNotifications.isEnabled(context) || foreground) return
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
        RootBackgroundNotifications.armInBackground(context, scope, watched, spawnLauncher = shouldSpawn && GuiProduct.usesRootExecution(context))
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
        try { persist() } catch (e: Exception) { mutableState.value = mutableState.value.copy(error = tr("无法保存会话选择：${e.message}", "Failed to save session selection: ${e.message}")) }
    }
    override fun discardUncertainSubmission() {
        val s = current() ?: return
        launch(s) { if (SubmissionLedger.uncertain(s.record)) { SubmissionLedger.discard(s.record); persist() } }
    }
    override fun reconnect() {
        val s = current() ?: return
        if (cliMaintenanceJob?.isActive == true) return
        startObservation(s, recoverExternal = true) { if (s.dispatchStorageBlocked) persistDurableAsync() }
    }

    override fun restartCliBridge() {
        val target = current()?.takeIf { it.view.cliServiceUpdateRequired || it.view.cliServiceUpdateAvailable } ?: return
        if (cliMaintenanceJob?.isActive == true) return
        try { persist() } catch (failure: Exception) {
            target.view = target.view.copy(error = tr("无法保存会话，CLI 服务未停止：${failure.message}", "Failed to save session; CLI service was not stopped: ${failure.message}")); publish(); return
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
                resetConnectionTransientState(s)
                s.view = resetConnectionView(
                    s.view,
                    loading = true,
                    connection = tr("正在更新 CLI 服务…", "Updating CLI service…"),
                    error = null,
                )
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
                        s.view = s.view.copy(loading = false, cliServiceUpdateRequired = false, cliServiceUpdateAvailable = false, connection = tr("CLI 服务已更新 · 待同步", "CLI service updated · Syncing"))
                        if (s.record.id in restore) startObservation(s, recoverExternal = true)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                affected.forEach { s -> if (sessions[s.record.id] === s) s.view = s.view.copy(loading = false,
                    error = tr("CLI 服务更新失败：${failure.message}；记录与草稿保留，请回到更新入口再次确认", "CLI service update failed: ${failure.message}; history and drafts preserved")) }
            } finally {
                affected.forEach { it.releasing = false }
                if (cliMaintenanceJob === currentCoroutineContext()[Job]) cliMaintenanceJob = null
                publish()
            }
        }
        cliMaintenanceJob = maintenance; maintenance.start()
    }

    private suspend fun connect(s: Live, recoverExternal: Boolean = false) {
        if (!s.observing || sessions[s.record.id] !== s) throw CancellationException(tr("界面观察已停止", "Session observation stopped"))
        currentCoroutineContext().ensureActive()
        s.connectJob = currentCoroutineContext()[Job]
        val epoch = ++s.generation
        s.idleConfirmed = false
        resetConnectionTransientState(s)
        s.view = resetConnectionView(
            s.view,
            loading = true,
            connection = tr("连接运行环境…", "Connecting to runtime…"),
            error = null,
        )
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
            if (!s.observing || s.generation != epoch) throw CancellationException(tr("界面观察已停止", "Session observation stopped"))
            s.endpoint = adapter.endpoint
            return
        }
        check(s.record.cli in CliCatalog.ids) { tr("此 CLI 适配尚未启用", "Adapter for this CLI is not enabled yet") }
        if (s.record.cli == "OpenCode") {
            openCodeIdleJob?.cancel(); openCodeIdleJob = null
            sessions.values.filter { it.record.environment == s.record.environment && it.record.cli == "OpenCode" }.forEach {
                runCatching { browser.bind(it.record.id, it.record.threadId) }
            }
            s.openCode?.close()
            val adapter = OpenCodeSession(s.record, runtime, ::persist,
                bindBrowser = { browser.bind(s.record.id, it) },
                changed = { view, models -> if (s.observing && s.generation == epoch) { s.openCode?.endpoint?.let { s.endpoint = it; lastOpenCodeEndpoint = it }; s.view = view; s.models = models; publish() } },
                childChanged = { id -> childObservation?.takeIf { it.parentId == s.record.id && it.childId == id }?.signals?.trySend(Unit) },
                persistCheckpoint = ::persistDurable).also { s.openCode = it }
            adapter.connect()
            currentCoroutineContext().ensureActive()
            if (!s.observing || s.generation != epoch) throw CancellationException(tr("界面观察已停止", "Session observation stopped"))
            s.endpoint = adapter.endpoint
            adapter.endpoint?.let { lastOpenCodeEndpoint = it }
            return
        }
        if (s.record.externalSocket == null) {
            codexIdleJob?.cancel()
            codexIdleJob = null
        }
        val endpoint = s.record.externalSocket?.let {
            if (recoverExternal) runtime.recoverExternalCodex(s.record.environment, it)
            runtime.externalCodex(s.record.environment, it)
        }
            ?: runtime.connect(s.record.environment)
        currentCoroutineContext().ensureActive()
        if (!s.observing || s.generation != epoch) throw CancellationException(tr("界面观察已停止", "Session observation stopped"))
        s.endpoint = endpoint
        s.rpc?.close(); s.deltaPublishJob?.cancel(); s.deltaPublishJob = null; s.requests.clear(); s.buffered.clear(); s.hydrationBoundary.clear(); s.endedTurns.clear(); s.subAgentMetadataReads.clear()
        lateinit var rpc: CodexRpc
        rpc = CodexRpc(endpoint.url, endpoint.authToken, transport = runtime.codexTransport(endpoint),
            onEvent = { message -> scope.launch { if (s.observing && s.generation == epoch && s.rpc === rpc) {
                if (s.hydrating) { s.buffered.add(message); return@launch }
                var failed = false
                try { event(s, message) }
                catch (failure: Exception) { failed = true; s.view = s.view.copy(error = tr("无法应用服务事件，请重新连接同步：${failure.message}", "Failed to apply service event; reconnect to resync: ${failure.message}")) }
                publishCodexEvent(s, message, forceImmediate = failed)
            } } },
            onDisconnect = { failure -> scope.launch { if (s.observing && s.generation == epoch && s.rpc === rpc) {
                s.deltaPublishJob?.cancel(); s.deltaPublishJob = null
                s.view = s.view.copy(connected = false, connection = tr("连接中断 · 任务状态待同步", "Disconnected · Task state syncing"), loading = false, sending = false, error = failure.message)
                s.requests.clear(); publish()
            } } })
        s.rpc = rpc; s.hydrating = true
        var succeeded = false
        try {
            rpc.connect()
            sessions.values.filter { it.record.environment == s.record.environment && it.record.cli == "Codex" }.forEach {
                runCatching { browser.bind(it.record.id, it.record.threadId) }
            }
            val browserOverrides = CodexConnectionSetup.prepare(s.record, runtime.skillsPath(s.record.environment),
                browserConfig = { browser.bind(s.record.id, s.record.threadId) }, request = rpc::request, persist = ::persist,
                recoverExternal = recoverExternal)
            var startedThread: JSONObject? = null
            if (s.record.threadId == null) {
                if (s.record.creationUnknown) {
                    val known = sessions.values.filter { it.record.environment == s.record.environment }.mapNotNull { it.record.threadId }.toSet()
                    val candidates = loadedThreads(rpc) - known
                    error(tr("上次创建结果尚未确认，不会自动再建。当前服务未关联的 ID：${candidates.joinToString().ifBlank { "无；可能服务已重启" }}。请保留这些 ID，由支持人员核对托管服务与本地索引；确需另建时再点新建。", "Previous session creation is unconfirmed; will not auto-recreate. Unlinked IDs on service: ${candidates.joinToString().ifBlank { "none (service may have restarted)" }}. Tap New session again if you want a fresh one."))
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
            check(resumed.getJSONObject("thread").getString("id") == id) { tr("服务返回了不同会话，已停止接入", "Service returned a different session; attach aborted") }
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
            val resumedPlan = resumed.optJSONObject("collaborationMode")?.optString("mode") == "plan"
            s.view = s.view.copy(permission = resumed.optJSONObject("activePermissionProfile")?.optString("id").orEmpty(), speed = tier, planMode = resumedPlan)
            hydrate(s)
            val models = mutableListOf<ModelOption>(); var cursor: String? = null
            val seenModelCursors = hashSetOf<String>()
            do {
                val page = rpc.request("model/list", obj("limit" to 100, "cursor" to cursor))
                models += CodexProjection.models(page.optJSONArray("data") ?: JSONArray())
                val nextCursor = page.stringOrNull("nextCursor")
                cursor = if (nextCursor != null && seenModelCursors.add(nextCursor)) nextCursor else null
            } while (cursor != null)
            s.models = models
            try {
                val options = mutableListOf<RequestChoice>(); var next: String? = null
                val seenPermissionCursors = hashSetOf<String>()
                do {
                    val page = rpc.request("permissionProfile/list", obj("cwd" to s.record.cwd, "cursor" to next, "limit" to 100))
                    options += CodexCapabilities.permissions(page)
                    val nextCursor = page.stringOrNull("nextCursor")
                    next = if (nextCursor != null && seenPermissionCursors.add(nextCursor)) nextCursor else null
                } while (next != null)
                s.view = s.view.copy(permissions = options, capabilityNotice = null)
            } catch (e: RpcRejected) { s.view = s.view.copy(permissions = emptyList(), capabilityNotice = tr("当前 CLI 未提供可选择的权限配置", "No selectable permission configurations provided by CLI")) }
            val planSupported = try {
                rpc.request("collaborationMode/list", JSONObject())
                    .optJSONArray("data")?.objects().orEmpty()
                    .any { it.optString("mode") == "plan" }
            } catch (e: RpcRejected) { false }
            s.view = s.view.copy(planModeSupported = planSupported, planMode = if (planSupported) s.view.planMode else false)
            refreshQueue(s)
            currentCoroutineContext().ensureActive()
            if (!s.observing || s.generation != epoch) throw CancellationException(tr("界面观察已停止", "Session observation stopped"))
            s.view = s.view.copy(connected = true, loading = false, connection = tr("已连接", "Connected"), canOpenTui = true, error = null)
            succeeded = true
        } finally {
            if (!succeeded) {
                rpc.close()
                if (s.rpc === rpc) s.rpc = null
                if (s.rpc == null) {
                    s.hydrating = false
                    s.buffered.clear()
                    s.hydrationBoundary.clear()
                    s.deltaPublishJob?.cancel()
                    s.deltaPublishJob = null
                }
            } else if (s.observing && s.generation == epoch && s.rpc === rpc) {
                s.hydrating = false
                val events = s.buffered.toList(); s.buffered.clear()
                val replay = s.hydrationBoundary.replayEvents(events) { itemId, toolDetail ->
                    val snap = s.items[s.nativeToClientId[itemId] ?: itemId]
                    if (toolDetail) snap?.detail.orEmpty() else snap?.text.orEmpty()
                }
                replay.forEach { e -> event(s, e, replaying = true) }
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
        val seenCursors = hashSetOf<String>()
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
            val nextCursor = page.stringOrNull("nextCursor")
            cursor = if (nextCursor != null && seenCursors.add(nextCursor)) nextCursor else null
        } while (cursor != null)
        s.items.clear(); s.items.putAll(hydrated)
        if (SessionTitlePolicy.history(s.record, s.items.values, ready = true)) persist()
        val turnsBefore = s.buffered.size
        val turns = rpc.request("thread/turns/list", obj("threadId" to id, "limit" to 1, "sortDirection" to "desc", "itemsView" to "notLoaded"))
        val turnsAfter = s.buffered.size
        s.hydrationBoundary.turns(turnsBefore, id)
        val turnRows = turns.optJSONArray("data")?.objects().orEmpty()
        val last = turnRows.firstOrNull()
        val activeTurnId = last?.takeIf { it.optString("status") == "inProgress" }?.optString("id")
        s.queueNeedsExplicitStart = last?.optString("status") in setOf("interrupted", "failed")
        recordHydratedEndedTurns(s, id, activeTurnId, turnRows, turnsBefore, turnsAfter)
        s.view = s.view.copy(activeTurnId = activeTurnId, stopping = false)
        for (item in s.items.values.filter { it.kind == "reasoning" && it.outcome == "unknown" }) {
            if (activeTurnId != null && item.turnId == activeTurnId) s.items[item.id] = item.copy(outcome = "running")
            else if (item.detail.isBlank()) s.items.remove(item.id)
            else s.items[item.id] = item.copy(outcome = s.endedTurns[item.turnId] ?: "succeeded")
        }
        val activeModel = s.record.model.ifBlank { s.view.summary.model }
        s.tokenPricer.updateCatalog(basePricingCatalog)
        val pricingPayload = runtime.readCodexSessionPricing(s.record.environment, s.rolloutPath)
        if (pricingPayload != null) {
            val state = s.tokenPricer.reconcileSessionPricing(pricingPayload, activeModel, s.view.speed)
            val hasUsage = pricingPayload.optJSONObject("rollout")?.optBoolean("hasUsage") == true || state.total != null || state.last != null
            if (hasUsage) {
                s.tokens = CodexCapabilities.tokenMetrics(state, activeModel)
                s.view = s.view.copy(metrics = s.tokens)
            }
        } else if (s.tokens.isEmpty() && !s.rolloutPath.isNullOrBlank()) {
            runtime.readCodexRolloutTokenUsage(s.record.environment, s.rolloutPath!!)?.let { info ->
                val state = s.tokenPricer.applyRolloutBaseline(info, activeModel, s.view.speed)
                s.tokens = CodexCapabilities.tokenMetrics(state, activeModel)
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
        val seenCursors = hashSetOf<String>()
        do {
            val page = rpc.request("thread/queue/list", obj("threadId" to id, "limit" to 100, "cursor" to cursor))
            queue += page.optJSONArray("data")?.objects().orEmpty()
            val nextCursor = page.stringOrNull("nextCursor")
            cursor = if (nextCursor != null && seenCursors.add(nextCursor)) nextCursor else null
        } while (cursor != null)
        applyQueue(s, queue)
        publish()
    }
    private suspend fun reconcileCodexSubmission(s: Live, id: String, rpc: CodexRpc, epoch: Int) {
        var cursor: String? = null
        val seenCursors = hashSetOf<String>()
        do {
            if (!s.observing || s.generation != epoch || s.rpc !== rpc || !rpc.connected) return
            val page = rpc.request("thread/items/list", obj("threadId" to s.record.threadId, "limit" to 100, "sortDirection" to "desc", "cursor" to cursor))
            if (!s.observing || s.generation != epoch || s.rpc !== rpc) return
            val row = page.optJSONArray("data")?.objects()?.firstOrNull { value -> value.optJSONObject("item")?.let {
                it.optString("type") == "userMessage" && (CodexProjection.clientMessageId(it) ?: s.nativeToClientId[it.optString("id")] ?: it.optString("id")) == id
            } == true }
            if (row != null) { putItem(s, row.getJSONObject("item"), completed = !row.isNull("completedAtMs"), turnId = row.optString("turnId")); publish(); return }
            val nextCursor = page.stringOrNull("nextCursor")
            cursor = if (nextCursor != null && seenCursors.add(nextCursor)) nextCursor else null
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
        var dismissedByQueue = false
        for (queued in linked) {
            val qid = queued.stringOrNull("id")?.takeIf(String::isNotBlank) ?: continue
            if (s.observedQueueIds.add(qid)) {
                val cid = queued.stringOrNull("clientUserMessageId")
                val localKnownAccepted = cid != null && SubmissionLedger.find(s.record, cid)?.state == "accepted"
                val text = CodexProjection.inputText(queued.optJSONArray("input") ?: JSONArray())
                if (!localKnownAccepted && CodexAsyncQuestions.replies(text).isEmpty()) {
                    dismissedByQueue = true
                }
            }
        }
        if (dismissedByQueue && dismissOpenAsyncQuestions(s)) {
            persist()
        }
        s.view = s.view.copy(queue = waiting.map { QueueEntry(it.getString("id"), CodexAsyncQuestions.displayReply(CodexProjection.inputText(it.optJSONArray("input") ?: JSONArray())), tr("尚未执行", "Pending"), it.optJSONArray("input")?.objects().orEmpty().filter { input -> input.optString("type") == "text" }.joinToString("\n") { input -> input.optString("text") },
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
    private fun dismissOpenAsyncQuestions(s: Live): Boolean {
        val openIds = asyncQuestions(s).map { it.id.removePrefix("codex-async:") }.filter { it.isNotBlank() }
        if (openIds.isEmpty()) return false
        return s.record.dismissedAsyncQuestionItems.addAll(openIds)
    }
    private fun clearSubmission(s: Live, id: String) {
        val prior = SubmissionLedger.find(s.record, id)
        if (!s.hydrating && prior != null && prior.state !in setOf("accepted", "rejected", "cancelled") &&
            CodexAsyncQuestions.replies(prior.text).isEmpty()) {
            dismissOpenAsyncQuestions(s)
        }
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
    private fun event(s: Live, message: JSONObject, replaying: Boolean = false) {
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
                val nextPlan = if (settings.has("collaborationMode") && !settings.isNull("collaborationMode")) {
                    settings.optJSONObject("collaborationMode")?.optString("mode") == "plan"
                } else s.view.planMode
                s.view = s.view.copy(
                    permission = settings.optJSONObject("activePermissionProfile")?.optString("id").orEmpty(),
                    speed = settings.stringOrNull("serviceTier").orEmpty(),
                    planMode = if (s.view.planModeSupported) nextPlan else false,
                )
                persist()
                if (NativeSettings.matches(s.settingsExpected, settings)) s.settingsWaiter?.complete(settings)
            }
            "thread/tokenUsage/updated" -> p.optJSONObject("tokenUsage")?.let {
                val activeModel = s.record.model.ifBlank { s.view.summary.model }
                val state = if (replaying) s.tokenPricer.onReplayedTokenUsage(it, activeModel, s.view.speed)
                    else s.tokenPricer.onTokenUsage(it, activeModel, s.view.speed)
                s.tokens = CodexCapabilities.tokenMetrics(state, activeModel); s.view = s.view.copy(metrics = s.tokens)
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
                val turn = p.getJSONObject("turn"); val turnId = turn.getString("id")
                s.endedTurns.remove(turnId)
                s.view = s.view.copy(activeTurnId = turnId, stopping = false)
                turn.optJSONArray("items")?.objects()?.forEach { putItem(s, it, completed = false, turnId = turnId) }
            }
            "turn/completed" -> {
                s.idleConfirmed = true
                val turn = p.getJSONObject("turn"); val turnId = turn.getString("id")
                val failedOrInterrupted = turn.optString("status") in setOf("interrupted", "failed")
                val terminal = if (failedOrInterrupted) "cancelled" else "succeeded"
                if (turnId.isNotEmpty()) recordEndedTurn(s, turnId, terminal)
                turn.optJSONArray("items")?.objects()?.forEach { putItem(s, it, turnId = turnId, endedOutcome = terminal) }
                if (turnId.isNotEmpty()) settleTurnReasoning(s, turnId, terminal)
                if (s.view.activeTurnId == turnId) s.view = s.view.copy(activeTurnId = null, stopping = false)
                if (s.view.activeTurnId == null) s.queueNeedsExplicitStart = failedOrInterrupted
                turn.optJSONObject("error")?.let { s.view = s.view.copy(error = CodexProjection.error(it)) }
            }
            "item/started", "item/completed" -> p.optJSONObject("item")?.let { putItem(s, it, method == "item/completed", p.optString("turnId")) }
            "item/agentMessage/delta", "item/commandExecution/outputDelta", "item/reasoning/summaryTextDelta", "item/reasoning/textDelta", "item/plan/delta" -> {
                val rawId = p.optString("itemId")
                val id = s.nativeToClientId[rawId] ?: rawId
                val assistant = method.contains("agentMessage") || method.contains("plan")
                val reasoning = method.contains("reasoning")
                val turnId = p.optString("turnId")
                val endedOutcome = if (reasoning) s.endedTurns[turnId] else null
                val old = s.items[id]
                    ?: ConversationItem(id, if (assistant) "assistant" else "tool",
                        text = if (reasoning) tr("推理摘要", "Reasoning summary") else "",
                        kind = when { reasoning -> "reasoning"; method.contains("commandExecution") -> "commandExecution"; method.contains("plan") -> "plan"; else -> "agentMessage" },
                        turnId = turnId, outcome = endedOutcome ?: "running")
                val delta = p.optString("delta")
                if (delta.isNotEmpty()) {
                    val next = if (old.role == "tool") old.copy(detail = old.detail + delta) else old.copy(text = old.text + delta)
                    s.items[id] = if (endedOutcome != null && next.outcome !in terminalOutcomes) next.copy(outcome = endedOutcome) else next
                }
            }
            "thread/name/updated" -> if (p.has("threadName")) {
                ManagedSessionPolicy.nativeTitle(s.record, p.stringOrNull("threadName")); persist()
            }
            "error" -> s.view = s.view.copy(error = CodexProjection.error(p.optJSONObject("error") ?: p))
        }
    }
    private val terminalOutcomes = setOf("succeeded", "failed", "cancelled")
    private fun recordEndedTurn(s: Live, turnId: String, terminal: String) {
        if (turnId.isEmpty()) return
        s.endedTurns.remove(turnId)
        s.endedTurns[turnId] = terminal
        while (s.endedTurns.size > 256) s.endedTurns.remove(s.endedTurns.keys.first())
    }
    private fun recordHydratedEndedTurns(
        s: Live,
        threadId: String,
        activeTurnId: String?,
        turnRows: List<JSONObject>,
        turnsBefore: Int,
        turnsAfter: Int,
    ) {
        for (item in s.items.values) {
            if (item.turnId.isNotEmpty() && item.turnId != activeTurnId && item.turnId !in s.endedTurns) {
                recordEndedTurn(s, item.turnId, "succeeded")
            }
        }
        for (index in 0 until minOf(turnsAfter, s.buffered.size)) {
            val msg = s.buffered[index]
            if (msg.has("id")) continue
            val p = msg.optJSONObject("params") ?: continue
            val msgThread = p.stringOrNull("threadId") ?: p.optJSONObject("thread")?.stringOrNull("id")
            if (msgThread != null && msgThread != threadId) continue
            if (msg.optString("method") == "turn/completed") {
                val turn = p.optJSONObject("turn") ?: continue
                val tid = turn.optString("id")
                if (tid.isNotEmpty() && tid != activeTurnId) {
                    recordEndedTurn(s, tid, if (turn.optString("status") in setOf("interrupted", "failed")) "cancelled" else "succeeded")
                }
            } else if (index < turnsBefore) {
                val tid = p.stringOrNull("turnId") ?: p.optJSONObject("turn")?.stringOrNull("id").orEmpty()
                if (tid.isNotEmpty() && tid != activeTurnId && tid !in s.endedTurns) {
                    recordEndedTurn(s, tid, "succeeded")
                }
            }
        }
        for (turn in turnRows) {
            val tid = turn.optString("id")
            val status = turn.optString("status")
            if (tid.isNotEmpty() && status != "inProgress") {
                recordEndedTurn(s, tid, if (status in setOf("interrupted", "failed")) "cancelled" else "succeeded")
            }
        }
    }
    private fun settleTurnReasoning(s: Live, turnId: String, terminal: String) {
        for (item in s.items.values.filter { it.kind == "reasoning" && it.turnId == turnId && it.outcome !in terminalOutcomes }) {
            if (item.detail.isBlank()) s.items.remove(item.id) else s.items[item.id] = item.copy(outcome = terminal)
        }
    }
    private fun putItem(s: Live, item: JSONObject, completed: Boolean? = null, turnId: String = "", endedOutcome: String? = null) {
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
        val ownerTurn = turnId.ifEmpty { previous?.turnId.orEmpty() }
        val turnTerminal = endedOutcome ?: if (raw.kind == "reasoning") s.endedTurns[ownerTurn] else null
        val itemCompleted = if (!item.isNull("completedAtMs")) true else completed
        if (raw.kind == "reasoning" && turnTerminal != null && itemCompleted != true && previous == null && raw.detail.isBlank()) return
        val resolvedOutcome = when {
            raw.outcome != "unknown" -> raw.outcome
            itemCompleted == true -> "succeeded"
            previous?.outcome in terminalOutcomes -> previous!!.outcome
            turnTerminal != null -> turnTerminal
            itemCompleted == false -> "running"
            else -> previous?.outcome ?: "unknown"
        }
        val finalEntry = raw.copy(id = canonicalId, outcome = resolvedOutcome, turnId = ownerTurn)
        if (!nativeId.isNullOrBlank() && nativeId != canonicalId && s.items.containsKey(nativeId)) {
            s.items.replaceKeyInPlace(nativeId, canonicalId, finalEntry)
        } else {
            s.items[canonicalId] = finalEntry
        }
        s.subAgents.item(item, ownerTurn); refreshSubAgentMetadata(s); confirmSubmission(s, item)
        if (finalEntry.role == "user") applyQueue(s, s.queueRaw)
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
                        s.view = s.view.copy(error = tr("子代理名称读取失败，请重连同步：${e.message}", "Failed to read sub-agent name; reconnect to sync: ${e.message}")); publish()
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
    fun appendDraft(sessionId: String, text: String) {
        val s = sessions[sessionId]?.takeIf { it.record.environment == environment } ?: return
        if (s.firstSendPending) return
        val updated = if (s.record.draft.isBlank()) text else s.record.draft.trimEnd() + "\n\n" + text
        if (s.record.draft != updated) s.record.draftRevision++
        s.record.draft = updated
        saveJob?.cancel(); saveJob = scope.launch {
            delay(250)
            try { persist() } catch (e: Exception) {
                s.view = s.view.copy(error = e.message)
                publish()
            }
        }
        publish()
    }
    override fun addImage(uri: String) {
        val s = current()
        if (s == null) {
            val id = UUID.randomUUID().toString(); val key = blank.key
            blankImports += Attachment(id, uri, tr("导入图片…", "Importing image…"), true); publish()
            launch(null) {
                try {
                    val path = runtime.importImage(environment, Uri.parse(uri))
                    val preview = runtime.imagePreviewUri(environment, path) ?: error(tr("导入图片不可读取", "Imported image is unreadable"))
                    check(blank.key == key) { tr("导入期间已切换空白会话，图片未添加，请重新选择", "Switched blank session during import; image was not added, please reselect") }
                    blank.images += StoredImage(id, preview, path, path.substringAfterLast('/')); persist()
                } finally { blankImports.removeAll { it.id == id } }
            }
            return
        }
        if (s.firstSendPending) return
        val id = UUID.randomUUID().toString()
        s.view = s.view.copy(attachments = s.view.attachments + Attachment(id, uri, tr("导入图片…", "Importing image…"), true)); publish()
        launch(s) {
            try {
                val path = runtime.importImage(s.record.environment, Uri.parse(uri))
                val preview = runtime.imagePreviewUri(s.record.environment, path) ?: error(tr("导入图片的私有副本不可读取", "Private copy of imported image is unreadable"))
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
            s.view = s.view.copy(error = s.view.error ?: tr("CLI 尚未就绪，请在原生 CLI 处理后重新连接；输入已保留", "CLI is not ready; resolve in native CLI and reconnect (input preserved)"))
            publish(); return
        }
        if (s.record.cli == "Grok Build" && s.record.threadId == null && !s.view.connected && !s.view.loading && s.view.error != null) {
            if (!s.view.error.orEmpty().let { it.contains("重新连接") || it.contains("reconnect", ignoreCase = true) }) s.view = s.view.copy(error = "${s.view.error}\n" + tr("请处理后重新连接；未发送的输入仍保留。", "Resolve the issue and reconnect; unsent input is preserved."))
            publish(); return
        }
        if (!s.view.connected || s.view.loading || s.releasing) {
            if (s.view.attachments.any { it.busy } || (s.record.draft.isBlank() && s.record.images.isEmpty() && s.record.skills.isEmpty())) return
            if (s.view.activeTurnId == null) s.userStoppedTurn = false
            val id = if (s.record.cli == "OpenCode") "msg_" + UUID.randomUUID().toString().replace("-", "") else UUID.randomUUID().toString()
            try {
                SubmissionLedger.checkpoint(s.record, id, ::persistDurable) {
                    SubmissionLedger.begin(s.record, id, settings = settings(s), busy = true)
                    SubmissionLedger.state(s.record, id, "local")
                }
            } catch (e: SubmissionPersistenceFailure) { s.view = s.view.copy(error = e.message); publish(); return }
            if (!s.view.loading && (!s.observing || (s.releasing && !inCliMaintenance(s)))) startObservation(s)
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
            mutableState.value = mutableState.value.copy(error = tr("无法创建会话，输入已保留：${e.message}", "Failed to create session (input preserved): ${e.message}")); publish(); return
        }
        preferences.edit().putString("session", record.id).apply()
        startObservation(s); publish()
    }
    private suspend fun submitCodex(s: Live, steer: Boolean, external: ManagedSubmission? = null) {
        check(s.view.connected && !s.view.configuring) { tr("会话尚未连接或正在更新设置", "Session is disconnected or updating settings") }
        val text = external?.text ?: s.record.draft
        val images = external?.images?.map { it.path } ?: s.record.images.map { it.path }
        val skills = external?.skills ?: s.record.skills.toList()
        check(text.isNotBlank() || images.isNotEmpty() || skills.isNotEmpty()) { tr("请输入消息或添加图片", "Enter a message or attach an image") }
        if (images.isNotEmpty()) check(s.models.firstOrNull { it.id == s.record.model }?.supportsImages == true) { tr("当前模型未声明图片能力，请选择支持图片的模型", "Current model does not support images; select an image-capable model") }
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
                        if (s.generation == epoch) s.view = s.view.copy(sending = false, awaitingHandshake = false)
                    }
                },
                isRejected = { it is RpcRejected },
                rethrowAcceptedRejection = true,
                onUnknown = { reconcile = true },
                onResponse = { response ->
                    reconcile = true
                    clearSubmission(s, id)
                    publish()
                    val idleDirect = turn == null && !resumeStoppedQueue && !s.queueNeedsExplicitStart &&
                        SubmissionLedger.find(s.record, id)?.queuePresentation == false
                    response.optJSONObject("queuedSubmission")?.let { queued ->
                        check(queued.stringOrNull("clientUserMessageId").let { it == null || it == id }) { tr("原生队列返回了不同提交身份", "Native queue returned a mismatched submission ID") }
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
                            ?: error(tr("CLI 已受理消息，但未返回可启动的队列 ID；不会重新发送", "CLI accepted message without returning a startable queue ID; will not resend"))
                        try {
                            s.rpc!!.request("thread/queue/start", obj("threadId" to s.record.threadId, "queuedSubmissionId" to queuedId))
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            throw java.io.IOException(tr("CLI 已受理消息，但启动排队消息${if (e is RpcRejected) "失败" else "的结果尚未确认"}：${e.message}", "CLI accepted message, but starting queued turn ${if (e is RpcRejected) "failed" else "is unconfirmed"}: ${e.message}"), e)
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
                catch (_: Exception) { s.view = s.view.copy(error = tr("发送结果待同步，请重新连接核对", "Send result awaiting sync; reconnect to verify")) }
                try { reconcileCodexSubmission(s, id, carrier, epoch) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { s.view = s.view.copy(error = tr("发送结果待同步，请重新连接核对", "Send result awaiting sync; reconnect to verify")) }
            }
        }
    }
    override fun stop() {
        val s = current() ?: return
        if (s.releasing) return
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
        val epoch = s.generation
        s.userStoppedTurn = true
        s.view = s.view.copy(stopping = true); publish()
        launch(s) {
            try {
                s.rpc!!.request("turn/interrupt", obj("threadId" to s.record.threadId, "turnId" to turn))
            } catch (e: Exception) {
                if (s.generation == epoch) {
                    if (e !is CancellationException && s.view.activeTurnId != null) s.userStoppedTurn = false
                    if (e is RpcRejected) s.view = s.view.copy(stopping = false)
                }
                throw e
            }
        }
    }
    private fun queueMutation(s: Live, block: suspend () -> Unit) {
        if (s.releasing || !s.view.connected || s.view.queueBusy || s.record.queueTransfer != null) return
        val epoch = s.generation
        s.view = s.view.copy(queueBusy = true); publish()
        launch(s) {
            try { block() }
            finally { s.view = clearQueueBusyIfSameGeneration(s.view, s.generation, epoch) }
        }
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
                        s.view = s.view.copy(error = if (intent.state == "rejected") tr("待发已移除，原生撤回未确认", "Removed locally; native cancellation unconfirmed") else tr("待发已移除，撤回结果待核对", "Removed locally; cancellation awaiting verification"))
                        persist()
                    } finally {
                        if (currentCoroutineContext().isActive && s.observing && s.generation == epoch) try {
                            when {
                                s.record.cli in CliCatalog.bridged -> s.nativeCli?.reconcileQueue()
                                s.record.cli == "OpenCode" -> s.openCode?.reconcileQueue()
                                else -> refreshQueue(s)
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { s.view = s.view.copy(error = tr("待发已移除，原生状态待同步", "Removed locally; native state awaiting sync")) }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { s.dispatchStorageBlocked = true; s.view = s.view.copy(error = tr("无法保存删除状态，原生操作已暂停", "Failed to save removal state; native operation paused")) }
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
        catch (e: SubmissionPersistenceFailure) { s.dispatchStorageBlocked = true; s.view = s.view.copy(error = tr("无法保存删除状态，待发仍保留", "Failed to save removal state; queued message kept")) }
        publish()
    }
    override fun editQueued(id: String, text: String) {
        val s = current() ?: return
        if (changeLocalQueue(s, id, text)) return
        if (s.record.cli != "Codex") { s.view = s.view.copy(error = tr("此 CLI 原生队列不支持编辑正文", "Editing native queue items is not supported by this CLI")); publish(); return }
        queueMutation(s) {
            val raw = s.queueRaw.find { it.optString("id") == id } ?: error(tr("消息已离开队列", "Message is no longer in queue"))
            val input = QueueEdits.replaceText(raw.getJSONArray("input"), text, s.record.cwd)
            check(input.length() > 0) { tr("消息不能为空", "Message cannot be empty") }
            s.rpc!!.request("thread/queue/update", obj("threadId" to s.record.threadId, "queuedSubmissionId" to id, "input" to input))
            refreshQueue(s)
        }
    }
    override fun restoreQueuedToComposer(id: String) {
        val s = current() ?: return
        try {
            check(!s.firstSendPending && !s.view.attachments.any { it.busy }) {
                tr("输入框正在准备内容，请先处理当前输入，再编辑待发消息；两边内容均保留", "Composer is busy preparing attachments; finish current draft before editing queued message (both are kept)")
            }
            val host = s.view.queue.firstOrNull { it.id == id && it.hostOwned && it.canEdit && !it.localRecovery }
            if (host != null) {
                check(!s.takingBack) { tr("正在取回待发消息，请稍候", "Taking back queued message; please wait") }
                check(!s.view.loading && !s.releasing) { tr("请先等待 CLI 观察同步后取回待发消息", "Wait for CLI sync before taking back queued message") }
                val clientId = host.clientUserMessageId ?: error(tr("后台待发未提供精确身份，不能取回", "Backend queue item lacks exact identity and cannot be taken back"))
                check(host.id == clientId) { tr("后台待发身份不一致，不能取回", "Backend queue identity mismatch; cannot take back") }
                val operation = HostQueueTakeback.begin(s.record, clientId, UUID.randomUUID().toString(), ::persistDurable)
                val expected = HostQueueTakeback.payload(checkNotNull(SubmissionLedger.find(s.record, clientId)))
                val adapter = s.nativeCli ?: error(tr("CLI 观察尚未就绪", "CLI observer is not ready yet"))
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
                            check(!s.view.attachments.any { it.busy }) { tr("输入框正在准备内容，请先处理当前输入，再编辑待发消息；两边内容均保留", "Composer is busy preparing attachments; finish current draft before editing queued message (both are kept)") }
                            SubmissionLedger.recoverStoredToComposer(s.record, clientId, host, ::persistDurable)
                            s.view = s.view.copy(error = null)
                        } else if (s.generation == epoch) s.view = s.view.copy(error = rejected.message)
                    } catch (_: Exception) {
                        if (s.generation == epoch) s.view = s.view.copy(error = tr("取回结果待核对；不会返回输入框或重发，请重新同步", "Takeback unconfirmed; will not restore or resend, resync first"))
                    } finally {
                        if (s.generation == epoch) {
                            s.takingBack = false
                            s.view = clearQueueBusyIfSameGeneration(s.view, s.generation, epoch)
                        }
                    }
                }
                return
            }
            val entry = s.view.queue.firstOrNull { it.id == id } ?: error(tr("消息已离开待发队列，未返回输入框", "Message already left queue; not restored to composer"))
            val message = entry.clientUserMessageId?.let { SubmissionLedger.find(s.record, it) }
                ?: s.record.submissions.firstOrNull { "submission-${it.id}" == id }
                ?: error(tr("原待发正文和附件不在本地索引中，无法无损恢复", "Queued text and attachments missing from local index; cannot restore losslessly"))
            if (SubmissionLedger.isLocal(message) || HostQueueTakeback.ready(message))
                SubmissionLedger.restoreLocalToComposer(s.record, message.id, ::persistDurable)
            else SubmissionLedger.recoverStoredToComposer(s.record, message.id, entry, ::persistDurable)
            s.view = s.view.copy(error = null)
        } catch (e: SubmissionPersistenceFailure) {
            s.dispatchStorageBlocked = true
            s.view = s.view.copy(error = tr("无法保存编辑状态，待发消息与当前输入均保留", "Failed to save edit state; both queued message and draft preserved"))
        } catch (e: Exception) { s.view = s.view.copy(error = e.message) }
        publish()
    }
    override fun steerQueued(id: String) {
        val s = current() ?: return
        if (s.releasing) return
        if (s.record.cli in CliCatalog.bridged) { s.nativeCli?.queueChange(id, true); return }
        if (s.record.cli == "OpenCode") { s.openCode?.queueChange(id, true); return }
        queueMutation(s) {
            val turn = s.view.activeTurnId ?: error(tr("当前任务已结束，消息保留在队列中", "Active turn already ended; message stays in queue"))
            val raw = s.queueRaw.find { it.optString("id") == id } ?: error(tr("消息已离开队列", "Message is no longer in queue"))
            val transfer = obj("id" to id, "input" to raw.getJSONArray("input"), "turn" to turn,
                "clientId" to UUID.randomUUID().toString(), "phase" to "deleting")
            s.record.queueTransfer = transfer.toString(); persist(); publish()
            try { s.rpc!!.request("thread/queue/delete", obj("threadId" to s.record.threadId, "queuedSubmissionId" to id)) }
            catch (e: RpcRejected) { s.record.queueTransfer = null; persist(); throw e }
            transfer.put("phase", "removed"); s.record.queueTransfer = transfer.toString(); persist()
            check(s.view.activeTurnId == turn) { tr("当前任务已结束，原消息已保存，可恢复到草稿", "Active turn already ended; original message saved and can be restored to draft") }
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
                check(transfer.getString("phase") == "removed") { tr("请先核对后端执行结果，不能自动重发", "Verify backend execution result first; will not auto-resend") }
                val input = transfer.getJSONArray("input").objects()
                val text = input.filter { it.optString("type") == "text" }.joinToString("\n") { it.optString("text") }
                val paths = input.filter { it.optString("type") == "localImage" }.map { it.getString("path") }
                val images = paths.map { path -> StoredImage(UUID.randomUUID().toString(), runtime.imagePreviewUri(s.record.environment, path) ?: error(tr("原图片不可读取，恢复未执行", "Original image unreadable; restore aborted")), path, path.substringAfterLast('/')) }
                check(input.all { it.optString("type") in listOf("text", "mention", "localImage", "skill") }) { tr("消息包含无法还原为草稿的内容，请保留记录并人工核对", "Message contains items that cannot be restored to draft; verify manually") }
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
    private fun codexCollaborationMode(mode: String, model: String, effort: String): JSONObject =
        obj("mode" to mode, "settings" to obj("model" to model, "reasoning_effort" to if (effort.isBlank()) JSONObject.NULL else effort, "developer_instructions" to JSONObject.NULL))
    override fun configure(model: String, effort: String) {
        val s = current() ?: return
        if (s.releasing) return
        if (s.record.cli in CliCatalog.bridged) { s.nativeCli?.configure(obj("model" to model, "effort" to effort)); return }
        if (s.record.cli == "OpenCode") { s.openCode?.configure(model, effort); return }
        val params = obj("model" to model).apply {
            if (effort.isNotEmpty()) put("effort", effort)
            if (s.view.planModeSupported && s.view.planMode) put("collaborationMode", codexCollaborationMode("plan", model, effort))
        }
        configureNative(params) { live ->
            val option = live.models.firstOrNull { it.id == model } ?: error(tr("该模型不在服务提供的列表中", "Model is not in service catalog"))
            check(effort.isEmpty() || effort in option.efforts) { tr("该模型不支持此推理强度", "Reasoning effort not supported by this model") }
        }
    }
    override fun refreshDashboard() {
        val s = current() ?: return
        if (s.dispatchStorageBlocked) {
            val epoch = s.generation
            scope.launch {
                try {
                    persistDurableAsync()
                    publish()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (sessions[s.record.id] === s && s.generation == epoch) {
                        s.view = s.view.copy(error = tr("无法保存发送状态，待发仍保留", "Failed to save send state; queued item preserved"))
                        publish()
                    }
                    return@launch
                }
                if (sessions[s.record.id] === s && s.generation == epoch) refreshDashboardBody(s)
            }
            return
        }
        refreshDashboardBody(s)
    }
    private fun refreshDashboardBody(s: Live) {
        if (s.record.cli in CliCatalog.bridged) { s.nativeCli?.refreshDashboard(); return }
        if (s.record.cli == "OpenCode") { s.openCode?.refreshDashboard(); return }
        if (!s.view.connected) return
        launch(s) {
            try { s.limits = CodexCapabilities.limitMetrics(s.rpc!!.request("account/rateLimits/read", JSONObject())) }
            catch (e: RpcRejected) { s.limits = emptyList() }
            val activeModel = s.record.model.ifBlank { s.view.summary.model }
            s.tokenPricer.updateCatalog(basePricingCatalog)
            val pricingPayload = runtime.readCodexSessionPricing(s.record.environment, s.rolloutPath)
            if (pricingPayload != null) {
                val state = s.tokenPricer.reconcileSessionPricing(pricingPayload, activeModel, s.view.speed)
                val hasUsage = pricingPayload.optJSONObject("rollout")?.optBoolean("hasUsage") == true || state.total != null || state.last != null
                if (hasUsage) {
                    s.tokens = CodexCapabilities.tokenMetrics(state, activeModel)
                }
            } else if (s.tokens.isEmpty() && !s.rolloutPath.isNullOrBlank()) {
                runtime.readCodexRolloutTokenUsage(s.record.environment, s.rolloutPath!!)?.let { info ->
                    val state = s.tokenPricer.applyRolloutBaseline(info, activeModel, s.view.speed)
                    s.tokens = CodexCapabilities.tokenMetrics(state, activeModel)
                }
            }
            s.view = s.view.copy(metrics = s.tokens)
        }
    }
    private fun configureNative(params: JSONObject, validate: (Live) -> Unit) {
        val s = current() ?: return
        if (s.releasing) return
        if (s.record.cli in CliCatalog.bridged) {
            val body = JSONObject()
            if (params.has("permissions")) body.put("permission", params.getString("permissions"))
            if (params.has("permissionConfigId")) body.put("permissionConfigId", params.getString("permissionConfigId"))
            if (params.has("serviceTier")) body.put("speed", params.optString("serviceTier", "default"))
            if (params.has("planMode")) body.put("planMode", params.getBoolean("planMode"))
            s.nativeCli?.configure(body); return
        }
        if (s.record.cli == "OpenCode") { s.view = s.view.copy(error = tr("OpenCode 未提供此设置选项", "Setting not supported by OpenCode")); publish(); return }
        if (s.view.configuring) return
        val epoch = s.generation
        s.view = s.view.copy(configuring = true); publish()
        launch(s) {
            runGenerationScopedSettings(
                setWaiter = { waiter, expected -> s.settingsWaiter = waiter; s.settingsExpected = expected },
                clearWaiterIfSame = { waiter ->
                    if (s.settingsWaiter === waiter) {
                        s.settingsWaiter = null
                        s.settingsExpected = null
                    }
                },
                clearConfiguring = { s.view = clearConfiguringIfSameGeneration(s.view, s.generation, epoch) },
            ) { assignWaiter ->
                check(s.view.connected && s.view.activeTurnId == null && nativeQueue(s).isEmpty() && !SubmissionLedger.pending(s.record)) { tr("请在任务与队列结束后修改设置", "Wait for active turn and queue to finish before changing settings") }
                validate(s)
                val nextWaiter = assignWaiter(params)
                s.rpc!!.request("thread/settings/update", params.put("threadId", s.record.threadId))
                val settings = withTimeoutOrNull(10000) { nextWaiter.await() }
                check(settings != null) { tr("设置结果尚未同步，请重新连接核对；不会自动重试", "Settings result awaiting sync; reconnect to verify") }
            }
        }
    }
    override fun configurePermission(id: String) {
        val s = current() ?: return
        if (s.releasing) return
        if (s.record.cli == "OpenCode") {
            if (SubmissionLedger.pending(s.record)) {
                s.view = s.view.copy(error = tr("请在任务与队列结束后修改权限模式", "Wait for active turn and queue to finish before changing permission mode"))
                publish()
                return
            }
            s.openCode?.configurePermission(id)
            return
        }
        configureNative(obj("permissions" to id)) { live ->
            check(live.view.permissions.any { it.id == id }) { tr("CLI 未允许此权限配置", "Permission mode not allowed by CLI") }
        }
    }
    override fun configurePermissionGroup(configId: String, id: String) = configureNative(obj("permissions" to id, "permissionConfigId" to configId)) { s ->
        check(s.view.permissions.any { it.id == id && it.configId == configId && configId.isNotBlank() }) { tr("CLI 未提供此模式组", "Mode group not provided by CLI") }
    }
    override fun configurePlanMode(enabled: Boolean) {
        val s = current() ?: return
        if (s.releasing) return
        if (s.record.cli in CliCatalog.bridged) {
            configureNative(obj("planMode" to enabled)) {}
            return
        }
        if (s.record.cli == "OpenCode") { s.view = s.view.copy(error = tr("OpenCode 未提供此设置选项", "Setting not supported by OpenCode")); publish(); return }
        val model = s.record.model.ifBlank { s.view.summary.model }
        val effort = s.record.effort.ifBlank { s.view.effort }
        configureNative(obj("collaborationMode" to codexCollaborationMode(if (enabled) "plan" else "default", model, effort))) { live ->
            check(live.view.planModeSupported) { tr("当前 CLI 未提供独立规划模式切换", "Independent Plan mode switching is not supported by CLI") }
            check(model.isNotBlank()) { tr("请先选择模型后再切换规划模式", "Select a model before switching Plan mode") }
        }
    }
    override fun configureSpeed(id: String) = configureNative(obj("serviceTier" to if (id == "default") JSONObject.NULL else id)) { s ->
        check(s.models.find { it.id == s.record.model }?.speeds?.any { it.id == id } == true) { tr("该模型未声明此速度选项", "Speed option not declared for this model") }
    }
    override fun command(id: String) {
        val s = current() ?: return
        when (id) {
            "compact" -> if (s.releasing) return else if (s.record.cli in CliCatalog.bridged) s.nativeCli?.compact() else if (s.record.cli == "OpenCode") s.openCode?.compact() else launch(s) { check(s.view.connected && s.view.activeTurnId == null && nativeQueue(s).isEmpty() && !SubmissionLedger.pending(s.record)) { tr("请等待当前任务与队列结束", "Wait for current turn and queue to finish") }; s.rpc!!.request("thread/compact/start", obj("threadId" to s.record.threadId)) }
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
                check(s.view.connected) { tr("请先连接会话", "Connect the session first") }
                val entries = if (s.record.cli in CliCatalog.bridged) s.nativeCli!!.inventory(kind) else if (s.record.cli == "OpenCode") s.openCode!!.inventory(kind) else if (kind == "skill") {
                    val page = s.rpc!!.request("skills/list", obj("cwds" to JSONArray(listOf(s.record.cwd)), "forceReload" to false))
                    page.optJSONArray("data")?.objects().orEmpty().flatMap { group ->
                        group.optJSONArray("skills")?.objects().orEmpty().map { skill ->
                            ReadOnlyEntry(skill.getString("name"), skill.optString("description") + "\n" + if (skill.optBoolean("enabled")) tr("已启用", "Enabled") else tr("未启用", "Disabled"), skill.getString("path"))
                        } + group.optJSONArray("errors")?.objects().orEmpty().map { ReadOnlyEntry(tr("读取错误", "Load error"), CodexCapabilities.redact(it.toString(2))) }
                    }
                } else {
                    val entries = mutableListOf<ReadOnlyEntry>(); var next: String? = null
                    val seenCursors = hashSetOf<String>()
                    do {
                        val page = s.rpc!!.request("mcpServerStatus/list", obj("threadId" to s.record.threadId, "cursor" to next, "limit" to 100))
                        entries += page.optJSONArray("data")?.objects().orEmpty().map(CodexCapabilities::mcp)
                        val nextCursor = page.stringOrNull("nextCursor")
                        next = if (nextCursor != null && seenCursors.add(nextCursor)) nextCursor else null
                    } while (next != null)
                    entries
                }
                Inventory(title, entries)
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { Inventory(title, error = e.message ?: tr("读取失败", "Failed to load")) }
            if (selected == s.record.id && inventoryGeneration == generation) mutableState.value = mutableState.value.copy(inventory = result)
        }
    }
    override fun loadPlugins() {
        val s = current() ?: return
        val generation = ++inventoryGeneration
        mutableState.value = mutableState.value.copy(plugins = PluginCatalog(loading = true))
        scope.launch {
            val result = try {
                check(s.view.connected) { tr("请先连接会话", "Connect the session first") }
                if (s.record.cli in CliCatalog.bridged) s.nativeCli!!.plugins()
                else if (s.record.cli == "OpenCode") s.openCode!!.plugins()
                else {
                    val data = s.rpc!!.request("skills/list", obj("cwds" to JSONArray(listOf(s.record.cwd)), "forceReload" to false))
                    val groups = data.optJSONArray("data")?.objects().orEmpty()
                    val skills = groups.flatMap { it.optJSONArray("skills")?.objects().orEmpty() }.filter { it.optBoolean("enabled") }.map {
                        DraftSkill(it.getString("path"), it.getString("name"), it.getString("path"))
                    }.distinctBy { it.id }
                    val mcp = mutableListOf<ReadOnlyEntry>(); var cursor: String? = null
                    val seenCursors = hashSetOf<String>()
                    do {
                        val page = s.rpc!!.request("mcpServerStatus/list", obj("threadId" to s.record.threadId, "limit" to 100, "cursor" to cursor))
                        mcp += page.optJSONArray("data")?.objects().orEmpty().map(CodexCapabilities::mcp)
                        val nextCursor = page.stringOrNull("nextCursor")
                        cursor = if (nextCursor != null && seenCursors.add(nextCursor)) nextCursor else null
                    } while (cursor != null)
                    PluginCatalog(skills, mcp, error = groups.flatMap { it.optJSONArray("errors")?.objects().orEmpty() }.takeIf { it.isNotEmpty() }?.joinToString("\n") { CodexCapabilities.redact(it.toString()) })
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { PluginCatalog(error = e.message ?: tr("读取插件失败", "Failed to load plugins")) }
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
                check(mutableState.value.plugins.skills.any { it == skill }) { tr("该 Skill 不在当前 CLI 提供的列表中", "Skill is not in current CLI catalog") }
                check(s.record.skills.size < s.view.capabilities.maxSkills) { tr("此 CLI 每条消息最多选择 ${s.view.capabilities.maxSkills} 个 Skill", "Up to ${s.view.capabilities.maxSkills} skills per message for this CLI") }
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
        if (s.releasing) return
        if (s.record.cli in CliCatalog.bridged) { s.nativeCli?.answer(requestId, choice, answers); return }
        if (s.record.cli == "OpenCode") { s.openCode?.answer(requestId, choice, answers); return }
        val async = asyncQuestions(s).find { it.id == requestId }
        if (async != null) {
            if (async.answering || !s.view.connected || s.view.loading) return
            launch(s) {
                val current = asyncQuestions(s).find { it.id == requestId } ?: return@launch
                if (current.answering) return@launch
                check(choice != "skip") { tr("此原生异步提问不支持跳过", "Skipping is not supported for this native async prompt") }
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
            try {
                s.rpc!!.respond(request.rawId, response)
            } catch (e: CancellationException) {
                request.answering = false
                publish()
                throw e
            } catch (e: Exception) {
                request.answering = false
                publish()
                throw e
            }
        }
    }
    override fun renameSession(id: String, title: String) {
        val s = sessions[id]?.takeIf { it.record.environment == environment } ?: return
        try { renameLocal(s, title) } catch (e: Exception) { s.view = s.view.copy(error = e.message); publish() }
    }
    private fun renameLocal(s: Live, title: String) {
        val trimmed = title.trim(); require(trimmed.isNotEmpty()) { tr("会话标题不能为空", "Session title cannot be empty") }
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
            mutableState.value = previousState.copy(error = tr("无法归档会话，原会话已保留：${e.message}", "Failed to archive session (preserved): ${e.message}"))
            publish(); return
        }
        disposeSession(s, destroyBackend = true)
        browser.closeOwned(id, s.record.threadId)
        if (wasSelected) preferences.edit().remove("session").apply()
        publish()
    }
    override fun openTui() {
        val s = current() ?: return
        launch(s) {
            check(s.view.canOpenTui) { tr("会话尚未准备好，请重新连接", "Session is not ready yet; please reconnect") }
            val nativeTerminal = s.nativeCli?.terminal()
            persist()
            runtime.openTerminal(context, s.record.environment, s.endpoint, s.record.threadId, s.record.cwd, false, s.record.cli, nativeTerminal, sessionId = s.record.id)
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
              catch (e: Exception) { FileBrowser(path, error = e.message ?: tr("读取目录失败", "Failed to read directory"), picking = picking) }
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
                catch (e: Exception) { tr("无法读取：${e.message ?: "未知错误"}", "Unable to read: ${e.message ?: "unknown error"}") }
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
                val generation = ++previewGeneration
                scope.launch {
                    val inspected = try {
                        runtime.inspectMedia(owner.record.environment, captured)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        captured
                    }
                    if (selected != owner.record.id || generation != previewGeneration) return@launch
                    if (inspected.mimeType == "inode/directory") {
                        val guestPath = try { io.github.modao7067.luma.gui.runtime.MediaSourcePolicy.guestPath(captured.source, captured.cwd ?: owner.record.cwd) } catch (_: Exception) { captured.source }
                        browseFiles(guestPath)
                        return@launch
                    }
                    if (io.github.modao7067.luma.gui.runtime.MediaSourcePolicy.isText(inspected.mimeType)) {
                        previewTextResource(owner, title, inspected)
                        return@launch
                    }
                    mutableState.value = mutableState.value.copy(
                        filePreview = FilePreview(title, "", media = inspected, sessionId = owner.record.id, cwd = captured.cwd ?: owner.record.cwd),
                    )
                }
                return
            }
            previewGeneration++
            mutableState.value = mutableState.value.copy(
                filePreview = FilePreview(title, "", media = captured, sessionId = owner.record.id, cwd = captured.cwd ?: owner.record.cwd),
            )
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
                val inspected = try { runtime.inspectMedia(owner.record.environment, captured) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                if (inspected?.mimeType == "inode/directory") {
                    val guestPath = try { io.github.modao7067.luma.gui.runtime.MediaSourcePolicy.guestPath(captured.source, captured.cwd ?: owner.record.cwd) } catch (_: Exception) { captured.source }
                    withContext(Dispatchers.Main.immediate) { browseFiles(guestPath) }
                    return@preview ""
                }
            }
            val resolved = runtime.resolveMedia(owner.record.environment, dereferenceMedia(owner, captured))
            withContext(Dispatchers.IO) {
                check(resolved.sizeBytes <= 512 * 1024) { tr("文本超过 512 KiB，请使用外部应用打开", "Text exceeds 512 KiB; open in an external app") }
                val bytes = java.io.File(Uri.parse(resolved.uri).path!!).readBytes()
                Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            }
        }
    }
    override fun previewDiff() {
        val s = current() ?: return
        var warning = ""
        preview(s, tr("工作区 Diff", "Workspace Diff"), snapshot = { raw ->
            MediaResource("data:text/x-diff;base64," + java.util.Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8)),
                "changes.patch", "text/x-diff", s.record.id, s.record.cwd)
        }, display = { raw -> (raw + warning).ifBlank { tr("没有已跟踪文件的变更；未跟踪文件请从文件列表查看。", "No tracked file changes; check Files for untracked files.") } + tr("\n\n预览最多 512 KiB；仅包含已跟踪文件。", "\n\nPreview up to 512 KiB; tracked files only.") }) {
            check(s.view.connected) { tr("请先连接会话", "Connect the session first") }
            check(s.record.cli !in CliCatalog.bridged) { tr("此 CLI 协议不提供 Diff；请在终端使用 git diff", "Diff is not provided by this CLI protocol; run git diff in terminal") }
            if (s.record.cli == "OpenCode") return@preview s.openCode!!.diff()
            val result = s.rpc!!.request("command/exec", obj("command" to JSONArray(listOf("git", "--no-pager", "diff", "--no-ext-diff", "HEAD")), "cwd" to s.record.cwd, "outputBytesCap" to 524288))
            warning = result.optString("stderr")
            if (result.optInt("exitCode") != 0) throw java.io.IOException(tr("git diff 退出码 ${result.optInt("exitCode")}\n${result.optString("stdout")}$warning", "git diff exited with ${result.optInt("exitCode")}\n${result.optString("stdout")}$warning"))
            result.optString("stdout")
        }
    }
    override fun closePreview() { previewGeneration++; mutableState.value = mutableState.value.copy(filePreview = null) }
    override fun dismissError() { current()?.openCode?.dismissError(); current()?.nativeCli?.dismissError(); current()?.let { it.view = it.view.copy(error = null) }; mutableState.value = mutableState.value.copy(error = null); publish() }
    fun saveDrafts() {
        saveJob?.cancel()
        try {
            persist()
        } catch (e: Exception) {
            mutableState.value = mutableState.value.copy(error = tr("无法保存草稿：${e.message}", "Failed to save draft: ${e.message}"))
            publish()
            return
        }
        scope.launch {
            try {
                withContext(ioDispatcher) {
                    store.flush()
                    commands.flush()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.value = mutableState.value.copy(error = tr("无法保存草稿：${e.message}", "Failed to save draft: ${e.message}"))
                publish()
            }
        }
    }
    fun disconnectAll() {
        wakeEpoch++
        clearSubAgentObservation()
        if (RootBackgroundNotifications.isEnabled(context)) {
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
                connection = tr("界面已断开 · 后端任务不受影响", "UI detached · Backend tasks unaffected"), canOpenTui = false, requests = emptyList())
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
        syncCodexDaemonIdleTimer()
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

internal fun resetConnectionView(
    view: SessionView,
    loading: Boolean = true,
    connection: String = view.connection,
    error: String? = view.error,
): SessionView = view.copy(
    connected = false,
    loading = loading,
    sending = false,
    stopping = false,
    configuring = false,
    queueBusy = false,
    canOpenTui = false,
    requests = emptyList(),
    connection = connection,
    error = error,
)

internal fun clearQueueBusyIfSameGeneration(
    view: SessionView,
    currentGeneration: Int,
    expectedGeneration: Int,
): SessionView = if (currentGeneration == expectedGeneration) view.copy(queueBusy = false) else view

internal fun clearConfiguringIfSameGeneration(
    view: SessionView,
    currentGeneration: Int,
    expectedGeneration: Int,
): SessionView = if (currentGeneration == expectedGeneration) view.copy(configuring = false) else view

internal suspend fun runGenerationScopedSettings(
    setWaiter: (CompletableDeferred<JSONObject>, JSONObject) -> Unit,
    clearWaiterIfSame: (CompletableDeferred<JSONObject>) -> Unit,
    clearConfiguring: () -> Unit,
    block: suspend (assignWaiter: (JSONObject) -> CompletableDeferred<JSONObject>) -> Unit,
) {
    var waiter: CompletableDeferred<JSONObject>? = null
    try {
        block { expected ->
            val nextWaiter = CompletableDeferred<JSONObject>()
            waiter = nextWaiter
            setWaiter(nextWaiter, expected)
            nextWaiter
        }
    } finally {
        waiter?.let(clearWaiterIfSame)
        clearConfiguring()
    }
}
