package io.github.modao7067.luma.gui.session

import io.github.modao7067.luma.gui.agent.CodexProjection
import io.github.modao7067.luma.gui.agent.OpenCodeProjection
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QueuedOutboxTest {
    private fun view() = SessionView(SessionSummary("local", "Title", "noroot", "/work"), connected = true, activeTurnId = "running")
    @Test fun pendingInputIsImmediateThenNativeQueueOwnsItWithoutAPhantomConversationRow() {
        val r = SessionRecord("local", "Title", "noroot", "/work", draft = "next task")
        val outbox = linkedMapOf<String, ConversationItem>()
        SubmissionLedger.begin(r, "client-1", 10)
        assertEquals("pending", SubmissionLedger.project(r, view(), outbox).messages.single().delivery)
        SubmissionLedger.accepted(r)
        val queued = view().copy(queue = listOf(QueueEntry("native-queue-id", "next task", "已排队", clientUserMessageId = "client-1")))
        val projected = SubmissionLedger.project(r, queued, outbox)
        assertTrue(projected.messages.isEmpty()); assertEquals(1, projected.queue.size); assertTrue(outbox.isEmpty())
        assertTrue(SubmissionLedger.project(r, view(), outbox).messages.isEmpty())
        val consumed = view().copy(messages = listOf(ConversationItem("client-1", "user", "next task")))
        assertEquals(1, SubmissionLedger.project(r, consumed, outbox).messages.size)
    }
    @Test fun sameTextOrUnrelatedQueueIdCannotRemoveOrdinaryPendingInputOrRestoreIt() {
        val r = SessionRecord("local", "Title", "noroot", "/work", draft = "same text")
        val outbox = linkedMapOf<String, ConversationItem>()
        SubmissionLedger.begin(r, "ordinary", 0)
        val projected = SubmissionLedger.project(r, view().copy(queue = listOf(QueueEntry("other", "same text", "已排队", clientUserMessageId = "another-client"))), outbox)
        assertEquals("ordinary", projected.messages.single().id)
        assertFalse(SubmissionLedger.expire(r, 30000)); assertEquals("", r.draft)
        assertEquals("pending", SubmissionLedger.project(r, view(), outbox).messages.single().delivery)
    }
    @Test fun nativeConsumptionReplacesThePendingRowOnceEvenIfAckAndQueueSnapshotRace() {
        val r = SessionRecord("local", "Title", "noroot", "/work", draft = "consume")
        val outbox = linkedMapOf<String, ConversationItem>()
        SubmissionLedger.begin(r, "client", 0); SubmissionLedger.project(r, view(), outbox)
        SubmissionLedger.accepted(r)
        val native = view().copy(messages = listOf(ConversationItem("client", "user", "consume")),
            queue = listOf(QueueEntry("queue", "consume", "已排队", clientUserMessageId = "client")))
        assertTrue(SubmissionLedger.project(r, native, outbox).messages.isEmpty())
        assertEquals(1, SubmissionLedger.project(r, native.copy(queue = emptyList()), outbox).messages.size)
    }
    @Test fun openCodeInboxRetainsOnlyTheNativeUserIdentityAndNativeErrorsKeepVisibleDetails() {
        val queue = OpenCodeProjection.queue(JSONArray("""[{"id":"msg_native","type":"user","payload":{"text":"task"}},{"id":"compaction","type":"compaction"}]"""))
        assertEquals("msg_native", queue.first().clientUserMessageId); assertNull(queue.last().clientUserMessageId)
        val error = CodexProjection.error(JSONObject().put("message", "Sandbox failed").put("additionalDetails", "native setup detail"))
        assertEquals("Sandbox failed\nnative setup detail", error)
    }
}
