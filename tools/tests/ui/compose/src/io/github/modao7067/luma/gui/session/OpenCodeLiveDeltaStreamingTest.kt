package io.github.modao7067.luma.gui.session

import android.app.Application
import kotlinx.coroutines.delay
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import android.os.Looper
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class OpenCodeLiveDeltaStreamingTest {
    private class EphemeralCarrier : AutoCloseable {
        val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val calls = CopyOnWriteArrayList<String>()
        @Volatile var closed = false
        @Volatile var persistedReasoning = ""
        @Volatile var persistedText = ""
        @Volatile var stepStreamed = false

        private fun turnMessage() = obj(
            "id" to "msg_turn",
            "type" to "assistant",
            "time" to (if (stepStreamed) obj("created" to 1, "completed" to 2) else obj("created" to 1)),
            "content" to JSONArray()
                .put(obj("type" to "reasoning", "text" to persistedReasoning, "time" to (if (stepStreamed) obj("created" to 1, "completed" to 2) else obj("created" to 1))))
                .put(obj("type" to "text", "text" to persistedText)),
        )

        val worker = Thread {
            while (!closed) try {
                listener.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    val first = reader.readLine() ?: return@use
                    while (reader.readLine().isNotEmpty()) {}
                    calls += first
                    val path = first.split(' ')[1]
                    val response = when {
                        path.endsWith("/message/msg_turn") -> obj("data" to turnMessage())
                        path.contains("/message?") -> obj(
                            "data" to JSONArray()
                                .put(obj("id" to "msg_hist", "type" to "user", "text" to "Historical prompt"))
                                .put(turnMessage()),
                        )
                        path.startsWith("/api/session?parentID=") || path.endsWith("/inbox") || path.endsWith("/permission") || path.endsWith("/form") -> obj("data" to JSONArray())
                        path == "/api/session/active" -> obj("data" to obj("ses_parent" to true))
                        else -> obj("data" to obj("id" to "ses_parent", "title" to "Parent"))
                    }
                    val bytes = response.toString().toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes)
                        flush()
                    }
                }
            } catch (failure: Exception) {
                if (!closed) throw failure
            }
        }.apply { isDaemon = true; start() }

        override fun close() {
            closed = true
            listener.close()
            worker.join(1000)
        }
    }

    @Test fun ephemeralReasoningAndTextDeltasStreamLiveWithoutPerDeltaRestPolls() = runBlocking {
        EphemeralCarrier().use { native ->
            val app: Application = RuntimeEnvironment.getApplication()
            val record = SessionRecord("local-stream", "Stream", "noroot", "/workspace", cli = "OpenCode", threadId = "ses_parent")
            var latestView: SessionView? = null
            val session = OpenCodeSession(record, AgentRuntime(app), {}, { "unused" }, { v, _ -> latestView = v }, {})
            set(session, "client", OpenCodeClient("http://127.0.0.1:${native.listener.localPort}", "public-fixture"))
            val invalidate = session.javaClass.getDeclaredMethod("invalidate", JSONObject::class.java).apply { isAccessible = true }
            val refresh = session.javaClass.getDeclaredMethod("refresh", Boolean::class.javaPrimitiveType, List::class.java, kotlin.coroutines.Continuation::class.java).apply { isAccessible = true }
            val publish = session.javaClass.getDeclaredMethod("publish").apply { isAccessible = true }
            try {
                kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Unit> { cont ->
                    refresh.invoke(session, true, emptyList<String>(), cont)
                }
                val currentView = session.javaClass.getDeclaredField("view").apply { isAccessible = true }.get(session) as SessionView
                set(session, "view", currentView.copy(connected = true))
                publish.invoke(session)
                val callsAfterHydration = native.calls.size

                for (piece in listOf("First ", "thinking ", "step.")) {
                    invalidate.invoke(session, obj("type" to "session.reasoning.delta",
                        "data" to obj("sessionID" to "ses_parent", "assistantMessageID" to "msg_turn", "ordinal" to 0, "delta" to piece)))
                }
                for (piece in listOf("Final ", "answer.")) {
                    invalidate.invoke(session, obj("type" to "session.text.delta",
                        "data" to obj("sessionID" to "ses_parent", "assistantMessageID" to "msg_turn", "ordinal" to 0, "delta" to piece)))
                }

                val deadline = System.nanoTime() + 1_500_000_000L
                while (System.nanoTime() < deadline && latestView?.messages?.lastOrNull()?.text != "Final answer.") {
                    shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(60))
                    delay(10)
                }
                shadowOf(Looper.getMainLooper()).idle()

                val streamed = checkNotNull(latestView)
                val reasoning = streamed.messages.first { it.kind == "reasoning" }
                val answer = streamed.messages.last { it.role == "assistant" }
                assertEquals("First thinking step.", reasoning.detail)
                assertEquals("running", reasoning.outcome)
                assertEquals("Final answer.", answer.text)
                assertEquals(callsAfterHydration, native.calls.size)

                invalidate.invoke(session, obj("type" to "session.reasoning.ended",
                    "data" to obj("sessionID" to "ses_parent", "assistantMessageID" to "msg_turn", "ordinal" to 0, "text" to "First thinking step.")))
                val endDeadline = System.nanoTime() + 1_500_000_000L
                while (System.nanoTime() < endDeadline && latestView.messages.firstOrNull { it.kind == "reasoning" }?.outcome != "succeeded") {
                    shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(60))
                    delay(10)
                }
                val afterReasoningEnded = checkNotNull(latestView).messages.first { it.kind == "reasoning" }
                assertEquals("First thinking step.", afterReasoningEnded.detail)
                assertEquals("succeeded", afterReasoningEnded.outcome)

                kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Unit> { cont ->
                    refresh.invoke(session, false, listOf("msg_turn"), cont)
                }
                publish.invoke(session)
                assertEquals("First thinking step.", checkNotNull(latestView).messages.first { it.kind == "reasoning" }.detail)
                assertEquals("Final answer.", checkNotNull(latestView).messages.last { it.role == "assistant" }.text)

                native.persistedReasoning = "First thinking step."
                native.persistedText = "Final answer."
                native.stepStreamed = true
                kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Unit> { cont ->
                    refresh.invoke(session, false, listOf("msg_turn"), cont)
                }
                publish.invoke(session)
                assertEquals("First thinking step.", checkNotNull(latestView).messages.first { it.kind == "reasoning" }.detail)
                assertEquals("Final answer.", checkNotNull(latestView).messages.last { it.role == "assistant" }.text)
            } finally {
                session.close()
            }
        }
    }

    @Test fun disconnectedHydrationNeverAccumulatesEphemeralDeltas() = runBlocking {
        EphemeralCarrier().use { native ->
            val app: Application = RuntimeEnvironment.getApplication()
            val record = SessionRecord("local-hydrate", "Hydrate", "noroot", "/workspace", cli = "OpenCode", threadId = "ses_parent")
            var latestView: SessionView? = null
            val session = OpenCodeSession(record, AgentRuntime(app), {}, { "unused" }, { v, _ -> latestView = v }, {})
            set(session, "client", OpenCodeClient("http://127.0.0.1:${native.listener.localPort}", "public-fixture"))
            val invalidate = session.javaClass.getDeclaredMethod("invalidate", JSONObject::class.java).apply { isAccessible = true }
            val refresh = session.javaClass.getDeclaredMethod("refresh", Boolean::class.javaPrimitiveType, List::class.java, kotlin.coroutines.Continuation::class.java).apply { isAccessible = true }
            val publish = session.javaClass.getDeclaredMethod("publish").apply { isAccessible = true }
            try {
                invalidate.invoke(session, obj("type" to "session.text.delta",
                    "data" to obj("sessionID" to "ses_parent", "assistantMessageID" to "msg_turn", "ordinal" to 0, "delta" to "partial-mid-stream")))
                kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Unit> { cont ->
                    refresh.invoke(session, true, emptyList<String>(), cont)
                }
                publish.invoke(session)
                assertEquals("", checkNotNull(latestView).messages.last { it.role == "assistant" }.text)
            } finally {
                session.close()
            }
        }
    }

    private fun set(owner: Any, name: String, value: Any) {
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value)
    }
}
