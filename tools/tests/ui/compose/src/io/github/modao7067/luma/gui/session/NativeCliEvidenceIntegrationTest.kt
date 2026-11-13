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
class NativeCliEvidenceIntegrationTest {
    @Test fun legacyRpcOnNewCarrierAndGrokExactPagerAdmissionKeepTheirDistinctContracts() {
        for ((cli, protocol, pager) in listOf(Triple("pi", 0, false), Triple("Grok Build", 1, true))) {
            val record = SessionRecord("local", "fixture", "noroot", "/work", cli = cli, threadId = "native", draft = "same")
            SubmissionLedger.begin(record, "one", busy = true)
            var saves = 0
            var projected: SessionView? = null
            val session = NativeCliSession(record, AgentRuntime(RuntimeEnvironment.getApplication()), { saves++ }, { "unused" }, { view, _ -> projected = SubmissionLedger.project(record, view) }, {})
            fun apply(revision: Int, accepted: Boolean) {
                val frame = obj("revision" to revision, "nativeId" to "native", "connected" to true, "inputEvidenceProtocol" to protocol,
                    "receipts" to obj("one" to if (accepted) "accepted" else "unknown"), "messages" to JSONArray())
                if (pager && accepted) frame.put("admittedDisplaySubmissions", obj("one" to true)).put("messages", JSONArray().put(obj("id" to "one", "role" to "user", "text" to "same", "origin" to "local")))
                session.javaClass.getDeclaredMethod("apply", JSONObject::class.java).apply { isAccessible = true }.invoke(session, frame)
            }
            try {
                apply(1, false); assertEquals(1, projected!!.queue.size); assertTrue(projected!!.messages.isEmpty())
                apply(2, true); assertTrue(projected!!.queue.isEmpty()); assertFalse(record.submissions.single().nativeConsumed)
                if (pager) assertEquals(1, projected!!.messages.size)
                val settledSaves = saves; apply(3, true); assertEquals(settledSaves, saves)
                val restored = decodeSessionIndex(JSONObject(encodeSessionIndex(listOf(record)).toString())).single()
                assertFalse(restored.submissions.single().queuePresentation); assertFalse(restored.submissions.single().nativeConsumed)
            } finally { session.close() }
        }
    }
}
