package io.github.modao7067.luma.gui.session

import org.junit.Assert.*
import org.junit.Test

class EmptySessionPolicyTest {
    private fun record(nativeId: String? = "native") = SessionRecord("local", "Native title", "root", "/workspace",
        cli = "DeepSeek Harness", threadId = nativeId)
    private fun view(record: SessionRecord) = SessionView(SessionSummary(record.id, record.title, record.environment,
        record.cwd, cli = record.cli, threadId = record.threadId), connected = true)

    @Test fun persistedDshReferenceSurvivesUntilHistoryIsConfirmed() {
        val record = record()
        assertFalse(EmptySessionPolicy.canDiscard(record, view(record), historyReady = false))
        assertEquals("native", record.threadId)
    }

    @Test fun loadingDisconnectedAndFailedRecoveryAreNeverEmptyEvidence() {
        val record = record()
        val ready = view(record)
        listOf(ready.copy(loading = true), ready.copy(connected = false), ready.copy(error = "History read failed"))
            .forEach { assertFalse(EmptySessionPolicy.canDiscard(record, it, historyReady = true)) }
    }

    @Test fun completedNewEmptySessionCanStillBeDiscarded() {
        val record = record()
        assertTrue(EmptySessionPolicy.canDiscard(record, view(record), historyReady = true))
    }

    @Test fun nativeHistoryPreventsDiscardAfterHydration() {
        val record = record()
        val hydrated = view(record).copy(messages = listOf(ConversationItem("native-message", "user", "original")))
        assertFalse(EmptySessionPolicy.canDiscard(record, hydrated, historyReady = true))
    }

    @Test fun unknownCreationAndUnknownSubmissionRemainRecoverable() {
        val record = record()
        record.creationUnknown = true
        assertFalse(EmptySessionPolicy.canDiscard(record, view(record), historyReady = true))
        record.creationUnknown = false
        record.submissionId = "original-submission"; record.submissionState = "unknown"
        assertFalse(EmptySessionPolicy.canDiscard(record, view(record), historyReady = true))
        assertEquals("original-submission", record.submissionId)
    }

    @Test fun draftQueueTransferAndNativeAgentAreNotEmptySessions() {
        val record = record()
        record.draft = "unsent"
        assertFalse(EmptySessionPolicy.canDiscard(record, view(record), historyReady = true))
        record.draft = ""; record.queueTransfer = "retained transfer"
        assertFalse(EmptySessionPolicy.canDiscard(record, view(record), historyReady = true))
        record.queueTransfer = null
        assertFalse(EmptySessionPolicy.canDiscard(record, view(record).copy(subAgents = listOf(SubAgentState("child", status = "running"))), historyReady = true))
    }
}
