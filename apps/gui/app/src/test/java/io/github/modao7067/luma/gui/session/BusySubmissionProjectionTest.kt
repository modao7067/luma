package io.github.modao7067.luma.gui.session

import io.github.modao7067.luma.gui.agent.NativeCliProjection
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BusySubmissionProjectionTest {
    private fun view(r: SessionRecord) = SessionView(SessionSummary(r.id, r.title, r.environment, r.cwd, cli = r.cli), connected = true, activeTurnId = "turn")
    @Test fun allTwelveClisKeepBusyUnknownInTheQueueAcrossPublicationAndIndexRecovery() {
        for (cli in CliCatalog.ids) {
            val r = SessionRecord("local", "title", "root", "/work", cli = cli, draft = "same")
            SubmissionLedger.begin(r, "first", busy = true)
            r.draft = "same"; SubmissionLedger.begin(r, "second", busy = true)
            SubmissionLedger.state(r, "first", "unknown")
            var projected = view(r)
            repeat(5) { projected = SubmissionLedger.project(r, projected) }
            assertTrue(cli, projected.messages.isEmpty())
            assertEquals(cli, setOf("first", "second"), projected.queue.mapNotNull { it.clientUserMessageId }.toSet())
            assertTrue(cli, projected.queue.all { it.localSubmission && !it.canRemove && !it.canSteer && it.canEdit && it.localRecovery })
            assertTrue(cli, r.submissions.none { it.nativeQueued })
            val restored = decodeSessionIndex(JSONObject(encodeSessionIndex(listOf(r)).toString())).single()
            val offline = SubmissionLedger.project(restored, view(restored).copy(activeTurnId = null, connected = false))
            assertEquals(cli, 2, offline.queue.size); assertTrue(cli, offline.messages.isEmpty())
            assertEquals(cli, "unknown", restored.submissions.first().state)
        }
    }
    @Test fun admissionAndCarrierLocalPlaceholderDoNotProveNativeConsumption() {
        val r = SessionRecord("local", "title", "root", "/work", cli = "Grok Build", draft = "busy")
        SubmissionLedger.begin(r, "one", busy = true)
        val local = ConversationItem("one", "user", "busy", delivery = "pending", nativeInputEvidence = false)
        assertTrue(SubmissionLedger.project(r, view(r).copy(messages = listOf(local))).messages.isEmpty())
        SubmissionLedger.accepted(r, id = "one")
        val admitted = SubmissionLedger.project(r, view(r).copy(messages = listOf(local.copy(delivery = ""))))
        assertEquals(1, admitted.queue.size); assertTrue(admitted.messages.isEmpty())
        SubmissionLedger.consumed(r, "one")
        val consumed = SubmissionLedger.project(r, view(r).copy(messages = listOf(local.copy(delivery = "", nativeInputEvidence = true))))
        assertTrue(consumed.queue.isEmpty()); assertEquals(1, consumed.messages.size)
    }
    @Test fun exactNativeInboxOwnsDisplayEvenAfterAckAndUntilItsConsumption() {
        val r = SessionRecord("local", "title", "root", "/work", cli = "OpenCode", draft = "busy")
        SubmissionLedger.begin(r, "one", busy = true); SubmissionLedger.accepted(r, id = "one")
        val row = ConversationItem("one", "user", "busy")
        val inbox = view(r).copy(messages = listOf(row), queue = listOf(QueueEntry("native", "busy", "待发", clientUserMessageId = "one")))
        val pending = SubmissionLedger.project(r, inbox)
        assertEquals(1, pending.queue.size); assertTrue(pending.messages.isEmpty()); assertFalse(r.submissions.single().nativeConsumed)
        val consumed = SubmissionLedger.project(r, inbox.copy(queue = emptyList()))
        assertTrue(consumed.queue.isEmpty()); assertEquals(1, consumed.messages.size); assertTrue(r.submissions.single().nativeConsumed)
    }
    @Test fun hostQueuedToDispatchingUnknownNeverFallsBackIntoTheTranscript() {
        val r = SessionRecord("local", "title", "root", "/work", cli = "Claude Code", draft = "busy")
        SubmissionLedger.begin(r, "one", busy = true)
        SubmissionLedger.state(r, "one", "queued")
        val host = view(r).copy(queue = listOf(QueueEntry("one", "busy", "待发", clientUserMessageId = "one", hostOwned = true)))
        assertEquals(1, SubmissionLedger.project(r, host).queue.size)
        for (state in listOf("pending", "unknown")) {
            SubmissionLedger.state(r, "one", state)
            val pending = SubmissionLedger.project(r, view(r).copy(activeTurnId = null))
            assertTrue(pending.messages.isEmpty()); assertEquals("one", pending.queue.single().clientUserMessageId)
        }
    }
    @Test fun identicalTextDoesNotConsumeAnotherRequestAndIdleInputKeepsItsBubble() {
        val r = SessionRecord("local", "title", "root", "/work", draft = "same")
        SubmissionLedger.begin(r, "one", busy = true); r.draft = "same"; SubmissionLedger.begin(r, "two", busy = true)
        val projected = SubmissionLedger.project(r, view(r).copy(messages = listOf(ConversationItem("one", "user", "same"))))
        assertEquals(listOf("two"), projected.queue.mapNotNull { it.clientUserMessageId }); assertEquals(listOf("one"), projected.messages.map { it.id })
        val idle = SessionRecord("idle", "title", "root", "/work", draft = "same")
        SubmissionLedger.begin(idle, "idle")
        val ordinary = SubmissionLedger.project(idle, view(idle).copy(activeTurnId = null))
        assertTrue(ordinary.queue.isEmpty()); assertEquals("pending", ordinary.messages.single().delivery)
    }
    @Test fun productionNativeProjectionMarksOptimisticBridgeRowsAsUnconfirmed() {
        val r = SessionRecord("local", "title", "root", "/work", cli = "Kimi Code", draft = "busy")
        SubmissionLedger.begin(r, "one", busy = true)
        val raw = JSONObject("""{"nativeId":"native","connected":true,"inputEvidenceProtocol":1,"receipts":{"one":"unknown"},"messages":[{"id":"one","role":"user","text":"busy","origin":"local"}]}""")
        val projected = SubmissionLedger.project(r, NativeCliProjection.view(raw, view(r)))
        assertTrue(projected.messages.isEmpty()); assertEquals(1, projected.queue.size)
    }
    @Test fun idleSendingBubbleCannotBecomeItsOwnNativeEvidenceWhenDispatchTurnsBusy() {
        val r = SessionRecord("local", "title", "root", "/work", draft = "offline")
        SubmissionLedger.begin(r, "one")
        var projected = SubmissionLedger.project(r, view(r).copy(activeTurnId = null))
        assertFalse(projected.messages.single().nativeInputEvidence)
        SubmissionLedger.markBusy(r, "one", true)
        repeat(3) { projected = SubmissionLedger.project(r, projected) }
        assertTrue(projected.messages.isEmpty()); assertEquals(1, projected.queue.size)
        assertFalse(r.submissions.single().nativeConsumed)
    }
    @Test fun oldCarrierAdmissionKeepsItsPriorContractWithoutClaimingConsumption() {
        val r = SessionRecord("local", "title", "root", "/work", cli = "pi", draft = "old carrier")
        SubmissionLedger.begin(r, "one", busy = true)
        assertEquals(1, SubmissionLedger.project(r, view(r)).queue.size)
        SubmissionLedger.accepted(r, id = "one"); SubmissionLedger.legacyAdmitted(r, "one")
        val old = SubmissionLedger.project(r, view(r))
        assertTrue(old.queue.isEmpty()); assertFalse(r.submissions.single().nativeConsumed)
        val exactQueue = view(r).copy(queue = listOf(QueueEntry("q", "old carrier", "待执行", clientUserMessageId = "one")))
        assertEquals(1, SubmissionLedger.project(r, exactQueue).queue.size)
    }
    @Test fun busyQueueSubmissionsRemainProtectedFromIdleAbsentRejection() {
        val oc = SessionRecord("local", "title", "root", "/work", cli = "OpenCode", draft = "queued while busy")
        SubmissionLedger.begin(oc, "msg_busy", busy = true)
        SubmissionLedger.state(oc, "msg_busy", "unknown")
        assertTrue(reconcileOpenCodeIdleSubmissions(oc, emptySet(), emptySet(), emptySet()).isEmpty())
        assertEquals("unknown", oc.submissions.single().state)

        val cx = SessionRecord("local", "title", "root", "/work", cli = "Codex", draft = "queued while busy")
        SubmissionLedger.begin(cx, "cx_busy", busy = true)
        SubmissionLedger.state(cx, "cx_busy", "unknown")
        assertFalse(reconcileCodexIdleSubmissions(cx, false, emptyMap(), emptyMap(), emptyList(), emptySet()))
        assertEquals("unknown", cx.submissions.single().state)
    }
}
