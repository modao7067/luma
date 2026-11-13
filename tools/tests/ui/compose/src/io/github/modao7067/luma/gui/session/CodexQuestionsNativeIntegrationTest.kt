package io.github.modao7067.luma.gui.session

import android.app.Application
import android.os.Looper
import kotlinx.coroutines.*
import io.github.modao7067.luma.gui.agent.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.*
import java.io.File
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CodexQuestionsNativeIntegrationTest {
    private class Paused : CoroutineDispatcher() { override fun dispatch(context: CoroutineContext, block: Runnable) {} }
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    private fun set(owner: Any, name: String, value: Any) { owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value) }
    private fun invoke(owner: Any, name: String, vararg args: Any): Any? = owner.javaClass.declaredMethods.single { it.name == name && it.parameterCount == args.size }.apply { isAccessible = true }.invoke(owner, *args)
    @Test fun guiAndOfficialTuiShareAsyncQuestionsPartialRepliesHistoryAndBlockingRequests() = runBlocking {
        val endpoint = System.getenv("LUMA_TEST_QUESTION_ENDPOINT")
        assumeTrue("Run tools/tests/cli/codex_questions_native.py", endpoint != null)
        val binary = System.getenv("LUMA_TEST_QUESTION_BINARY")!!; val work = System.getenv("LUMA_TEST_QUESTION_WORK")!!
        val output = File(System.getenv("LUMA_TEST_QUESTION_OUTPUT")!!)
        fun control(path: String): JSONObject = JSONObject(URL(System.getenv("LUMA_TEST_QUESTION_CONTROL") + path).readText()).also { assertFalse(it.toString(), it.has("error")) }
        val app = RuntimeEnvironment.getApplication(); val controller = SessionController(app)
        (field(controller, "scope") as CoroutineScope).cancel()
        var scope = CoroutineScope(SupervisorJob() + Paused()); set(controller, "scope", scope)
        val events = CopyOnWriteArrayList<JSONObject>(); val observer = CopyOnWriteArrayList<JSONObject>(); val late = CopyOnWriteArrayList<JSONObject>()
        fun rpc(target: MutableList<JSONObject>) = CodexRpc(endpoint!!, null, { target += it }, {},
            if (endpoint.startsWith("unix://")) CodexProcessTransport { ProcessBuilder(binary, "app-server", "proxy", "--sock", endpoint.removePrefix("unix://")).start() } else null)
        var a = rpc(events);val b = rpc(observer);var c: CodexRpc? = null;var live: Any? = null;var applied = 0
        fun flush() {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            live?.let { target ->
                while (applied < events.size) invoke(controller, "event", target, events[applied++])
                invoke(controller, "publish")
            }
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
        suspend fun await(message: String, condition: () -> Boolean) {
            try { withTimeout(15000) { while (true) { flush(); if (condition()) break; delay(20) } } }
            catch (timeout: TimeoutCancellationException) { throw AssertionError("$message; view=${controller.state.value.selected}", timeout) }
        }
        fun input(text: String) = JSONArray().put(obj("type" to "text", "text" to text))
        fun completed(rows: List<JSONObject>) = rows.count { it.optString("method") == "turn/completed" }
        fun replyIds(rows: List<JSONObject>) = rows.filter { it.optString("method") == "item/completed" }.flatMap { e ->
            val i = e.getJSONObject("params").getJSONObject("item")
            if (i.optString("type") == "userMessage") CodexAsyncQuestions.replies(CodexProjection.inputText(i.optJSONArray("content") ?: JSONArray())).map { it.questionId } else emptyList()
        }
        suspend fun rebuild(rpc: CodexRpc, thread: String) {
            set(live!!, "rpc", rpc);set(live!!, "hydrating", true)
            try {
                suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                    val result = invoke(controller, "hydrate", live!!, continuation)
                    if (result === COROUTINE_SUSPENDED) COROUTINE_SUSPENDED else Unit
                }
            } finally { set(live!!, "hydrating", false);invoke(controller, "publish") }
            assertEquals(thread, (field(live!!, "record") as SessionRecord).threadId)
        }

        try {
            a.connect();b.connect()
            val thread = a.request("thread/start", obj("cwd" to work, "sandbox" to "read-only", "approvalPolicy" to "on-request")).getJSONObject("thread").getString("id")
            a.request("thread/name/set", obj("threadId" to thread, "name" to "Question fixture"))
            a.request("thread/queue/add", obj("threadId" to thread, "clientUserMessageId" to "warmup", "input" to input("Persist fixture")))
            await("warmup complete") { completed(events) == 1 }
            b.request("thread/resume", obj("threadId" to thread))
            control("/tui/start?thread=" + URLEncoder.encode(thread, "UTF-8"))
            val local = controller.manage(obj("action" to "attach", "cli" to "Codex", "cwd" to work, "nativeId" to thread)).getJSONObject("session").getString("id")
            controller.openSession(local); live = (field(controller, "sessions") as Map<*, *>)[local]!!
            val record = field(live!!, "record") as SessionRecord
            record.draft = "Preserve the user's composer";record.images += StoredImage("draft-image", "private://draft", "/draft/image", "draft")
            set(live!!, "rpc", a);set(live!!, "view", (field(live!!, "view") as SessionView).copy(connected = true, loading = false))
            scope.cancel();scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate);set(controller, "scope", scope)
            flush()
            control("/next/async?count=2")
            a.request("thread/queue/add", obj("threadId" to thread, "clientUserMessageId" to "async-1", "input" to input("Async fixture")))
            await("two native questions projected") { controller.state.value.selected!!.requests.any { it.questions.size == 2 } && completed(events) >= 2 }
            assertEquals("待回答", controller.state.value.selected!!.summary.status)
            val first = controller.state.value.selected!!.requests.single();val q0 = first.questions[0];val q1 = first.questions[1]
            assertEquals(listOf("One"), q0.defaultSelection)
            assertTrue(observer.any { it.optString("method") == "item/completed" && it.getJSONObject("params").getJSONObject("item").optString("delivery") == "async" })
            controller.answer(first.id, "submit", mapOf(q0.id to listOf("Two")))
            controller.answer(first.id, "submit", mapOf(q0.id to listOf("Two")))
            await("GUI answer consumed once") { q0.id in replyIds(observer) && controller.state.value.selected!!.requests.singleOrNull()?.questions?.singleOrNull()?.id == q1.id }
            assertEquals(1, record.submissions.size);assertEquals("Preserve the user's composer", record.draft);assertEquals(1, record.images.size)
            val pane = control("/tui/open").getString("text");File(output, "tui-partial-question.txt").writeText(pane)
            assertFalse("TUI must retire answered question 1: $pane", (pane.contains("enter submit") && pane.contains(q0.prompt)))
            File(output, "upstream-limits.json").writeText(obj("officialTuiRetainsOtherQuestionsAfterExternalPartialReply" to (pane.contains("enter submit") && pane.contains(q1.prompt)),
                "guiRetainsExactUnansweredQuestion" to true).toString(2))
            await("partial reply turn completed before next deliberate GUI answer") { controller.state.value.selected!!.activeTurnId == null }
            controller.answer(first.id, "submit", mapOf(q1.id to listOf("Two")))
            await("second GUI answer clears only remaining question") { q1.id in replyIds(observer) && controller.state.value.selected!!.requests.isEmpty() }
            controller.answer(first.id, "submit", mapOf(q1.id to listOf("Two")));flush();assertEquals(2, record.submissions.size)
            await("first answers completed") { controller.state.value.selected!!.activeTurnId == null }
            control("/next/async?count=1&hold=true")
            a.request("thread/queue/add", obj("threadId" to thread, "clientUserMessageId" to "async-tui", "input" to input("TUI answer fixture")))
            await("separate native TUI question") { controller.state.value.selected!!.requests.isNotEmpty() }
            val tuiQuestion = controller.state.value.selected!!.requests.single().questions.single()
            val tuiPane = control("/tui/open").getString("text");assertTrue(tuiPane, tuiPane.contains(tuiQuestion.prompt))
            control("/tui/submit")
            await("genuine official TUI answer clears GUI") { tuiQuestion.id in replyIds(observer) && controller.state.value.selected!!.requests.isEmpty() }
            c = rpc(late);c!!.connect();c!!.request("thread/resume", obj("threadId" to thread));rebuild(c!!, thread)
            assertTrue("answered history never reopens", controller.state.value.selected!!.requests.isEmpty())
            await("TUI answer turn completed") { controller.state.value.selected!!.activeTurnId == null }

            control("/next/async?count=1&hold=true")
            a.request("thread/queue/add", obj("threadId" to thread, "clientUserMessageId" to "async-2", "input" to input("Active async fixture")))
            await("new active question") { controller.state.value.selected!!.requests.any { it.id != first.id } && controller.state.value.selected!!.activeTurnId != null }
            assertEquals("执行中", controller.state.value.selected!!.summary.status)
            val second = controller.state.value.selected!!.requests.single();val q = second.questions.single()
            a.close();a = rpc(events);a.connect();a.request("thread/resume", obj("threadId" to thread));set(live!!, "rpc", a);rebuild(a, thread)
            assertEquals(q.id, controller.state.value.selected!!.requests.single().questions.single().id)
            val active = controller.state.value.selected!!.activeTurnId!!
            set(live!!, "view", (field(live!!, "view") as SessionView).copy(activeTurnId = "stale-turn"));flush()
            controller.answer(second.id, "submit", mapOf(q.id to listOf("One")))
            await("definitive rejection restores question") { record.submissions.last().state == "rejected" }
            val rejectedId = record.submissions.last().id
            set(live!!, "view", (field(live!!, "view") as SessionView).copy(activeTurnId = active));flush()
            controller.answer(second.id, "submit", mapOf(q.id to listOf("Free text @/must-stay-text")))
            await("active GUI reply consumed") { q.id in replyIds(observer) && controller.state.value.selected!!.requests.isEmpty() }
            assertNotEquals(rejectedId, record.submissions.last().id)
            val replied = observer.filter { it.optString("method") == "item/completed" }.map { it.getJSONObject("params").getJSONObject("item") }.single { item ->
                item.optString("type") == "userMessage" && CodexAsyncQuestions.replies(CodexProjection.inputText(item.optJSONArray("content") ?: JSONArray())).any { it.questionId == q.id }
            }
            assertEquals(1, replied.getJSONArray("content").length());assertEquals("text", replied.getJSONArray("content").getJSONObject(0).getString("type"))
            val closed = control("/tui/open").getString("text");File(output, "tui-after-gui-answer.txt").writeText(closed)
            assertFalse("answered native TUI form must disappear: $closed", closed.contains("enter submit"))
            control("/release");await("active reply turn completed") { controller.state.value.selected!!.activeTurnId == null }

            val beforeResolved = observer.count { it.optString("method") == "serverRequest/resolved" }
            control("/next/sync")
            a.request("turn/start", obj("threadId" to thread, "input" to input("Blocking question fixture"), "collaborationMode" to obj("mode" to "plan", "settings" to obj("model" to "fixture", "reasoning_effort" to "medium", "developer_instructions" to JSONObject.NULL))))
            await("blocking request reaches both clients") { events.any { it.optString("method") == "item/tool/requestUserInput" } && observer.any { it.optString("method") == "item/tool/requestUserInput" } }
            val blocking = controller.state.value.selected!!.requests.single();assertEquals("choice", blocking.questions.single().id)
            c!!.close();c = rpc(late);late.clear();c!!.connect();c!!.request("thread/resume", obj("threadId" to thread))
            await("late connection replays pending blocking request") { late.any { it.optString("method") == "item/tool/requestUserInput" } }
            controller.answer(blocking.id, "submit", mapOf("choice" to listOf("Two")))
            await("blocking answer resolves everywhere") { observer.count { it.optString("method") == "serverRequest/resolved" } > beforeResolved && controller.state.value.selected!!.requests.isEmpty() && late.any { it.optString("method") == "serverRequest/resolved" } }
            assertEquals(4, record.submissions.size)
            assertTrue(controller.state.value.selected!!.messages.filter { it.role == "user" }.all { !it.text.contains("send_user_message_question_reply") })
            File(output, "controller-events.json").writeText(JSONObject().put("gui", JSONArray(events)).put("observer", JSONArray(observer)).put("late", JSONArray(late)).toString(2))
            println("PASS native async fields, exact GUI partial replies, official TUI single reply, native replay, active steer, rejected retry identity, readable replies and blocking RPC resolution")
        } finally {
            File(output, "controller-events.json").writeText(JSONObject().put("gui", JSONArray(events)).put("observer", JSONArray(observer)).put("late", JSONArray(late)).toString(2))
            live?.let { target -> File(output, "controller-state.txt").writeText((field(target, "record") as SessionRecord).submissions.toString() + "\n" + controller.state.value.selected.toString()) }
            scope.cancel();a.close();b.close();c?.close()
        }
    }
}
