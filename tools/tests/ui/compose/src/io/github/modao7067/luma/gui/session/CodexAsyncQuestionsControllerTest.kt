package io.github.modao7067.luma.gui.session

import android.app.Application
import android.os.Looper
import kotlinx.coroutines.*
import io.github.modao7067.luma.gui.agent.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.*
import java.io.IOException
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CodexAsyncQuestionsControllerTest {
    private class Paused : CoroutineDispatcher() { override fun dispatch(context: CoroutineContext, block: Runnable) {} }
    private class DropAnswer : CodexTransport {
        lateinit var receive: (String) -> Unit;lateinit var failure: (Throwable) -> Unit;var writes = 0
        override fun connect(opened: () -> Unit, message: (String) -> Unit, failed: (Throwable) -> Unit) { receive = message;failure = failed;opened() }
        override fun send(text: String): Boolean {
            val m = JSONObject(text)
            when (m.optString("method")) {
                "initialize" -> receive(obj("id" to m.get("id"), "result" to JSONObject()).toString())
                "turn/steer" -> { writes++;failure(IOException("Fixture dropped native receipt")) }
            }
            return true
        }
        override fun close() {}
    }
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    private fun set(owner: Any, name: String, value: Any) { owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value) }
    @Test fun lostAsyncReplyReceiptIsDurableAndNeitherDoubleClickNorPublicationReplaysIt() = runBlocking {
        val app = RuntimeEnvironment.getApplication();val controller = SessionController(app)
        (field(controller, "scope") as CoroutineScope).cancel();var scope = CoroutineScope(SupervisorJob() + Paused());set(controller, "scope", scope)
        val transport = DropAnswer();val rpc = CodexRpc("fixture", null, {}, {}, transport)
        try {
            val local = controller.manage(obj("action" to "attach", "cli" to "Codex", "cwd" to "/work", "nativeId" to "native-fixture")).getJSONObject("session").getString("id")
            controller.openSession(local);val live = (field(controller, "sessions") as Map<*, *>)[local]!!;val record = field(live, "record") as SessionRecord
            val question = CodexProjection.item(obj("type" to "agentMessage", "id" to "native-call", "delivery" to "async", "text" to "Question?",
                "questions" to JSONArray().put(obj("title" to "Question?", "options" to JSONArray(listOf("One", "Two"))))))
            (field(live, "items") as MutableMap<String, ConversationItem>)[question.id] = question
            rpc.connect();set(live, "rpc", rpc);set(live, "view", (field(live, "view") as SessionView).copy(connected = true, activeTurnId = "native-turn"))
            scope.cancel();scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate);set(controller, "scope", scope)
            val publish = controller.javaClass.getDeclaredMethod("publish").apply { isAccessible = true };publish.invoke(controller)
            val request = controller.state.value.selected!!.requests.single();val answers = mapOf(request.questions.single().id to listOf("One"))
            controller.answer(request.id, "submit", answers)
            withTimeout(5000) { while (record.submissions.singleOrNull()?.state != "unknown") { Shadows.shadowOf(Looper.getMainLooper()).idle();delay(10) } }
            repeat(3) { controller.answer(request.id, "submit", answers);publish.invoke(controller);Shadows.shadowOf(Looper.getMainLooper()).idle() }
            assertEquals(1, transport.writes);assertEquals(1, record.submissions.size)
            assertTrue(controller.state.value.selected!!.requests.single().answering)
            val saved = SessionStore(app).load().single().submissions.single()
            assertEquals("unknown", saved.state);assertEquals(record.submissions.single().id, saved.id)
            val restored = CodexAsyncQuestions.pending(listOf(question), submissions = listOf(CodexAsyncQuestions.Submission(saved.text, saved.state)))
            assertTrue(restored.single().answering)
            assertThrows(IllegalStateException::class.java) { CodexAsyncQuestions.submissionId(saved.text, listOf(CodexAsyncQuestions.Submission(saved.text, saved.state))) }
            println("PASS lost receipt stays durable unknown; repeated GUI answer/publication never replays native mutation")
        } finally { scope.cancel();rpc.close() }
    }
}
