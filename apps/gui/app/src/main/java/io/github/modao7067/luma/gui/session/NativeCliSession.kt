package io.github.modao7067.luma.gui.session

import kotlinx.coroutines.*
import io.github.modao7067.luma.gui.agent.*
import io.github.modao7067.luma.gui.runtime.AgentRuntime
import io.github.modao7067.luma.gui.runtime.NativeCliRuntimePolicy
import io.github.modao7067.luma.gui.runtime.RuntimeEndpoint
import org.json.JSONArray
import org.json.JSONObject
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
    private val path get() = "/sessions/${record.id}"
    val historyReady: Boolean get() = snapshot.optBoolean("historyReady")
    private fun publish() = changed(view, models)
    private fun apply(s: JSONObject) = apply(s, null)
    private fun apply(s: JSONObject, preprojected: Pair<SessionView, SessionView>?) {
        if (s.optBoolean("unchanged") || s.optLong("revision", -1) < revision) return
        revision = s.optLong("revision", -1); snapshot = s
        val oldRequests = view.requests.map { it.id }.toSet()
        val before = listOf(record.threadId, record.model, record.effort, record.title, record.submissions.map { listOf(it.id, it.state, it.nativeQueued, it.nativeConsumed, it.queuePresentation, it.takebackState) }, record.nativeProtocol)
        if (record.nativeProtocol != null) check(s.stringOrNull("nativeProtocol") == record.nativeProtocol) { "服务未确认同一原生协议，已拒绝同步" }
        s.stringOrNull("nativeProtocol")?.takeIf { it.isNotBlank() }?.let {
            check(record.nativeProtocol == null || record.nativeProtocol == it) { "原生协议身份改变，已拒绝同步" }
            record.nativeProtocol = it
        }
        s.stringOrNull("nativeId")?.takeIf { it.isNotBlank() }?.let {
            check(record.threadId == null || record.threadId == it) { "原生会话身份改变，已拒绝同步" }
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
        if (record.cli == "Antigravity" && view.metrics.none { it.label == "上下文" }) {
            if (agyFallbackMetrics.isNotEmpty()) {
                view = view.copy(metrics = agyFallbackMetrics)
            }
            val tid = record.threadId
            if (!tid.isNullOrBlank() && view.connected && !view.loading) {
                val key = "$tid:${record.model}:${view.messages.size}:${view.activeTurnId}"
                if (key != agyFallbackKey) {
                    agyFallbackKey = key
                    val currentEpoch = epoch
                    scope.launch {
                        val rows = runtime.readAntigravitySessionMetrics(record.environment, tid, record.model)
                        if (currentEpoch == epoch && rows.isNotEmpty()) {
                            agyFallbackMetrics = rows
                            if (view.metrics.none { it.label == "上下文" }) {
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
        answering.retainAll(view.requests.map { it.id }.toSet())
        publish()
        if (view.requests.any { it.id !in oldRequests }) approval()
    }
    private fun run(block: suspend () -> Unit) {
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { view = view.copy(error = e.message ?: "CLI 操作失败") }
            finally { publish() }
        }
    }
    suspend fun connect() {
        val previous = job
        close(); val generation = epoch
        previous.join()
        if (generation != epoch) throw CancellationException("界面连接已取消")
        job = SupervisorJob(); scope = CoroutineScope(job + Dispatchers.Main.immediate); revision = -1
        view = view.copy(loading = true, connected = false, error = null); publish()
        withContext(scope.coroutineContext) {
            try {
                val ep = runtime.connectNativeCli(record.cli, record.environment)
                endpoint = ep
                val client = NativeCliClient(ep.url, ep.authToken!!); api = client
                connectClient(client)
                scope.launch {
                    try {
                        while (isActive) {
                            delay(if (view.activeTurnId != null || view.loading) 60 else 1000)
                            val next = client.call("$path?after=$revision")
                            if (!next.optBoolean("unchanged") && next.optLong("revision", -1) >= revision) {
                                val base = view
                                val projected = withContext(Dispatchers.Default) { NativeCliProjection.view(next, base) }
                                apply(next, base to projected)
                            }
                            if (!view.connected && !view.loading) break
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { view = view.copy(loading = false, connected = false, requests = emptyList(), connection = "连接中断 · 请重新同步", error = e.message); publish() }
                }
            } catch (e: Exception) {
                if (e is NativeCliRuntimePolicy.CarrierUpdateRequired || e is ManagedSessionPolicy.ProtocolUpdateRequired) {
                    snapshot = obj("catalogState" to "error", "error" to e.message,
                        "lifecycle" to obj("phase" to "blocked", "reason" to "configuration", "retryable" to false, "message" to e.message))
                    view = view.copy(loading = false, connected = false, connection = e.message.orEmpty(), error = e.message, cliServiceUpdateRequired = true)
                } else view = view.copy(loading = false, connected = false, error = if (record.threadId == null)
                    "${e.message ?: "CLI 初始化失败"}\n请处理后重新连接；未发送的输入仍保留。" else e.message)
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
        check(view.canOpenTui) { "原生会话终端尚未就绪" }
        val response = api!!.call("$path/terminal", JSONObject())
        apply(response)
        return response.getJSONObject("terminal").also {
            check(it.getString("nativeId") == record.threadId.orEmpty()) { "原生终端身份已改变，请重新连接核对" }
        }
    }
    internal val idleConfirmed: Boolean get() = view.connected && !view.loading && snapshot.has("running") && !snapshot.optBoolean("running")
    internal val releasable: Boolean get() = view.connected && !view.loading && snapshot.optBoolean("releasable")
    internal suspend fun releaseIdle() {
        check(releasable) { "原生会话尚不能安全休眠" }
        val result = api!!.call("$path/release", obj("nativeId" to record.threadId, "nativeProtocol" to record.nativeProtocol))
        check(result.optBoolean("released") && result.optString("nativeId") == record.threadId) { "休眠未确认原生身份" }
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
        check(!view.configuring) { "会话正在更新设置，输入已保留" }
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
        if (steer) check(view.activeTurnId != null && view.capabilities.steer) { "当前没有可追加的运行任务" }
        check(images.isEmpty() || view.capabilities.images) { "当前模型不支持图片" }
        check(skills.size <= view.capabilities.maxSkills) { "此 CLI 每条消息最多选择 ${view.capabilities.maxSkills} 个 Skill" }
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
                    view = view.copy(sending = true, awaitingHandshake = view.activeTurnId == null, error = null)
                    publish()
                } else {
                    admitting.remove(id)
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
            runCatching {
                val next = client.call("$path?after=$revision")
                if (currentEpoch == epoch && !next.optBoolean("unchanged") && next.optLong("revision", -1) >= revision) {
                    val base = view
                    val projected = withContext(Dispatchers.Default) { NativeCliProjection.view(next, base) }
                    apply(next, base to projected)
                }
            }
        }
    }
    fun configure(body: JSONObject) {
        if (view.configuring) return
        run {
            check(view.connected && view.activeTurnId == null && view.queue.isEmpty()) { "请等待任务与队列结束再修改设置" }
            view = view.copy(configuring = true); publish()
            try { action("configure", body) } finally { view = view.copy(configuring = false) }
        }
    }
    internal val nativeQueue: List<QueueEntry> get() = view.queue
    internal suspend fun removePending(id: String) {
        val entry = view.queue.firstOrNull { it.id == id && it.canRemove } ?: error("原生消息已离开可撤回队列")
        check(view.connected && !view.loading)
        if (entry.hostOwned) action("cancel-pending", obj("id" to id))
        else action("queue", obj("id" to id, "steer" to false))
    }
    internal suspend fun reconcileQueue() { if (view.connected) action("refresh") }
    internal suspend fun takeBack(id: String, operationId: String, expected: JSONObject) {
        check(api != null && !view.loading) { "请先重新连接后取回待发消息" }
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
            answering.add(id); view = view.copy(requests = view.requests.map { it.copy(answering = it.id in answering) }); publish()
            try { action("answer", obj("id" to id, "choice" to choice, "answers" to JSONObject(answers))) }
            catch (e: NativeCliRejected) { answering.remove(id); throw e }
        }
    }
    fun rename(title: String) = run { check(view.connected); check(view.capabilities.rename) { "此 CLI 未提供原生改名接口" }; require(title.isNotBlank()); action("rename", obj("title" to title.trim())) }
    fun compact() = run { check(view.connected && view.capabilities.compact) { "此 CLI 未提供压缩接口" }; action("compact") }
    fun refreshDashboard() = run {
        check(view.connected)
        if (record.cli == "Antigravity") agyFallbackKey = ""
        action("refresh")
    }
    fun plugins() = PluginCatalog(skills = NativeCliProjection.skills(snapshot), mcp = NativeCliProjection.mcp(snapshot),
        error = snapshot.stringOrNull("mcpError") ?: if (snapshot.optBoolean("mcpAvailable")) null else "MCP：此 CLI 协议未提供配置清单；保留原生配置。")
    fun inventory(kind: String): List<ReadOnlyEntry> {
        if (kind == "mcp") {
            check(snapshot.optBoolean("mcpAvailable")) { "此 CLI 协议未提供 MCP 配置清单" }
            check(snapshot.stringOrNull("mcpError").isNullOrBlank()) { snapshot.optString("mcpError") }
            return NativeCliProjection.mcp(snapshot)
        }
        check(kind == "skill") { "未知清单类型" }
        return NativeCliProjection.skills(snapshot).map { ReadOnlyEntry(it.name, "CLI 原生 Skill", it.path.takeIf(String::isNotBlank)) }
    }
    fun dismissError() { view = view.copy(error = null); publish() }
    fun close() {
        epoch++; job.cancel(); api?.close(); api = null; endpoint = null; answering.clear()
        view = view.copy(connected = false, canOpenTui = false, loading = false, sending = false, configuring = false, queueBusy = false, requests = emptyList(), connection = "界面已断开 · 后端任务不受影响")
    }
}
