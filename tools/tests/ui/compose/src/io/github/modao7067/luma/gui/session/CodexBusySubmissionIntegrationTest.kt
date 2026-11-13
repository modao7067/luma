package io.github.modao7067.luma.gui.session

import android.app.Application
import android.os.Looper
import kotlinx.coroutines.*
import io.github.modao7067.luma.gui.agent.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.*
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CodexBusySubmissionIntegrationTest {
    private class Paused : CoroutineDispatcher() { override fun dispatch(context: CoroutineContext, block: Runnable) {} }
    private class Transport : CodexTransport {
        lateinit var receive: (String) -> Unit
        val sends = mutableListOf<JSONObject>()
        override fun connect(opened: () -> Unit, message: (String) -> Unit, failed: (Throwable) -> Unit) { receive = message; opened() }
        override fun send(message: String): Boolean {
            val frame = JSONObject(message); val method = frame.optString("method")
            if (method == "turn/steer") sends += frame
            else if (frame.has("id")) receive(obj("id" to frame.get("id"), "result" to if (method == "thread/queue/list") obj("submissions" to org.json.JSONArray()) else JSONObject()).toString())
            return true
        }
        fun admit(frame: JSONObject) { receive(obj("id" to frame.get("id"), "result" to JSONObject()).toString()) }
        override fun close() {}
    }
    @Test fun productionControllerSteersImmediatelyButKeepsUnconsumedInputInTheFloatingQueue() {
        val app: Application = RuntimeEnvironment.getApplication()
        val controller = SessionController(app)
        (field(controller, "scope") as CoroutineScope).cancel()
        val paused = CoroutineScope(SupervisorJob() + Paused()); set(controller, "scope", paused)
        val id = runBlocking { controller.manage(obj("action" to "create", "cli" to "Codex", "cwd" to "/workspace", "requestId" to "busy-controller")) }.getString("id")
        val live = (field(controller, "sessions") as Map<*, *>)[id]!!
        val record = field(live, "record") as SessionRecord; record.threadId = "native"
        controller.openSession(id)
        val transport = Transport()
        val rpc = CodexRpc("unused", null, {}, {}, transport)
        runBlocking { rpc.connect() }
        set(live, "rpc", rpc)
        set(live, "view", SessionView(SessionSummary(id, record.title, record.environment, record.cwd), connected = true, activeTurnId = "turn"))
        paused.cancel(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate); set(controller, "scope", scope)
        fun publish() { controller.javaClass.getDeclaredMethod("publish").apply { isAccessible = true }.invoke(controller) }
        fun pump() { Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20)) }
        fun view() = controller.state.value.selected!!
        try {
            publish(); controller.draft("same"); controller.send(); pump()
            assertEquals("turn/steer", transport.sends.single().getString("method"))
            val first = transport.sends.single().getJSONObject("params").getString("clientUserMessageId")
            assertEquals(first, view().queue.single().clientUserMessageId); assertTrue(view().messages.isEmpty())
            controller.draft("same"); controller.send(); pump()
            assertEquals(2, transport.sends.size); assertEquals(2, view().queue.size)
            transport.admit(transport.sends.first()); pump()
            assertEquals("accepted", record.submissions.first().state)
            assertEquals(2, view().queue.size); assertTrue(view().messages.isEmpty())
            val item = obj("id" to "native-item", "type" to "userMessage", "clientUserMessageId" to first,
                "content" to org.json.JSONArray().put(obj("type" to "text", "text" to "same")))
            val event = obj("method" to "item/started", "params" to obj("threadId" to "native", "turnId" to "turn", "item" to item))
            controller.javaClass.getDeclaredMethod("event", live.javaClass, JSONObject::class.java).apply { isAccessible = true }.invoke(controller, live, event)
            publish()
            assertEquals(1, view().messages.size); assertEquals(1, view().queue.size)
            repeat(4) { publish() }
            assertEquals(1, view().queue.size); assertFalse(record.submissions.last().nativeQueued)
            val restored = decodeSessionIndex(JSONObject(encodeSessionIndex(listOf(record)).toString())).single()
            assertTrue(restored.submissions.all { it.queuePresentation }); assertTrue(restored.submissions.first().nativeConsumed)
        } finally { scope.cancel(); rpc.close() }
    }
    companion object {
        private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
        private fun set(owner: Any, name: String, value: Any) { owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value) }
    }
}
