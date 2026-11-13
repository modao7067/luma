package io.github.modao7067.luma.gui.session

internal object SessionIdlePolicy {
    const val graceMillis = 5 * 60_000L
    fun restoreObservation(record: SessionRecord, commandStates: Collection<String>): Boolean {
        SubmissionLedger.migrate(record)
        return record.observationRequired || commandStates.any { it !in setOf("ready", "accepted", "rejected") } || record.submissionId != null ||
            record.queueTransfer != null || record.queueDismissals.any { it.state !in setOf("local", "confirmed") } ||
            record.submissions.any {
                it.state in setOf("local", "pending", "unknown", "queued") ||
                    (!it.dismissed && it.queuePresentation && !it.nativeConsumed && it.state == "accepted")
            }
    }
    fun observationIntent(record: SessionRecord, view: SessionView, idleConfirmed: Boolean): Boolean = when {
        !view.connected || view.loading -> record.observationRequired
        view.activeTurnId != null || view.requests.isNotEmpty() || view.queue.isNotEmpty() ||
            view.subAgents.any { it.status !in setOf("succeeded", "failed", "cancelled", "closed") } -> true
        idleConfirmed && eligible(record, view) -> false
        else -> record.observationRequired
    }
    fun eligible(record: SessionRecord, view: SessionView): Boolean {
        SubmissionLedger.migrate(record)
        return view.connected && !view.loading && !view.sending && !view.configuring && !view.stopping && !view.queueBusy &&
            !record.threadId.isNullOrBlank() && !record.creationUnknown && !record.requireLoaded &&
            view.activeTurnId == null && view.requests.isEmpty() && view.queue.isEmpty() &&
            view.subAgents.none { it.status !in setOf("succeeded", "failed", "cancelled", "closed") } &&
            view.attachments.none { it.busy } && record.queueTransfer == null && record.submissionId == null &&
            record.queueDismissals.none { it.state !in setOf("local", "confirmed") } &&
            record.submissions.none { it.state in setOf("local", "pending", "unknown", "queued") ||
                (!it.dismissed && it.queuePresentation && !it.nativeConsumed && it.state == "accepted") }
    }
}
