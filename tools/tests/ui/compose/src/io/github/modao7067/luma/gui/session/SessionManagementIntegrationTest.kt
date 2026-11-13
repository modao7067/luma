package io.github.modao7067.luma.gui.session

import android.app.Application
import android.os.Looper
import kotlinx.coroutines.*
import io.github.modao7067.luma.gui.agent.NativeCliClient
import io.github.modao7067.luma.gui.LumaApplication
import io.github.modao7067.luma.gui.runtime.AgentRuntime
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.*
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SessionManagementIntegrationTest {
    private class Paused : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {  }
    }
    private class Fixture : AutoCloseable {
        val app: Application = RuntimeEnvironment.getApplication()
        val controller = SessionController(app)
        var scope = CoroutineScope(SupervisorJob() + Paused())
        init {
            (field(controller, "scope") as CoroutineScope).cancel()
            set(controller, "scope", scope)
        }
        fun call(raw: String) = call(JSONObject(raw))
        fun call(request: JSONObject): JSONObject = runBlocking { controller.manage(request) }
        fun create(requestId: String, cli: String = "pi", title: String = "") = call(JSONObject()
            .put("action", "create").put("cli", cli).put("cwd", "/work").put("requestId", requestId).put("title", title))
        fun live(id: String): Any = (field(controller, "sessions") as Map<*, *>)[id]!!
        fun record(id: String) = field(live(id), "record") as SessionRecord
        fun publish() { controller.javaClass.getDeclaredMethod("publish").apply { isAccessible = true }.invoke(controller) }
        fun persist() { controller.javaClass.getDeclaredMethod("persist").apply { isAccessible = true }.invoke(controller) }
        fun enableDispatch() {
            scope.cancel(); scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            set(controller, "scope", scope)
        }
        override fun close() { scope.cancel() }
    }
    @Test fun createAttachAndRenameAreBackgroundLocalAndIdempotent() = Fixture().use { f ->
        val first = f.create("create-1", title = "本地别名"); val id = first.getString("id")
        assertNull(f.controller.state.value.selected)
        assertEquals(id, f.create("create-1", title = "本地别名").getString("id"))
        assertThrows(Exception::class.java) { f.create("create-1", title = "different payload") }
        assertEquals(1, f.controller.state.value.sessions.size)
        assertNull(f.record(id).initialNativeTitle); assertEquals("本地别名", f.record(id).manualTitle)
        f.controller.openSession(id); f.controller.draft("用户草稿")
        f.create("create-2", cli = "Codex")
        assertEquals(id, f.controller.state.value.selected!!.summary.id)
        assertEquals("用户草稿", f.controller.state.value.selected!!.draft)
        f.record(id).threadId = "native-pi-id"
        val attached = f.call("""{"action":"attach","cli":"pi","cwd":"/work","nativeId":"native-pi-id"}""")
        assertTrue(attached.getBoolean("reused")); assertEquals(id, attached.getJSONObject("session").getString("id"))
        assertEquals(2, f.controller.state.value.sessions.size)
        f.call(JSONObject().put("action", "rename").put("id", id).put("title", "离线改名"))
        SessionTitlePolicy.nativeTitle(f.record(id), "原生新标题"); f.publish()
        assertEquals("离线改名", f.record(id).title)
        assertThrows(Exception::class.java) { f.call(JSONObject().put("action", "rename").put("id", id).put("title", "  ")) }
        assertEquals("离线改名", f.record(id).title)
        assertThrows(Exception::class.java) { f.call("""{"action":"attach","cli":"pi","cwd":"/work","nativeId":"/work/sessions/file.jsonl"}""") }
        val journal = SessionCommandStore(java.io.File(f.app.filesDir, "session-commands.json"))
        assertNull(journal.error); assertEquals(id, journal.entries.getValue("create-1").sessionId)
    }

    @Test fun sendUsesNativeReceiptAndRetainsRequestIdentityAndTheUsersDraft() = sendFixture(false)
    @Test fun droppedNativeReceiptStaysUnknownAndIsNeverReplayed() = sendFixture(true)

    @Test @Config(application = LumaApplication::class)
    fun installedWrapperAuthenticatesIntoTheAppControllerWithoutOpeningASessionScreen() {
        val app = RuntimeEnvironment.getApplication() as LumaApplication
        val controller = field(app, "sessions\$delegate") as Lazy<*>
        assertFalse(controller.isInitialized())
        val script = java.io.File(app.filesDir, "runtime/bin/luma-sessions")
        val binding = java.io.File(app.filesDir, "${io.github.modao7067.luma.gui.GuiProduct.agentRuntimePath()}/sessions/binding.json")
        assertTrue(script.isFile); assertTrue(binding.isFile)
        val process = ProcessBuilder("python3", script.absolutePath, "{\"action\":\"list\"}").apply {
            environment()["LUMA_SESSIONS_CONFIG"] = binding.absolutePath
        }.start()
        try {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (process.isAlive && System.nanoTime() < deadline) {
                Thread.sleep(10); Shadows.shadowOf(Looper.getMainLooper()).idle()
            }
            assertFalse(process.isAlive); assertEquals(0, process.exitValue())
            val result = JSONObject(process.inputStream.bufferedReader().readText())
            assertTrue(result.getBoolean("ok"))
            assertEquals(CliCatalog.ids, result.getJSONArray("clis").let { a -> (0 until a.length()).map { a.getString(it) } })
            assertEquals(0, result.getJSONArray("sessions").length()); assertFalse(result.has("token"))
            assertTrue(controller.isInitialized())
        } finally {
            if (process.isAlive) process.destroy()
            ((field(app, "management\$delegate") as Lazy<*>).value as SessionManagementBinding).transport.close()
            (field(app, "managementScope") as CoroutineScope).cancel()
            if (controller.isInitialized()) (field(controller.value!!, "scope") as CoroutineScope).cancel()
        }
    }

    @Test fun antigravityProtocolIsPartOfIdentityAndSurvivesLocalIndexReload() = Fixture().use { f ->
        val cliId = f.create("create-agy", "Antigravity").getString("id")
        f.record(cliId).threadId = "same-native-uuid"
        assertEquals("antigravity-agy-acp", f.record(cliId).nativeProtocol)
        val agyAcpAttach = f.call("""{"action":"attach","cli":"Antigravity","cwd":"/work","nativeId":"same-native-uuid","nativeProtocol":"antigravity-agy-acp"}""")
        assertTrue(agyAcpAttach.getBoolean("reused")); assertEquals(cliId, agyAcpAttach.getJSONObject("session").getString("id"))
        val streamAttach = f.call("""{"action":"attach","cli":"Antigravity","cwd":"/work","nativeId":"same-native-uuid","nativeProtocol":"antigravity-cli-stream-json"}""")
        assertFalse(streamAttach.getBoolean("reused")); assertNotEquals(cliId, streamAttach.getJSONObject("session").getString("id"))
        val acpAttach = f.call("""{"action":"attach","cli":"Antigravity","cwd":"/work","nativeId":"same-native-uuid","nativeProtocol":"antigravity-acp"}""")
        assertFalse(acpAttach.getBoolean("reused")); assertNotEquals(cliId, acpAttach.getJSONObject("session").getString("id"))
        val restored = SessionStore(f.app).load()
        assertEquals(setOf("antigravity-agy-acp", "antigravity-cli-stream-json", "antigravity-acp"), restored.map { it.nativeProtocol }.toSet())
        assertEquals("antigravity-agy-acp", f.call("""{"action":"list"}""").getJSONObject("nativeProtocols").getJSONArray("Antigravity").getString(0))
    }

    @Test fun rootExternalAttachRepairsOnlyExplicitFailedReferenceAndPreservesNavigationAndDraft() {
        org.junit.Assume.assumeTrue(io.github.modao7067.luma.gui.GuiProduct.isRoot)
        Fixture().use { f ->
            fun attach(native: String, socket: String? = null, id: String? = null) = f.call(JSONObject()
                .put("action", "attach").put("cli", "Codex").put("cwd", "/work").put("nativeId", native)
                .apply { if (socket != null) put("externalSocket", socket); if (id != null) put("id", id) })
            val socket = "/tmp/codex-fixture.sock"
            val failed = attach("native-1").getJSONObject("session").getString("id")
            f.controller.openSession(failed); f.controller.draft("保留草稿")
            val repaired = attach("native-1", socket, failed)
            assertTrue(repaired.getBoolean("reused")); assertEquals(failed, repaired.getJSONObject("session").getString("id"))
            assertEquals(socket, repaired.getJSONObject("session").getString("externalSocket"))
            assertEquals("保留草稿", f.record(failed).draft)
            assertEquals(failed, f.controller.state.value.selected!!.summary.id)
            assertEquals(1, f.controller.state.value.sessions.size)
            assertEquals(socket, SessionStore(f.app).load().single().externalSocket)
            assertEquals(failed, attach("native-1", socket, failed).getJSONObject("session").getString("id"))
            assertThrows(Exception::class.java) { attach("native-2", socket, failed) }
            assertThrows(Exception::class.java) { attach("native-1", "/tmp/other-owner.sock", failed) }
            val nonCodex = f.create("other-cli", "pi").getString("id")
            assertThrows(Exception::class.java) { attach("native-1", socket, nonCodex) }
        }
    }
    @Test fun rootExternalAttachReusesAnExistingExternalIdentityRatherThanCreatingAThirdReference() {
        org.junit.Assume.assumeTrue(io.github.modao7067.luma.gui.GuiProduct.isRoot)
        Fixture().use { f ->
            val request = JSONObject().put("action", "attach").put("cli", "Codex").put("cwd", "/work").put("nativeId", "native-1")
            val failed = f.call(request).getJSONObject("session").getString("id")
            request.put("externalSocket", "/tmp/codex-fixture.sock")
            val external = f.call(request).getJSONObject("session").getString("id")
            assertNotEquals(failed, external)
            request.put("id", failed)
            val result = f.call(request)
            assertTrue(result.getBoolean("reused")); assertEquals(external, result.getJSONObject("session").getString("id"))
            assertEquals(2, f.controller.state.value.sessions.size)
            assertNull(f.record(failed).externalSocket)
        }
    }

    private fun sendFixture(drop: Boolean) = Fixture().use { f ->
        val id = f.create("create-target").getString("id"); val r = f.record(id)
        r.threadId = "native-pi-id"; r.draft = "用户未发的草稿"
        r.images += StoredImage("user-image", "private://image", "/user/image", "image")
        r.skills += DraftSkill("user-skill", "Skill", "/skill")
        val live = f.live(id)
        val ready = SessionView(SessionSummary(id, r.title, r.environment, r.cwd, cli = "pi"), connected = true)
        set(live, "view", ready)
        val requests = CopyOnWriteArrayList<JSONObject>()
        ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { listener ->
            listener.soTimeout = 5000
            val worker = Thread {
                listener.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream().bufferedReader()
                    assertEquals("POST /sessions/$id/send HTTP/1.1", input.readLine())
                    var length = 0; var authenticated = false
                    while (true) {
                        val line = input.readLine(); if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                        if (line.equals("Authorization: Bearer fixture-token", true)) authenticated = true
                    }
                    assertTrue(authenticated)
                    val chars = CharArray(length); var count = 0
                    while (count < length) count += input.read(chars, count, length - count)
                    val body = JSONObject(String(chars)); requests += body
                    if (!drop) {
                        val response = JSONObject().put("connected", true).put("nativeId", r.threadId).put("revision", 1)
                            .put("receipts", JSONObject().put(body.getString("submissionId"), "accepted"))
                            .put("messages", JSONArray().put(JSONObject().put("id", body.getString("submissionId")).put("role", "user").put("text", body.getString("text"))))
                        val bytes = response.toString().toByteArray()
                        socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                        socket.getOutputStream().write(bytes)
                    }
                }
            }.apply { isDaemon = true; start() }
            val native = NativeCliSession(r, AgentRuntime(f.app), f::persist, { error("No browser bind should be needed for the same native ID") },
                { view, _ -> set(live, "view", view); f.publish() }, {})
            val api = NativeCliClient("http://127.0.0.1:${listener.localPort}", "fixture-token")
            set(native, "api", api); set(native, "view", ready); set(live, "nativeCli", native)
            f.enableDispatch()
            val send = JSONObject().put("action", "send").put("id", id).put("text", "agent task").put("requestId", "stable-send")
            f.call(send)
            val deadline = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < deadline && (requests.isEmpty() || if (drop) r.submissions.lastOrNull()?.state != "unknown" else r.submissionId != null)) {
                Thread.sleep(10); Shadows.shadowOf(Looper.getMainLooper()).idle()
            }
            worker.join(1000); assertFalse(worker.isAlive)
            assertEquals(1, requests.size); assertEquals("agent task", requests.single().getString("text"))
            assertEquals(0, requests.single().getJSONArray("images").length()); assertEquals(0, requests.single().getJSONArray("skills").length())
            if (drop) { assertFalse(SubmissionLedger.expire(r, r.submissionDeadlineMs)); f.publish() }
            val result = f.call(send)
            assertEquals(if (drop) "unknown" else "accepted", result.getString("state"))
            assertEquals(!drop, result.getBoolean("accepted")); assertEquals(1, requests.size)
            assertEquals("用户未发的草稿", r.draft); assertEquals(1, r.images.size); assertEquals(1, r.skills.size)
            val saved = SessionCommandStore(java.io.File(f.app.filesDir, "session-commands.json"))
            assertEquals(result.getString("state"), saved.entries.getValue("stable-send").state)
            native.close(); api.close()
        }
    }
    companion object {
        private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
        private fun set(owner: Any, name: String, value: Any) { owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value) }
    }
}
