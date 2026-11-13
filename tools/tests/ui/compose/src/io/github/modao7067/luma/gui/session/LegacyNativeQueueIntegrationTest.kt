package io.github.modao7067.luma.gui.session

import android.app.Application
import android.os.Looper
import android.util.AtomicFile
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class LegacyNativeQueueIntegrationTest {
    private class Paused : CoroutineDispatcher() { override fun dispatch(context: CoroutineContext, block: Runnable) {} }
    private class Backend(val reliable: Boolean = false, val steer: Boolean = false, val nativeQueue: Boolean = false, val takeback: Boolean = false) : AutoCloseable {
        data class Post(val path: String, val body: JSONObject)
        val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val posts = CopyOnWriteArrayList<Post>()
        val cancellations = CopyOnWriteArrayList<Post>()
        val takebacks = CopyOnWriteArrayList<Post>()
        @Volatile var takebackReplyLost = false
        @Volatile var holdTakebackReply: CountDownLatch? = null
        @Volatile var dispatchBeforeTakeback = false
        @Volatile var cancelReplyLost = false
        @Volatile var holdInNativeQueue = false
        var nativeProtocol: String? = null
        @Volatile var busy = true
        @Volatile var loseNextReply = false
        @Volatile var images = false
        @Volatile var skills = false
        @Volatile var model = ""
        @Volatile var reportRunning = true
        @Volatile var finishImmediately = false
        @Volatile var holdNextReply: CountDownLatch? = null
        @Volatile var closed = false
        private val receipts = JSONObject()
        private val deliveries = JSONObject()
        private val takebackProofs = JSONObject()
        private val rows = JSONArray()
        private val queue = JSONArray()
        private val nativeWaiting = JSONArray()
        private var revision = 0
        private fun snapshot() = synchronized(this) {
            obj("revision" to ++revision, "nativeId" to "fixture-native", "nativeProtocol" to nativeProtocol,
                "connected" to true, "connecting" to false, "running" to busy,
                "model" to model, "effort" to "", "messages" to rows, "queue" to nativeWaiting, "hostQueue" to queue,
                "receipts" to receipts, "deliveries" to deliveries, "takebacks" to takebackProofs,
                "capabilities" to obj("queue" to nativeQueue, "steer" to steer, "images" to images, "skills" to skills, "maxSkills" to if (skills) Int.MAX_VALUE else 0)).also { if (!reportRunning) it.remove("running") }.toString()
        }
        private val worker = Thread {
            while (!closed) try { listener.accept().use { socket ->
                socket.soTimeout = 5000
                val input = socket.getInputStream().bufferedReader()
                val line = input.readLine() ?: return@use
                var length = 0
                while (true) { val header = input.readLine(); if (header.isEmpty()) break
                    if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                }
                val chars = CharArray(length); var count = 0
                while (count < length) { val read = input.read(chars, count, length - count); check(read >= 0); count += read }
                val path = line.split(' ')[1]
                val body = if (length > 0) JSONObject(String(chars)) else JSONObject()
                var drop = false
                var rejected: String? = null
                val response = if (path == "/health") obj("protocol" to 1, "clis" to JSONArray(CliCatalog.ids), "nativeProtocols" to JSONObject(CliCatalog.nativeProtocols), "carrierRevision" to io.github.modao7067.luma.gui.runtime.NativeCliRuntimePolicy.revision)
                    .also { if (reliable) it.put("deliveryProtocol", 2); if (takeback) it.put("queueTakebackProtocol", 1) }.toString()
                else {
                    if (path.endsWith("/send") || path.endsWith("/submit")) synchronized(this) {
                        posts += Post(path, body)
                        val id = body.getString("submissionId")
                        if (reliable) {
                            deliveries.put(id, obj("state" to "queued", "payload" to body))
                            queue.put(obj("id" to id, "text" to body.getString("text"), "clientUserMessageId" to id, "hostOwned" to true, "canRemove" to true, "canSteer" to (busy && steer && !body.optBoolean("steer")), "canEdit" to takeback))
                        } else if (loseNextReply) { loseNextReply = false; drop = true; receipts.put(id, "unknown"); busy = true }
                        else if (holdInNativeQueue) {
                            receipts.put(id, "accepted"); nativeWaiting.put(obj("id" to "native-$id", "text" to body.getString("text"), "clientUserMessageId" to id, "canRemove" to true))
                        }
                        else { receipts.put(id, "accepted"); rows.put(obj("id" to id, "role" to "user", "text" to body.getString("text"))); busy = !finishImmediately }
                    }
                    if (path.endsWith("/steer-pending")) synchronized(this) {
                        posts += Post(path, body)
                        val id = body.getString("id")
                        for (i in queue.length()-1 downTo 0) if (queue.getJSONObject(i).optString("id") == id) queue.remove(i)
                        if (deliveries.has(id)) {
                            val d = deliveries.getJSONObject(id)
                            d.getJSONObject("payload").put("steer", true)
                            d.put("state", "unknown")
                            receipts.put(id, "accepted")
                            queue.put(obj("id" to id, "text" to d.getJSONObject("payload").getString("text"), "status" to "插话 · 等待当前步骤结束", "clientUserMessageId" to id, "hostOwned" to true, "canRemove" to false, "canSteer" to false, "canEdit" to false))
                        }
                    }
                    if (path.endsWith("/cancel-pending") || path.endsWith("/queue")) synchronized(this) {
                        cancellations += Post(path, body)
                        val id = body.getString("id")
                        for (list in listOf(queue, nativeWaiting)) for (i in list.length()-1 downTo 0) if (list.getJSONObject(i).optString("id") == id) list.remove(i)
                        if (deliveries.has(id)) deliveries.getJSONObject(id).put("state", "cancelled")
                        if (cancelReplyLost) { drop = true; cancelReplyLost = false }
                    }
                    if (path.endsWith("/take-back")) synchronized(this) {
                        takebacks += Post(path, body)
                        val id = body.getString("id"); val delivery = deliveries.getJSONObject(id)
                        if (dispatchBeforeTakeback) { delivery.put("state", "dispatching"); receipts.put(id, "unknown") }
                        if (delivery.getString("state") == "queued") {
                            delivery.put("state", "cancelled")
                            takebackProofs.put(id, obj("operationId" to body.getString("operationId"), "payload" to delivery.getJSONObject("payload")))
                        } else rejected = "消息已开始投递，不能返回输入框"
                        for (i in queue.length()-1 downTo 0) if (queue.getJSONObject(i).getString("id") == id) queue.remove(i)
                        if (takebackReplyLost) { drop = true; takebackReplyLost = false }
                    }
                    if (rejected == null) snapshot() else obj("rejected" to true, "message" to rejected).toString()
                }
                if (path.endsWith("/send") || path.endsWith("/submit")) {
                    holdNextReply?.let { held -> holdNextReply = null; check(held.await(5, TimeUnit.SECONDS)) }
                }
                if (path.endsWith("/take-back")) holdTakebackReply?.let { held -> holdTakebackReply = null; check(held.await(5, TimeUnit.SECONDS)) }
                val bytes = response.toByteArray()
                val output = socket.getOutputStream()
                output.write("HTTP/1.1 ${if (rejected == null) "200 OK" else "422 Rejected"}\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                output.write(if (drop) bytes.copyOf(bytes.size / 2) else bytes); output.flush()
            } } catch (error: Exception) { if (!closed) throw error }
        }.apply { isDaemon = true; start() }
        fun client() = NativeCliClient("http://127.0.0.1:${listener.localPort}", "public-fixture")
        override fun close() { closed = true; listener.close(); worker.join(1000) }
    }
    private class Harness : AutoCloseable {
        val app: Application = RuntimeEnvironment.getApplication()
        val controller = SessionController(app)
        private val paused = CoroutineScope(SupervisorJob() + Paused())
        val uncaught = CopyOnWriteArrayList<Throwable>()
        private val running = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, failure -> uncaught += failure })
        private val backends = mutableListOf<Backend>()
        private val natives = mutableListOf<NativeCliSession>()
        init { (field(controller, "scope") as CoroutineScope).cancel(); set(controller, "scope", paused) }
        data class Bound(val id: String, val live: Any, val record: SessionRecord, val native: NativeCliSession, val backend: Backend)
        fun bind(cli: String, backend: Backend = Backend()): Bound {
            set(controller, "scope", paused)
            val id = runBlocking { controller.manage(obj("action" to "create", "cli" to cli, "cwd" to "/workspace", "requestId" to "create-${java.util.UUID.randomUUID()}")) }.getString("id")
            val live = (field(controller, "sessions") as Map<*, *>)[id]!!
            (field(live, "connectJob") as? Job)?.cancel(); set(live, "connectJob", null)
            val record = field(live, "record") as SessionRecord
            backend.nativeProtocol = record.nativeProtocol
            val native = connect(live, record, backend)
            controller.openSession(id)
            (field(live, "connectJob") as? Job)?.cancel(); set(live, "connectJob", null)
            set(controller, "scope", running); backends += backend
            return Bound(id, live, record, native, backend)
        }
        private fun connect(live: Any, record: SessionRecord, backend: Backend): NativeCliSession {
            val native = NativeCliSession(record, AgentRuntime(app), { persist() }, { "" }, { view, models ->
                set(live, "view", view); set(live, "models", models); publish()
            }, {})
            set(live, "nativeCli", native)
            val api = backend.client(); set(native, "api", api)
            runBlocking { native.connectClient(api) }; natives += native
            return native
        }
        fun poll(bound: Bound) { (field(bound.live, "nativeCli") as NativeCliSession).refreshDashboard(); pump() }
        fun blockStore(): AtomicFile {
            (field(controller, "saveJob") as? Job)?.cancel()
            val store = field(controller, "store") as SessionStore
            val original = field(store, "file") as AtomicFile
            val blocker = File(app.filesDir, "save-blocker"); blocker.writeText("not a directory")
            set(store, "file", AtomicFile(File(blocker, "sessions.json")))
            return original
        }
        fun restoreStore(file: AtomicFile) { set(field(controller, "store")!!, "file", file) }
        fun checkpoint() { persist() }
        fun stored(): List<SessionRecord> = (field(controller, "store") as SessionStore).load()
        fun restoreObservation(bound: Bound) {
            set(controller, "scope", paused); controller.reconnect()
            (field(bound.live, "connectJob") as? Job)?.cancel(); set(bound.live, "connectJob", null)
            connect(bound.live, bound.record, bound.backend)
            (field(bound.live, "dispatchJob") as? Job)?.cancel(); set(bound.live, "dispatchJob", null); set(bound.live, "dispatchingLocal", false)
            set(controller, "scope", running); publish()
        }
        fun view() = controller.state.value.selected!!
        fun publish() { controller.javaClass.getDeclaredMethod("publish").apply { isAccessible = true }.invoke(controller) }
        private fun persist() { controller.javaClass.getDeclaredMethod("persist").apply { isAccessible = true }.invoke(controller) }
        fun pump() { Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(40)) }
        fun await(check: () -> Boolean) {
            val end = System.nanoTime() + 5_000_000_000L
            while (!check() && System.nanoTime() < end) { Thread.sleep(10); pump() }
            assertTrue("fixture condition timed out", check())
        }
        override fun close() { controller.disconnectAll(); paused.cancel(); running.cancel(); natives.forEach { it.close() }; backends.forEach { it.close() } }
    }
    @Test fun everyLegacyBridgedCliAcceptsBusyInputLocallyFromControllerAndBurstClicksDoNotDuplicate() = Harness().use { h ->
        for (cli in CliCatalog.bridged) {
            val b = h.bind(cli); h.controller.draft("same"); repeat(8) { h.controller.send() }; h.pump()
            assertEquals(cli, "", b.record.draft)
            assertEquals(cli, 1, b.record.submissions.size)
            assertEquals(cli, "local", b.record.submissions.single().state)
            assertEquals(cli, 1, h.view().queue.size); assertTrue(cli, h.view().messages.isEmpty())
            assertTrue(cli, b.backend.posts.isEmpty())
            h.controller.draft("same"); h.controller.send(); h.pump()
            assertEquals(cli, 2, b.record.submissions.size)
            assertEquals(cli, 2, b.record.submissions.map { it.id }.toSet().size)
        }
    }
    @Test fun legacyLocalEditAndCancellationAreOfflineAndOnlyUncancelledInputCrossesOnceAfterExplicitReconnect() = Harness().use { h ->
        val b = h.bind("Antigravity"); h.controller.draft("first"); h.controller.send(); h.pump()
        h.controller.draft("second"); h.controller.send(); h.pump()
        val original = b.record.submissions.first().id
        val queue = h.view().queue.toList(); h.controller.disconnectAll(); h.pump()
        h.controller.editQueued(queue[0].id, "edited"); h.controller.removeQueued(queue[1].id); h.pump()
        assertEquals("edited", b.record.submissions.first().text); assertEquals("cancelled", b.record.submissions.last().state)
        b.backend.busy = false; h.publish(); h.pump(); assertTrue(b.backend.posts.isEmpty())
        h.restoreObservation(b); h.await { b.backend.posts.size == 1 }
        assertEquals(original, b.backend.posts.single().body.getString("submissionId"))
        assertEquals("edited", b.backend.posts.single().body.getString("text"))
        repeat(5) { h.publish(); h.pump() }; assertEquals(1, b.backend.posts.size)
    }
    @Test fun nativeSteerOrNativeQueueAndReliableHostContinueImmediateHandoffWithTheirOriginalProtocol() = Harness().use { h ->
        for ((backend, expectedRoute, expectedSteer) in listOf(Triple(Backend(steer = true), "/send", true), Triple(Backend(nativeQueue = true), "/send", false), Triple(Backend(reliable = true), "/submit", false))) {
            val b = h.bind("Antigravity", backend); h.controller.draft("now"); h.controller.send()
            h.await { backend.posts.size == 1 }
            assertTrue(backend.posts.single().path.endsWith(expectedRoute)); assertEquals(expectedSteer, backend.posts.single().body.getBoolean("steer"))
            assertTrue(b.record.submissions.none { it.state == "local" })
        }
    }
    @Test fun unknownNativeWriteIsNeverReplayedAndDoesNotBlockAdmittingANewBusyInput() = Harness().use { h ->
        val backend = Backend().apply { busy = false; loseNextReply = true }; val b = h.bind("Antigravity", backend)
        h.controller.draft("first"); h.controller.send(); h.await { b.record.submissions.firstOrNull()?.state == "unknown" }
        h.poll(b); h.controller.draft("next"); h.controller.send(); h.pump()
        assertEquals(listOf("unknown", "local"), b.record.submissions.map { it.state })
        repeat(5) { h.publish(); h.pump() }; assertEquals(1, backend.posts.size)
        assertEquals(b.record.submissions.first().id, h.view().messages.single().id)
        assertEquals("pending", h.view().messages.single().delivery)
        assertEquals(b.record.submissions.last().id, h.view().queue.single().clientUserMessageId)
        assertTrue(h.view().queue.single().canRemove); assertTrue(h.view().queue.single().canEdit)
    }
    @Test fun managementApiUsesTheSameBusyLegacyLocalOwnershipAndDoesNotRejectOrRebindItsId() = Harness().use { h ->
        val b = h.bind("Antigravity"); h.controller.draft("user draft")
        val command = obj("action" to "send", "id" to b.id, "text" to "agent input", "requestId" to "send-local-1")
        val first = runBlocking { h.controller.manage(command) }; h.pump()
        val again = runBlocking { h.controller.manage(command) }
        assertEquals(first.getString("submissionId"), again.getString("submissionId"))
        assertEquals("user draft", b.record.draft); assertEquals("local", b.record.submissions.single().state); assertTrue(b.backend.posts.isEmpty())
    }
    @Test fun failedInitialAdmissionRetainsComposerAndAttachmentsAndMakesNoNativeCall() = Harness().use { h ->
        val b = h.bind("Antigravity", Backend().apply { images = true })
        h.controller.draft("unsaved")
        b.record.images += StoredImage("image", "file:///fixture.png", "/fixture.png", "fixture.png")
        h.publish(); val original = h.blockStore()
        h.controller.send(); h.pump()
        assertEquals("unsaved", h.view().draft); assertEquals(listOf("image"), h.view().attachments.map { it.id })
        assertTrue(b.record.submissions.isEmpty()); assertTrue(h.view().queue.isEmpty()); assertTrue(b.backend.posts.isEmpty())
        assertTrue(h.view().error!!.contains("无法保存发送状态")); assertTrue(h.uncaught.isEmpty())
        h.restoreStore(original)
    }
    @Test fun offlineAdmissionSaveFailureDoesNotCreateARejectedBubbleOrDiscardTheDraft() = Harness().use { h ->
        val b = h.bind("Antigravity"); h.controller.disconnectAll(); h.controller.draft("offline unsaved")
        val original = h.blockStore(); h.controller.send(); h.pump()
        assertEquals("offline unsaved", h.view().draft); assertTrue(b.record.submissions.isEmpty())
        assertTrue(h.view().queue.isEmpty()); assertTrue(h.view().messages.isEmpty()); assertTrue(b.backend.posts.isEmpty())
        assertTrue(h.uncaught.isEmpty()); h.restoreStore(original)
    }
    @Test fun failedPreparedClaimPausesWithoutNativeWriteAndSuccessfulExplicitCheckpointRecoversOnce() = Harness().use { h ->
        val b = h.bind("Antigravity"); h.controller.draft("durable local"); h.controller.send(); h.pump()
        val id = b.record.submissions.single().id; val original = h.blockStore()
        b.backend.busy = false; h.poll(b)
        h.await { field(b.live, "dispatchStorageBlocked") == true }
        assertEquals("local", b.record.submissions.single().state); assertEquals("", h.view().draft)
        assertEquals(id, h.view().queue.single().clientUserMessageId); assertTrue(h.view().queue.single().canEdit)
        assertTrue(b.backend.posts.isEmpty()); assertTrue(h.view().error!!.contains("无法保存发送状态"))
        repeat(10) { h.publish(); h.pump() }
        assertTrue(h.uncaught.isEmpty()); assertTrue(b.backend.posts.isEmpty())
        h.restoreStore(original); h.controller.refreshDashboard(); h.await { b.backend.posts.size == 1 }
        h.await { b.record.submissions.single().state == "accepted" }
        assertEquals(id, b.backend.posts.single().body.getString("submissionId"))
        repeat(5) { h.publish(); h.pump() }; assertEquals(1, b.backend.posts.size)
    }
    @Test fun failedLocalEditAndCancelRollBackQueueAndDraftOffline() = Harness().use { h ->
        val b = h.bind("Antigravity"); h.controller.draft("original"); h.controller.send(); h.pump()
        h.controller.disconnectAll(); h.controller.draft("new draft"); val id = h.view().queue.single().id
        val original = h.blockStore()
        h.controller.editQueued(id, "not saved"); h.pump()
        assertEquals("original", h.view().queue.single().text); assertEquals("new draft", h.view().draft)
        h.controller.removeQueued(id); h.pump()
        assertEquals("local", b.record.submissions.single().state); assertEquals("original", h.view().queue.single().text)
        assertEquals("new draft", h.view().draft); assertTrue(b.backend.posts.isEmpty()); assertTrue(h.uncaught.isEmpty())
        h.restoreStore(original)
    }
    @Test fun settingsRejectionSaveFailurePreservesLocalTruthAndDoesNotEscapeMainCoroutine() = Harness().use { h ->
        val b = h.bind("Antigravity", Backend().apply { model = "a" })
        h.controller.draft("old setting"); h.controller.send(); h.pump()
        val original = h.blockStore(); b.record.model = "b"; b.backend.model = "b"; b.backend.busy = false; h.poll(b)
        h.await { field(b.live, "dispatchStorageBlocked") == true }
        assertEquals("local", b.record.submissions.single().state); assertEquals("", h.view().draft)
        assertEquals("old setting", h.view().queue.single().text); assertTrue(b.record.deliveryResults.isEmpty())
        assertTrue(b.backend.posts.isEmpty()); assertTrue(h.uncaught.isEmpty())
        h.restoreStore(original); h.controller.refreshDashboard(); h.await { b.record.submissions.single().state == "rejected" }
        assertEquals("old setting", h.view().draft); assertTrue(b.backend.posts.isEmpty())
        assertEquals("rejected", h.stored().single().submissions.single().state)
    }
    @Test fun validationRejectionSaveFailureAlsoPreservesPayloadAndHasNoUncaughtCoroutine() = Harness().use { h ->
        val b = h.bind("Antigravity", Backend().apply { images = true })
        h.controller.draft("image input"); b.record.images += StoredImage("image", "file:///fixture.png", "/fixture.png", "fixture.png")
        h.controller.send(); h.pump(); val original = h.blockStore()
        b.backend.images = false; b.backend.busy = false; h.poll(b)
        h.await { field(b.live, "dispatchStorageBlocked") == true }
        assertEquals("local", b.record.submissions.single().state); assertEquals("image", b.record.submissions.single().images.single().id)
        assertEquals("", h.view().draft); assertTrue(h.view().queue.single().canRemove)
        assertTrue(b.backend.posts.isEmpty()); assertTrue(h.uncaught.isEmpty()); h.restoreStore(original)
    }
    @Test fun queuedPayloadIsReadAgainAfterPreviousAwaitSoEditAndCancelWinBeforeClaim() = Harness().use { h ->
        val b = h.bind("Antigravity"); for (text in listOf("first", "second", "third")) { h.controller.draft(text); h.controller.send(); h.pump() }
        val ids = b.record.submissions.map { it.id }; val hold = CountDownLatch(1)
        b.backend.finishImmediately = true; b.backend.holdNextReply = hold; b.backend.busy = false
        h.poll(b); h.await { b.backend.posts.size == 1 }
        val queue = h.view().queue.associateBy { it.clientUserMessageId }
        assertFalse(queue.getValue(ids[0]).canRemove)
        h.controller.editQueued(queue.getValue(ids[1]).id, "edited second")
        h.controller.removeQueued(queue.getValue(ids[2]).id); hold.countDown()
        h.await { b.backend.posts.size == 2 }; h.await { b.record.submissions[1].state == "accepted" }
        assertEquals(listOf(ids[0], ids[1]), b.backend.posts.map { it.body.getString("submissionId") })
        assertEquals("edited second", b.backend.posts.last().body.getString("text"))
        assertEquals("cancelled", b.record.submissions[2].state); assertTrue(h.uncaught.isEmpty())
    }
    @Test fun missingForegroundStateCannotDispatchButStillAdmitsLocallyAndIndexRecoveryKeepsIdentity() = Harness().use { h ->
        val b = h.bind("Antigravity", Backend().apply { reportRunning = false; busy = false })
        h.controller.draft("not yet trusted"); h.controller.send(); h.pump()
        val saved = h.stored().single(); val id = b.record.submissions.single().id
        assertEquals(id, saved.submissions.single().id); assertEquals("local", saved.submissions.single().state)
        assertTrue(saved.submissions.single().queuePresentation); assertTrue(b.backend.posts.isEmpty())
        h.controller.disconnectAll()
        b.record.submissions.clear(); b.record.submissions.addAll(saved.submissions)
        b.backend.reportRunning = true; h.restoreObservation(b); h.await { b.backend.posts.size == 1 }
        assertEquals(id, b.backend.posts.single().body.getString("submissionId")); assertTrue(h.uncaught.isEmpty())
    }
    @Test fun oldIndexLocalPresentationStaysFloatingWhileItsIdlePreparedClaimHasUnknownOutcome() = Harness().use { h ->
        val b = h.bind("Antigravity"); h.controller.draft("legacy local"); h.controller.send(); h.pump()
        b.record.submissions.single().queuePresentation = false
        b.backend.busy = false; b.backend.loseNextReply = true; h.poll(b)
        h.await { b.record.submissions.single().state == "unknown" }
        assertTrue(b.record.submissions.single().queuePresentation)
        assertEquals(b.record.submissions.single().id, h.view().queue.single().clientUserMessageId)
        assertFalse(h.view().queue.single().canRemove); assertTrue(h.view().queue.single().canEdit); assertTrue(h.view().queue.single().localRecovery)
        assertTrue(h.view().messages.isEmpty()); assertEquals(1, b.backend.posts.size)
    }
    @Test fun durableHostAndLegacyNativeQueuesActuallyRetractAndLostCancelReplyIsNeverRetried() = Harness().use { h ->
        for (backend in listOf(Backend(reliable=true), Backend(nativeQueue=true).apply { holdInNativeQueue=true })) {
            val b=h.bind("Antigravity",backend); h.controller.draft("retract fixture"); h.controller.send()
            h.await { backend.posts.size==1 && h.view().queue.any { !it.localSubmission } }
            val q=h.view().queue.single(); backend.cancelReplyLost=true
            h.controller.removeQueued(q.id); h.await { backend.cancellations.size==1 }
            assertTrue(h.view().queue.isEmpty()); assertTrue(b.record.submissions.single().dismissed)
            assertTrue(backend.cancellations.single().path.endsWith(if(backend.reliable) "/cancel-pending" else "/queue"))
            h.await { b.record.queueDismissals.single().state=="unknown" }
            repeat(4){ h.poll(b); h.publish(); h.pump() }
            assertEquals(1,backend.cancellations.size); assertEquals(1,backend.posts.size)
            assertTrue(h.view().queue.isEmpty()); assertTrue(h.uncaught.isEmpty())
        }
    }
    @Test fun hostQueueTakebackWithLostAckReconcilesOnReconnectAndRestoresExactMediaWithoutResending() = Harness().use { h ->
        val backend = Backend(reliable = true, takeback = true).apply { images = true; skills = true; takebackReplyLost = true }
        val b = h.bind("Antigravity", backend)
        assertFalse(h.view().cliServiceUpdateAvailable)
        b.record.images += StoredImage("image", "file:///fixture.png", "/fixture.png", "fixture.png")
        b.record.skills += DraftSkill("skill", "Skill", "/skill")
        h.controller.draft("host original"); h.controller.send()
        h.await { backend.posts.size == 1 && b.record.submissions.single().state == "queued" }
        val queue = h.view().queue.single(); assertTrue(queue.hostOwned); assertTrue(queue.canEdit)
        h.controller.restoreQueuedToComposer(queue.id)
        h.await { backend.takebacks.size == 1 && h.view().error.orEmpty().contains("取回结果待核对") }
        assertEquals("", b.record.draft); assertEquals("pending", b.record.submissions.single().takebackState)
        assertEquals("pending", h.stored().single().submissions.single().takebackState)
        h.restoreObservation(b); h.await { b.record.submissions.single().takebackState == "restored" }
        assertEquals("host original", b.record.draft); assertEquals("image", b.record.images.single().id); assertEquals("skill", b.record.skills.single().id)
        assertTrue(h.view().queue.isEmpty()); assertEquals(1, backend.posts.size); assertEquals(1, backend.takebacks.size)
        assertTrue(h.uncaught.isEmpty())
    }
    @Test fun hostQueueTakebackKeepsANewerDraftAndOffersTheConfirmedReturnedCardForExplicitEdit() = Harness().use { h ->
        val backend = Backend(reliable = true, takeback = true); val b = h.bind("Antigravity", backend)
        h.controller.draft("host original"); h.controller.send(); h.await { b.record.submissions.singleOrNull()?.state == "queued" }
        val held = CountDownLatch(1); backend.holdTakebackReply = held
        h.controller.restoreQueuedToComposer(h.view().queue.single().id); h.await { backend.takebacks.size == 1 }
        h.controller.draft("newer input"); held.countDown()
        h.await { b.record.submissions.single().takebackState == "ready" }
        assertEquals("newer input", b.record.draft); assertEquals("已取回 · 点击编辑", h.view().queue.single().status)
        h.controller.restoreQueuedToComposer(h.view().queue.single().id); h.pump(); assertEquals("newer input", b.record.draft)
        h.controller.draft(""); h.controller.restoreQueuedToComposer(h.view().queue.single().id); h.pump()
        assertEquals("host original", b.record.draft); assertTrue(h.view().queue.isEmpty())
        assertEquals(1, backend.posts.size); assertEquals(1, backend.takebacks.size)
    }
    @Test fun hostQueueClaimRejectsProvenTakebackButAllowsExplicitLocalRecoveryWithoutResending() = Harness().use { h ->
        val backend = Backend(reliable = true, takeback = true).apply { dispatchBeforeTakeback = true }
        val b = h.bind("Antigravity", backend); h.controller.draft("claimed"); h.controller.send()
        h.await { b.record.submissions.singleOrNull()?.state == "queued" }
        h.controller.restoreQueuedToComposer(h.view().queue.single().id)
        h.await { b.record.submissions.single().takebackState == "handled" }
        assertEquals("claimed", b.record.draft); assertTrue(b.record.submissions.single().state in setOf("pending", "unknown"))
        assertTrue(b.record.submissions.single().dismissed); assertTrue(h.view().queue.isEmpty())
        assertEquals(1, backend.posts.size); assertEquals(1, backend.takebacks.size); assertTrue(h.uncaught.isEmpty())
    }
    @Test fun hostQueueTakebackSaveFailuresNeverLosePayloadOrExposeAnUncommittedComposerDraft() = Harness().use { h ->
        val backend = Backend(reliable = true, takeback = true); val b = h.bind("Antigravity", backend)
        h.controller.draft("durable original"); h.controller.send(); h.await { b.record.submissions.singleOrNull()?.state == "queued" }
        val card = h.view().queue.single(); val store = h.blockStore()
        h.controller.restoreQueuedToComposer(card.id); h.pump()
        assertTrue(backend.takebacks.isEmpty()); assertNull(b.record.submissions.single().takebackId)
        h.restoreStore(store)
        val held = CountDownLatch(1); backend.holdTakebackReply = held
        h.controller.restoreQueuedToComposer(card.id); h.await { backend.takebacks.size == 1 }
        val blocked = h.blockStore(); held.countDown()
        h.await { field(b.live, "dispatchStorageBlocked") == true }
        assertEquals("", b.record.draft); assertEquals("durable original", b.record.submissions.single().text)
        assertEquals("ready", b.record.submissions.single().takebackState)
        h.restoreStore(blocked); h.controller.restoreQueuedToComposer(h.view().queue.single().id); h.pump()
        assertEquals("durable original", b.record.draft); assertTrue(h.view().queue.isEmpty())
        assertEquals(1, backend.posts.size); assertEquals(1, backend.takebacks.size); assertTrue(h.uncaught.isEmpty())
    }
    @Test fun olderCompatibleHostCarrierOnlyOffersAnOptionalUpdateAndKeepsNormalSending() = Harness().use { h ->
        val backend = Backend(reliable = true); val b = h.bind("Antigravity", backend)
        assertTrue(h.view().connected); assertFalse(h.view().cliServiceUpdateRequired); assertTrue(h.view().cliServiceUpdateAvailable)
        assertTrue(h.view().cliServiceUpdateImpact.contains("Antigravity"))
        h.controller.draft("still usable"); h.controller.send(); h.await { backend.posts.size == 1 }
        assertEquals("queued", b.record.submissions.single().state); assertTrue(backend.takebacks.isEmpty())
        assertTrue(h.view().cliServiceUpdateAvailable)
    }
    @Test fun uncertainNativeInputCanBeEditedOfflineWithMediaAndStaysHandledAfterReconnectWithoutAnotherPost() = Harness().use { h ->
        val backend = Backend(steer = true).apply { images = true; skills = true; loseNextReply = true }
        val b = h.bind("Antigravity", backend)
        b.record.images += StoredImage("image", "file:///fixture.png", "/fixture.png", "fixture.png")
        b.record.skills += DraftSkill("skill", "Skill", "/skill")
        h.controller.draft("unknown original"); h.controller.send()
        h.await { b.record.submissions.singleOrNull()?.state == "unknown" }
        val id = h.view().queue.single().id
        h.controller.disconnectAll(); h.pump(); h.controller.restoreQueuedToComposer(id); h.pump()
        assertEquals("unknown original", b.record.draft); assertEquals("image", b.record.images.single().id); assertEquals("skill", b.record.skills.single().id)
        assertEquals("unknown", b.record.submissions.single().state); assertTrue(b.record.submissions.single().dismissed)
        assertTrue(h.stored().single().receiptRecoveryRequired.contains(b.record.submissions.single().id))
        h.restoreObservation(b); repeat(3) { h.poll(b); h.publish(); h.pump() }
        assertTrue(h.view().queue.isEmpty()); assertEquals(1, backend.posts.size); assertTrue(backend.takebacks.isEmpty())
        assertEquals("unknown", b.record.submissions.single().state); assertTrue(h.uncaught.isEmpty())
    }
    @Test fun hostQueueSupportsExplicitSteerPendingAndDirectComposerSteer() = Harness().use { h ->
        val backend = Backend(reliable = true, steer = true, takeback = true).apply { nativeProtocol = "grok-private-leader-v1" }
        val b = h.bind("Grok Build", backend)
        h.controller.draft("queued first"); h.controller.send()
        h.await { backend.posts.size == 1 && h.view().queue.size == 1 }
        assertFalse(backend.posts.first().body.getBoolean("steer"))
        val card = h.view().queue.single()
        assertTrue(card.hostOwned); assertTrue(card.canSteer); assertTrue(card.canEdit); assertTrue(card.canRemove)
        h.controller.steerQueued(card.id)
        h.await { backend.posts.any { it.path.endsWith("/steer-pending") } }
        val waiting = h.view().queue.single()
        assertEquals("插话 · 等待当前步骤结束", waiting.status)
        assertFalse(waiting.canSteer); assertFalse(waiting.canRemove); assertTrue(waiting.localRecovery)
        h.controller.draft("direct steer"); h.controller.steer()
        h.await { backend.posts.count { it.path.endsWith("/submit") } == 2 }
        assertTrue(backend.posts.last { it.path.endsWith("/submit") }.body.getBoolean("steer"))
        assertTrue(h.uncaught.isEmpty())
    }
    companion object {
        private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
        private fun set(owner: Any, name: String, value: Any?) { owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value) }
    }
}
