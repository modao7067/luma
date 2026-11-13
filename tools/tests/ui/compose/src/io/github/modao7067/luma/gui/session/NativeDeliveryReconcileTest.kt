package io.github.modao7067.luma.gui.session

import android.app.Application
import io.github.modao7067.luma.gui.agent.obj
import io.github.modao7067.luma.gui.runtime.AgentRuntime
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class NativeDeliveryReconcileTest {
    private class Fixture(reliable: Boolean = true) {
        val record = SessionRecord("local", "Title", "root", "/work", threadId = "native", cli = "Qoder CLI", draft = "first")
        var saves = 0
        val adapter = NativeCliSession(record, AgentRuntime(RuntimeEnvironment.getApplication()), { saves++ }, { error("No browser bind") }, { _, _ -> }, {})
        init {
            SubmissionLedger.begin(record, "one")
            adapter.javaClass.getDeclaredField("reliableDelivery").apply { isAccessible = true }.set(adapter, reliable)
        }
        fun apply(revision: Int, deliveries: JSONObject = JSONObject(), receipts: JSONObject = JSONObject(), queue: JSONArray = JSONArray(), host: JSONArray = JSONArray()) {
            val value = obj("revision" to revision, "connected" to true, "nativeId" to "native", "model" to "fixture", "deliveries" to deliveries,
                "receipts" to receipts, "queue" to queue, "hostQueue" to host)
            adapter.javaClass.getDeclaredMethod("apply", JSONObject::class.java).apply { isAccessible = true }.invoke(adapter, value)
        }
    }
    @Test fun onlyDurableProtocolAbsenceProvesSafeSameIdReadmission() {
        val f = Fixture(); SubmissionLedger.state(f.record, "one", "unknown")
        f.apply(1)
        assertEquals("local", f.record.submissions.single().state)
        assertEquals("one", f.record.submissions.single().id); assertEquals("first", f.record.submissions.single().text)
        assertTrue(f.saves > 0)
        val legacy = Fixture(false); SubmissionLedger.state(legacy.record, "one", "unknown"); legacy.apply(1)
        assertEquals("unknown", legacy.record.submissions.single().state)
    }
    @Test fun CrossedBoundaryUnknownCannotBeReplayedAndLateAckCannotEraseAnotherDraft() {
        val f = Fixture(); f.record.draft = "new draft"; f.record.draftRevision++
        f.apply(1, obj("one" to obj("state" to "unknown")), obj("one" to "unknown"))
        assertEquals("unknown", f.record.submissions.single().state)
        f.apply(3, obj("one" to obj("state" to "accepted")), obj("one" to "accepted"))
        f.apply(2, obj("one" to obj("state" to "queued")))
        f.apply(4, obj("one" to obj("state" to "unknown")), obj("one" to "unknown"))
        assertEquals("accepted", f.record.submissions.single().state); assertEquals("new draft", f.record.draft)
    }
    @Test fun NativeQueueOwnershipIsPersistentButHostQueueDoesNotHideLaterUnknown() {
        val queued = JSONArray().put(obj("id" to "queue-id", "text" to "first", "clientUserMessageId" to "one"))
        val native = Fixture(); native.apply(1, receipts = obj("one" to "accepted"), queue = queued)
        assertTrue(native.record.submissions.single().nativeQueued); assertTrue(native.saves > 0)
        val host = Fixture(); queued.getJSONObject(0).put("hostOwned", true)
        host.apply(1, obj("one" to obj("state" to "queued")), host = queued)
        assertFalse(host.record.submissions.single().nativeQueued)
        host.apply(2, obj("one" to obj("state" to "unknown")), obj("one" to "unknown"))
        assertEquals("pending", SubmissionLedger.messages(host.record, emptyList()).single().delivery)
    }
}
