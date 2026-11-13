package io.github.modao7067.luma.gui.session

import android.app.Application
import kotlinx.coroutines.runBlocking
import io.github.modao7067.luma.gui.agent.*
import io.github.modao7067.luma.gui.runtime.AgentRuntime
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class OpenCodeSubAgentReadonlyIntegrationTest {
    private class Carrier : AutoCloseable {
        val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val calls = CopyOnWriteArrayList<String>()
        @Volatile var closed = false
        @Volatile var parent = "ses_parent"
        @Volatile var output = "Native child output"
        private fun assistant() = obj("id" to "output", "type" to "assistant", "time" to obj("completed" to 2), "content" to JSONArray().put(obj("type" to "reasoning", "text" to "Native child reasoning")).put(obj("type" to "text", "text" to output)))
        val worker = Thread {
            while (!closed) try { listener.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                val first = reader.readLine() ?: return@use
                while (reader.readLine().isNotEmpty()) {}
                calls += first; check(first.startsWith("GET ")) { "Read-only child made a mutation: $first" }
                val path = first.split(' ')[1]
                val response = when {
                    path.endsWith("/message/output") -> obj("data" to assistant())
                    path.contains("cursor=") -> obj("data" to JSONArray().put(assistant()))
                    path.contains("/message?") -> obj("data" to JSONArray().put(obj("id" to "task", "type" to "user", "text" to "Native child task")), "cursor" to obj("next" to "page-2"))
                    path.startsWith("/api/session?parentID=") -> obj("data" to JSONArray().put(obj("id" to "ses_child", "parentID" to parent, "title" to "Discovered child", "agent" to "general", "time" to obj("updated" to 1, "busy" to 2), "model" to obj("providerID" to "fixture", "id" to "child", "variant" to "high"))))
                    path.endsWith("/inbox") || path.endsWith("/permission") || path.endsWith("/form") -> obj("data" to JSONArray())
                    path == "/api/session/active" -> obj("data" to obj("ses_child" to true))
                    path == "/api/session/ses_parent" -> obj("data" to obj("id" to "ses_parent", "title" to "Parent"))
                    else -> obj("data" to obj("id" to "ses_child", "parentID" to parent, "time" to obj("updated" to 1), "model" to obj("providerID" to "fixture", "id" to "child", "variant" to "high")))
                }
                val bytes = response.toString().toByteArray()
                socket.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(bytes); flush() }
            } } catch (failure: Exception) { if (!closed) throw failure }
        }.apply { isDaemon = true; start() }
        override fun close() { closed = true; listener.close(); worker.join(1000) }
    }
    @Test fun existingParentClientReadsAllChildPagesAndSseInvalidationsUseSingleMessageGetWithoutControls() = runBlocking {
        Carrier().use { native ->
            val app: Application = RuntimeEnvironment.getApplication()
            val record = SessionRecord("parent-local", "Fixture", "noroot", "/workspace", cli = "OpenCode", threadId = "ses_parent")
            val session = OpenCodeSession(record, AgentRuntime(app), {}, { "unused" }, { _, _ -> }, {})
            val api = OpenCodeClient("http://127.0.0.1:${native.listener.localPort}", "public-fixture")
            set(session, "client", api)
            set(session, "view", SessionView(SessionSummary(record.id, record.title, record.environment, record.cwd), connected = true, subAgents = listOf(SubAgentState("ses_child", task = "Native child task", status = "running"))))
            try {
                var detail = session.readSubAgent("ses_child")
                assertEquals(listOf("Native child task", "Native child reasoning", "Native child output"), detail.messages.map { it.detail.ifEmpty { it.text } })
                assertEquals("fixture/child", detail.modelLabel); assertEquals("high", detail.effort)
                assertEquals(2, native.calls.count { it.contains("/message?") })
                session.readSubAgent("ses_child")
                assertEquals(2, native.calls.count { it.contains("/message?") })
                native.output = "Native updated output"
                session.javaClass.getDeclaredMethod("invalidate", JSONObject::class.java).apply { isAccessible = true }.invoke(session,
                    obj("type" to "session.text.delta", "data" to obj("sessionID" to "ses_child", "assistantMessageID" to "output")))
                detail = session.readSubAgent("ses_child")
                assertEquals("Native updated output", detail.messages.last().text)
                assertEquals(1, native.calls.count { it.contains("/message/output") })
                assertEquals(2, native.calls.count { it.contains("/message?") })
                session.closeSubAgentRead("ses_child")
                assertEquals("ses_child", api.call("/api/session/ses_child").getJSONObject("data").getString("id"))
                assertTrue(native.calls.all { it.startsWith("GET ") })
                assertTrue(native.calls.none { it.contains("/permission") || it.contains("/form") || it.contains("/interrupt") })
            } finally { session.close() }
        }
    }
    @Test fun mismatchedParentMetadataStopsBeforeAnyChildMessageRead() = runBlocking {
        Carrier().use { native ->
            native.parent = "ses_foreign"
            val app: Application = RuntimeEnvironment.getApplication()
            val record = SessionRecord("local", "Fixture", "noroot", "/workspace", cli = "OpenCode", threadId = "ses_parent")
            val session = OpenCodeSession(record, AgentRuntime(app), {}, { "unused" }, { _, _ -> }, {})
            set(session, "client", OpenCodeClient("http://127.0.0.1:${native.listener.localPort}", "public-fixture"))
            set(session, "view", SessionView(SessionSummary(record.id, record.title, record.environment, record.cwd), connected = true, subAgents = listOf(SubAgentState("ses_child"))))
            try { session.readSubAgent("ses_child"); fail("Foreign child was accepted") } catch (_: IllegalStateException) {}
            finally { session.close() }
            assertEquals(1, native.calls.size); assertFalse(native.calls.single().contains("/message"))
        }
    }
    @Test fun refreshDiscoversNativeChildrenByParentIdEvenWithoutToolMetadata() = runBlocking {
        Carrier().use { native ->
            val app: Application = RuntimeEnvironment.getApplication()
            val record = SessionRecord("local-discover", "Fixture", "noroot", "/workspace", cli = "OpenCode", threadId = "ses_parent")
            var latestView: SessionView? = null
            val session = OpenCodeSession(record, AgentRuntime(app), {}, { "unused" }, { v, _ -> latestView = v }, {})
            set(session, "client", OpenCodeClient("http://127.0.0.1:${native.listener.localPort}", "public-fixture"))
            try {
                kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Unit> { cont ->
                    session.javaClass.getDeclaredMethod("refresh", Boolean::class.javaPrimitiveType, List::class.java, kotlin.coroutines.Continuation::class.java)
                        .apply { isAccessible = true }.invoke(session, true, emptyList<String>(), cont)
                }
                session.javaClass.getDeclaredMethod("publish").apply { isAccessible = true }.invoke(session)
                val agent = latestView!!.subAgents.single()
                assertEquals("ses_child", agent.id)
                assertEquals("Discovered child", agent.name)
                assertEquals("general", agent.role)
                assertEquals("running", agent.status)
                assertTrue(native.calls.any { it.contains("/api/session?parentID=ses_parent") })
            } finally { session.close() }
        }
    }
    private fun set(owner: Any, name: String, value: Any) { owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value) }
}
