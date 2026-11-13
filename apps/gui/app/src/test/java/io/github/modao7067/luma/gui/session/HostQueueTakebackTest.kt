package io.github.modao7067.luma.gui.session

import io.github.modao7067.luma.gui.agent.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HostQueueTakebackTest {
    private fun record() = SessionRecord("s", "Title", "noroot", "/work", cli = "Antigravity", draft = "original\n中文",
        images = mutableListOf(StoredImage("image", "file:///image", "/image", "image")), skills = mutableListOf(DraftSkill("skill", "Skill", "/skill"))).also {
        SubmissionLedger.begin(it, "id", busy = true); SubmissionLedger.state(it, "id", "queued")
    }
    private fun proof(r: SessionRecord, operation: String = "op") = obj("deliveries" to obj("id" to obj("state" to "cancelled")), "receipts" to JSONObject(),
        "takebacks" to obj("id" to obj("operationId" to operation, "payload" to HostQueueTakeback.payload(r.submissions.single()))))
    private fun view(r: SessionRecord) = SessionView(SessionSummary(r.id, r.title, r.environment, r.cwd))

    @Test fun ackLossAndIndexReloadRequireTheExactDurableProofBeforeComposerRecovery() {
        val r = record(); HostQueueTakeback.begin(r, "id", "op") {}
        val restored = decodeSessionIndex(JSONObject(encodeSessionIndex(listOf(r)).toString())).single()
        assertEquals("pending", restored.submissions.single().takebackState)
        assertFalse(NativeCliStatePolicy.recoverableAbsence(restored, "id"))
        HostQueueTakeback.reconcile(restored, JSONObject())
        assertFalse(HostQueueTakeback.ready(restored.submissions.single())); assertEquals("", restored.draft)
        assertNull(HostQueueTakeback.reconcile(restored, proof(restored)))
        assertTrue(HostQueueTakeback.ready(restored.submissions.single()))
        SubmissionLedger.restoreLocalToComposer(restored, "id") {}
        assertEquals("original\n中文", restored.draft); assertEquals("image", restored.images.single().id)
        assertEquals("skill", restored.skills.single().id); assertEquals("restored", restored.submissions.single().takebackState)
        HostQueueTakeback.reconcile(restored, proof(restored))
        assertTrue(SubmissionLedger.project(restored, view(restored)).queue.isEmpty())
    }

    @Test fun manualRetryKeepsItsOperationIdAndInputConflictDoesNotLoseTheReturnedCard() {
        val r = record(); assertEquals("op", HostQueueTakeback.begin(r, "id", "op") {})
        assertEquals("op", HostQueueTakeback.begin(r, "id", "new-token") {})
        r.draft = "newer"; r.draftRevision++
        HostQueueTakeback.reconcile(r, proof(r))
        assertThrows(IllegalStateException::class.java) { SubmissionLedger.restoreLocalToComposer(r, "id") {} }
        assertEquals("newer", r.draft)
        val returned = SubmissionLedger.project(r, view(r)).queue.single()
        assertTrue(returned.canEdit); assertEquals("已取回 · 点击编辑", returned.status)
    }

    @Test fun mismatchedOrNativeClaimedEvidenceNeverManufacturesAProvenCancellation() {
        for (kind in listOf("operation", "payload", "unknown", "accepted")) {
            val r = record(); HostQueueTakeback.begin(r, "id", "op") {}
            val snapshot = proof(r, if (kind == "operation") "other" else "op")
            if (kind == "payload") snapshot.getJSONObject("takebacks").getJSONObject("id").getJSONObject("payload").put("text", "other")
            if (kind in setOf("unknown", "accepted")) snapshot.getJSONObject("receipts").put("id", kind)
            assertNotNull(HostQueueTakeback.reconcile(r, snapshot))
            assertEquals("failed", r.submissions.single().takebackState); assertEquals("", r.draft)
            assertFalse(HostQueueTakeback.ready(r.submissions.single()))
            assertFalse(NativeCliStatePolicy.recoverableAbsence(r, "id"))
        }
    }

    @Test fun intentAndFinalComposerSaveFailuresKeepTheOriginalPayloadAndTransaction() {
        val r = record()
        assertThrows(SubmissionPersistenceFailure::class.java) { HostQueueTakeback.begin(r, "id", "op") { throw java.io.IOException("fixture") } }
        assertNull(r.submissions.single().takebackId); assertEquals("queued", r.submissions.single().state)
        HostQueueTakeback.begin(r, "id", "op") {}; HostQueueTakeback.reconcile(r, proof(r))
        assertThrows(SubmissionPersistenceFailure::class.java) { SubmissionLedger.restoreLocalToComposer(r, "id") { throw java.io.IOException("fixture") } }
        assertEquals("", r.draft); assertTrue(HostQueueTakeback.ready(r.submissions.single()))
        assertEquals(0L, r.draftRevision); assertEquals("image", r.submissions.single().images.single().id)
    }
}
