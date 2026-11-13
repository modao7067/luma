package io.github.modao7067.luma.gui.session

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ManagedSubmissionTest {
    private fun record() = SessionRecord("target", "未命名会话", "noroot", "/work", draft = "用户还没发的草稿",
        images = mutableListOf(StoredImage("user-image", "private://image", "/image", "image")),
        skills = mutableListOf(DraftSkill("user-skill", "Skill", "/skill")), useNativeTitle = true)

    @Test fun externalSendTimeoutAndLateAckNeverBorrowOrClearComposerInput() {
        val r = record()
        SubmissionLedger.begin(r, "agent-message", 10, externalText = "agent派发内容")
        assertEquals("用户还没发的草稿", r.draft); assertEquals(1, r.images.size); assertEquals(1, r.skills.size)
        assertEquals("agent派发内容", r.submissionText); assertTrue(r.submissionImages.isEmpty()); assertTrue(r.submissionSkills.isEmpty())
        assertFalse(SubmissionLedger.expire(r, 30_009)); assertFalse(SubmissionLedger.expire(r, 30_010))
        assertEquals("", r.submissionRecovery); assertEquals("用户还没发的草稿", r.draft)
        SubmissionLedger.accepted(r)
        assertEquals("用户还没发的草稿", r.draft); assertEquals(1, r.images.size); assertEquals(1, r.skills.size)
        assertEquals("", r.deliveryResults["agent-message"])
        assertEquals("agent派发内容", r.messageTitle)
    }
    @Test fun externalOriginRoundtripsAndDefinitiveRefusalNeverRestoresIntoEmptyComposer() {
        val r = record().also { it.draft = ""; it.images.clear(); it.skills.clear() }
        SubmissionLedger.begin(r, "agent-message", 1, externalText = "agent input")
        val restored = decodeSessionIndex(JSONObject(encodeSessionIndex(listOf(r)).toString())).single()
        assertTrue(restored.submissionExternal)
        SubmissionLedger.rejected(restored)
        assertEquals("", restored.draft); assertEquals("rejected", restored.deliveryResults["agent-message"])
        assertFalse(restored.submissionExternal)
    }
    @Test fun externalUnknownDoesNotAlterANewerDraftEvenIfAgentTextMatchesIt() {
        val r = record().also { it.draft = "same text" }
        SubmissionLedger.begin(r, "agent-message", 0, externalText = "same text")
        r.draftRevision++
        SubmissionLedger.expire(r, 30_000); SubmissionLedger.accepted(r)
        assertEquals("same text", r.draft)
    }
    @Test fun explicitNativeProtocolCannotDispatchThroughAnOlderCarrierButLegacyCallersKeepWorking() {
        val legacy = SessionRecord("legacy", "旧ACP", "noroot", "/work", cli = "Antigravity", threadId = "native")
        ManagedSessionPolicy.acceptNativeProtocol(legacy, JSONObject())
        val explicit = legacy.copy(nativeProtocol = "antigravity-cli-stream-json")
        assertThrows(Exception::class.java) { ManagedSessionPolicy.acceptNativeProtocol(explicit, JSONObject()) }
        val health = JSONObject("""{"nativeProtocols":{"Antigravity":["antigravity-cli-stream-json","antigravity-acp"]}}""")
        ManagedSessionPolicy.acceptNativeProtocol(explicit, health)
    }
}
