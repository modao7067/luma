package io.github.modao7067.luma.gui.session

import android.app.Application
import android.os.Looper
import android.util.AtomicFile
import kotlinx.coroutines.*
import io.github.modao7067.luma.gui.agent.NativeCliClient
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
class BlankSessionIntegrationTest {
    private class Paused : CoroutineDispatcher() { override fun dispatch(context: CoroutineContext, block: Runnable) {} }
    private class Fixture : AutoCloseable {
        val app: Application = RuntimeEnvironment.getApplication()
        val controller = SessionController(app)
        val store get() = field(controller, "store") as SessionStore
        var scope = CoroutineScope(SupervisorJob() + Paused())
        init { (field(controller, "scope") as CoroutineScope).cancel(); set(controller, "scope", scope) }
        fun create(key: String, cli: String) = runBlocking { controller.manage(JSONObject().put("action", "create").put("cli", cli).put("cwd", "/work").put("requestId", key)) }.getString("id")
        fun live(id: String): Any = (field(controller, "sessions") as Map<*, *>)[id]!!
        fun record(id: String) = field(live(id), "record") as SessionRecord
        fun publish() { controller.javaClass.getDeclaredMethod("publish").apply { isAccessible = true }.invoke(controller) }
        fun persist() { controller.javaClass.getDeclaredMethod("persist").apply { isAccessible = true }.invoke(controller) }
        fun enable() { scope.cancel(); scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate); set(controller, "scope", scope) }
        override fun close() { scope.cancel() }
    }
    @Test fun currentArchivePersistsBlankCliAndNoncurrentArchiveDoesNotChangeSelection() = Fixture().use { f ->
        val a = f.create("a", "Codex"); val b = f.create("b", "Claude Code"); val c = f.create("c", "pi")
        f.controller.openSession(b)
        f.controller.archiveSession(c)
        assertEquals(b, f.controller.state.value.selected!!.summary.id)
        f.controller.archiveSession(b)
        assertNull(f.controller.state.value.selected); assertEquals("Claude Code", f.controller.state.value.blank.cli)
        assertEquals(listOf(a), f.controller.state.value.sessions.map { it.id })
        val restored = SessionStore(f.app); val records = restored.load()
        assertTrue(restored.navigation.initialized); assertNull(restored.navigation.selectedId)
        assertEquals("Claude Code", restored.navigation.blank.cli)
        assertNull(restoredSelection(restored.navigation, records, a, f.controller.environment))
        f.controller.draft("空白草稿")
        f.create("background", "pi")
        assertNull(f.controller.state.value.selected); assertEquals("Claude Code", f.controller.state.value.blank.cli)
        assertEquals("空白草稿", f.controller.state.value.blank.draft)
    }
    @Test fun firstClickCreatesOneSameCliReferenceAndTransfersAllInputOnlyThen() = Fixture().use { f ->
        f.store.navigation.blank.cli = "pi"; f.store.navigation.blank.cwd = "/original"
        f.controller.draft("首条消息")
        f.store.navigation.blank.images += StoredImage("image", "private://image", "/image", "image")
        f.store.navigation.blank.skills += DraftSkill("skill", "Skill", "/skill")
        f.publish(); assertTrue(f.controller.state.value.sessions.isEmpty())
        f.controller.send()
        val selected = f.controller.state.value.selected!!; val record = f.record(selected.summary.id)
        assertEquals("pi", record.cli); assertEquals("/original", record.cwd)
        assertEquals("首条消息", record.draft); assertEquals(1, record.images.size); assertEquals(1, record.skills.size)
        assertTrue(selected.preparingFirstSend); assertNull(record.threadId); assertNull(record.submissionId)
        f.controller.send(); f.controller.send(); f.controller.draft("不能改写已点击的首条消息")
        assertEquals(1, f.controller.state.value.sessions.size); assertEquals("首条消息", record.draft)
        assertEquals("", f.controller.state.value.blank.draft)
    }
    @Test fun unknownCreationUnlocksTheOriginalInputWithoutAnotherCreateOrAutomaticSend() = Fixture().use { f ->
        f.controller.draft("保留输入"); f.controller.send()
        val id = f.controller.state.value.selected!!.summary.id; val r = f.record(id)
        r.creationUnknown = true
        set(f.live(id), "view", f.controller.state.value.selected!!.copy(loading = false, connected = false, error = "创建结果未知"))
        f.publish()
        assertFalse(f.controller.state.value.selected!!.preparingFirstSend); assertEquals("保留输入", r.draft)
        f.controller.send(); f.controller.reconnect(); f.publish()
        assertEquals(1, f.controller.state.value.sessions.size); assertNull(r.submissionId); assertTrue(r.creationUnknown)
    }
    @Test fun failedLocalPersistenceRestoresTheBlankDraftWithoutCreatingAReference() = Fixture().use { f ->
        f.controller.draft("磁盘失败也保留")
        val blocked = java.io.File(f.app.filesDir, "blocked").apply { writeText("not a directory") }
        set(f.store, "file", AtomicFile(java.io.File(blocked, "index.json")))
        f.controller.send()
        assertTrue(f.controller.state.value.sessions.isEmpty()); assertNull(f.controller.state.value.selected)
        assertNull(f.store.navigation.selectedId)
        assertEquals("磁盘失败也保留", f.controller.state.value.blank.draft)
    }
    @Test fun failedArchivePersistenceKeepsTheOriginalSelectionAndReference() = Fixture().use { f ->
        val id = f.create("keep", "Claude Code"); f.controller.openSession(id)
        val blocked = java.io.File(f.app.filesDir, "blocked").apply { writeText("not a directory") }
        set(f.store, "file", AtomicFile(java.io.File(blocked, "index.json")))
        f.controller.archiveSession(id)
        assertEquals(id, f.controller.state.value.selected!!.summary.id)
        assertEquals(id, f.store.navigation.selectedId)
        assertEquals(listOf(id), f.controller.state.value.sessions.map { it.id })
    }
    @Test fun nativeReadySendsOnceToItsOwnRecordEvenAfterTheUserSelectedAnotherSession() = receiptFixture(false)
    @Test fun droppedFirstReceiptUsesTheExistingThirtySecondUnknownAndNeverReplays() = receiptFixture(true)
    private fun receiptFixture(drop: Boolean) = Fixture().use { f ->
        f.store.navigation.blank.cli = "pi"; f.controller.draft("first task"); f.controller.send()
        val id = f.controller.state.value.selected!!.summary.id; val r = f.record(id); r.threadId = "native-first"
        val other = f.create("other", "Codex"); f.controller.openSession(other); f.controller.draft("用户别处草稿")
        val live = f.live(id); val ready = SessionView(SessionSummary(id, r.title, r.environment, r.cwd, cli = "pi"), connected = true)
        val requests = CopyOnWriteArrayList<JSONObject>()
        ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { listener ->
            listener.soTimeout = 5000
            val worker = Thread {
                listener.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader(); assertEquals("POST /sessions/$id/send HTTP/1.1", input.readLine())
                    var length = 0
                    while (true) { val line = input.readLine(); if (line.isEmpty()) break; if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt() }
                    val chars = CharArray(length); var n = 0; while (n < length) n += input.read(chars, n, length - n)
                    val body = JSONObject(String(chars)); requests += body
                    if (!drop) {
                        val reply = JSONObject().put("connected", true).put("nativeId", r.threadId).put("revision", 1)
                            .put("receipts", JSONObject().put(body.getString("submissionId"), "accepted"))
                            .put("messages", JSONArray().put(JSONObject().put("id", body.getString("submissionId")).put("role", "user").put("text", body.getString("text"))))
                        val bytes = reply.toString().toByteArray()
                        socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray()); socket.getOutputStream().write(bytes)
                    }
                }
            }.apply { isDaemon = true; start() }
            val native = NativeCliSession(r, AgentRuntime(f.app), f::persist, { error("No identity change") }, { view, _ -> set(live, "view", view); f.publish() }, {})
            val api = NativeCliClient("http://127.0.0.1:${listener.localPort}", "fixture-token")
            set(native, "api", api); set(native, "view", ready); set(live, "nativeCli", native)
            f.enable(); set(live, "view", ready); f.publish()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < deadline && (requests.isEmpty() || (!drop && r.submissionId != null))) {
                Thread.sleep(10); Shadows.shadowOf(Looper.getMainLooper()).idle()
            }
            worker.join(1000); assertFalse(worker.isAlive); assertEquals(1, requests.size)
            assertEquals("first task", requests.single().getString("text"))
            if (drop) { assertTrue(SubmissionLedger.expire(r, r.submissionDeadlineMs)); assertEquals("first task", r.draft) }
            f.publish(); f.publish(); Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, requests.size); assertEquals(other, f.controller.state.value.selected!!.summary.id)
            assertEquals("用户别处草稿", f.record(other).draft)
            assertEquals(drop, SubmissionLedger.uncertain(r))
            native.close(); api.close()
        }
    }
    companion object {
        private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
        private fun set(owner: Any, name: String, value: Any) { owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value) }
    }
}
