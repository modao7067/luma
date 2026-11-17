package io.github.modao7067.luma.gui.session

import io.github.modao7067.luma.gui.i18n.tr

import io.github.modao7067.luma.gui.agent.CodexAsyncQuestions

internal class SubmissionPersistenceFailure(cause: Exception) : java.io.IOException(tr("无法保存发送状态，输入与待发已保留", "Failed to save send state; draft and queue preserved"), cause)

internal object SubmissionLedger {
    const val confirmationWindowMs = 30_000L
    fun migrate(record: SessionRecord) {
        val id = record.submissionId
        if (id != null && record.submissions.none { it.id == id }) {
            record.submissions += StoredSubmission(id, record.submissionText.orEmpty(), record.submissionImageData,
                record.submissionSkillData, record.submissionExternal, record.submissionDraftRevision, "unknown")
        }
        record.queueDismissals.forEach { d ->
            if (d.id.startsWith("submission-") && d.nativeQueueId == null && d.state == "waiting") {
                d.state = "local"
            }
        }
        syncLegacy(record)
    }
    private fun syncLegacy(record: SessionRecord) {
        val first = record.submissions.firstOrNull { it.state in setOf("pending", "unknown", "queued", "local") }
        record.submissionId = first?.id; record.submissionText = first?.text
        record.submissionState = first?.state.orEmpty(); record.submissionDeadlineMs = 0
        record.submissionImageData = first?.images.orEmpty(); record.submissionSkillData = first?.skills.orEmpty()
        record.submissionImages = first?.images.orEmpty().map { it.id }; record.submissionSkills = first?.skills.orEmpty().map { it.id }
        record.submissionExternal = first?.external ?: false; record.submissionDraftRevision = first?.draftRevision ?: 0
        record.submissionRecovery = ""
    }
    fun begin(record: SessionRecord, id: String, elapsedRealtimeMs: Long = 0, externalText: String? = null, settings: Map<String, String> = emptyMap(), busy: Boolean = false) {
        migrate(record)
        check(record.submissions.none { it.id == id }) { tr("发送标识已存在", "Submission ID already exists") }
        record.submissions += StoredSubmission(id, externalText ?: record.draft,
            if (externalText == null) record.images.toList() else emptyList(),
            if (externalText == null) record.skills.toList() else emptyList(), externalText != null, record.draftRevision, settings = settings, queuePresentation = busy)
        if (externalText == null) { record.draft = ""; record.images.clear(); record.skills.clear() }
        syncLegacy(record)
    }
    fun find(record: SessionRecord, id: String) = record.submissions.find { it.id == id }
    fun isLocal(message: StoredSubmission) = message.state == "local" && !message.dismissed && !message.nativeQueued && !message.nativeConsumed
    fun checkpoint(record: SessionRecord, id: String, persist: () -> Unit, change: () -> Unit) {
        migrate(record)
        val index = record.submissions.indexOfFirst { it.id == id }
        val before = record.submissions.getOrNull(index)?.copy()
        val dismissals = record.queueDismissals.map { it.copy() }
        val receiptFences = record.receiptRecoveryRequired.toSet()
        val dismissedAsync = record.dismissedAsyncQuestionItems.toSet()
        val draft = record.draft; val draftRevision = record.draftRevision
        val images = record.images.toList(); val skills = record.skills.toList()
        val hadResult = record.deliveryResults.containsKey(id); val result = record.deliveryResults[id]
        change(); syncLegacy(record)
        try { persist() }
        catch (failure: Exception) {
            val current = record.submissions.indexOfFirst { it.id == id }
            if (before == null) { if (current >= 0) record.submissions.removeAt(current) }
            else if (current >= 0) record.submissions[current] = before else record.submissions.add(index, before)
            record.draft = draft; record.draftRevision = draftRevision
            record.images.clear(); record.images.addAll(images); record.skills.clear(); record.skills.addAll(skills)
            record.queueDismissals.clear(); record.queueDismissals.addAll(dismissals)
            record.receiptRecoveryRequired.clear(); record.receiptRecoveryRequired.addAll(receiptFences)
            record.dismissedAsyncQuestionItems.clear(); record.dismissedAsyncQuestionItems.addAll(dismissedAsync)
            if (hadResult) record.deliveryResults[id] = result!! else record.deliveryResults.remove(id)
            syncLegacy(record)
            throw SubmissionPersistenceFailure(failure)
        }
    }
    private fun localMutation(record: SessionRecord, id: String, persist: () -> Unit, change: (StoredSubmission, Int) -> Unit) {
        migrate(record)
        val index = record.submissions.indexOfFirst { it.id == id }
        val message = record.submissions.getOrNull(index)
        check(message != null && isLocal(message)) { tr("消息已交给后台，不能再修改本地待发", "Message already handed to backend; cannot edit local queue item") }
        checkpoint(record, id, persist) { change(message, index) }
    }
    fun changeLocal(record: SessionRecord, id: String, text: String? = null, cancel: Boolean = false, persist: () -> Unit) =
        localMutation(record, id, persist) { message, index ->
            if (cancel) message.state = "cancelled"
            else {
                check(text != null && (text.isNotBlank() || message.images.isNotEmpty() || message.skills.isNotEmpty())) { tr("消息不能为空", "Message cannot be empty") }
                record.submissions[index] = message.copy(text = text)
            }
        }
    fun rejectLocal(record: SessionRecord, id: String, persist: () -> Unit) = localMutation(record, id, persist) { _, _ -> rejected(record, id) }
    fun restoreLocalToComposer(record: SessionRecord, id: String, persist: () -> Unit) {
        val message = find(record, id) ?: error(tr("消息已离开本地待发队列", "Message is no longer in local queue"))
        check(isLocal(message) || HostQueueTakeback.ready(message)) { tr("消息已交给后台，不能再修改本地待发", "Message already handed to backend; cannot edit local queue item") }
        checkpoint(record, id, persist) {
            check(id !in record.receiptRecoveryRequired) { tr("此消息仍需核对原生送达状态，不能返回输入框重发", "Delivery status still needs verification; cannot restore to composer") }
            check(record.draft.isEmpty() && record.images.isEmpty() && record.skills.isEmpty()) {
                tr("输入框已有内容，请先处理当前输入，再编辑待发消息；两边内容均保留", "Composer already has text; handle current draft before editing queued message (both are kept)")
            }
            record.draft = message.text
            record.images.addAll(message.images); record.skills.addAll(message.skills)
            record.draftRevision++
            message.state = "cancelled"; message.dismissed = true
            if (message.takebackState == "ready") message.takebackState = "restored"
        }
    }
    fun bindQueue(record: SessionRecord, id: String, queueId: String) {
        find(record, id)?.let { it.nativeQueueId = queueId; it.nativeQueued = true }
    }
    fun queue(record: SessionRecord, entries: List<QueueEntry>) = entries.filterNot { entry ->
        entry.hostOwned && entry.clientUserMessageId?.let { id -> find(record, id)?.takebackState in setOf("ready", "restored") } == true
    }.map { entry ->
        val linked = if (entry.clientUserMessageId != null) entry else entry.copy(clientUserMessageId = record.submissions.firstOrNull { it.nativeQueueId == entry.id }?.id)
        val message = linked.clientUserMessageId?.let { find(record, it) }
        if (message != null && canRecoverStored(message) && (message.state in setOf("pending", "unknown") || !linked.canEdit))
            linked.copy(canEdit = true, localRecovery = true) else linked
    }
    fun canRecoverStored(message: StoredSubmission) = !message.dismissed && message.state in setOf("pending", "unknown", "queued", "accepted")

