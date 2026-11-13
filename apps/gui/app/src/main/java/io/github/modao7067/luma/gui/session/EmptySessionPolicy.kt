package io.github.modao7067.luma.gui.session

internal object EmptySessionPolicy {
    fun canDiscard(record: SessionRecord, view: SessionView, historyReady: Boolean): Boolean =
        historyReady && view.connected && !view.loading && view.error == null &&
            !record.creationUnknown && view.messages.isEmpty() && view.subAgents.isEmpty() &&
            record.draft.isBlank() && record.images.isEmpty() && record.skills.isEmpty() &&
            view.attachments.isEmpty() && view.activeTurnId == null &&
            view.queue.isEmpty() && record.queueTransfer == null &&
            record.queueDismissals.none { it.state !in setOf("local", "confirmed") } &&
            record.submissionId == null && !view.sending &&
            view.requests.isEmpty()
}
