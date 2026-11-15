package io.github.modao7067.luma.gui.session

import org.junit.Assert.*
import org.junit.Test

class SessionIdlePolicyTest {
    private fun record() = SessionRecord("local", "Original", "root", "/workspace", threadId = "native", needsName = false)
    private fun view() = SessionView(SessionSummary("local", "Original", "root", "/workspace"), connected = true)
    @Test fun appRestoreDoesNotReloadEveryHistoricalManagedConversation() {
        val r = record()
        assertFalse(SessionIdlePolicy.restoreObservation(r, listOf("ready", "accepted", "rejected")))
        assertTrue(SessionIdlePolicy.restoreObservation(r, listOf("ready", "unknown")))
        r.submissions += StoredSubmission("uncertain", "Protect", state = "unknown")
        assertTrue(SessionIdlePolicy.restoreObservation(r, emptyList()))
        r.submissions.clear(); r.queueDismissals += StoredQueueDismissal("pending-removal")
        assertTrue(SessionIdlePolicy.restoreObservation(r, emptyList()))
    }
    @Test fun consumedMessageDoesNotHideAnActiveNativeTurnOrApprovalAcrossAppRestore() {
        val r = record(); r.submissions += StoredSubmission("done", "Consumed", state = "accepted", nativeConsumed = true)
        r.observationRequired = SessionIdlePolicy.observationIntent(r, view().copy(activeTurnId = "still-running"), idleConfirmed = false)
        assertTrue(SessionIdlePolicy.restoreObservation(r, listOf("accepted", "ready")))
        r.observationRequired = SessionIdlePolicy.observationIntent(r, view().copy(connected = false), idleConfirmed = false)
        assertTrue(r.observationRequired)
        r.observationRequired = SessionIdlePolicy.observationIntent(r, view(), idleConfirmed = true)
        assertFalse(r.observationRequired)
        r.observationRequired = SessionIdlePolicy.observationIntent(r, view().copy(requests = listOf(UserRequest("approval", "Approval", ""))), idleConfirmed = true)
        val restored = decodeSessionIndex(encodeSessionIndex(listOf(r))).single()
        assertTrue(restored.observationRequired)
        assertTrue(SessionIdlePolicy.restoreObservation(restored, listOf("ready")))
    }
    @Test fun legacyIndexNeedsOneReconciliationButConfirmedIdleRecordsDoNotWakeAgain() {
        val encoded = encodeSessionIndex(listOf(record()))
        encoded.getJSONArray("sessions").getJSONObject(0).remove("observationRequired")
        val legacy = decodeSessionIndex(encoded).single()
        assertTrue(legacy.observationRequired)
        assertTrue(SessionIdlePolicy.restoreObservation(legacy, listOf("ready")))
        legacy.observationRequired = SessionIdlePolicy.observationIntent(legacy, view(), idleConfirmed = true)
        assertFalse(decodeSessionIndex(encodeSessionIndex(listOf(legacy))).single().observationRequired)
    }
    @Test fun confirmedIdleKeepsUnsentDraftAndStableIdentity() {
        val r = record(); r.draft = "Retain draft"
        assertTrue(SessionIdlePolicy.eligible(r, view()))
        assertEquals("native", r.threadId); assertEquals("Retain draft", r.draft)
    }
    @Test fun activeDisconnectedLoadingApprovalAndChildrenNeverCountAsIdle() {
        val r = record(); val v = view()
        for (protected in listOf(v.copy(connected = false), v.copy(loading = true), v.copy(activeTurnId = "turn"),
            v.copy(requests = listOf(UserRequest("request", "Approval", ""))),
            v.copy(queue = listOf(QueueEntry("native", "", ""))),
            v.copy(subAgents = listOf(SubAgentState("child", status = "unknown"))), v.copy(sending = true), v.copy(configuring = true))) {
            assertFalse(SessionIdlePolicy.eligible(r, protected))
        }
    }
    @Test fun everyUnsettledDeliveryAndUnknownCreationBlocksRelease() {
        for (state in listOf("local", "pending", "unknown", "queued")) {
            val r = record(); r.submissions += StoredSubmission("message", "same", state = state)
            assertFalse(state, SessionIdlePolicy.eligible(r, view()))
        }
        val r = record(); r.creationUnknown = true
        assertFalse(SessionIdlePolicy.eligible(r, view()))
        r.creationUnknown = false; r.submissions += StoredSubmission("accepted-but-not-consumed", "", state = "accepted", queuePresentation = true)
        assertFalse(SessionIdlePolicy.eligible(r, view()))
    }
    @Test fun foregroundActivityTrackerPreservesForegroundAcrossOverlappingRecreationAndPinnedBrowser() {
        val tracker = ForegroundActivityTracker()
        val oldMain = Any()
        val newMain = Any()
        val browser = Any()
        assertFalse(tracker.isForeground)
        assertTrue(tracker.start(oldMain))
        assertTrue(tracker.isForeground)
        assertTrue(tracker.start(newMain))
        assertTrue(tracker.stop(oldMain))
        assertTrue(tracker.isForeground)
        assertTrue(tracker.start(browser, pinnedSessionId = "s-1"))
        assertTrue(tracker.stop(newMain))
        assertTrue(tracker.isForeground)
        assertTrue(tracker.isPinned("s-1"))
        assertFalse(tracker.isPinned("s-2"))
        assertFalse(tracker.stop(browser))
        assertFalse(tracker.isForeground)
        assertFalse(tracker.isPinned("s-1"))
    }
    @Test fun externalSocketCodexIsExcludedFromAutoReleaseWhileStillSettlingObservationIntent() {
        val managed = record()
        val external = record().copy(externalSocket = "/tmp/external-codex.sock")
        assertTrue(SessionIdlePolicy.canAutoRelease(managed, view()))
        assertFalse(SessionIdlePolicy.canAutoRelease(external, view()))
        assertFalse(SessionIdlePolicy.observationIntent(external, view(), idleConfirmed = true))
    }
}
