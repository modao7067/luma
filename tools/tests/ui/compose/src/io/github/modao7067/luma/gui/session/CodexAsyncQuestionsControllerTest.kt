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
    private class SwitchableSteerTransport : CodexTransport {
        lateinit var receive: (String) -> Unit
        lateinit var failure: (Throwable) -> Unit
        var mode: String = "reject"
        override fun connect(opened: () -> Unit, message: (String) -> Unit, failed: (Throwable) -> Unit) {
            receive = message
            failure = failed
            opened()
        }
        override fun send(text: String): Boolean {
            val m = JSONObject(text)
            when (m.optString("method")) {
                "initialize" -> receive(obj("id" to m.get("id"), "result" to JSONObject()).toString())
                "thread/queue/list", "thread/items/list" -> receive(obj("id" to m.get("id"), "result" to obj("data" to JSONArray())).toString())
                "turn/steer" -> when (mode) {
                    "drop" -> failure(IOException("Fixture dropped native receipt"))
                    "reject" -> receive(obj("id" to m.get("id"), "error" to obj("code" to -32000, "message" to "rejected")).toString())
                    "accept" -> receive(obj("id" to m.get("id"), "result" to JSONObject()).toString())
                }
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
            rpc.connect();set(live, "rpc", rpc);set(live, "view", (field(live, "view") as SessionView).copy(connected = true, loading = false, activeTurnId = "native-turn"))
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
    @Test fun ordinaryComposerSendRetiresAsyncCardOnlyAfterAdmissionAndPersistsWithoutBlockingLaterQuestions() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val controller = SessionController(app)
        (field(controller, "scope") as CoroutineScope).cancel()
        var scope = CoroutineScope(SupervisorJob() + Paused())
        set(controller, "scope", scope)
        val transport = SwitchableSteerTransport()
        val rpc = CodexRpc("fixture", null, {}, {}, transport)
        try {
            val local = controller.manage(obj("action" to "attach", "cli" to "Codex", "cwd" to "/work", "nativeId" to "native-fixture")).getJSONObject("session").getString("id")
            controller.openSession(local)
            val live = (field(controller, "sessions") as Map<*, *>)[local]!!
            val record = field(live, "record") as SessionRecord
            val question1 = CodexProjection.item(
                obj(
                    "type" to "agentMessage",
                    "id" to "call-1",
                    "delivery" to "async",
                    "text" to "Question 1?",
                    "questions" to JSONArray().put(obj("title" to "Question 1?", "options" to JSONArray(listOf("One", "Two")))),
                ),
            )
            (field(live, "items") as MutableMap<String, ConversationItem>)[question1.id] = question1
            rpc.connect()
            set(live, "rpc", rpc)
            set(live, "view", (field(live, "view") as SessionView).copy(connected = true, loading = false, activeTurnId = "native-turn"))
            scope.cancel()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            set(controller, "scope", scope)
            val publish = controller.javaClass.getDeclaredMethod("publish").apply { isAccessible = true }
            publish.invoke(controller)
            assertEquals(listOf("codex-async:call-1"), controller.state.value.selected!!.requests.map { it.id })

            transport.mode = "reject"
            controller.draft("override rejected")
            controller.send()
            withTimeout(5000) {
                while (record.submissions.lastOrNull()?.state != "rejected") {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
            assertEquals(listOf("codex-async:call-1"), controller.state.value.selected!!.requests.map { it.id })
            assertTrue(record.dismissedAsyncQuestionItems.isEmpty())

            transport.mode = "accept"
            controller.draft("override accepted")
            controller.send()
            withTimeout(5000) {
                while (record.submissions.lastOrNull()?.state != "accepted") {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
            assertTrue(controller.state.value.selected!!.requests.isEmpty())
            val reloadedRecord = SessionStore(app).load().single { it.id == local }
            assertTrue("call-1" in reloadedRecord.dismissedAsyncQuestionItems)
            assertTrue(CodexAsyncQuestions.pending(listOf(question1), dismissedItemIds = reloadedRecord.dismissedAsyncQuestionItems).isEmpty())

            val question2 = CodexProjection.item(
                obj(
                    "type" to "agentMessage",
                    "id" to "call-2",
                    "delivery" to "async",
                    "text" to "Question 2?",
                    "questions" to JSONArray().put(obj("title" to "Question 2?", "options" to JSONArray(listOf("Yes", "No")))),
                ),
            )
            (field(live, "items") as MutableMap<String, ConversationItem>)[question2.id] = question2
            publish.invoke(controller)
            assertEquals(listOf("codex-async:call-2"), controller.state.value.selected!!.requests.map { it.id })
        } finally {
            scope.cancel()
            rpc.close()
        }
    }
    @Test fun droppedOrdinaryComposerReceiptPreservesAsyncCardUntilConfirmed() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val controller = SessionController(app)
        (field(controller, "scope") as CoroutineScope).cancel()
        var scope = CoroutineScope(SupervisorJob() + Paused())
        set(controller, "scope", scope)
        val transport = SwitchableSteerTransport().apply { mode = "drop" }
        val rpc = CodexRpc("fixture", null, {}, {}, transport)
        try {
            val local = controller.manage(obj("action" to "attach", "cli" to "Codex", "cwd" to "/work", "nativeId" to "native-fixture-drop")).getJSONObject("session").getString("id")
            controller.openSession(local)
            val live = (field(controller, "sessions") as Map<*, *>)[local]!!
            val record = field(live, "record") as SessionRecord
            val question1 = CodexProjection.item(
                obj(
                    "type" to "agentMessage",
                    "id" to "call-drop",
                    "delivery" to "async",
                    "text" to "Question drop?",
                    "questions" to JSONArray().put(obj("title" to "Question drop?", "options" to JSONArray(listOf("One", "Two")))),
                ),
            )
            (field(live, "items") as MutableMap<String, ConversationItem>)[question1.id] = question1
            rpc.connect()
            set(live, "rpc", rpc)
            set(live, "view", (field(live, "view") as SessionView).copy(connected = true, loading = false, activeTurnId = "native-turn"))
            scope.cancel()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            set(controller, "scope", scope)
            val publish = controller.javaClass.getDeclaredMethod("publish").apply { isAccessible = true }
            publish.invoke(controller)

            controller.draft("override dropped")
            controller.send()
            withTimeout(5000) {
                while (record.submissions.lastOrNull()?.state != "unknown") {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
            assertEquals(listOf("codex-async:call-drop"), controller.state.value.selected!!.requests.map { it.id })
            assertTrue(record.dismissedAsyncQuestionItems.isEmpty())
        } finally {
            scope.cancel()
            rpc.close()
        }
    }
    @Test fun turnCompletedSettlesResidualReasoningAcrossBoundaries() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val controller = SessionController(app)
        (field(controller, "scope") as CoroutineScope).cancel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        set(controller, "scope", scope)
        try {
            val local = controller.manage(obj("action" to "attach", "cli" to "Codex", "cwd" to "/work", "nativeId" to "native-reasoning")).getJSONObject("session").getString("id")
            controller.openSession(local)
            val live = (field(controller, "sessions") as Map<*, *>)[local]!!
            val items = field(live, "items") as MutableMap<String, ConversationItem>
            val event = controller.javaClass.getDeclaredMethod("event", live.javaClass, JSONObject::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            val publish = controller.javaClass.getDeclaredMethod("publish").apply { isAccessible = true }
            fun send(method: String, params: JSONObject) {
                event.invoke(controller, live, obj("method" to method, "params" to params), false)
            }

            send("turn/started", obj("threadId" to "native-reasoning", "turn" to obj("id" to "t1", "status" to "inProgress", "items" to JSONArray())))
            send("item/started", obj("threadId" to "native-reasoning", "turnId" to "t1", "item" to obj("id" to "rs-done", "type" to "reasoning")))
            send("item/reasoning/summaryTextDelta", obj("threadId" to "native-reasoning", "turnId" to "t1", "itemId" to "rs-done", "delta" to "first"))
            send("item/completed", obj("threadId" to "native-reasoning", "turnId" to "t1", "item" to obj("id" to "rs-done", "type" to "reasoning", "summary" to JSONArray().put("first"))))

            send("item/started", obj("threadId" to "native-reasoning", "turnId" to "t1", "item" to obj("id" to "rs-streamed", "type" to "reasoning")))
            send("item/reasoning/summaryTextDelta", obj("threadId" to "native-reasoning", "turnId" to "t1", "itemId" to "rs-streamed", "delta" to "streamed"))

            send("item/started", obj("threadId" to "native-reasoning", "turnId" to "t1", "item" to obj("id" to "rs-blank", "type" to "reasoning")))

            send("item/started", obj("threadId" to "native-reasoning", "turnId" to "t1", "item" to obj("id" to "rs-empty-done", "type" to "reasoning")))
            send("item/completed", obj("threadId" to "native-reasoning", "turnId" to "t1", "item" to obj("id" to "rs-empty-done", "type" to "reasoning")))

            send("item/started", obj("threadId" to "native-reasoning", "turnId" to "t1", "item" to obj("id" to "cmd-1", "type" to "commandExecution", "command" to "ls", "status" to "inProgress")))
            send("item/started", obj("threadId" to "native-reasoning", "turnId" to "t2", "item" to obj("id" to "rs-t2", "type" to "reasoning")))

            send("turn/completed", obj("threadId" to "native-reasoning", "turn" to obj("id" to "t1", "status" to "completed", "items" to JSONArray().put(obj("id" to "rs-done", "type" to "reasoning", "summary" to JSONArray().put("first"))))))
            publish.invoke(controller)

            assertFalse(items.containsKey("rs-blank"))
            assertEquals("succeeded", items["rs-done"]?.outcome)
            assertEquals("first", items["rs-done"]?.detail)
            assertEquals("succeeded", items["rs-empty-done"]?.outcome)
            assertEquals("succeeded", items["rs-streamed"]?.outcome)
            assertEquals("streamed", items["rs-streamed"]?.detail)
            assertEquals("running", items["cmd-1"]?.outcome)
            assertEquals("running", items["rs-t2"]?.outcome)

            send("item/started", obj("threadId" to "native-reasoning", "turnId" to "t1", "item" to obj("id" to "rs-late-blank", "type" to "reasoning")))
            send("item/reasoning/summaryTextDelta", obj("threadId" to "native-reasoning", "turnId" to "t1", "itemId" to "rs-late-delta", "delta" to "late"))
            assertFalse(items.containsKey("rs-late-blank"))
            assertEquals("succeeded", items["rs-late-delta"]?.outcome)
            assertEquals("late", items["rs-late-delta"]?.detail)

            send("turn/started", obj("threadId" to "native-reasoning", "turn" to obj("id" to "t3", "status" to "inProgress", "items" to JSONArray())))
            send("item/started", obj("threadId" to "native-reasoning", "turnId" to "t3", "item" to obj("id" to "rs-int", "type" to "reasoning")))
            send("item/reasoning/summaryTextDelta", obj("threadId" to "native-reasoning", "turnId" to "t3", "itemId" to "rs-int", "delta" to "partial"))
            send("item/started", obj("threadId" to "native-reasoning", "turnId" to "t3", "item" to obj("id" to "rs-int-blank", "type" to "reasoning")))
            send("turn/completed", obj("threadId" to "native-reasoning", "turn" to obj("id" to "t3", "status" to "interrupted", "items" to JSONArray())))
            publish.invoke(controller)

            assertFalse(items.containsKey("rs-int-blank"))
            assertEquals("cancelled", items["rs-int"]?.outcome)
        } finally {
            scope.cancel()
        }
    }
    @Test fun oldTurnStartedDuringHydrationCannotReviveAfterNewTurnSnapshot() = runBlocking {
        val controller = SessionController(RuntimeEnvironment.getApplication())
        (field(controller, "scope") as CoroutineScope).cancel()
        val scope = CoroutineScope(SupervisorJob() + Paused())
        set(controller, "scope", scope)
        var rpc: CodexRpc? = null
        try {
            val local = controller.manage(obj("action" to "attach", "cli" to "Codex", "cwd" to "/work", "nativeId" to "review-thread")).getJSONObject("session").getString("id")
            controller.openSession(local)
            val live = (field(controller, "sessions") as Map<*, *>)[local]!!
            val record = field(live, "record")!!
            set(record, "environment", "fixture-no-runtime-execution")
            val buffered = field(live, "buffered") as MutableList<JSONObject>
            fun envelope(method: String, params: JSONObject) = obj("method" to method, "params" to params.put("threadId", "review-thread"))
            val transport = object : CodexTransport {
                lateinit var receive: (String) -> Unit
                override fun connect(opened: () -> Unit, message: (String) -> Unit, failed: (Throwable) -> Unit) {
                    receive = message
                    opened()
                }
                override fun send(text: String): Boolean {
                    val req = JSONObject(text)
                    val result = when (req.optString("method")) {
                        "initialize" -> JSONObject()
                        "thread/items/list" -> {
                            buffered += envelope("item/started", obj("turnId" to "older-turn", "item" to obj("id" to "ghost", "type" to "reasoning")))
                            buffered += envelope("turn/completed", obj("turn" to obj("id" to "older-turn", "status" to "completed", "items" to JSONArray())))
                            buffered += envelope("turn/completed", obj("turn" to obj("id" to "older-int", "status" to "interrupted", "items" to JSONArray())))
                            buffered += envelope("turn/started", obj("turn" to obj("id" to "new-turn", "status" to "inProgress", "items" to JSONArray())))
                            buffered += envelope("item/started", obj("turnId" to "new-turn", "item" to obj("id" to "rs-new", "type" to "reasoning")))
                            obj(
                                "data" to JSONArray()
                                    .put(obj("turnId" to "older-turn", "item" to obj("id" to "ghost", "type" to "reasoning")))
                                    .put(obj("turnId" to "older-int", "item" to obj("id" to "older-int-rs", "type" to "reasoning", "summary" to JSONArray().put("partial"))))
                                    .put(obj("turnId" to "older-turn", "item" to obj("id" to "cmd-old", "type" to "commandExecution", "command" to "ls", "status" to "inProgress"))),
                                "nextCursor" to JSONObject.NULL,
                            )
                        }
                        "thread/turns/list" -> obj("data" to JSONArray().put(obj("id" to "new-turn", "status" to "inProgress")))
                        else -> JSONObject()
                    }
                    if (req.has("id")) receive(obj("id" to req.get("id"), "result" to result).toString())
                    return true
                }
                override fun close() {}
            }
            rpc = CodexRpc("fixture", null, {}, {}, transport)
            rpc.connect()
            set(live, "rpc", rpc)
            val hydrate = controller.javaClass.declaredMethods.single { it.name == "hydrate" }.apply { isAccessible = true }
            var stoppedBeforeRuntime = false
            try {
                kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                    try {
                        val value = hydrate.invoke(controller, live, continuation)
                        if (value === kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) value else Unit
                    } catch (error: java.lang.reflect.InvocationTargetException) {
                        throw error.targetException
                    }
                }
            } catch (expected: IllegalArgumentException) {
                stoppedBeforeRuntime = true
            }
            assertTrue(stoppedBeforeRuntime)
            val items = field(live, "items") as MutableMap<String, ConversationItem>
            assertFalse(items.containsKey("ghost"))
            assertEquals("cancelled", items["older-int-rs"]?.outcome)
            assertEquals("running", items["cmd-old"]?.outcome)
            assertEquals("new-turn", (field(live, "view") as SessionView).activeTurnId)
            val boundary = field(live, "hydrationBoundary") as HydrationBoundary
            val replay = boundary.replayEvents(buffered.toList()) { _, _ -> "" }
            val eventMethod = controller.javaClass.getDeclaredMethod("event", live.javaClass, JSONObject::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            replay.forEach { eventMethod.invoke(controller, live, it, true) }
            assertFalse(items.containsKey("ghost"))
            assertEquals("cancelled", items["older-int-rs"]?.outcome)
            assertEquals("running", items["rs-new"]?.outcome)
            assertEquals("running", items["cmd-old"]?.outcome)
            assertEquals("new-turn", (field(live, "view") as SessionView).activeTurnId)
        } finally {
            rpc?.close()
            scope.cancel()
        }
    }
}
