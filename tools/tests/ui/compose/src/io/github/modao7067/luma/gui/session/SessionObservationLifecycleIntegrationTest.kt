package io.github.modao7067.luma.gui.session

import android.app.Application
import android.os.Looper
import android.util.AtomicFile
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
import java.io.File
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SessionObservationLifecycleIntegrationTest {
    private class Paused : CoroutineDispatcher() { override fun dispatch(context: CoroutineContext, block: Runnable) {} }
    private class Carrier : CodexTransport {
        lateinit var receive: (String) -> Unit
        val calls = mutableListOf<String>(); var closed = false; var nativeId = "native"
        override fun connect(opened: () -> Unit, message: (String) -> Unit, failed: (Throwable) -> Unit) { receive = message; opened() }
        override fun send(message: String): Boolean {
            val p = JSONObject(message); val method = p.optString("method"); calls += method
            if (p.has("id")) receive(obj("id" to p.get("id"), "result" to when (method) {
                "thread/queue/list" -> obj("data" to JSONArray())
                "thread/read" -> obj("thread" to obj("id" to nativeId, "status" to obj("type" to "idle")))
                "thread/unsubscribe" -> obj("status" to "unsubscribed")
                else -> JSONObject()
            }).toString())
            return true
        }
        override fun close() { closed = true }
    }
    private class Harness : AutoCloseable {
        val app: Application = RuntimeEnvironment.getApplication()
        val controller = SessionController(app)
        val paused = CoroutineScope(SupervisorJob() + Paused())
        val running = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val rpcs = mutableListOf<CodexRpc>(); val carriers = mutableListOf<Carrier>()
        init { (field(controller, "scope") as CoroutineScope).cancel(); set(controller, "scope", paused) }
        fun parent(name: String): Pair<String, Any> {
            val id = runBlocking { controller.manage(obj("action" to "create", "cli" to "Codex", "cwd" to "/workspace", "requestId" to "$name-${java.util.UUID.randomUUID()}")) }.getString("id")
            val live = (field(controller, "sessions") as Map<*, *>)[id]!!
            val record = field(live, "record") as SessionRecord; record.threadId = "native-$name"; record.creationUnknown = false
            set(live, "view", SessionView(SessionSummary(id, name, record.environment, record.cwd), connected = true))
            bind(live)
            (field(live, "connectJob") as? Job)?.cancel(); set(live, "connectJob", null)
            return id to live
        }
        fun bind(live: Any) {
            val carrier = Carrier().also { it.nativeId = (field(live, "record") as SessionRecord).threadId!! }; val rpc = CodexRpc("unused", null, {}, {}, carrier)
            runBlocking { rpc.connect() }; set(live, "rpc", rpc); rpcs += rpc; carriers += carrier
        }
        fun activate() {
            if (field(controller, "scope") === paused) {
                (field(controller, "sessions") as Map<*, *>).values.forEach { live ->
                    (field(live!!, "idleJob") as? Job)?.cancel(); set(live, "idleJob", null)
                }
            }
            set(controller, "scope", running)
        }
        fun publish() { controller.javaClass.getDeclaredMethod("publish").apply { isAccessible = true }.invoke(controller) }
        fun pump(ms: Long = 40) { Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms)) }
        override fun close() { paused.cancel(); running.cancel(); rpcs.forEach(CodexRpc::close) }
    }
    @Test fun disconnectCancelsScheduledFirstDraftAndLocalDispatchWithoutReconnectingOrStoppingNativeOwner() {
        Harness().use { h ->
            val (id, live) = h.parent("scheduled")
            h.controller.openSession(id)
            val record = field(live, "record") as SessionRecord; record.draft = "First draft remains"
            record.submissions += StoredSubmission("local", "Queued input remains", state = "local")
            set(live, "firstSendPending", true)
            h.activate(); h.publish(); h.controller.disconnectAll(); h.pump(10_000)
            assertFalse(field(live, "observing") as Boolean)
            assertTrue(field(live, "firstSendPending") as Boolean)
            assertEquals("First draft remains", record.draft)
            assertEquals("local", record.submissions.single().state)
            assertFalse(h.controller.state.value.selected!!.connected)
            assertFalse(h.controller.state.value.selected!!.loading)
            assertNull(field(live, "reconnectJob")); assertNull(field(live, "connectJob"))
            assertTrue(h.carriers.single().closed)
            assertTrue(h.carriers.single().calls.all { it in setOf("initialize", "initialized") })
        }
    }
    @Test fun disconnectDuringBackoffRevokesIntentAndExplicitReconnectRestoresOriginalLocalQueueContract() {
        Harness().use { h ->
            val (id, live) = h.parent("backoff")
            h.controller.openSession(id)
            val record = field(live, "record") as SessionRecord; record.draft = "Keep selection"
            record.submissions += StoredSubmission("local", "Resume explicitly", state = "local")
            set(live, "view", (field(live, "view") as SessionView).copy(connected = false))
            h.activate(); h.publish()
            assertTrue((field(live, "reconnectJob") as Job).isActive)
            h.controller.disconnectAll(); val epoch = field(live, "generation")
            h.pump(10_000)
            assertEquals(epoch, field(live, "generation")); assertEquals("local", record.submissions.single().state)
            set(h.controller, "scope", h.paused)
            h.controller.reconnect()
            assertTrue(field(live, "observing") as Boolean)
            val intent = field(live, "connectJob") as Job; assertTrue(intent.isActive); intent.cancel()
            h.bind(live)
            set(live, "view", (field(live, "view") as SessionView).copy(connected = true, loading = false))
            h.activate(); h.publish(); h.pump()
            assertEquals(1, h.carriers.last().calls.count { it == "thread/queue/add" })
            assertEquals("accepted", record.submissions.single().state)
        }
    }
    @Test fun disconnectCancelsAnInProgressConnectionContinuationAndLateCompletionCannotReconnect() {
        Harness().use { h ->
            val (id, live) = h.parent("connecting")
            h.controller.openSession(id); h.activate()
            val pending = CompletableDeferred<Unit>()
            set(live, "view", (field(live, "view") as SessionView).copy(connected = false, loading = true))
            val connection = h.running.launch(start = CoroutineStart.UNDISPATCHED) {
                pending.await()
                set(live, "view", (field(live, "view") as SessionView).copy(connected = true, loading = false)); h.publish()
            }
            set(live, "connectJob", connection)
            h.controller.disconnectAll(); pending.complete(Unit); h.pump(10_000)
            assertTrue(connection.isCancelled)
            assertFalse(h.controller.state.value.selected!!.connected)
            assertFalse(field(live, "observing") as Boolean)
            assertNull(field(live, "reconnectJob"))
        }
    }
    @Test fun refreshWhileLoadingRevokesPriorEpochImmediatelyAndRetainsUnknownNativeIdentity() {
        Harness().use { h ->
            val (id, live) = h.parent("refresh-loading")
            h.controller.openSession(id)
            val record = field(live, "record") as SessionRecord
            record.submissions += StoredSubmission("uncertain", "Do not replay", state = "unknown")
            record.draft = "Keep draft"
            val oldEpoch = field(live, "generation") as Int
            val old = Job(); set(live, "connectJob", old)
            set(live, "view", (field(live, "view") as SessionView).copy(loading = true))
            h.controller.reconnect()
            assertTrue(old.isCancelled)
            assertTrue((field(live, "generation") as Int) > oldEpoch)
            assertTrue(h.carriers.first().closed)
            assertTrue((field(live, "connectJob") as Job).isActive)
            assertEquals("native-refresh-loading", record.threadId)
            assertEquals("unknown", record.submissions.single().state)
            assertEquals("Keep draft", record.draft)
            assertEquals(0, h.carriers.first().calls.count { it in setOf("thread/start", "thread/queue/add", "turn/steer") })
            val firstRetry = field(live, "connectJob") as Job
            h.controller.reconnect()
            assertTrue(firstRetry.isCancelled)
            assertTrue((field(live, "connectJob") as Job).isActive)
        }
    }
    @Test fun backgroundIdleGraceReleasesOnlyItsObserverAndCachedHistoryThenReopensOriginalRecord() {
        Harness().use { h ->
            val (id, live) = h.parent("sleeping")
            val record = field(live, "record") as SessionRecord; record.draft = "Retained draft"
            @Suppress("UNCHECKED_CAST") val items = field(live, "items") as MutableMap<String, ConversationItem>
            items["history"] = ConversationItem("history", "assistant", "Native history")
            set(live, "idleConfirmed", true); set(live, "historyResumable", true)
            val (current, foreground) = h.parent("foreground")
            @Suppress("UNCHECKED_CAST") val currentItems = field(foreground, "items") as MutableMap<String, ConversationItem>
            currentItems["keep"] = ConversationItem("keep", "assistant", "Current stays warm")
            set(foreground, "idleConfirmed", true); set(foreground, "historyResumable", true)
            h.controller.openSession(current)
            set(h.controller, "idleGraceMillis", 100L); h.activate(); h.publish()
            h.pump(99)
            assertFalse(field(live, "dormant") as Boolean)
            h.pump(5)
            assertTrue(field(live, "dormant") as Boolean)
            assertTrue(items.isEmpty()); assertNull(field(live, "rpc")); assertNull(field(live, "idleJob"))
            assertFalse(field(live, "observing") as Boolean)
            assertEquals(listOf("thread/read", "thread/unsubscribe"), h.carriers.first().calls.filter { it.startsWith("thread/") })
            assertFalse(h.carriers.last().closed)
            assertEquals("native-sleeping", record.threadId); assertEquals("Retained draft", record.draft)
            set(h.controller, "scope", h.paused)
            h.controller.openSession(id)
            assertSame(record, field(live, "record"))
            assertFalse(field(live, "dormant") as Boolean)
            assertTrue((field(live, "connectJob") as Job).isActive)
            assertFalse(h.carriers.first().calls.any { it in setOf("thread/start", "thread/queue/add", "turn/steer") })
        }
    }
    @Test fun idleExternalSessionDropsOnlyLumaObservationAndNeverUnsubscribesOrStopsItsOwner() {
        Harness().use { h ->
            val (_, live) = h.parent("external-idle")
            val record = field(live, "record") as SessionRecord
            record.externalSocket = "/tmp/fixture-external.sock"
            @Suppress("UNCHECKED_CAST") val items = field(live, "items") as MutableMap<String, ConversationItem>
            items["history"] = ConversationItem("history", "assistant", "Retained natively")
            set(live, "idleConfirmed", true); set(live, "historyResumable", true)
            set(h.controller, "idleGraceMillis", 10L); h.activate(); h.publish(); h.pump(20)
            assertTrue(field(live, "dormant") as Boolean)
            assertTrue(h.carriers.single().closed)
            assertTrue(h.carriers.single().calls.all { it in setOf("initialize", "initialized") })
            assertEquals("/tmp/fixture-external.sock", record.externalSocket)
        }
    }
    @Test fun unknownOrDisconnectedBackgroundSessionDoesNotScheduleIdleRelease() {
        Harness().use { h ->
            val (_, live) = h.parent("protected-unknown")
            val record = field(live, "record") as SessionRecord
            record.submissions += StoredSubmission("unknown", "Never replay", state = "unknown")
            set(live, "idleConfirmed", true); set(live, "historyResumable", true)
            set(h.controller, "idleGraceMillis", 10L); h.activate(); h.publish(); h.pump(20)
            assertNull(field(live, "idleJob")); assertFalse(h.carriers.single().closed)
            assertEquals("unknown", record.submissions.single().state)
        }
    }
    @Test fun repeatedIdleSessionBatchesReturnRetainedPayloadConnectionsAndTimersToZeroWithoutGc() {
        Harness().use { h ->
            val samples = JSONArray()
            repeat(2) { batch ->
                val lives = (0 until 12).map { index ->
                    val (_, live) = h.parent("payload-$batch-$index")
                    val record = field(live, "record") as SessionRecord
                    record.externalSocket = "/tmp/fixture-$batch-$index.sock"
                    @Suppress("UNCHECKED_CAST") val items = field(live, "items") as MutableMap<String, ConversationItem>
                    items["payload"] = ConversationItem("payload", "assistant", "x".repeat(256 * 1024) + index)
                    set(live, "idleConfirmed", true); set(live, "historyResumable", true)
                    live
                }
                fun cachedChars() = lives.sumOf { live ->
                    @Suppress("UNCHECKED_CAST") val items = field(live, "items") as Map<String, ConversationItem>
                    items.values.sumOf { it.text.length.toLong() + it.detail.length }
                }
                val before = cachedChars()
                set(h.controller, "idleGraceMillis", 10L); h.activate(); h.publish(); h.pump(30)
                val after = cachedChars()
                assertTrue(before >= 3L * 1024 * 1024); assertEquals(0, after)
                assertTrue(lives.all { field(it, "rpc") == null && field(it, "idleJob") == null && !(field(it, "observing") as Boolean) })
                assertTrue(lives.all { (field(it, "record") as SessionRecord).threadId!!.startsWith("native-payload-") })
                samples.put(obj("batch" to batch, "sessions" to 12, "cachedCharactersBefore" to before, "cachedCharactersAfter" to after,
                    "connectionsBefore" to 12, "connectionsAfter" to 0, "idleTimersAfter" to 0,
                    "heapUsed" to Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()))
                set(h.controller, "scope", h.paused)
            }
            System.getProperty("luma.recovery.evidence")?.let { File(it, "controller-cache-trend.json").writeText(samples.toString(2)) }
            println("LUMA_IDLE_CACHE_TREND=$samples")
        }
    }
    @Test fun nativeWorkObservationIntentIsPersistedAfterConsumptionAndSurvivesDisconnect() {
        Harness().use { h ->
            val (_, live) = h.parent("persist-intent")
            val record = field(live, "record") as SessionRecord
            record.submissions += StoredSubmission("consumed", "Already native", state = "accepted", nativeConsumed = true)
            set(live, "view", (field(live, "view") as SessionView).copy(activeTurnId = "still-running"))
            h.publish()
            assertTrue(record.observationRequired)
            assertTrue(SessionStore(h.app).load().single().observationRequired)
            set(live, "view", (field(live, "view") as SessionView).copy(connected = false, activeTurnId = null))
            h.publish()
            assertTrue(record.observationRequired)
            set(live, "idleConfirmed", true)
            set(live, "view", (field(live, "view") as SessionView).copy(connected = true, activeTurnId = null))
            h.publish()
            assertFalse(record.observationRequired)
            assertFalse(SessionStore(h.app).load().single().observationRequired)
        }
    }
    @Test fun failedEmptySessionSaveKeepsIndexSelectionAndConnectionUntilSuccessfulRetry() {
        Harness().use { h ->
            val (old, oldLive) = h.parent("empty")
            val (next, nextLive) = h.parent("next")
            @Suppress("UNCHECKED_CAST") val items = field(nextLive, "items") as MutableMap<String, ConversationItem>
            items["keep"] = ConversationItem("keep", "assistant", "Not empty")
            h.controller.openSession(old); h.publish()
            val store = field(h.controller, "store") as SessionStore
            val original = field(store, "file") as AtomicFile
            val previousBytes = original.readFully().toList()
            val blocked = File(h.app.filesDir, "readonly-save-blocker"); blocked.writeText("not a directory")
            set(store, "file", AtomicFile(File(blocked, "sessions.json")))
            h.controller.openSession(next)
            assertEquals(old, h.controller.state.value.selected!!.summary.id)
            assertTrue((field(h.controller, "sessions") as Map<*, *>).containsKey(old))
            assertEquals(previousBytes, original.readFully().toList())
            assertFalse(h.carriers.first().closed)
            assertTrue(h.controller.state.value.selected!!.error!!.contains("原会话已保留"))
            set(store, "file", original); h.controller.dismissError(); h.controller.openSession(next)
            assertEquals(next, h.controller.state.value.selected!!.summary.id)
            assertFalse((field(h.controller, "sessions") as Map<*, *>).containsKey(old))
            assertTrue(h.carriers.first().closed)
            assertEquals(listOf(next), decodeSessionIndex(JSONObject(String(original.readFully()))).map { it.id })
        }
    }
    companion object {
        private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
        private fun set(owner: Any, name: String, value: Any?) { owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value) }
    }
}
