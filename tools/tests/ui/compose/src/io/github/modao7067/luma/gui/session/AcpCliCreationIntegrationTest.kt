package io.github.modao7067.luma.gui.session

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.*
import io.github.modao7067.luma.gui.agent.NativeCliClient
import io.github.modao7067.luma.gui.runtime.AgentRuntime
import io.github.modao7067.luma.gui.runtime.NativeCliRuntimePolicy
import org.json.JSONArray
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
class AcpCliCreationIntegrationTest {
    @Test fun noRootBridgeAllowsTheNewStableTaskWithoutStartingAServiceAndStillRejectsOtherTasks() {
        org.junit.Assume.assumeFalse(io.github.modao7067.luma.gui.GuiProduct.isRoot)
        val bridge = Class.forName("io.github.modao7067.luma.GuiRuntimeBridge").getMethod("connectNativeCli", Context::class.java,
            String::class.java, String::class.java, String::class.java, String::class.java)
        fun connect(context: Context, task: String) {
            try { bridge.invoke(null, context, "/private/main.py", "/private/token", "/private/sessions", task) }
            catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.targetException }
        }
        class ServiceBoundary : RuntimeException()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = throw ServiceBoundary()
        }
        val tasks = (CliCatalog.bridged.map(NativeCliRuntimePolicy::task)).toSet()
        tasks.forEach { task ->
            assertThrows(ServiceBoundary::class.java) {
                connect(context, task)
            }
        }
        assertThrows(java.io.IOException::class.java) {
            connect(context, "unrelated-task")
        }
    }
    private class Paused : CoroutineDispatcher() { override fun dispatch(context: CoroutineContext, block: Runnable) {} }
    private class Fixture : AutoCloseable {
        val app: Application = RuntimeEnvironment.getApplication()
        val controller = SessionController(app)
        private val scope = CoroutineScope(SupervisorJob() + Paused())
        init { (field(controller, "scope") as CoroutineScope).cancel(); set(controller, "scope", scope) }
        fun record(id: String) = field((field(controller, "sessions") as Map<*, *>)[id]!!, "record") as SessionRecord
        fun gui(cli: String): SessionRecord {
            controller.createCliSession(cli, "", controller.environment, "/workspace", "")
            return record(controller.state.value.selected!!.summary.id)
        }
        fun selected() = record(controller.state.value.selected!!.summary.id)
        override fun close() { scope.cancel() }
    }
    private class Carrier(val health: JSONObject, val response: JSONObject?, val posts: Boolean) : AutoCloseable {
        val calls = CopyOnWriteArrayList<Pair<String, JSONObject?>>()
        private val listener = ServerSocket(0, 3, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 5000 }
        val client = NativeCliClient("http://127.0.0.1:${listener.localPort}", "fixture-token")
        private val worker = Thread {
            repeat(if (posts) 2 else 1) {
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
                    calls += first to if (length == 0) null else JSONObject(String(chars))
                    val result = if (first.startsWith("GET /health ")) health else response!!
                    val bytes = result.toString().toByteArray()
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                    socket.getOutputStream().write(bytes)
                }
            }
        }.apply { isDaemon = true; start() }
        fun finish() { worker.join(6000); assertFalse(worker.isAlive) }
        override fun close() { client.close(); listener.close(); worker.join(1000) }
    }
    @Test fun ordinaryGuiCreationPostsTheExactCliAfterItsHealthGate() = Fixture().use { f ->
        NativeCliRuntimePolicy.isolatedAcpClis.forEach { cli ->
            val record = f.gui(cli)
            val session = NativeCliSession(record, AgentRuntime(f.app), {}, { "/owned/browser" }, { _, _ -> }, {})
            Carrier(JSONObject().put("protocol", 1).put("clis", JSONArray().put(cli)),
                JSONObject().put("nativeId", "native-${record.id}").put("revision", 1).put("connected", true), true).use { carrier ->
                runBlocking { session.connectClient(carrier.client) }; carrier.finish()
                assertEquals(listOf("GET /health HTTP/1.1", "POST /sessions/connect HTTP/1.1"), carrier.calls.map { it.first })
                val body = carrier.calls.last().second!!
                assertEquals(cli, body.getString("cli")); assertEquals(record.id, body.getString("id"))
                assertEquals("/workspace", body.getString("cwd")); assertEquals("/owned/browser", body.getString("browserConfig"))
                assertFalse(body.has("nativeProtocol")); assertFalse(body.has("nativeId")); assertFalse(body.has("initialTitle"))
                assertEquals("native-${record.id}", record.threadId)
            }
            session.close()
        }
    }
    @Test fun ordinaryGuiRecordRejectsAnOldBridgeBeforePostOrBrowserBinding() = Fixture().use { f ->
        NativeCliRuntimePolicy.isolatedAcpClis.forEach { cli ->
            val record = f.gui(cli); var bindings = 0
            val session = NativeCliSession(record, AgentRuntime(f.app), {}, { bindings++; "never" }, { _, _ -> }, {})
            Carrier(JSONObject("""{"protocol":1,"clis":["pi","Antigravity"]}"""), null, false).use { carrier ->
                val failure = try { runBlocking { session.connectClient(carrier.client) }; null } catch (e: IllegalStateException) { e }
                assertTrue(failure!!.message!!.contains("未提供 $cli")); carrier.finish()
                assertEquals(listOf("GET /health HTTP/1.1"), carrier.calls.map { it.first })
                assertEquals(0, bindings); assertNull(record.threadId); assertFalse(record.creationUnknown)
            }
            session.close()
        }
    }
    @Test fun archiveBlankFirstSendAndAgentCreateShareTheCatalogWithoutStealingDrafts() = Fixture().use { f ->
        NativeCliRuntimePolicy.isolatedAcpClis.forEach { cli ->
            val gui = f.gui(cli); f.controller.archiveSession(gui.id)
            assertEquals(cli, f.controller.state.value.blank.cli); assertNull(f.controller.state.value.selected)
            f.controller.draft("first $cli"); f.controller.send()
            val first = f.selected(); assertEquals(cli, first.cli); assertEquals("first $cli", first.draft)
            assertTrue(f.controller.state.value.selected!!.preparingFirstSend); assertNull(first.threadId)
            val request = JSONObject().put("action", "create").put("cli", cli).put("cwd", "/workspace").put("requestId", "managed-${gui.id}")
            val created = runBlocking { f.controller.manage(request) }
            assertEquals(cli, f.record(created.getString("id")).cli)
            assertEquals(first.id, f.selected().id); assertEquals("first $cli", first.draft)
            val listed = runBlocking { f.controller.manage(JSONObject().put("action", "list")) }.getJSONArray("clis")
            assertEquals(CliCatalog.ids, (0 until listed.length()).map { listed.getString(it) })
        }
    }
    @Test fun missingNativeCommandAndNativeConfigurationErrorsRemainVisible() = Fixture().use { f ->
        NativeCliRuntimePolicy.isolatedAcpClis.forEach { cli ->
            val record = f.gui(cli); var projected = SessionView(SessionSummary(record.id, record.title, record.environment, record.cwd, cli = cli))
            val session = NativeCliSession(record, AgentRuntime(f.app), {}, { "/owned/browser" }, { view, _ -> projected = view }, {})
            val error = "未找到 $cli；请在当前运行环境的普通终端安装并配置"
            Carrier(JSONObject().put("protocol", 1).put("clis", JSONArray().put(cli)),
                JSONObject().put("revision", 1).put("connected", false).put("connecting", false).put("error", error), true).use { carrier ->
                runBlocking { session.connectClient(carrier.client) }; carrier.finish()
                assertEquals(error, projected.error); assertFalse(projected.connected); assertFalse(projected.loading)
                assertNull(record.threadId)
            }
            session.close()
        }
    }
    companion object {
        private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
        private fun set(owner: Any, name: String, value: Any) { owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value) }
    }
}
