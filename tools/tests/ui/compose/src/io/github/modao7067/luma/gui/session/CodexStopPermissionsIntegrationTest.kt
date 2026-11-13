package io.github.modao7067.luma.gui.session

import android.app.Application
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.*
import io.github.modao7067.luma.gui.agent.*
import io.github.modao7067.luma.gui.ui.SessionDetail
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
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
class CodexStopPermissionsIntegrationTest {
    @get:Rule val rule = createComposeRule()
    private class Paused : CoroutineDispatcher() { override fun dispatch(context: CoroutineContext, block: Runnable) {} }
    private class Fixture(url: String, val native: Boolean) : AutoCloseable {
        val app: Application = RuntimeEnvironment.getApplication()
        val controller = SessionController(app)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val id: String
        val live: Any
        val record: SessionRecord
        val rpc: CodexRpc
        val view get() = controller.state.value.selected!!
        init {
            (field(controller, "scope") as CoroutineScope).cancel()
            val paused = CoroutineScope(SupervisorJob() + Paused()); set(controller, "scope", paused)
            id = runBlocking { controller.manage(obj("action" to "create", "cli" to "Codex", "cwd" to System.getenv("LUMA_STOP_WORK"), "requestId" to "owned-controller")) }.getString("id")
            live = (field(controller, "sessions") as Map<*, *>)[id]!!; record = field(live, "record") as SessionRecord
            controller.openSession(id)
            rpc = CodexRpc(url, null, { event -> scope.launch { apply(event) } }, { failure -> scope.launch {
                set(live, "view", view.copy(connected = false, error = failure.message)); publish()
            } })
            runBlocking {
                rpc.connect()
                record.threadId = if (native) {
                    val created = rpc.request("thread/start", obj("cwd" to record.cwd, "approvalPolicy" to "on-request", "sandbox" to "workspace-write", "historyMode" to "paginated"))
                    created.getJSONObject("thread").getString("id").also { thread ->
                        rpc.request("thread/section/move", obj("threadId" to thread, "sectionId" to JSONObject.NULL))
                        rpc.request("thread/resume", obj("threadId" to thread, "excludeTurns" to true))
                    }
                } else "fault-thread"
            }
            set(live, "rpc", rpc)
            val permissions = if (native) runBlocking { CodexCapabilities.permissions(rpc.request("permissionProfile/list", obj("cwd" to record.cwd, "limit" to 100))) } else emptyList()
            set(live, "view", SessionView(SessionSummary(id, record.title, record.environment, record.cwd), connected = true, permissions = permissions))
            paused.cancel(); set(controller, "scope", scope); publish()
        }
        fun apply(event: JSONObject) {
            controller.javaClass.getDeclaredMethod("event", live.javaClass, JSONObject::class.java).apply { isAccessible = true }.invoke(controller, live, event)
            publish()
        }
        fun publish() { controller.javaClass.getDeclaredMethod("publish").apply { isAccessible = true }.invoke(controller) }
        fun send(text: String) { controller.draft(text); controller.send() }
        fun until(predicate: () -> Boolean) {
            val deadline = System.nanoTime() + 10_000_000_000L
            while (!predicate() && System.nanoTime() < deadline) { Thread.sleep(10); Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(10)) }
            assertTrue("condition timed out; view=$view", predicate())
        }
        override fun close() { rpc.close(); scope.cancel() }
    }
    @Test fun nativeDefaultSteerThenStopPermissionsAndResendUsesTheSameThreadOnce() {
        val endpoint = System.getenv("LUMA_STOP_URL"); assumeTrue("Run codex_stop_permissions.py --controller", endpoint != null)
        val model = requireNotNull(System.getenv("LUMA_STOP_MODEL"))
        Fixture(endpoint!!, true).use { f ->
            f.send("first held turn")
            assertTrue(f.view.messages.any { it.text == "first held turn" })
            f.until { f.view.activeTurnId != null && f.record.submissionId == null }
            f.send("steer this input")
            f.until { f.record.submissionId == null }
            assertEquals("accepted", f.record.submissions.last().state)
            java.net.URL(model + "/release").readText()
            f.until { f.view.activeTurnId == null && f.view.queue.isEmpty() }
            java.net.URL(model + "/hold").readText()
            f.send("turn that the user stops")
            f.until { f.view.activeTurnId != null && f.record.submissionId == null }
            f.controller.stop(); f.until { f.view.activeTurnId == null && !f.view.stopping }
            val permission = f.view.permissions.first { it.id == ":read-only" }.id
            f.controller.configurePermission(permission)
            f.until { !f.view.configuring && f.view.permission == permission }
            f.send("after stop and permissions")
            f.until { f.view.activeTurnId != null && f.record.submissionId == null }
            java.net.URL(model + "/release").readText()
            f.until { f.view.activeTurnId == null && f.view.queue.isEmpty() }
            assertEquals(1, f.view.messages.count { it.text == "after stop and permissions" })
            assertFalse(f.view.uncertainSubmission); assertNull(f.view.error)
            println("PASS same-thread native default steer accepted, stop/settings/resend completes once")
        }
    }
    @Test fun acceptedQueueButRejectedStartShowsTheNativeErrorWithoutRestoringOrReplayingTheInput() {
        val endpoint = System.getenv("LUMA_STOP_FAULT_URL"); assumeTrue("Private fault carrier required", endpoint != null)
        Fixture(endpoint!!, false).use { f ->
            f.apply(obj("method" to "turn/completed", "params" to obj("threadId" to f.record.threadId, "turn" to obj("id" to "stopped", "status" to "interrupted"))))
            val request = obj("action" to "send", "id" to f.id, "text" to "queued after stop", "requestId" to "accepted-but-start-failed")
            runBlocking { f.controller.manage(request) }
            f.until { f.view.error?.contains("Sandbox failed for fixture") == true }
            assertNull(f.record.submissionId); assertEquals("", f.record.draft)
            assertEquals(1, f.view.queue.size); assertFalse(f.view.messages.any { it.text == "queued after stop" })
            val receipt = runBlocking { f.controller.manage(request) }
            assertEquals("accepted", receipt.getString("state")); assertTrue(receipt.getBoolean("accepted"))
            rule.setContent { MaterialTheme {
                val state by f.controller.state.collectAsState()
                SessionDetail(state, state.selected!!, f.controller, onPickImages = {})
            } }
            rule.onNodeWithText("Sandbox failed for fixture", substring = true).assertDoesNotExist()
            rule.onNode(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "连接异常"), useUnmergedTree = true).assertExists()
            rule.onAllNodes(hasText("queued after stop", substring = false) and !hasContentDescription("会话 Dashboard")).assertCountEquals(1)
            println("PASS native queue-start refusal retained in state and red dot, no ErrorNotice; queue only, no fake user bubble or resend")
        }
    }
    companion object {
        private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
        private fun set(owner: Any, name: String, value: Any) { owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value) }
    }
}
