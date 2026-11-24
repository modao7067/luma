package io.github.modao7067.luma.gui.session

import io.github.modao7067.luma.gui.i18n.LumaI18n
import io.github.modao7067.luma.gui.i18n.tr

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import io.github.modao7067.luma.gui.agent.*
import io.github.modao7067.luma.gui.runtime.AgentRuntime
import io.github.modao7067.luma.gui.runtime.NativeCliRuntimePolicy
import io.github.modao7067.luma.gui.runtime.RuntimeEndpoint
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

internal class NativeCliSession(
    private val record: SessionRecord,
    private val runtime: AgentRuntime,
    private val persist: () -> Unit,
    private val bindBrowser: (String?) -> String,
    private val changed: (SessionView, List<ModelOption>) -> Unit,
    private val approval: () -> Unit = {},
    initialModels: List<ModelOption> = emptyList(),
    private var resetCatalogOnSnapshot: Boolean = false,
    private val persistCheckpoint: () -> Unit = persist,
) {
    private var job = SupervisorJob()
    private var scope = CoroutineScope(job + Dispatchers.Main.immediate)
    private var api: NativeCliClient? = null
    var endpoint: RuntimeEndpoint? = null
        private set
    private var epoch = 0
    private var revision = -1L
    private var snapshot = JSONObject()
    private var watchSignals: Channel<Unit>? = null
    @Volatile private var watchSseSupported: Boolean? = null
    private var view = SessionView(SessionSummary(record.id, record.title, record.environment, record.cwd, cli = record.cli))
    private var models = initialModels
    internal var catalogIdentity = NativeCliCatalogIdentity.of(record)
        private set
    internal val retryable: Boolean get() = NativeCliStatePolicy.retryable(snapshot)
    private var titleHistoryReady = false
    private var reliableDelivery = false
    private var agyFallbackMetrics = emptyList<Metric>()
    private var agyFallbackKey = ""
    private val admitting = mutableSetOf<String>()
    private val answering = mutableSetOf<String>()
    private var fastPollDeadlineNs = 0L
    private var lastPendingIds = emptySet<String>()
    private val path get() = "/sessions/${record.id}"
    val historyReady: Boolean get() = snapshot.optBoolean("historyReady")
    private fun armTurnStartBurst(nowNs: Long = System.nanoTime()) {
        fastPollDeadlineNs = nowNs + TURN_START_BURST_NS
    }
    private val awaitingApproval: Boolean
        get() = view.connected &&
            !view.loading &&
            view.requests.isNotEmpty() &&
            answering.isEmpty() &&
            view.subAgents.none { it.status !in setOf("succeeded", "completed", "failed", "cancelled", "closed", "aborted") }
    private val awaitingTurnStart: Boolean
        get() = NativeCliStatePolicy.shouldFastPollTurnStart(
            connected = view.connected,
            loading = view.loading,
            activeTurnId = view.activeTurnId,
            admitting = admitting.isNotEmpty(),
            sending = view.sending,
            awaitingHandshake = view.awaitingHandshake,
            hasPending = record.submissions.any { !it.dismissed && it.state == "pending" },
            hasUnsettledUnknown = record.submissions.any { !it.dismissed && it.state == "unknown" },
            nowNs = System.nanoTime(),
            burstDeadlineNs = fastPollDeadlineNs,
        )
    private fun publish() = changed(view, models)
    private fun apply(s: JSONObject) = apply(s, null)
    private fun apply(s: JSONObject, preprojected: Pair<SessionView, SessionView>?) {
        if (s.optBoolean("unchanged") || s.optLong("revision", -1) < revision) return
        revision = s.optLong("revision", -1); snapshot = s
        val oldRequests = view.requests.map { it.id }.toSet()
        val before = listOf(record.threadId, record.model, record.effort, record.title, record.submissions.map { listOf(it.id, it.state, it.nativeQueued, it.nativeConsumed, it.queuePresentation, it.takebackState) }, record.nativeProtocol)
        if (record.nativeProtocol != null) check(s.stringOrNull("nativeProtocol") == record.nativeProtocol) { tr("服务未确认同一原生协议，已拒绝同步", "Service did not confirm matching native protocol; sync refused") }
        s.stringOrNull("nativeProtocol")?.takeIf { it.isNotBlank() }?.let {
            check(record.nativeProtocol == null || record.nativeProtocol == it) { tr("原生协议身份改变，已拒绝同步", "Native protocol identity changed; sync refused") }
            record.nativeProtocol = it
        }
        s.stringOrNull("nativeId")?.takeIf { it.isNotBlank() }?.let {
            check(record.threadId == null || record.threadId == it) { tr("原生会话身份改变，已拒绝同步", "Native session identity changed; sync refused") }
            if (record.threadId == null) bindBrowser(it)
            record.threadId = it
        }
        NativeCliStatePolicy.settings(s, record.model, record.effort).let { (model, effort) ->
            record.model = model; record.effort = effort
        }
        val titleChanged = SessionTitlePolicy.nativeTitle(record, s.stringOrNull("title"))
        models = NativeCliStatePolicy.models(s, models, resetCatalogOnSnapshot)
        resetCatalogOnSnapshot = false
        catalogIdentity = NativeCliCatalogIdentity.of(record)
        val nativeRunning = s.optBoolean("running")
        val projected = if (preprojected != null && preprojected.first === view) preprojected.second else NativeCliProjection.view(s, view)
        view = projected.let { fresh ->
            val filteredQueue = if (nativeRunning) {
                fresh.queue
            } else {
                fresh.queue.filterNot { entry ->
                    entry.hostOwned && entry.clientUserMessageId?.let { cid ->
                        SubmissionLedger.find(record, cid)?.queuePresentation == false
                    } == true
                }
            }
            fresh.copy(queue = filteredQueue, requests = fresh.requests.map { it.copy(answering = it.id in answering) })
        }
        if (record.cli == "Antigravity" && view.metrics.none { it.label == "上下文" || it.label == "Context" }) {
            if (agyFallbackMetrics.isNotEmpty()) {
                view = view.copy(metrics = agyFallbackMetrics)
            }
            val tid = record.threadId
            if (!tid.isNullOrBlank() && view.connected && !view.loading) {
                val key = "$tid:${record.model}:${view.messages.size}:${view.activeTurnId}:${LumaI18n.langCode()}"
                if (key != agyFallbackKey) {
                    agyFallbackKey = key
                    val currentEpoch = epoch
                    scope.launch {
                        val rows = runtime.readAntigravitySessionMetrics(record.environment, tid, record.model)
                        if (currentEpoch == epoch && rows.isNotEmpty()) {
                            agyFallbackMetrics = rows
                            if (view.metrics.none { it.label == "上下文" || it.label == "Context" }) {
                                view = view.copy(metrics = rows)
                                publish()
                            }
                        }
                    }
                }
            }
        }
        val titleReady = view.connected && !view.loading && (record.cli != "DeepSeek Harness" || historyReady)
        if (titleReady) titleHistoryReady = true
        val messageTitleChanged = SessionTitlePolicy.history(record, view.messages, titleReady)
        s.optJSONObject("deliveries")?.let { deliveries -> deliveries.keys().forEach { id ->
            when (val state = deliveries.optJSONObject(id)?.optString("state")) {
                "queued" -> {
                    val msg = SubmissionLedger.find(record, id)
                    if (!nativeRunning && msg != null && !msg.queuePresentation) {
                        SubmissionLedger.state(record, id, "pending")
                    } else {
                        SubmissionLedger.state(record, id, "queued")
                    }
                }
                "unknown", "cancelled" -> SubmissionLedger.state(record, id, state)
                "dispatching" -> SubmissionLedger.state(record, id, "pending")
            }
        } }
        if (reliableDelivery && s.has("deliveries") && s.has("receipts")) {
            record.submissions.filter { it.state in setOf("pending", "unknown") && it.id !in admitting &&
                NativeCliStatePolicy.recoverableAbsence(record, it.id) &&
                !s.getJSONObject("deliveries").has(it.id) && !s.getJSONObject("receipts").has(it.id)
            }.forEach { SubmissionLedger.state(record, it.id, "local") }
        }
        view.queue.filterNot { it.hostOwned }.mapNotNull { it.clientUserMessageId }.forEach { id ->
            SubmissionLedger.find(record, id)?.nativeQueued = true
        }
        s.optJSONObject("receipts")?.let { receipts -> receipts.keys().forEach { id ->
            when (receipts.optString(id)) {
                "accepted" -> {
                    record.receiptRecoveryRequired.remove(id)
                    SubmissionLedger.accepted(record, titleEligible = titleHistoryReady, id = id)
                    if (s.optInt("inputEvidenceProtocol") < 1) SubmissionLedger.legacyAdmitted(record, id)
                }
                "rejected" -> { record.receiptRecoveryRequired.remove(id); SubmissionLedger.rejected(record, id) }
            }
        } }
        s.optJSONObject("admittedDisplaySubmissions")?.let { admitted -> admitted.keys().forEach { id ->
            if (admitted.optBoolean(id) && s.optJSONObject("receipts")?.optString(id) == "accepted") SubmissionLedger.legacyAdmitted(record, id)
        } }
        s.optJSONObject("consumedSubmissions")?.let { consumed -> consumed.keys().forEach { id -> if (consumed.optBoolean(id)) SubmissionLedger.consumed(record, id) } }
        HostQueueTakeback.reconcile(record, s)?.let { view = view.copy(error = it) }
        if (titleChanged || messageTitleChanged || before != listOf(record.threadId, record.model, record.effort, record.title, record.submissions.map { listOf(it.id, it.state, it.nativeQueued, it.nativeConsumed, it.queuePresentation, it.takebackState) }, record.nativeProtocol)) persist()
        val currentPendingIds = record.submissions.asSequence().filter { !it.dismissed && it.state == "pending" }.map { it.id }.toSet()
        if (view.activeTurnId != null || currentPendingIds.isEmpty()) {
            fastPollDeadlineNs = 0L
        } else if (currentPendingIds.any { it !in lastPendingIds }) {
            armTurnStartBurst()
        }
        lastPendingIds = currentPendingIds
        answering.retainAll(view.requests.map { it.id }.toSet())
        publish()
        if (view.loading || awaitingTurnStart || (view.activeTurnId != null && !awaitingApproval)) watchSignals?.trySend(Unit)
        if (view.requests.any { it.id !in oldRequests }) approval()
    }
    private fun run(block: suspend () -> Unit) {
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { view = view.copy(error = e.message ?: tr("CLI 操作失败", "CLI action failed")) }
            finally { publish() }
        }
    }
    suspend fun connect() {
        val previous = job
        close(); val generation = epoch
        previous.join()
        if (generation != epoch) throw CancellationException(tr("界面连接已取消", "UI connection cancelled"))
        job = SupervisorJob(); scope = CoroutineScope(job + Dispatchers.Main.immediate); revision = -1
        val signals = Channel<Unit>(Channel.CONFLATED)
        watchSignals = signals
        watchSseSupported = null
        fastPollDeadlineNs = 0L
        lastPendingIds = emptySet()
        view = view.copy(loading = true, connected = false, error = null); publish()
        withContext(scope.coroutineContext) {
            try {
                val ep = runtime.connectNativeCli(record.cli, record.environment)
                endpoint = ep
                val client = NativeCliClient(ep.url, ep.authToken!!); api = client
                connectClient(client)
                scope.launch {
                    try {
                        while (isActive && (view.connected || view.loading)) {
                            val supported = try {
                                client.watchEvents(
                                    onConnected = {
                                        watchSseSupported = true
                                        signals.trySend(Unit)
                                    },
                                    onEvent = { signals.trySend(Unit) },
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: IOException) {
                                watchSseSupported = null
                                signals.trySend(Unit)
                                delay(2_000)
                                continue
                            }
                            if (!supported) {
                                watchSseSupported = false
                                signals.trySend(Unit)
                                break
                            }
                            signals.trySend(Unit)
                        }
                    } catch (e: CancellationException) { throw e }
                }
                scope.launch {
                    try {
                        var idleBackoffMs = 1_000L
                        while (isActive) {
                            if (view.loading || awaitingTurnStart || (view.activeTurnId != null && !awaitingApproval)) {
                                idleBackoffMs = 1_000L
                                delay(60)
                            } else if (watchSseSupported == true) {
                                idleBackoffMs = 1_000L
                                signals.receive()
                            } else {
                                val woken = withTimeoutOrNull(idleBackoffMs) { signals.receive() } != null
                                idleBackoffMs = if (woken) 1_000L else (idleBackoffMs * 2).coerceAtMost(4_000L)
                            }
                            val next = client.call("$path?after=$revision")
                            if (!next.optBoolean("unchanged") && next.optLong("revision", -1) >= revision) {
                                idleBackoffMs = 1_000L
                                val base = view
                                val projected = withContext(Dispatchers.Default) { NativeCliProjection.view(next, base) }
                                apply(next, base to projected)
                            }
                            if (!view.connected && !view.loading) break
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { view = view.copy(loading = false, connected = false, requests = emptyList(), connection = tr("连接中断 · 请重新同步", "Disconnected · Resync required"), error = e.message); publish() }
                }
            } catch (e: CancellationException) {
                api?.close(); api = null; job.cancel(); throw e
            } catch (e: Exception) {
                api?.close(); api = null; job.cancel()
                if (e is NativeCliRuntimePolicy.CarrierUpdateRequired || e is ManagedSessionPolicy.ProtocolUpdateRequired) {
                    snapshot = obj("catalogState" to "error", "error" to e.message,
                        "lifecycle" to obj("phase" to "blocked", "reason" to "configuration", "retryable" to false, "message" to e.message))
                    view = view.copy(loading = false, connected = false, connection = e.message.orEmpty(), error = e.message, cliServiceUpdateRequired = true)
                } else view = view.copy(loading = false, connected = false, error = if (record.threadId == null)
                    tr("${e.message ?: "CLI 初始化失败"}\n请处理后重新连接；未发送的输入仍保留。", "${e.message ?: "CLI initialization failed"}\nResolve the issue and reconnect; unsent input is preserved.") else e.message)
                publish(); throw e
            }
        }
    }
    internal suspend fun connectClient(client: NativeCliClient) {
        val health = client.call("/health")
        reliableDelivery = health.optInt("deliveryProtocol") >= 2
        if (NativeCliRuntimePolicy.requiresHealthValidation(record.cli)) NativeCliRuntimePolicy.acceptHealth(record.cli, health)
        ManagedSessionPolicy.acceptNativeProtocol(record, health)
        view = view.copy(cliServiceUpdateAvailable = health.optInt("queueTakebackProtocol") < 1 || NativeCliRuntimePolicy.hasCarrierUpdate(health))
        apply(client.call("/sessions/connect", obj("id" to record.id, "cli" to record.cli, "cwd" to record.cwd,
            "nativeId" to record.threadId, "model" to record.model, "initialTitle" to record.initialNativeTitle.takeIf { record.needsName && !record.requireLoaded && !record.useNativeTitle }, "browserConfig" to bindBrowser(record.threadId)).also {
            record.nativeProtocol?.let { protocol -> it.put("nativeProtocol", protocol) }
        }))
    }
    private suspend fun action(name: String, body: JSONObject = JSONObject()) { apply(api!!.call("$path/$name", body)) }
    suspend fun terminal(): JSONObject {
        check(view.canOpenTui) { tr("原生会话终端尚未就绪", "Native session terminal is not ready yet") }
        val response = api!!.call("$path/terminal", JSONObject())
        apply(response)
        return response.getJSONObject("terminal").also {
            check(it.getString("nativeId") == record.threadId.orEmpty()) { tr("原生终端身份已改变，请重新连接核对", "Native terminal identity changed; reconnect to verify") }
        }
    }
    internal val idleConfirmed: Boolean get() = view.connected && !view.loading && snapshot.has("running") && !snapshot.optBoolean("running")
    internal val releasable: Boolean get() = view.connected && !view.loading && snapshot.optBoolean("releasable")
    internal val ownerInstanceId: String? get() = snapshot.optString("ownerInstanceId").takeIf { it.isNotBlank() }
    internal suspend fun releaseIdle() {
        check(releasable) { tr("原生会话尚不能安全休眠", "Native session cannot safely enter dormancy yet") }
        val result = api!!.call("$path/release", obj("nativeId" to record.threadId, "nativeProtocol" to record.nativeProtocol))
        check(result.optBoolean("released") && result.optString("nativeId") == record.threadId) { tr("休眠未确认原生身份", "Dormancy release did not confirm native identity") }
    }

    internal val canDispatchLocal: Boolean get() {
        if (!view.connected || view.loading || view.configuring || record.threadId.isNullOrBlank()) return false
        if (reliableDelivery) return true
        if (!snapshot.has("running")) return false
        if (view.capabilities.steer || view.capabilities.queue) return true
        return view.activeTurnId == null && admitting.isEmpty() && record.submissions.none { it.state in setOf("pending", "unknown") }
    }
    fun submit(steer: Boolean) = run { submitInput(steer) }
    suspend fun submitManaged(input: ManagedSubmission) = submitInput(false, input)
    suspend fun submitFirstDraft() = submitInput(false)
    private suspend fun submitInput(steer: Boolean, external: ManagedSubmission? = null) {
        check(!view.configuring) { tr("会话正在更新设置，输入已保留", "Session is updating settings; input preserved") }
        val input = if (external?.prepared == true) {
            val latest = SubmissionLedger.find(record, external.id)?.takeIf(SubmissionLedger::isLocal) ?: return
            ManagedSubmission(latest.id, latest.text, latest.images, latest.skills, true)
        } else external
        val dispatchNow = canDispatchLocal && (input?.prepared == true || record.submissions.none(SubmissionLedger::isLocal))
        if (input?.prepared == true && !dispatchNow) return
        val text = input?.text ?: record.draft
        val images = input?.images?.map { it.path } ?: record.images.map { it.path }
        val skills = input?.skills?.map { it.id } ?: record.skills.map { it.id }
        if (text.isBlank() && images.isEmpty() && skills.isEmpty()) return
        if (steer) check(view.activeTurnId != null && view.capabilities.steer) { tr("当前没有可追加的运行任务", "No active turn available to steer") }
        check(images.isEmpty() || view.capabilities.images) { tr("当前模型不支持图片", "Current model does not support images") }
        check(skills.size <= view.capabilities.maxSkills) { tr("此 CLI 每条消息最多选择 ${view.capabilities.maxSkills} 个 Skill", "Up to ${view.capabilities.maxSkills} skills can be selected per message for this CLI") }
        val id = input?.id ?: UUID.randomUUID().toString()
        val body = obj("submissionId" to id, "text" to text, "steer" to ((steer || !reliableDelivery) && view.activeTurnId != null && view.capabilities.steer),
            "images" to JSONArray(images), "skills" to JSONArray(skills))
        SubmissionLedger.admit(
            record = record,
            id = id,
            persist = persistCheckpoint,
            input = input,
            busy = view.activeTurnId != null,
            dispatchNow = dispatchNow,
            settings = mapOf("model" to record.model, "effort" to record.effort, "permission" to view.permission).filterValues { it.isNotBlank() },
            onQueuedLocal = ::publish,
            setSending = { sending ->
                if (sending) {
                    admitting.add(id)
                    armTurnStartBurst()
                    view = view.copy(sending = true, awaitingHandshake = view.activeTurnId == null, error = null)
                    publish()
                    watchSignals?.trySend(Unit)
                } else {
                    admitting.remove(id)
                    if (view.activeTurnId == null && record.submissions.any { !it.dismissed && it.state == "pending" }) {
                        armTurnStartBurst()
                    }
                    view = view.copy(sending = false, awaitingHandshake = false)
                }
            },
            isRejected = { it is NativeCliRejected },
            onResponse = ::apply,
        ) {
            api!!.call("$path/${if (reliableDelivery) "submit" else "send"}", body)
        }
    }
    fun stop(onFailed: () -> Unit = {}) {
        if (!view.connected || view.stopping || view.activeTurnId == null) return
        run {
            view = view.copy(stopping = true); publish()
            try { action("stop") }
            catch (e: Exception) { if (e !is CancellationException && view.activeTurnId != null) onFailed(); throw e }
            finally { view = view.copy(stopping = false) }
        }
    }
    internal fun pullLatest() {
        val client = api ?: return
        if (!view.connected && !view.loading) return
        val currentEpoch = epoch
        scope.launch {
            try {
                val next = client.call("$path?after=$revision")
                if (currentEpoch == epoch && !next.optBoolean("unchanged") && next.optLong("revision", -1) >= revision) {
                    val base = view
                    val projected = withContext(Dispatchers.Default) { NativeCliProjection.view(next, base) }
                    apply(next, base to projected)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }
    fun configure(body: JSONObject) {
        if (view.configuring) return
        run {
            check(view.connected && view.activeTurnId == null && view.queue.isEmpty() && !SubmissionLedger.pending(record)) { tr("请等待任务与队列结束再修改设置", "Wait for active turn and queue to finish before changing settings") }
            view = view.copy(configuring = true); publish()
            try { action("configure", body) } finally { view = view.copy(configuring = false) }
        }
    }
    internal val nativeQueue: List<QueueEntry> get() = view.queue
    internal suspend fun removePending(id: String) {
        val entry = view.queue.firstOrNull { it.id == id && it.canRemove } ?: error(tr("原生消息已离开可撤回队列", "Message is no longer in the removable native queue"))
        check(view.connected && !view.loading)
        if (entry.hostOwned) action("cancel-pending", obj("id" to id))
        else action("queue", obj("id" to id, "steer" to false))
    }
    internal suspend fun reconcileQueue() { if (view.connected) action("refresh") }
    internal suspend fun takeBack(id: String, operationId: String, expected: JSONObject) {
        check(api != null && !view.loading) { tr("请先重新连接后取回待发消息", "Reconnect before taking back queued message") }
        action("take-back", obj("id" to id, "operationId" to operationId, "expectedPayload" to expected))
    }
    fun queueChange(id: String, steer: Boolean) {
        if (!view.connected || view.queueBusy) return
        run { view = view.copy(queueBusy = true); publish(); try {
                if (snapshot.optJSONArray("hostQueue")?.objects()?.any { it.optString("id") == id } == true) {
                    action(if (steer) "steer-pending" else "cancel-pending", obj("id" to id))
                } else action("queue", obj("id" to id, "steer" to steer))
            } finally { view = view.copy(queueBusy = false) } }
    }
    fun answer(id: String, choice: String, answers: Map<String, List<String>>) {
        if (id in answering || view.requests.none { it.id == id }) return
        run {
            answering.add(id); view = view.copy(requests = view.requests.map { it.copy(answering = it.id in answering) }); publish(); watchSignals?.trySend(Unit)
            try {
                action("answer", obj("id" to id, "choice" to choice, "answers" to JSONObject(answers)))
            } catch (e: CancellationException) {
                answering.remove(id)
                view = view.copy(requests = view.requests.map { it.copy(answering = it.id in answering) })
                publish()
                throw e
            } catch (e: Exception) {
                answering.remove(id)
                view = view.copy(requests = view.requests.map { it.copy(answering = it.id in answering) })
                publish()
                if (e !is NativeCliRejected) pullLatest()
                throw e
            }
        }
    }
    fun rename(title: String) = run { check(view.connected); check(view.capabilities.rename) { tr("此 CLI 未提供原生改名接口", "Native rename is not supported by this CLI") }; require(title.isNotBlank()); action("rename", obj("title" to title.trim())) }
    fun compact() = run { check(view.connected && view.capabilities.compact) { tr("此 CLI 未提供压缩接口", "Context compaction is not supported by this CLI") }; action("compact") }
    fun refreshDashboard() = run {
        check(view.connected)
        if (record.cli == "Antigravity") agyFallbackKey = ""
        action("refresh")
    }
    fun plugins() = PluginCatalog(skills = NativeCliProjection.skills(snapshot), mcp = NativeCliProjection.mcp(snapshot),
        error = snapshot.stringOrNull("mcpError") ?: if (snapshot.optBoolean("mcpAvailable")) null else tr("MCP：此 CLI 协议未提供配置清单；保留原生配置。", "MCP: configuration listing is not exposed by this CLI protocol; native config remains active."))
    fun inventory(kind: String): List<ReadOnlyEntry> {
        if (kind == "mcp") {
            check(snapshot.optBoolean("mcpAvailable")) { tr("此 CLI 协议未提供 MCP 配置清单", "MCP listing is not supported by this CLI protocol") }
            check(snapshot.stringOrNull("mcpError").isNullOrBlank()) { snapshot.optString("mcpError") }
            return NativeCliProjection.mcp(snapshot)
        }
        check(kind == "skill") { tr("未知清单类型", "Unknown inventory kind") }
        return NativeCliProjection.skills(snapshot).map { ReadOnlyEntry(it.name, tr("CLI 原生 Skill", "CLI native skill"), it.path.takeIf(String::isNotBlank)) }
    }
    fun dismissError() { view = view.copy(error = null); publish() }
    internal fun onLocaleChanged() {
        agyFallbackMetrics = emptyList()
        agyFallbackKey = ""
        if (view.connected && !view.loading && snapshot.length() > 0 && revision >= 0) {
            val base = view
            apply(snapshot, base to NativeCliProjection.view(snapshot, base))
            pullLatest()
        } else if (!view.connected && !view.loading) {
            val updated = when (view.connection) {
                "界面已断开 · 后端任务不受影响", "UI detached · Backend tasks unaffected" ->
                    tr("界面已断开 · 后端任务不受影响", "UI detached · Backend tasks unaffected")
                "连接中断 · 请重新同步", "Disconnected · Resync required" ->
                    tr("连接中断 · 请重新同步", "Disconnected · Resync required")
                else -> null
            }
            if (updated != null && updated != view.connection) {
                view = view.copy(connection = updated)
                publish()
            }
        }
    }
    fun close() {
        epoch++; job.cancel(); watchSignals?.close(); watchSignals = null; watchSseSupported = null; fastPollDeadlineNs = 0L; lastPendingIds = emptySet(); api?.close(); api = null; endpoint = null; answering.clear()
        view = view.copy(connected = false, canOpenTui = false, loading = false, sending = false, configuring = false, queueBusy = false, requests = emptyList(), connection = tr("界面已断开 · 后端任务不受影响", "UI detached · Backend tasks unaffected"))
    }
}

private const val TURN_START_BURST_NS = 2_500_000_000L
