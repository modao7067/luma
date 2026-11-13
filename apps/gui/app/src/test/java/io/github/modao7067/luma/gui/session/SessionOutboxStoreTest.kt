package io.github.modao7067.luma.gui.session

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SessionOutboxStoreTest {
    @Test fun explicitCarrierReplacementKeepsUncertainIdentityAcrossAppReload() {
        val record = SessionRecord("local", "Title", "noroot", "/work", cli = "Grok Build", draft = "unsent")
        record.submissions += StoredSubmission("old-write", "may have reached native", state = "unknown")
        record.receiptRecoveryRequired += "old-write"
        val restored = decodeSessionIndex(JSONObject(encodeSessionIndex(listOf(record)).toString())).single()
        assertEquals(setOf("old-write"), restored.receiptRecoveryRequired)
        assertEquals("unknown", restored.submissions.single().state)
        assertEquals("unsent", restored.draft)
    }
    @Test fun allPendingPayloadsSurviveReloadWithoutNativeHistory() {
        val r = SessionRecord("local", "Title", "noroot", "/work", threadId = "native", draft = "你好",
            images = mutableListOf(StoredImage("image", "content://private/image", "/image", "image")),
            cli = "DeepSeek Harness", skills = mutableListOf(DraftSkill("skill", "Skill", "/skill")))
        SubmissionLedger.begin(r, "submission", 100)
        val json = encodeSessionIndex(listOf(r)); val restored = decodeSessionIndex(JSONObject(json.toString())).single()
        assertEquals("local", restored.id); assertEquals("native", restored.threadId)
        assertEquals("", restored.draft); assertEquals("你好", restored.submissionText)
        assertEquals(0L, restored.submissionDeadlineMs); assertTrue(SubmissionLedger.pending(restored))
        assertFalse(SubmissionLedger.expire(restored, 30_099)); assertFalse(SubmissionLedger.expire(restored, 30_100))
        assertEquals("", restored.draft); assertEquals(listOf("image"), restored.submissions.single().images.map { it.id })
        assertEquals(listOf("skill"), restored.submissions.single().skills.map { it.id })
        assertFalse(json.toString().contains("messages")); assertFalse(json.toString().contains("deliveryResults"))
    }
    @Test fun legacyUnknownInputIsKeptUntilRealConfirmation() {
        val legacy = JSONObject("""{"version":1,"sessions":[{"id":"local","title":"Old","environment":"noroot","cwd":"/work","draft":"old","submissionId":"submission","submissionText":"old","submissionImages":[],"images":[]}]}""")
        val r = decodeSessionIndex(legacy).single()
        assertTrue(r.receiptRecoveryRequired.isEmpty())
        assertTrue(SubmissionLedger.uncertain(r)); assertEquals("old", r.draft); assertEquals("", r.submissionRecovery)
        SubmissionLedger.accepted(r); assertEquals("old", r.draft); assertNull(r.submissionId)
    }
    @Test fun newerIndexVersionCannotBeSilentlyReinterpreted() {
        assertThrows(IllegalArgumentException::class.java) { decodeSessionIndex(JSONObject("""{"version":2,"sessions":[]}""")) }
    }
}
