package io.github.modao7067.luma.gui.session

import android.app.Application
import android.os.Looper
import kotlinx.coroutines.*
import io.github.modao7067.luma.gui.agent.*
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class OpenCodeSendRecoveryIntegrationTest {
    private class Carrier(val mode: String, val store: Boolean) : AutoCloseable {
        val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val posts = AtomicInteger(); val gets = AtomicInteger()
        val postEntered = java.util.concurrent.CountDownLatch(1); val releasePost = java.util.concurrent.CountDownLatch(1)
        @Volatile var message: JSONObject? = null
        @Volatile var closed = false
        val worker = Thread {
            while (!closed) try { listener.accept().use { socket ->
                socket.soTimeout = 5000
                val input = socket.getInputStream().bufferedReader()
                val first = input.readLine() ?: return@use
                var length = 0
                while (true) { val line = input.readLine(); if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                }
                val chars = CharArray(length); var count = 0
                while (count < length) count += input.read(chars, count, length - count)
                val path = first.split(' ')[1]
                var status = 200
                val body = if (first.startsWith("POST")) {
                    posts.incrementAndGet(); postEntered.countDown()
                    if (mode == "held") releasePost.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    val p = JSONObject(String(chars))
                    if (store) message = obj("id" to p.getString("id"), "type" to "user", "text" to p.getString("text"))
                    if (mode == "500") { status = 500; "{\"message\":\"fixture unavailable\"}" }
                    else if (mode == "malformed") "{\"data\":{\"wrong\":true}}"
                    else "{\"data\":{\"id\":\"${p.getString("id")}\"}}"
                } else {
                    gets.incrementAndGet()
                    when {
                        path.contains("/message?") -> obj("data" to JSONArray().also { message?.let(it::put) }).toString()
                        path.endsWith("/inbox") || path.endsWith("/permission") || path.endsWith("/form") -> obj("data" to JSONArray()).toString()
                        path == "/api/session/active" -> obj("data" to obj("ses_fixture" to true)).toString()
                        else -> obj("data" to obj("id" to "ses_fixture", "title" to "fixture")).toString()
                    }
                }
                val data = body.toByteArray()
                val output = socket.getOutputStream()
                output.write("HTTP/1.1 $status Fixture\r\nContent-Type: application/json\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n".toByteArray())
                output.write(if (mode == "truncated" && first.startsWith("POST")) data.copyOf(data.size / 2) else data)
                output.flush()
            } } catch (failure: Exception) { if (!closed) throw failure }
        }.apply { isDaemon = true; start() }
        override fun close() { closed = true; releasePost.countDown(); listener.close(); worker.join(1500) }
    }
    @Test fun lostOrMalformedAdmissionAlwaysGetsReadOnlyReconciliationWithoutASecondPost() {
        for ((mode, stored) in listOf("truncated" to true, "truncated" to false, "500" to false, "malformed" to true)) {
            Carrier(mode, stored).use { carrier ->
                val app: Application = RuntimeEnvironment.getApplication()
                val record = SessionRecord("local-$mode-$stored", "fixture", "noroot", "/workspace", cli = "OpenCode", threadId = "ses_fixture")
                var projected = SessionView(SessionSummary(record.id, "fixture", "noroot", "/workspace", cli = "OpenCode"))
                val session = OpenCodeSession(record, AgentRuntime(app), {}, { "unused" }, { view, _ -> projected = SubmissionLedger.project(record, view) }, {})
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                val api = OpenCodeClient("http://127.0.0.1:${carrier.listener.localPort}", "public-fixture")
                set(session, "client", api)
                set(session, "view", projected.copy(connected = true, activeTurnId = "ses_fixture"))
                val refreshes = AtomicInteger()
                val sync = OpenCodeSync(scope, { true }, {}, { all, ids, _ ->
                    suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                        session.javaClass.getDeclaredMethod("refresh", Boolean::class.javaPrimitiveType, List::class.java, kotlin.coroutines.Continuation::class.java)
                            .apply { isAccessible = true }.invoke(session, all, ids, continuation)
                    }
                    session.javaClass.getDeclaredMethod("publish").apply { isAccessible = true }.invoke(session)
                    refreshes.incrementAndGet()
                }, {})
                set(session, "synchronizer", sync)
                try {
                    sync.start()
                    await { refreshes.get() == 1 }
                    val baselineGets = carrier.gets.get()
                    val baselineRefreshes = refreshes.get()
                    runBlocking { session.submitManaged(ManagedSubmission("msg_fixture", "same")) }
                    val before = field(session, "view") as SessionView
                    assertEquals("unknown", record.submissions.single().state)
                    assertNotNull("$mode must expose diagnostic state", before.error)
                    val until = System.nanoTime() + 10_000_000_000L
                    while ((refreshes.get() <= baselineRefreshes || stored && record.submissions.single().state != "accepted") && System.nanoTime() < until) {
                        Thread.sleep(10); Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(25))
                    }
                    assertEquals(1, carrier.posts.get()); assertTrue("$mode full GET missing", carrier.gets.get() >= baselineGets + 6)
                    assertTrue("$mode post-completion refresh missing", refreshes.get() > baselineRefreshes)
                    assertEquals(if (stored) "accepted" else "unknown", record.submissions.single().state)
                    if (stored) { assertNull(projected.error); assertEquals(1, projected.messages.size); assertTrue(projected.queue.isEmpty()) }
                    else { assertNotNull(projected.error); assertTrue(projected.messages.isEmpty()); assertEquals(1, projected.queue.size) }
                    sync.invalidate(all = true)
                    repeat(8) { Thread.sleep(10); Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(25)) }
                    assertEquals(1, carrier.posts.get()); assertEquals(if (stored) 1 else 0, projected.messages.size)
                    if (!stored) {
                        carrier.message = obj("id" to "msg_fixture", "type" to "user", "text" to "same")
                        sync.invalidate(all = true)
                        val late = System.nanoTime() + 5_000_000_000L
                        while (record.submissions.single().state != "accepted" && System.nanoTime() < late) { Thread.sleep(10); Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(25)) }
                        assertEquals("accepted", record.submissions.single().state); assertEquals(1, carrier.posts.get())
                    }
                } finally { session.close(); scope.cancel(); api.close() }
            }
        }
    }
    @Test fun cancellationAndCloseDoNotScheduleReconciliationOnAnOldCarrier() {
        for (closeSession in listOf(false, true)) Carrier("held", false).use { carrier ->
            val app: Application = RuntimeEnvironment.getApplication()
            val record = SessionRecord("local-cancel-$closeSession", "fixture", "noroot", "/workspace", cli = "OpenCode", threadId = "ses_fixture")
            val session = OpenCodeSession(record, AgentRuntime(app), {}, { "unused" }, { _, _ -> }, {})
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val api = OpenCodeClient("http://127.0.0.1:${carrier.listener.localPort}", "public-fixture")
            set(session, "client", api)
            set(session, "view", SessionView(SessionSummary(record.id, "fixture", "noroot", "/workspace", cli = "OpenCode"), connected = true))
            val refreshes = AtomicInteger()
            val sync = OpenCodeSync(scope, { true }, {}, { _, _, _ -> refreshes.incrementAndGet() }, {})
            set(session, "synchronizer", sync)
            try {
                sync.start(); await { refreshes.get() == 1 }
                val baseline = refreshes.get()
                val sendScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val send = sendScope.launch { session.submitManaged(ManagedSubmission("msg_cancel", "same")) }
                assertTrue(carrier.postEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                if (closeSession) session.close() else send.cancel()
                carrier.releasePost.countDown()
                runBlocking { send.join() }
                repeat(12) { Thread.sleep(10); Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(25)) }
                assertEquals("closed/cancelled attempt must not refresh old sync", baseline, refreshes.get())
                assertEquals(1, carrier.posts.get()); sendScope.cancel()
            } finally { session.close(); scope.cancel(); api.close() }
        }
    }
    private fun await(check: () -> Boolean) {
        val end = System.nanoTime() + 10_000_000_000L
        while (!check() && System.nanoTime() < end) { Thread.sleep(10); Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(25)) }
        assertTrue("condition timed out", check())
    }
    companion object {
        private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
        private fun set(owner: Any, name: String, value: Any) { owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value) }
    }
}
