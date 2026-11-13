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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CodexSubAgentNativeIntegrationTest {
    private class Paused : CoroutineDispatcher() { override fun dispatch(context: CoroutineContext, block: Runnable) {} }
    @Test fun realNativeChildHistoryStreamingAndCloseUseTheSameParentOwnerWithoutChildMutations() = runBlocking {
        val configuredEndpoint = System.getenv("LUMA_TEST_SUBAGENT_ENDPOINT")
        assumeTrue("Run tools/tests/cli/codex_subagent_native.py", configuredEndpoint != null)
        val endpoint = configuredEndpoint!!
        val work = System.getenv("LUMA_TEST_SUBAGENT_WORK")!!
        val output = File(System.getenv("LUMA_TEST_SUBAGENT_OUTPUT")!!)
        fun control(path: String) = URL(System.getenv("LUMA_TEST_SUBAGENT_CONTROL")!! + path).readText()
        val controller = SessionController(RuntimeEnvironment.getApplication())
        (field(controller, "scope") as CoroutineScope).cancel()
        var scope = CoroutineScope(SupervisorJob() + Paused()); set(controller, "scope", scope)
        val events = CopyOnWriteArrayList<JSONObject>(); val calls = CopyOnWriteArrayList<JSONObject>()
        val delegate = CodexWebSocketTransport(endpoint, null)
        val transport = object : CodexTransport {
            override fun connect(opened: () -> Unit, message: (String) -> Unit, failed: (Throwable) -> Unit) = delegate.connect(opened, message, failed)
            override fun send(message: String): Boolean { calls += JSONObject(message); return delegate.send(message) }
            override fun close() = delegate.close()
        }
        val rpc = CodexRpc(endpoint, null, { events += it }, {}, transport)
        var live: Any? = null; var applied = 0
        fun flush() {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            live?.let { target ->
                while (applied < events.size) invoke(controller, "event", target, events[applied++])
                invoke(controller, "publish")
            }
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
        suspend fun await(message: String, predicate: () -> Boolean) {
            try { withTimeout(15000) { while (true) { flush(); if (predicate()) break; delay(20) } } }
            catch (failure: TimeoutCancellationException) { throw AssertionError(message, failure) }
        }
        try {
            rpc.connect()
            val parent = rpc.request("thread/start", obj("cwd" to work, "sandbox" to "read-only", "approvalPolicy" to "on-request", "historyMode" to "paginated")).getJSONObject("thread").getString("id")
            val local = controller.manage(obj("action" to "attach", "cli" to "Codex", "cwd" to work, "nativeId" to parent)).getJSONObject("session").getString("id")
            controller.openSession(local); live = (field(controller, "sessions") as Map<*, *>)[local]!!
            val record = field(live!!, "record") as SessionRecord; record.draft = "Preserve native parent draft"
            set(live!!, "rpc", rpc); set(live!!, "view", (field(live!!, "view") as SessionView).copy(connected = true, loading = false))
            scope.cancel(); scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate); set(controller, "scope", scope)
            controller.setForeground(true)
            rpc.request("turn/start", obj("threadId" to parent, "input" to JSONArray().put(obj("type" to "text", "text" to "Explicitly spawn one fixture_child for this native observer test."))))
            await("Real native child identity") { controller.state.value.selected!!.subAgents.size == 1 }
            val child = controller.state.value.selected!!.subAgents.single().id
            val baseline = calls.size
            controller.openSubAgent(local, child)
            await("Real persisted child reasoning") { controller.state.value.subAgentDetail?.messages?.any { it.detail.contains("Native child reasoning") } == true }
            assertEquals("fixture", controller.state.value.subAgentDetail!!.modelLabel)
            assertEquals("medium", controller.state.value.subAgentDetail!!.effort)
            control("/release-text")
            await("Real child partial text delta") { controller.state.value.subAgentDetail?.messages?.any { it.text == "Native child streamed output" } == true }
            assertTrue(events.any { it.optString("method") == "item/agentMessage/delta" && it.optJSONObject("params")?.optString("threadId") == child })
            controller.closeSubAgent(); flush()
            assertTrue(rpc.connected); assertNull(controller.state.value.subAgentDetail)
            val active = rpc.request("thread/read", obj("threadId" to child, "includeTurns" to false)).getJSONObject("thread")
            assertEquals("active", active.getJSONObject("status").getString("type"))
            control("/finish")
            await("Child completes after closing detail") { controller.state.value.selected!!.subAgents.single().status == "succeeded" }
            controller.openSubAgent(local, child)
            await("Complete persisted real child output") { controller.state.value.subAgentDetail?.messages?.lastOrNull()?.text == "Native child streamed output" && controller.state.value.subAgentDetail?.loading == false }
            assertEquals("Preserve native parent draft", controller.state.value.selected!!.draft)
            assertTrue(controller.state.value.selected!!.requests.isEmpty())
            val readOnly = calls.drop(baseline).filter { it.has("id") }
            assertTrue(readOnly.toString(), readOnly.all { it.optString("method") in setOf("thread/read", "thread/items/list", "thread/turns/list") })
            File(output, "production-result.json").writeText(obj("parentNativeId" to parent, "childNativeId" to child,
                "actualChildReasoning" to true, "actualChildTextDelta" to true, "actualChildModel" to "fixture", "actualChildEffort" to "medium",
                "childActiveAfterClose" to true, "childCompletedAfterClose" to true, "sameRpcRemainedConnected" to rpc.connected,
                "parentDraftPreserved" to true, "zeroChildMutations" to true, "readOnlyCalls" to JSONArray(readOnly.map { it.getString("method") })).toString(2))
        } finally { control("/finish"); scope.cancel(); rpc.close() }
    }
    companion object {
        private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
        private fun set(owner: Any, name: String, value: Any) { owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value) }
        private fun invoke(owner: Any, name: String, vararg args: Any) = owner.javaClass.declaredMethods.single { it.name == name && it.parameterCount == args.size }.apply { isAccessible = true }.invoke(owner, *args)
    }
}
