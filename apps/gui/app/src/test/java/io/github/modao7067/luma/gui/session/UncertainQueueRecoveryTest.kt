package io.github.modao7067.luma.gui.session

import io.github.modao7067.luma.gui.agent.NativeCliStatePolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UncertainQueueRecoveryTest {
    private fun record(cli: String, state: String) = SessionRecord("s", "Title", "noroot", "/work", cli = cli, draft = "original\n中文",
        images = mutableListOf(StoredImage("i", "file:///i", "/i", "i")), skills = mutableListOf(DraftSkill("skill", "Skill", "/skill"))).also {
        SubmissionLedger.begin(it, "id", busy = true); SubmissionLedger.state(it, "id", state)
    }
    private fun view(r: SessionRecord) = SessionView(SessionSummary(r.id, r.title, r.environment, r.cwd, cli = r.cli))

    @Test fun allCliUnsettledInputsAreDirectlyEditableLocallyWithoutChangingNativeDeliveryTruth() {
        for (cli in CliCatalog.ids) for (state in listOf("pending", "unknown", "queued", "accepted")) {
            val r = record(cli, state); val card = SubmissionLedger.project(r, view(r)).queue.single()
            assertTrue("$cli/$state", card.canEdit); assertTrue(card.localRecovery)
            SubmissionLedger.recoverStoredToComposer(r, "id", card) {}
            assertEquals("original\n中文", r.draft); assertEquals("i", r.images.single().id); assertEquals("skill", r.skills.single().id)
            assertEquals(state, r.submissions.single().state); assertTrue(r.submissions.single().dismissed)
            assertTrue(r.receiptRecoveryRequired.contains("id")); assertFalse(NativeCliStatePolicy.recoverableAbsence(r, "id"))
            val restored = decodeSessionIndex(JSONObject(encodeSessionIndex(listOf(r)).toString())).single()
            repeat(3) { assertTrue(SubmissionLedger.project(restored, view(restored)).queue.isEmpty()) }
            SubmissionLedger.state(restored, "id", "local"); assertEquals(state, restored.submissions.single().state)
        }
    }

    @Test fun nativeCapabilityAbsenceDoesNotBlockTheAppsPayloadAndHandledNativeCardsDoNotReappear() {
        val r = record("Antigravity", "unknown")
        r.submissions.single().nativeQueued = true
        val native = QueueEntry("native-id", "original", "unknown", canEdit = false, canSteer = false, canRemove = false, clientUserMessageId = "id")
        val card = SubmissionLedger.project(r, view(r).copy(queue = listOf(native))).queue.single()
        assertTrue(card.canEdit); assertTrue(card.localRecovery)
        SubmissionLedger.recoverStoredToComposer(r, "id", card) {}
        assertEquals("unknown", r.submissions.single().state); assertTrue(r.submissions.single().nativeQueued)
        repeat(3) { assertTrue(SubmissionLedger.project(r, view(r).copy(queue = listOf(native))).queue.isEmpty()) }
        SubmissionLedger.accepted(r, id = "id"); r.receiptRecoveryRequired.remove("id")
        assertFalse(NativeCliStatePolicy.recoverableAbsence(r, "id"))
        assertTrue(SubmissionLedger.project(r, view(r).copy(queue = listOf(native))).queue.isEmpty())
    }

    @Test fun unknownDeletionPreservesTheDedupRecordAndSaveFailureRollsBackAllLocalFences() {
        val r = record("Grok Build", "unknown"); val card = SubmissionLedger.project(r, view(r)).queue.single()
        val before = encodeSessionIndex(listOf(r)).toString()
        assertThrows(SubmissionPersistenceFailure::class.java) { SubmissionLedger.dismiss(r, card) { throw java.io.IOException("fixture") } }
        assertEquals(before, encodeSessionIndex(listOf(r)).toString())
        SubmissionLedger.dismiss(r, card) {}
        assertEquals("unknown", r.submissions.single().state); assertEquals("original\n中文", r.submissions.single().text)
        assertTrue(r.submissions.single().dismissed); assertTrue(r.receiptRecoveryRequired.contains("id"))
        assertTrue(SubmissionLedger.project(r, view(r)).queue.isEmpty())
    }

    @Test fun existingComposerAndFailedRecoveryCheckpointNeverLoseEitherPayloadOrItsRevision() {
        val r = record("pi", "unknown"); val card = SubmissionLedger.project(r, view(r)).queue.single()
        r.draft = "newer"
        assertThrows(IllegalStateException::class.java) { SubmissionLedger.recoverStoredToComposer(r, "id", card) {} }
        assertEquals("newer", r.draft); assertFalse(r.submissions.single().dismissed)
        r.draft = ""; val before = encodeSessionIndex(listOf(r)).toString()
        assertThrows(SubmissionPersistenceFailure::class.java) { SubmissionLedger.recoverStoredToComposer(r, "id", card) { throw java.io.IOException("fixture") } }
        assertEquals(before, encodeSessionIndex(listOf(r)).toString()); assertEquals(0L, r.draftRevision)
    }
    @Test fun receiptRecoveryFencePreventsAutoRejectionAfterBridgeRestart() {
        val r = SessionRecord("s", "Title", "noroot", "/work", cli = "OpenCode", draft = "bridge restart send")
        SubmissionLedger.begin(r, "msg_fenced")
        SubmissionLedger.state(r, "msg_fenced", "unknown")
        r.receiptRecoveryRequired += "msg_fenced"
        assertTrue(reconcileOpenCodeIdleSubmissions(r, emptySet(), emptySet(), emptySet()).isEmpty())
        assertEquals("unknown", r.submissions.single().state)
        assertEquals("", r.draft)
    }
}
