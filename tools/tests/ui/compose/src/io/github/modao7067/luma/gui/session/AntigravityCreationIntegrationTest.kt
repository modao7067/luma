package io.github.modao7067.luma.gui.session

import android.app.Application
import kotlinx.coroutines.*
import io.github.modao7067.luma.gui.agent.NativeCliClient
import io.github.modao7067.luma.gui.runtime.AgentRuntime
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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
class AntigravityCreationIntegrationTest {
    private class Paused : CoroutineDispatcher() { override fun dispatch(context: CoroutineContext, block: Runnable) {} }
    private class Fixture : AutoCloseable {
        val app: Application = RuntimeEnvironment.getApplication()
        val controller = SessionController(app)
        private val scope = CoroutineScope(SupervisorJob() + Paused())
        init { (field(controller, "scope") as CoroutineScope).cancel(); set(controller, "scope", scope) }
        fun record(id: String): SessionRecord = field((field(controller, "sessions") as Map<*, *>)[id]!!, "record") as SessionRecord
        fun gui(): SessionRecord {
            controller.createCliSession("Antigravity", "", controller.environment, "/workspace", "")
            return record(controller.state.value.selected!!.summary.id)
        }
        fun blank(): SessionRecord {
            (field(controller, "store") as SessionStore).navigation.blank.cli = "Antigravity"
            controller.draft("first Antigravity input"); controller.send()
            return record(controller.state.value.selected!!.summary.id)
        }
        override fun close() { scope.cancel() }
    }
    private class Carrier(private val health: JSONObject?, private val snapshot: JSONObject) : AutoCloseable {
        val calls = CopyOnWriteArrayList<Pair<String, JSONObject?>>()
        private val listener = ServerSocket(0, 3, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 5000 }
        val client = NativeCliClient("http://127.0.0.1:${listener.localPort}", "fixture-token")
        private val worker = Thread {
            repeat(if (health == null) 1 else if (health.has("nativeProtocols")) 2 else 1) {
                listener.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream().bufferedReader(); val first = input.readLine()
                    var length = 0; var authorized = false
                    while (true) {
                        val line = input.readLine(); if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                        if (line.equals("Authorization: Bearer fixture-token", true)) authorized = true
                    }
                    check(authorized)
                    val chars = CharArray(length); var n = 0; while (n < length) n += input.read(chars, n, length - n)
                    val body = if (length == 0) null else JSONObject(String(chars))
                    calls += first to body
                    val response = if (first.startsWith("GET /health ")) health!! else snapshot
                    val bytes = response.toString().toByteArray()
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                    socket.getOutputStream().write(bytes)
                }
            }
        }.apply { isDaemon = true; start() }
        fun finish() { worker.join(6000); assertFalse("fixture carrier must finish", worker.isAlive) }
        override fun close() { client.close(); listener.close(); worker.join(1000) }
    }
    @Test fun ordinaryGuiAndBlankFirstSendBothChooseTheSameExplicitNativeProtocol() = Fixture().use { f ->
        val gui = f.gui(); assertEquals("antigravity-agy-acp", gui.nativeProtocol)
        f.controller.archiveSession(gui.id)
        assertEquals("Antigravity", f.controller.state.value.blank.cli)
        val blank = f.blank(); assertEquals("antigravity-agy-acp", blank.nativeProtocol)
        assertEquals("first Antigravity input", blank.draft)
    }
    @Test fun ordinaryGuiRecordRejectsLegacyBridgeBeforeBrowserBindingOrAnyConnectPost() = Fixture().use { f ->
        val record = f.gui(); var bindings = 0
        val session = NativeCliSession(record, AgentRuntime(f.app), {}, { bindings++; "/owned/browser" }, { _, _ -> }, {})
        Carrier(JSONObject("""{"protocol":1,"clis":["Antigravity"]}"""), JSONObject()).use { carrier ->
            val failure = try { runBlocking { session.connectClient(carrier.client) }; null } catch (e: IllegalStateException) { e }
            assertNotNull(failure); assertTrue(failure!!.message!!.contains("未声明 antigravity-agy-acp"))
            carrier.finish(); assertEquals(listOf("GET /health HTTP/1.1"), carrier.calls.map { it.first })
            assertEquals(0, bindings); assertNull(record.threadId); assertFalse(record.creationUnknown)
        }
        session.close()
    }
    @Test fun ordinaryGuiRecordPostsExplicitCliProtocolAfterMatchingHealth() = Fixture().use { f ->
        val record = f.gui()
        val session = NativeCliSession(record, AgentRuntime(f.app), {}, { "/owned/browser" }, { _, _ -> }, {})
        val health = JSONObject("""{"protocol":1,"nativeProtocols":{"Antigravity":["antigravity-agy-acp","antigravity-cli-stream-json","antigravity-acp"]}}""")
        val snapshot = JSONObject("""{"nativeProtocol":"antigravity-agy-acp","nativeId":"native-fixture","revision":1,"connected":true}""")
        Carrier(health, snapshot).use { carrier ->
            runBlocking { session.connectClient(carrier.client) }; carrier.finish()
            assertEquals(listOf("GET /health HTTP/1.1", "POST /sessions/connect HTTP/1.1"), carrier.calls.map { it.first })
            val posted = carrier.calls.last().second!!
            assertEquals(record.id, posted.getString("id")); assertEquals("Antigravity", posted.getString("cli"))
            assertEquals("antigravity-agy-acp", posted.getString("nativeProtocol")); assertFalse(posted.has("nativeId"))
            assertEquals("native-fixture", record.threadId)
        }
        session.close()
    }
    @Test fun knownLegacyAcpIdKeepsTheOldPostContractWithoutANewCapabilityPrerequisite() = Fixture().use { f ->
        val record = ManagedSessionPolicy.newRecord("legacy", "Antigravity", "", f.controller.environment, "/workspace", "", "known-acp")
        val session = NativeCliSession(record, AgentRuntime(f.app), {}, { "/owned/browser" }, { _, _ -> }, {})
        val snapshot = JSONObject("""{"nativeProtocol":"antigravity-acp","nativeId":"known-acp","revision":1,"connected":true}""")
        Carrier(null, snapshot).use { carrier ->
            runBlocking { session.connectClient(carrier.client) }; carrier.finish()
            assertEquals(listOf("POST /sessions/connect HTTP/1.1"), carrier.calls.map { it.first })
            assertFalse(carrier.calls.single().second!!.has("nativeProtocol"))
            assertEquals("known-acp", record.threadId); assertEquals("antigravity-acp", record.nativeProtocol)
        }
        session.close()
    }
    @Test fun managementDefaultAndExplicitAcpUseTheSameCreationPolicy() = Fixture().use { f ->
        fun create(key: String, protocol: String? = null): SessionRecord {
            val request = JSONObject().put("action", "create").put("requestId", key).put("cli", "Antigravity").put("cwd", "/workspace")
            if (protocol != null) request.put("nativeProtocol", protocol)
            val reply = runBlocking { f.controller.manage(request) }
            return f.record(reply.getString("id"))
        }
        assertEquals("antigravity-agy-acp", create("default").nativeProtocol)
        assertEquals("antigravity-cli-stream-json", create("stream-json", "antigravity-cli-stream-json").nativeProtocol)
        assertEquals("antigravity-acp", create("explicit", "antigravity-acp").nativeProtocol)
        assertNull(f.controller.state.value.selected)
    }
    companion object {
        private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
        private fun set(owner: Any, name: String, value: Any) { owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value) }
    }
}
