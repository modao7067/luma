package io.github.modao7067.luma.gui.session

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LocalQueueRestoreTest {
    private fun localRecord(cli: String = "Grok Build"): SessionRecord = SessionRecord("s", "Title", "noroot", "/workspace", cli = cli,
        draft = "原文\n@\"/workspace/中文.kt\"", images = mutableListOf(StoredImage("image", "file:///image", "/image", "图片")),
        skills = mutableListOf(DraftSkill("skill", "Skill", "/skill"))).also {
        SubmissionLedger.begin(it, "local", busy = true); SubmissionLedger.state(it, "local", "local")
    }
    private fun view(r: SessionRecord) = SessionView(SessionSummary(r.id, r.title, r.environment, r.cwd, cli = r.cli))

    @Test fun exactLocalPayloadReturnsToComposerAndCannotDispatchAfterReload() {
        for (cli in CliCatalog.ids) {
            val r = localRecord(cli); val original = r.submissions.single().copy()
            var saved: JSONObject? = null
            SubmissionLedger.restoreLocalToComposer(r, "local") { saved = encodeSessionIndex(listOf(r)) }
            assertEquals(original.text, r.draft); assertEquals(original.images, r.images); assertEquals(original.skills, r.skills)
            assertEquals("cancelled", r.submissions.single().state); assertTrue(r.submissions.single().dismissed)
            assertFalse(SubmissionLedger.isLocal(r.submissions.single())); assertTrue(SubmissionLedger.project(r, view(r)).queue.isEmpty())
            val restored = decodeSessionIndex(JSONObject(saved!!.toString())).single()
            assertEquals(original.text, restored.draft); assertEquals(original.images, restored.images)
            assertTrue(SubmissionLedger.project(restored, view(restored)).queue.isEmpty())
            SubmissionLedger.state(restored, "local", "local")
            assertFalse(SubmissionLedger.isLocal(restored.submissions.single()))
        }
    }

    @Test fun textImageAndSkillConflictsKeepBothPayloadsWithoutConcatenation() {
        for (conflict in listOf("text", "image", "skill")) {
            val r = localRecord()
            when (conflict) {
                "text" -> r.draft = "newer draft"
                "image" -> r.images += StoredImage("new", "file:///new", "/new", "new")
                else -> r.skills += DraftSkill("new", "New", "/new")
            }
            val before = encodeSessionIndex(listOf(r)).toString(); var saves = 0
            assertThrows(IllegalStateException::class.java) { SubmissionLedger.restoreLocalToComposer(r, "local") { saves++ } }
            assertEquals(before, encodeSessionIndex(listOf(r)).toString()); assertEquals(0, saves)
        }
    }

    @Test fun saveFailureRollsBackComposerRevisionAndQueueClaimTogether() {
        val r = localRecord(); val before = encodeSessionIndex(listOf(r)).toString()
        assertThrows(SubmissionPersistenceFailure::class.java) {
            SubmissionLedger.restoreLocalToComposer(r, "local") { throw java.io.IOException("fixture") }
        }
        assertEquals(before, encodeSessionIndex(listOf(r)).toString())
        assertTrue(SubmissionLedger.isLocal(r.submissions.single())); assertEquals(0L, r.draftRevision)
    }

    @Test fun nativeEvidenceRequiresTheLocalRecoveryPathRatherThanProvenCancellation() {
        for (state in listOf("pending", "unknown", "queued", "accepted", "rejected", "cancelled")) {
            val r = localRecord(); r.submissions.single().state = state
            assertThrows(IllegalStateException::class.java) { SubmissionLedger.restoreLocalToComposer(r, "local") {} }
            assertEquals("", r.draft); assertEquals(state, r.submissions.single().state)
        }
        for (evidence in listOf("queued", "consumed", "receiptRecovery")) {
            val r = localRecord()
            when (evidence) {
                "queued" -> r.submissions.single().nativeQueued = true
                "consumed" -> r.submissions.single().nativeConsumed = true
                else -> r.receiptRecoveryRequired += "local"
            }
            assertThrows(IllegalStateException::class.java) { SubmissionLedger.restoreLocalToComposer(r, "local") {} }
            assertEquals("", r.draft); assertEquals("local", r.submissions.single().state)
        }
    }

    @Test fun returningOneIdentityDoesNotTouchAnotherSameTextOrReceiptRecoveryFence() {
        val r = localRecord(); r.submissions += r.submissions.single().copy(id = "other", state = "unknown")
        r.receiptRecoveryRequired += "other"
        SubmissionLedger.restoreLocalToComposer(r, "local") {}
        assertEquals("unknown", r.submissions.last().state); assertEquals(setOf("other"), r.receiptRecoveryRequired)
        assertEquals(listOf("other"), SubmissionLedger.project(r, view(r)).queue.mapNotNull { it.clientUserMessageId })
    }
}