    fun recoverStoredToComposer(record: SessionRecord, id: String, entry: QueueEntry, persist: () -> Unit) {
        val message = find(record, id) ?: error(tr("原待发正文和附件不在本地索引中，无法无损恢复", "Queued text and attachments are missing from local index; cannot restore losslessly"))
        check(canRecoverStored(message)) { tr("此待发项已在本地处理，请使用当前输入框", "This queued item was already handled locally; use the composer") }
        check(entry.clientUserMessageId == id || entry.id == "submission-$id") { tr("待发身份不一致，无法恢复", "Queued item identity mismatch; cannot restore") }
        check(record.draft.isEmpty() && record.images.isEmpty() && record.skills.isEmpty()) {
            tr("输入框已有内容，请先处理当前输入，再编辑待发消息；两边内容均保留", "Composer already has text; handle current draft before editing queued message (both are kept)")
        }
        checkpoint(record, id, persist) {
            record.draft = message.text; record.images.addAll(message.images); record.skills.addAll(message.skills)
            record.draftRevision++; message.dismissed = true
            if (message.takebackId != null) message.takebackState = "handled"
            record.receiptRecoveryRequired += id
            if (record.queueDismissals.none { matches(it, entry) }) record.queueDismissals += StoredQueueDismissal(entry.id, id,
                entry.id.takeUnless { entry.localSubmission }, entry.hostOwned, "local")
        }
    }
    fun matches(dismissal: StoredQueueDismissal, entry: QueueEntry) = dismissal.id == entry.id || dismissal.nativeQueueId == entry.id ||
        (dismissal.clientUserMessageId != null && dismissal.clientUserMessageId == entry.clientUserMessageId)
    fun dismiss(record: SessionRecord, entry: QueueEntry, persist: () -> Unit) {
        val client = entry.clientUserMessageId ?: record.submissions.firstOrNull { "submission-${it.id}" == entry.id }?.id
        checkpoint(record, client ?: entry.id, persist) {
            val message = client?.let { find(record, it) }
            val local = message?.let(::isLocal) == true
            message?.let { if (local) it.state = "cancelled"; it.dismissed = true }
            if (message != null && !local) record.receiptRecoveryRequired += message.id
            if (record.queueDismissals.none { matches(it, entry) }) record.queueDismissals += StoredQueueDismissal(entry.id, client,
                entry.id.takeUnless { entry.localSubmission }, entry.hostOwned, if (local || (entry.localSubmission && message?.nativeQueueId == null)) "local" else "waiting")
        }
    }
    fun markBusy(record: SessionRecord, id: String, busy: Boolean) { if (busy) find(record, id)?.queuePresentation = true }
    suspend fun <T> admit(
        record: SessionRecord,
        id: String,
        persist: () -> Unit,
        input: ManagedSubmission? = null,
        busy: Boolean,
        dispatchNow: Boolean = true,
        settings: Map<String, String> = emptyMap(),
        onQueuedLocal: () -> Unit = {},
        setSending: (Boolean) -> Unit,
        isRejected: (Exception) -> Boolean,
        rethrowAcceptedRejection: Boolean = false,
        unknownOnCancel: Boolean = true,
        onUnknown: (Exception) -> Unit = {},
        onResponse: suspend (T) -> Unit = {},
        send: suspend () -> T,
    ) {
        checkpoint(record, id, persist) {
            if (input?.prepared == true) {
                state(record, id, "pending")
            } else {
                begin(record, id, externalText = input?.text, settings = settings)
                if (input != null && (input.images.isNotEmpty() || input.skills.isNotEmpty())) {
                    record.submissions[record.submissions.lastIndex] =
                        record.submissions.last().copy(images = input.images, skills = input.skills)
                }
            }
            markBusy(record, id, input?.prepared == true || busy || !dispatchNow)
            if (!dispatchNow) state(record, id, "local")
        }
        if (!dispatchNow) {
            onQueuedLocal()
            return
        }
        setSending(true)
        try {
            val response = try {
                send()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                if (unknownOnCancel && find(record, id)?.state == "pending") {
                    state(record, id, "unknown")
                    persist()
                }
                throw cancelled
            } catch (failure: Exception) {
                if (isRejected(failure)) {
                    if (find(record, id)?.state != "accepted") {
                        rejected(record, id)
                        persist()
                        throw failure
                    }
                    if (rethrowAcceptedRejection) throw failure
                    null
                } else {
                    state(record, id, "unknown")
                    persist()
                    onUnknown(failure)
                    null
                }
            }
            if (response != null) onResponse(response)
        } finally {
            setSending(false)
        }
    }
    fun consumed(record: SessionRecord, id: String) { find(record, id)?.nativeConsumed = true }
    fun legacyAdmitted(record: SessionRecord, id: String) { find(record, id)?.queuePresentation = false }
    fun pending(record: SessionRecord): Boolean { migrate(record); return record.submissions.any { !it.dismissed && it.state in setOf("pending", "unknown", "local") } }
    fun uncertain(record: SessionRecord): Boolean { migrate(record); return record.submissions.any { it.state == "unknown" } }
    fun expire(record: SessionRecord, elapsedRealtimeMs: Long) = false
    suspend fun awaitExpiry(record: SessionRecord, id: String, now: () -> Long, wait: suspend (Long) -> Unit) = false
    fun state(record: SessionRecord, id: String, state: String) {
        migrate(record)
        val message = find(record, id) ?: return
        if (message.dismissed && state == "local") return
        if (message.state in setOf("accepted", "rejected", "cancelled") && state != message.state) return
        message.state = state
        syncLegacy(record)
    }
    fun accepted(record: SessionRecord, titleEligible: Boolean = true, id: String? = record.submissionId) {
        migrate(record)
        val message = id?.let { find(record, it) } ?: return
        if (titleEligible) SessionTitlePolicy.firstMessage(record, UserMessageContent(message.text,
            message.images.map { SessionTitlePolicy.attachment(tr("图片", "Image"), it.path, it.name) } + message.skills.map { SessionTitlePolicy.attachment("Skill", it.path, it.name) }))
        message.state = "accepted"; record.deliveryResults[message.id] = ""
        syncLegacy(record)
    }
    fun rejected(record: SessionRecord, id: String? = record.submissionId) {
        migrate(record)
        val message = id?.let { find(record, it) } ?: return
        if (message.state in setOf("accepted", "rejected")) return
        val canRestore = !message.dismissed && !message.external && record.draftRevision == message.draftRevision && record.draft.isEmpty() && record.images.isEmpty() && record.skills.isEmpty()
        if (canRestore) {
            record.draft = message.text
            record.images.addAll(message.images)
            record.skills.addAll(message.skills)
            message.restoredToComposer = true
        }
        message.state = "rejected"; record.deliveryResults[message.id] = "rejected"
        syncLegacy(record)
    }
    fun recover(record: SessionRecord, id: String, persist: () -> Unit = {}) {
        val message = find(record, id) ?: return
        when {
            message.state == "rejected" && !message.dismissed && !message.restoredToComposer -> checkpoint(record, id, persist) {
                if (record.draft != message.text) record.draft = listOf(record.draft, message.text).filter { it.isNotEmpty() }.joinToString("\n")
                message.images.filterNot { image -> record.images.any { it.id == image.id } }.forEach(record.images::add)
                message.skills.filterNot { skill -> record.skills.any { it.id == skill.id } }.forEach(record.skills::add)
                record.draftRevision++
                message.restoredToComposer = true
                message.dismissed = true
            }
            message.state == "unknown" && !message.dismissed && !message.nativeConsumed -> checkpoint(record, id, persist) {
                if (record.draft != message.text) record.draft = listOf(record.draft, message.text).filter { it.isNotEmpty() }.joinToString("\n")
                message.images.filterNot { image -> record.images.any { it.id == image.id } }.forEach(record.images::add)
                message.skills.filterNot { skill -> record.skills.any { it.id == skill.id } }.forEach(record.skills::add)
                record.draftRevision++
                message.dismissed = true
                record.receiptRecoveryRequired += id
            }
        }
    }
    fun discard(record: SessionRecord) = Unit
    fun project(record: SessionRecord, native: SessionView, outbox: MutableMap<String, ConversationItem> = linkedMapOf()): SessionView {
        val queue = queue(record, native.queue.filterNot { it.localSubmission })
        val displayedIds = queue.flatMap { listOfNotNull(it.id, it.clientUserMessageId) }.toSet()
        val rows = messages(record, native.messages, outbox, queue.filterNot { it.hostOwned }.mapNotNull { it.clientUserMessageId }.toSet())
        val local = record.submissions.filter { !it.dismissed && !it.nativeConsumed && it.id !in displayedIds &&
            ((it.takebackState in setOf("pending", "ready")) || ((it.queuePresentation || it.state == "local") && it.state !in setOf("rejected", "cancelled"))) }.map {
            QueueEntry("submission-${it.id}", display(record, it.text), when {
                HostQueueTakeback.ready(it) -> tr("已取回 · 点击编辑", "Taken back · Tap to edit")
                it.takebackState == "pending" -> tr("取回结果待核对", "Takeback awaiting check")
                it.state == "local" -> tr("本地待发", "Queued locally")
                it.state == "accepted" -> tr("已接收 · 待同步", "Accepted · Syncing")
                else -> tr("发送中", "Sending") },
                canEdit = isLocal(it) || HostQueueTakeback.ready(it) || canRecoverStored(it), canSteer = false, canRemove = isLocal(it),
                clientUserMessageId = it.id, localSubmission = true, canDismiss = true, localRecovery = canRecoverStored(it))
        }
        val filteredQueue = queue.filterNot { entry -> record.queueDismissals.any { matches(it, entry) } }.map { it.copy(canDismiss = true) } + local
        val awaitingHandshake = record.submissions.any {
            !it.dismissed && !it.queuePresentation && !it.nativeQueued && !it.nativeConsumed && it.state == "pending"
        } || (native.sending && native.activeTurnId == null && filteredQueue.isEmpty())
        val canRecoverUnknown = native.activeTurnId == null && !native.loading && !awaitingHandshake
        val recoverableUnknownIds = if (canRecoverUnknown) {
            record.submissions.filter {
                !it.dismissed && !it.queuePresentation && !it.nativeConsumed && it.state == "unknown"
            }.mapTo(HashSet()) { it.id }
        } else emptySet()
        val finalRows = if (recoverableUnknownIds.isEmpty()) rows else rows.map { row ->
            if (row.role == "user" && !row.nativeInputEvidence && row.id in recoverableUnknownIds && !row.canRecover) {
                row.copy(canRecover = true)
            } else row
        }
        return native.copy(draft = record.draft, messages = finalRows, queue = filteredQueue,
            sending = pending(record), awaitingHandshake = awaitingHandshake, uncertainSubmission = false, submissionRecovery = "")
    }
    private fun display(record: SessionRecord, text: String) = if (record.cli == "Codex") CodexAsyncQuestions.displayReply(text) else text
    fun messages(record: SessionRecord, native: List<ConversationItem>, outbox: MutableMap<String, ConversationItem> = linkedMapOf(), queuedClientIds: Set<String> = emptySet()): List<ConversationItem> {
        migrate(record)
        if (record.submissions.isEmpty() && record.deliveryResults.isEmpty() && outbox.isEmpty() && queuedClientIds.isEmpty()) {
            return native
        }
        val submissionIds = record.submissions.mapTo(HashSet(record.submissions.size * 2)) { it.id }
        val nativeIds = HashSet<String>(native.size * 2)
        val evidencedUserIds = HashSet<String>()
        val uncorrelatedNativeUserIds = ArrayList<String>()
        for (row in native) {
            nativeIds.add(row.id)
            if (row.role == "user") {
                if (row.delivery != "pending" && row.id !in submissionIds) {
                    uncorrelatedNativeUserIds.add(row.id)
                }
                if (row.nativeInputEvidence && row.delivery != "pending") {
                    evidencedUserIds.add(row.id)
                }
            }
        }
        val uncorrelatedSet = uncorrelatedNativeUserIds.toHashSet()
        val claimedIds = HashSet<String>()
        record.submissions.forEach { message ->
            if (message.id !in queuedClientIds && message.id in evidencedUserIds) message.nativeConsumed = true
            if (message.id in queuedClientIds) message.nativeQueued = true
            if (!message.queuePresentation && message.state in setOf("pending", "unknown")) {
                message.livePendingObserved = true
                if (message.baselineNativeUserIds == null) {
                    message.baselineNativeUserIds = LinkedHashSet(uncorrelatedNativeUserIds)
                }
            }
            if (message.state in setOf("rejected", "cancelled") || message.dismissed || message.matchedNativeUserId !in uncorrelatedSet) {
                message.matchedNativeUserId = null
            } else if (message.state == "accepted" && message.matchedNativeUserId != null) {
                claimedIds.add(message.matchedNativeUserId!!)
            }
        }
        if (record.cli in CliCatalog.bridged) {
            record.submissions.forEach { message ->
                if (message.state == "accepted" && !message.dismissed && message.livePendingObserved &&
                    message.id !in nativeIds && message.matchedNativeUserId == null
                ) {
                    val baseline = message.baselineNativeUserIds ?: emptySet()
                    val uid = uncorrelatedNativeUserIds.firstOrNull { it !in baseline && it !in claimedIds }
                    if (uid != null) {
                        message.matchedNativeUserId = uid
                        claimedIds.add(uid)
                    }
                }
            }
        }
        val suppressedUnconfirmedIds = HashSet<String>()
        record.submissions.forEach { message ->
            val restoredRejected = message.state == "rejected" && message.restoredToComposer
            if (message.dismissed || restoredRejected || ((message.queuePresentation || message.state in setOf("local", "cancelled")) && !message.nativeConsumed && message.state != "rejected")) {
                suppressedUnconfirmedIds.add(message.id)
            }
            val bridgeAcceptedSettled = record.cli in CliCatalog.bridged && message.state == "accepted" && (
                !message.livePendingObserved || message.nativeConsumed || message.id in nativeIds || message.matchedNativeUserId != null
            )
            if (message.dismissed || restoredRejected || ((message.queuePresentation || message.state == "local") && !message.nativeConsumed && message.state != "rejected") || message.nativeQueued || message.id in queuedClientIds || message.state in setOf("queued", "cancelled") || bridgeAcceptedSettled) {
                outbox.remove(message.id)
            } else {
                val delivery = when (message.state) { "accepted" -> ""; "rejected" -> "rejected"; else -> "pending" }
                outbox[message.id] = ConversationItem(
                    message.id,
                    "user",
                    display(record, message.text),
                    images = message.images.map { it.uri },
                    delivery = delivery,
                    nativeInputEvidence = false,
                    canRecover = delivery == "rejected",
                )
            }
        }
        record.deliveryResults.forEach { (id, state) ->
            outbox[id]?.let {
                val mapped = if (state == "unknown") "pending" else state
                outbox[id] = it.copy(delivery = mapped, canRecover = mapped == "rejected")
            }
        }
        queuedClientIds.forEach { outbox.remove(it) }
        var needsRewrite = false
        for (row in native) {
            val drop = (row.role == "user" && row.id in queuedClientIds) || (!row.nativeInputEvidence && row.id in suppressedUnconfirmedIds)
            val out = outbox[row.id]
            if (drop || (out != null && (row.delivery != out.delivery || row.canRecover != out.canRecover))) {
                needsRewrite = true
                break
            }
        }
        val trailing = if (outbox.isEmpty()) emptyList() else outbox.values.filter { it.id !in nativeIds }
        if (!needsRewrite && trailing.isEmpty()) return native
        val result = ArrayList<ConversationItem>(native.size + trailing.size)
        for (row in native) {
            val drop = (row.role == "user" && row.id in queuedClientIds) || (!row.nativeInputEvidence && row.id in suppressedUnconfirmedIds)
            if (drop) continue
            val out = outbox[row.id]
            result.add(if (out != null && (row.delivery != out.delivery || row.canRecover != out.canRecover)) row.copy(delivery = out.delivery, canRecover = out.canRecover) else row)
        }
        result.addAll(trailing)
        return result
    }
}
