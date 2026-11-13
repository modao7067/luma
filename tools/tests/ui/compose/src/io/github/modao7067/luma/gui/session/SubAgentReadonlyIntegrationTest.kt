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
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SubAgentReadonlyIntegrationTest {
    private class Paused : CoroutineDispatcher() { override fun dispatch(context: CoroutineContext, block: Runnable) {} }
    private class Carrier(val parent: String, val label: String, var hold: Boolean = false) : CodexTransport {
        lateinit var receive: (String) -> Unit
        val calls = mutableListOf<JSONObject>()
        val held = mutableListOf<JSONObject>()
        var closed = false
        var nativeStatus = "completed"
        override fun connect(opened: () -> Unit, message: (String) -> Unit, failed: (Throwable) -> Unit) { receive = message; opened() }
        override fun send(message: String): Boolean {
            val frame = JSONObject(message); calls += frame
            if (!frame.has("id")) return true
            if (frame.optString("method") == "thread/read" && hold) held += frame else reply(frame)
            return true
        }
        fun reply(frame: JSONObject) {
            val p = frame.optJSONObject("params") ?: JSONObject()
            val result = when (frame.optString("method")) {
                "initialize" -> JSONObject()
                "thread/read" -> obj("thread" to obj("id" to p.getString("threadId"), "parentThreadId" to parent, "historyMode" to "paginated", "model" to "$label-model", "reasoningEffort" to "xhigh", "agentNickname" to label))
                "thread/items/list" -> if (p.stringOrNull("cursor") == null) obj("data" to JSONArray().put(obj("turnId" to "child-turn", "item" to obj("id" to "thought", "type" to "reasoning", "summary" to JSONArray().put("$label thought")), "completedAtMs" to 1)), "nextCursor" to "page-two")
                    else obj("data" to JSONArray().put(obj("turnId" to "child-turn", "item" to obj("id" to "output", "type" to "agentMessage", "text" to "$label output"), "completedAtMs" to 2)))
                "thread/turns/list" -> obj("data" to JSONArray().put(obj("id" to "child-turn", "status" to nativeStatus,
                    "items" to if (p.optString("itemsView") == "full") JSONArray().put(obj("id" to "output", "type" to "agentMessage", "text" to "$label output")) else JSONArray())), "backwardsCursor" to "native-tail")
                else -> error("Unexpected child mutation: ${frame.optString("method")}")
            }
            receive(obj("id" to frame.get("id"), "result" to result).toString())
        }
        override fun close() { closed = true }
    }
    private class Harness : AutoCloseable {
        val controller = SessionController(RuntimeEnvironment.getApplication())
        private val paused = CoroutineScope(SupervisorJob() + Paused())
        private val running = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val carriers = mutableListOf<Carrier>()
        val connections = mutableListOf<CodexRpc>()
        init { (field(controller, "scope") as CoroutineScope).cancel(); set(controller, "scope", paused) }
        fun parent(native: String, child: String = "child", cli: String = "Codex", hold: Boolean = false): Pair<String, Any> {
            val id = runBlocking { controller.manage(obj("action" to "create", "cli" to cli, "cwd" to "/workspace", "requestId" to "readonly-$native-${java.util.UUID.randomUUID()}")) }.getString("id")
            val live = (field(controller, "sessions") as Map<*, *>)[id]!!
            val record = field(live, "record") as SessionRecord; record.threadId = native; record.model = "parent-only-model"; record.effort = "low"
            val agent = SubAgentState(child, name = "Native child", task = "$native task", detail = "$native result", status = "running")
            set(live, "view", SessionView(SessionSummary(id, record.title, record.environment, record.cwd, cli = cli), connected = true, subAgents = listOf(agent)))
            if (cli == "Codex") {
                (field(live, "subAgents") as CodexSubAgents).item(obj("type" to "collabAgentToolCall", "id" to "spawn", "tool" to "spawnAgent", "senderThreadId" to native,
                    "receiverThreadIds" to JSONArray().put(child), "agentsStates" to obj(child to obj("status" to "running")), "prompt" to "$native task"), "parent-turn")
                val carrier = Carrier(native, native, hold); carriers += carrier
                val rpc = CodexRpc("unused", null, { event -> emit(live, event) }, {}, carrier); connections += rpc
                runBlocking { rpc.connect() }; set(live, "rpc", rpc)
            }
            return id to live
        }
        fun run() { set(controller, "scope", running); controller.setForeground(true) }
        fun publish() { controller.javaClass.getDeclaredMethod("publish").apply { isAccessible = true }.invoke(controller) }
        fun emit(live: Any, event: JSONObject) { controller.javaClass.getDeclaredMethod("event", live.javaClass, JSONObject::class.java).apply { isAccessible = true }.invoke(controller, live, event); publish() }
        fun pump() { Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(40)) }
        override fun close() { paused.cancel(); running.cancel(); connections.forEach(CodexRpc::close) }
    }
    @Test fun actualChildMessagesModelReasoningAndLiveEventsUseOnlyReadCallsAndCloseLeavesParentOwnerAlive() {
        Harness().use { h ->
            val (parent, live) = h.parent("native-parent")
            h.controller.openSession(parent); h.run(); h.controller.draft("Keep parent draft")
            @Suppress("UNCHECKED_CAST") val parentItems = field(live, "items") as MutableMap<String, ConversationItem>
            parentItems["parent-message"] = ConversationItem("parent-message", "assistant", "Keep parent history")
            h.publish(); h.controller.openSubAgent(parent, "child"); h.pump()
            var detail = h.controller.state.value.subAgentDetail!!
            assertEquals(parent, detail.parentSessionId); assertFalse(detail.loading)
            assertEquals(listOf("native-parent thought", "native-parent output"), detail.messages.map { it.detail.ifEmpty { it.text } })
            assertEquals("native-parent-model", detail.modelLabel); assertEquals("xhigh", detail.effort)
            h.emit(live, obj("method" to "item/agentMessage/delta", "params" to obj("threadId" to "child", "itemId" to "output", "turnId" to "child-turn", "delta" to " LIVE")))
            detail = h.controller.state.value.subAgentDetail!!
            assertEquals("native-parent output LIVE", detail.messages.last().text)
            h.emit(live, obj("id" to 200, "method" to "item/commandExecution/requestApproval", "params" to obj("threadId" to "child", "turnId" to "child-turn", "command" to "fixture")))
            h.emit(live, obj("method" to "item/completed", "params" to obj("threadId" to "child", "item" to obj("type" to "agentMessage", "id" to "question", "text" to "Native question?", "delivery" to "async", "questions" to JSONArray().put(obj("title" to "Choose", "options" to JSONArray(listOf("One", "Two"))))))))
            assertTrue(h.controller.state.value.selected!!.requests.isEmpty())
            assertTrue(h.controller.state.value.subAgentDetail!!.messages.all { it.asyncQuestions.isEmpty() })
            h.controller.closeSubAgent(); h.pump()
            assertNull(h.controller.state.value.subAgentDetail)
            assertTrue(h.connections.single().connected); assertFalse(h.carriers.single().closed)
            assertEquals("Keep parent draft", h.controller.state.value.selected!!.draft)
            assertEquals(listOf("Keep parent history"), h.controller.state.value.selected!!.messages.map { it.text })
            assertTrue(h.carriers.single().calls.filter { it.has("id") }.all { it.optString("method") in setOf("initialize", "thread/read", "thread/items/list", "thread/turns/list") })
            h.emit(live, obj("method" to "turn/completed", "params" to obj("threadId" to "child", "turn" to obj("id" to "child-turn", "status" to "completed"))))
            assertNull(h.controller.state.value.subAgentDetail)
            assertEquals("succeeded", h.controller.state.value.selected!!.subAgents.single().status)
        }
    }
    @Test fun sameChildIdAcrossParentsLateReadsClosedDetailsAndConnectionEpochsCannotLeak() {
        Harness().use { h ->
            val (one, _) = h.parent("native-one", "shared", hold = true)
            val (two, liveTwo) = h.parent("native-two", "shared", hold = true)
            h.controller.openSession(one); h.run(); h.controller.openSubAgent(one, "shared"); h.pump()
            assertTrue(h.carriers[0].held.isNotEmpty())
            h.controller.openSession(two); h.controller.openSubAgent(two, "shared"); h.pump()
            h.carriers[0].held.toList().forEach(h.carriers[0]::reply); h.pump()
            assertEquals(two, h.controller.state.value.subAgentDetail!!.parentSessionId)
            assertTrue(h.controller.state.value.subAgentDetail!!.messages.isEmpty())
            val generation = field(liveTwo, "generation") as Int; set(liveTwo, "generation", generation + 1)
            h.carriers[1].hold = false; h.publish(); h.pump()
            assertEquals("native-two output", h.controller.state.value.subAgentDetail!!.messages.last().text)
            h.carriers[1].held.toList().forEach(h.carriers[1]::reply); h.pump()
            assertEquals("native-two output", h.controller.state.value.subAgentDetail!!.messages.last().text)
            h.controller.closeSubAgent(); h.carriers[1].held.toList().forEach(h.carriers[1]::reply); h.pump()
            assertNull(h.controller.state.value.subAgentDetail)
            h.controller.openSubAgent(one, "shared"); assertNull(h.controller.state.value.subAgentDetail)
        }
    }
    @Test fun providerWithoutChildHistoryKeepsOnlyActualTaskResultAndStatusWithoutParentModelGuessing() {
        Harness().use { h ->
            val (id, _) = h.parent("native-grok", cli = "Grok Build")
            h.controller.openSession(id); h.run(); h.controller.openSubAgent(id, "child"); h.pump()
            val detail = h.controller.state.value.subAgentDetail!!
            assertEquals("native-grok task", detail.agent.task); assertEquals("native-grok result", detail.agent.detail)
            assertEquals("running", detail.agent.status); assertTrue(detail.messages.isEmpty())
            assertEquals("", detail.modelLabel); assertEquals("", detail.effort); assertFalse(detail.loading)
        }
    }
    @Test fun completedHistoryStopsPollingAndActivityVisibilityPausesActiveReads() {
        Harness().use { h ->
            val (id, live) = h.parent("native-visible")
            h.controller.openSession(id); h.run(); h.controller.openSubAgent(id, "child"); h.pump()
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
            val carrier = h.carriers.single()
            val settled = carrier.calls.size
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(10))
            assertEquals(settled, carrier.calls.size)
            assertEquals(2, carrier.calls.count { it.optString("method") == "thread/items/list" })
            carrier.nativeStatus = "inProgress"
            h.emit(live, obj("method" to "turn/started", "params" to obj("threadId" to "child", "turn" to obj("id" to "next", "status" to "inProgress"))))
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(250))
            h.controller.setForeground(false)
            val hidden = carrier.calls.size
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(10))
            assertEquals(hidden, carrier.calls.size)
            assertTrue(h.connections.single().connected)
            h.controller.setForeground(true); h.pump()
            assertTrue(carrier.calls.size > hidden)
            assertEquals(2, carrier.calls.count { it.optString("method") == "thread/items/list" })
        }
    }
    @Test fun nativeShutdownRemainsClosedWhenStoredLastTurnWasSuccessful() {
        Harness().use { h ->
            val (id, live) = h.parent("native-closed")
            (field(live, "subAgents") as CodexSubAgents).item(obj("type" to "collabAgentToolCall", "id" to "close", "senderThreadId" to "native-closed",
                "receiverThreadIds" to JSONArray().put("child"), "agentsStates" to obj("child" to obj("status" to "shutdown"))), "parent-turn")
            h.controller.openSession(id); h.run(); h.controller.openSubAgent(id, "child"); h.pump()
            assertEquals("closed", h.controller.state.value.subAgentDetail!!.agent.status)
            assertEquals("closed", h.controller.state.value.selected!!.subAgents.single().status)
            assertTrue(h.controller.state.value.subAgentDetail!!.messages.isNotEmpty())
        }
    }
    companion object {
        private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
        private fun set(owner: Any, name: String, value: Any) { owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value) }
    }
}
