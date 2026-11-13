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
import java.net.InetAddress
import java.net.ServerSocket
import io.github.modao7067.luma.gui.runtime.AgentRuntime
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class QueueDismissalIntegrationTest {
    private class Paused : CoroutineDispatcher() { override fun dispatch(context: CoroutineContext, block: Runnable) {} }
    private class Harness(cli:String="Codex") : AutoCloseable {
        val app: Application = RuntimeEnvironment.getApplication()
        val controller = SessionController(app)
        val paused = CoroutineScope(SupervisorJob() + Paused())
        val uncaught = mutableListOf<Throwable>()
        val running = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e -> uncaught += e })
        val id: String; val live: Any
        val record get() = field(live, "record") as SessionRecord
        var native = Transport()
        var rpc: CodexRpc
        init {
            (field(controller, "scope") as CoroutineScope).cancel(); set(controller, "scope", paused)
            id = runBlocking { controller.manage(obj("action" to "create", "cli" to cli, "cwd" to "/workspace", "requestId" to "create-queue")) }.getString("id")
            live = (field(controller, "sessions") as Map<*, *>)[id]!!; record.threadId = if(cli=="OpenCode") "ses_fixture" else "native"
            (field(live, "connectJob") as? Job)?.cancel(); set(live, "connectJob", null)
            controller.openSession(id)
            (field(live, "connectJob") as? Job)?.cancel(); set(live, "connectJob", null)
            rpc = bind(native)
            set(live,"view",SessionView(SessionSummary(id,record.title,record.environment,record.cwd),connected=true,activeTurnId="turn"))
            set(controller,"scope",running); publish()
        }
        inner class Transport : CodexTransport {
            lateinit var receive: (String)->Unit
            val writes = mutableListOf<JSONObject>(); val deletes = mutableListOf<JSONObject>(); val starts = mutableListOf<JSONObject>()
            val queue = mutableListOf<JSONObject>(); val history = mutableListOf<JSONObject>()
            var loseWrite = false; var storeHistory = false; var loseDelete = false; var removeOnDelete = true
            override fun connect(opened:()->Unit,message:(String)->Unit,failed:(Throwable)->Unit) { receive=message; opened() }
            override fun send(message:String):Boolean {
                val f=JSONObject(message); val m=f.optString("method")
                if (m in setOf("turn/steer","thread/queue/add")) {
                    writes += f
                    if (storeHistory) history += row(f)
                    return !loseWrite
                }
                if (m=="thread/queue/start") starts += f
                if (m=="thread/queue/delete") {
                    deletes += f
                    if (removeOnDelete) queue.removeAll { it.optString("id")==f.getJSONObject("params").getString("queuedSubmissionId") }
                    if (loseDelete) return false
                }
                if (f.has("id")) reply(f, when(m) {
                    "thread/queue/list" -> obj("data" to JSONArray(queue))
                    "thread/items/list" -> obj("data" to JSONArray(history.map { obj("item" to it,"turnId" to "turn","completedAtMs" to 1) }))
                    else -> JSONObject()
                })
                return true
            }
            fun reply(frame:JSONObject,result:JSONObject=JSONObject()) { receive(obj("id" to frame.get("id"),"result" to result).toString()) }
            fun row(frame:JSONObject) = obj("id" to "native-"+client(frame),"type" to "userMessage","clientUserMessageId" to client(frame),
                "content" to frame.getJSONObject("params").getJSONArray("input"))
            override fun close() {}
        }
        fun bind(transport:Transport):CodexRpc {
            val value=CodexRpc("fixture",null,{}, {},transport); runBlocking { value.connect() }; set(live,"rpc",value); return value
        }
        fun client(f:JSONObject)=f.getJSONObject("params").getString("clientUserMessageId")
        fun send(text:String="input") { controller.draft(text); controller.send(); pump() }
        fun event(item:JSONObject) { controller.javaClass.getDeclaredMethod("event",live.javaClass,JSONObject::class.java).apply { isAccessible=true }.invoke(controller,live,
            obj("method" to "item/started","params" to obj("threadId" to "native","turnId" to "turn","item" to item))); publish(); pump() }
        fun nativeQueue(rows:List<JSONObject>) { controller.javaClass.getDeclaredMethod("applyQueue",live.javaClass,List::class.java).apply { isAccessible=true }.invoke(controller,live,rows); publish(); pump() }
        fun publish() { controller.javaClass.getDeclaredMethod("publish").apply { isAccessible=true }.invoke(controller) }
        fun pump() { Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(40)) }
        fun await(check:()->Boolean) { val end=System.nanoTime()+3_000_000_000; while(!check()&&System.nanoTime()<end){Thread.sleep(5);pump()}; assertTrue("fixture timed out",check()) }
        fun view()=controller.state.value.selected!!
        fun offline() { controller.disconnectAll(); pump() }
        fun enqueueLocal(text: String = "local payload") {
            offline(); set(controller, "scope", paused)
            controller.draft(text); controller.send()
            (field(live, "connectJob") as? Job)?.cancel(); set(live, "connectJob", null)
            set(live, "observing", false); set(live, "view", view().copy(loading = false, connected = false))
            set(controller, "scope", running); publish(); pump()
        }
        fun restoreFromDisk() {
            val saved=(field(controller,"store") as SessionStore).load().single { it.id==id }
            set(live,"record",saved); publish()
        }
        fun reconnectFixture() {
            rpc=bind(native); set(live,"observing",true)
            set(live,"view",view().copy(connected=true,loading=false)); nativeQueue(native.queue.toList())
        }
        fun blockStore():AtomicFile {
            (field(controller,"saveJob") as? Job)?.cancel()
            val store=field(controller,"store")!!; val original=field(store,"file") as AtomicFile
            val blocked=File(app.filesDir,"dismiss-blocker"); blocked.writeText("not directory")
            set(store,"file",AtomicFile(File(blocked,"sessions.json"))); return original
        }
        fun restoreStore(file:AtomicFile) { set(field(controller,"store")!!,"file",file) }
        override fun close() { controller.disconnectAll(); paused.cancel(); running.cancel(); rpc.close(); assertTrue(uncaught.toString(),uncaught.isEmpty()) }
    }
    private class OpenCodeCarrier(val mode:String="truncated"):AutoCloseable {
        val listener=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
        @Volatile var deleted=0; @Volatile var gets=0; @Volatile var closed=false; @Volatile var queued=true
        val entry=obj("id" to "msg_fixture","type" to "user","delivery" to "queue","payload" to obj("text" to "native input"))
        private val worker=Thread {
            while(!closed) try { listener.accept().use { socket ->
                socket.soTimeout=5000
                val input=socket.getInputStream().bufferedReader(); val request=input.readLine()?:return@use
                while(input.readLine().isNotEmpty()){}
                val path=request.split(' ')[1]; val delete=request.startsWith("DELETE")
                val body=if(delete){deleted++;queued=false;obj("data" to JSONObject())} else {
                    gets++
                    when {
                        path.contains("/message?") || path.endsWith("/permission") || path.endsWith("/form") -> obj("data" to JSONArray())
                        path.endsWith("/inbox") -> obj("data" to JSONArray().also { if(queued)it.put(entry) })
                        path=="/api/session/active" -> obj("data" to JSONObject())
                        else -> obj("data" to obj("id" to "ses_fixture","title" to "fixture"))
                    }
                }.toString().toByteArray()
                val out=socket.getOutputStream()
                val status=if(delete && mode=="500") "500 Server Error" else "200 OK"
                out.write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                out.write(if(delete && mode=="truncated")body.copyOf(body.size/2) else body);out.flush()
            } } catch(e:Exception){if(!closed)throw e}
        }.apply {isDaemon=true;start()}
        override fun close(){closed=true;listener.close();worker.join(1000)}
    }
    @Test fun openCodeActualDeleteWithTruncatedAckStillHidesAndReconcilesWithoutRetry() = openCodeCancel("truncated")
    @Test fun openCodeAmbiguous500CancellationIsUnknownNotAConfirmedNativeRefusal() = openCodeCancel("500")
    private fun openCodeCancel(mode:String) = OpenCodeCarrier(mode).use { server ->
        Harness("OpenCode").use { h ->
            val raw=h.view().copy(activeTurnId=null,queue=OpenCodeProjection.queue(JSONArray().put(server.entry)))
            val api=OpenCodeClient("http://127.0.0.1:${server.listener.localPort}","public-fixture")
            val oc=OpenCodeSession(h.record,AgentRuntime(h.app),{
                h.controller.javaClass.getDeclaredMethod("persist").apply{isAccessible=true}.invoke(h.controller)
            },{"unused"},{view,models->set(h.live,"view",view);set(h.live,"models",models);h.publish()},{})
            set(oc,"client",api);set(oc,"view",raw);set(oc,"queue",JSONArray().put(server.entry));set(h.live,"openCode",oc);set(h.live,"view",raw);h.publish()
            try {
                h.controller.removeQueued("msg_fixture");h.await{server.deleted==1 && server.gets>=6}
                assertTrue(h.view().queue.isEmpty());assertEquals("unknown",h.record.queueDismissals.single().state)
                h.restoreFromDisk();repeat(4){h.publish();h.pump()}
                assertTrue(h.view().queue.isEmpty());assertEquals(1,server.deleted);assertTrue(h.native.writes.isEmpty())
            } finally {oc.close();api.close()}
        }
    }
    @Test fun busyPendingDismissalHidesOnlyTheCardAndLateAckHistoryStillShowsOnceAfterIndexRecovery() = Harness().use { h ->
        h.send(); val f=h.native.writes.single(); val client=h.client(f)
        assertTrue(h.view().queue.single().canDismiss); assertFalse(h.view().queue.single().canRemove)
        h.controller.removeQueued(h.view().queue.single().id); h.pump()
        assertTrue(h.view().queue.isEmpty()); assertEquals("pending",h.record.submissions.single().state)
        assertTrue(h.record.submissions.single().dismissed); assertTrue(h.native.deletes.isEmpty())
        h.native.reply(f,obj("turnId" to "turn")); h.pump(); h.restoreFromDisk()
        assertTrue(h.view().queue.isEmpty()); assertEquals("accepted",h.record.submissions.single().state)
        h.event(h.native.row(f)); assertEquals(listOf(client),h.view().messages.map{it.id})
        repeat(4){h.publish()}; assertTrue(h.view().queue.isEmpty()); assertEquals(1,h.native.writes.size)
    }
    @Test fun unknownIsDismissibleOfflineAndARealLateHistoryDoesNotRecreateItsFloatingCard() = Harness().use { h ->
        h.native.loseWrite=true; h.send(); val f=h.native.writes.single()
        assertEquals("unknown",h.record.submissions.single().state); h.offline()
        h.controller.removeQueued(h.view().queue.single().id); h.pump(); h.restoreFromDisk()
        assertTrue(h.view().queue.isEmpty()); assertEquals("unknown",h.record.submissions.single().state)
        h.reconnectFixture(); h.event(h.native.row(f))
        assertTrue(h.view().queue.isEmpty()); assertEquals(1,h.view().messages.size)
        assertEquals(1,h.native.writes.size); assertTrue(h.native.deletes.isEmpty())
    }
    @Test fun lostMutationAckGetsOneReadOnlyHistoryReconciliationWithoutASecondNativeWrite() = Harness().use { h ->
        h.native.loseWrite=true; h.native.storeHistory=true; h.send()
        h.await { h.record.submissions.single().nativeConsumed }
        assertEquals("accepted",h.record.submissions.single().state); assertEquals(1,h.view().messages.size)
        assertTrue(h.view().queue.isEmpty()); assertEquals(1,h.native.writes.size)
    }
    @Test fun ackBindsQueueWithoutClientEchoExactlyAndOfflineDeletionOnlyRetractsAfterReconnect() = Harness().use { h ->
        set(h.live,"view",h.view().copy(activeTurnId=null)); h.send(); val f=h.native.writes.single()
        val queue=obj("id" to "native-q", "input" to f.getJSONObject("params").getJSONArray("input"))
        h.native.queue += queue
        h.native.reply(f,obj("queuedSubmission" to queue)); h.pump()
        assertEquals(1,h.view().queue.size); assertEquals(h.client(f),h.view().queue.single().clientUserMessageId)
        assertEquals("native-q",h.record.submissions.single().nativeQueueId)
        h.offline(); h.controller.removeQueued("native-q"); h.pump(); h.restoreFromDisk()
        assertTrue(h.view().queue.isEmpty()); assertTrue(h.native.deletes.isEmpty())
        h.reconnectFixture(); h.await { h.native.deletes.size==1 }
        assertEquals("native-q",h.native.deletes.single().getJSONObject("params").getString("queuedSubmissionId"))
        h.await { h.record.queueDismissals.single().state=="confirmed" }
        assertTrue(h.view().queue.isEmpty()); assertEquals(1,h.native.writes.size)
    }
    @Test fun cancelAckLossIsNotRetriedOrCalledSuccessAndLateQueueCannotResurrectTheCard() = Harness().use { h ->
        val raw=obj("id" to "native-q","input" to JSONArray().put(obj("type" to "text","text" to "native input")))
        h.native.queue += raw; h.native.loseDelete=true; h.native.removeOnDelete=false; h.nativeQueue(listOf(raw))
        h.controller.removeQueued("native-q"); h.await { h.native.deletes.size==1 }
        assertEquals("unknown",h.record.queueDismissals.single().state); assertTrue(h.view().queue.isEmpty())
        h.native.reply(h.native.deletes.single()); h.pump(); h.restoreFromDisk()
        repeat(4){h.nativeQueue(listOf(raw));h.publish()}
        assertTrue(h.view().queue.isEmpty()); assertEquals(1,h.native.deletes.size)
        assertEquals("unknown",h.record.queueDismissals.single().state)
    }
    @Test fun deleteVersusLocalClaimHasOneWinnerAndFailureBeforePersistKeepsTheCardAndPayload() = Harness().use { h ->
        h.offline(); set(h.controller,"scope",h.paused)
        h.controller.draft("local"); h.controller.send()
        (field(h.live,"connectJob") as? Job)?.cancel(); set(h.live,"connectJob",null)
        set(h.controller,"scope",h.running); h.pump()
        val queue=h.view().queue.single(); val original=h.blockStore()
        h.controller.removeQueued(queue.id); h.pump()
        assertEquals("local",h.record.submissions.single().state); assertFalse(h.record.submissions.single().dismissed)
        assertEquals(queue.id,h.view().queue.single().id); assertTrue(h.native.writes.isEmpty()); assertTrue(h.native.deletes.isEmpty())
        h.restoreStore(original); h.controller.removeQueued(queue.id); h.pump()
        assertEquals("cancelled",h.record.submissions.single().state); assertTrue(h.record.submissions.single().dismissed)
        h.reconnectFixture(); repeat(4){h.publish();h.pump()}; assertTrue(h.native.writes.isEmpty())
    }
    @Test fun cancellationFenceSaveFailureNeverTouchesNativeAndCanRecoverAtAnExplicitCheckpoint() = Harness().use { h ->
        val raw=obj("id" to "q","input" to JSONArray())
        h.native.queue += raw; h.nativeQueue(listOf(raw))
        h.offline(); h.controller.removeQueued("q"); h.pump(); val original=h.blockStore()
        h.reconnectFixture(); h.await { field(h.live,"dispatchStorageBlocked")==true }
        assertTrue(h.native.deletes.isEmpty()); assertEquals("waiting",h.record.queueDismissals.single().state)
        assertTrue(h.view().queue.isEmpty()); repeat(5){h.publish();h.pump()}; assertTrue(h.native.deletes.isEmpty())
        h.restoreStore(original); h.controller.saveDrafts(); h.publish(); h.await { h.native.deletes.size==1 }
    }
    @Test fun heldAddAckCannotStartAStoppedQueueAfterTheUserDismissedItsPreparedInput() = Harness().use { h ->
        set(h.live,"view",h.view().copy(activeTurnId=null));set(h.live,"queueNeedsExplicitStart",true)
        runBlocking { h.controller.manage(obj("action" to "send","id" to h.id,"text" to "prepared input","requestId" to "prepared-send")) }
        h.await { h.native.writes.size==1 }
        val frame=h.native.writes.single();assertEquals("thread/queue/add",frame.getString("method"))
        h.controller.removeQueued(h.view().queue.single().id);h.pump()
        val queued=obj("id" to "held-q","input" to frame.getJSONObject("params").getJSONArray("input"))
        h.native.queue += queued;h.native.reply(frame,obj("queuedSubmission" to queued))
        h.await { h.native.deletes.size==1 };h.await { h.record.queueDismissals.single().state=="confirmed" }
        assertTrue(h.native.starts.isEmpty());assertEquals("accepted",h.record.submissions.single().state)
        assertTrue(h.record.submissions.single().dismissed);assertFalse(h.record.submissions.single().nativeConsumed)
        assertTrue(h.view().queue.isEmpty());assertTrue(h.view().messages.isEmpty())
    }
    @Test fun explicitDisconnectCancelsScheduledRetractionAndOnlyFreshObservationCanContinueIt() = Harness().use { h ->
        val raw=obj("id" to "scheduled-q","input" to JSONArray());h.native.queue+=raw;h.nativeQueue(listOf(raw))
        h.controller.removeQueued("scheduled-q");h.controller.disconnectAll();h.pump()
        assertTrue(h.native.deletes.isEmpty());assertEquals("waiting",h.record.queueDismissals.single().state)
        assertTrue(h.view().queue.isEmpty());h.reconnectFixture();h.await { h.native.deletes.size==1 }
    }
    @Test fun hidingAnUnretractableNativeQueueDoesNotAuthorizeChangingItsSettings() = Harness().use { h ->
        val raw=obj("id" to "blocked-q","input" to JSONArray());h.native.queue+=raw
        set(h.live,"view",h.view().copy(activeTurnId=null));h.native.removeOnDelete=false;h.native.loseDelete=true;h.nativeQueue(listOf(raw))
        h.controller.removeQueued("blocked-q");h.await { h.native.deletes.size==1 }
        assertTrue(h.view().queue.isEmpty());h.controller.configurePermission("ignored");h.pump()
        assertEquals("请在任务与队列结束后修改设置",h.view().error)
    }
    @Test fun localEditReturnsExactTextAndMediaToComposerWithoutSendingOnReconnect() = Harness().use { h ->
        h.record.images += StoredImage("image", "file:///fixture", "/fixture", "image")
        h.record.skills += DraftSkill("skill", "Skill", "/skill")
        h.enqueueLocal("original\n@\"/workspace/中文.kt\"")
        val card = h.view().queue.single(); assertTrue(card.canEdit)
        h.controller.restoreQueuedToComposer(card.id); h.pump(); h.restoreFromDisk()
        assertEquals("original\n@\"/workspace/中文.kt\"", h.record.draft)
        assertEquals("image", h.record.images.single().id); assertEquals("skill", h.record.skills.single().id)
        assertEquals("cancelled", h.record.submissions.single().state); assertTrue(h.view().queue.isEmpty())
        h.reconnectFixture(); repeat(3) { h.publish(); h.pump() }
        assertTrue(h.native.writes.isEmpty()); assertTrue(h.native.deletes.isEmpty())
    }
    @Test fun actualDispatchWinningStillAllowsLocalEditingWithoutAnotherWriteOrClaimingCancellation() = Harness().use { h ->
        h.enqueueLocal(); val stale = h.view().queue.single()
        h.reconnectFixture(); h.await { h.native.writes.size == 1 }
        assertEquals("pending", h.record.submissions.single().state)
        h.controller.restoreQueuedToComposer(stale.id); h.pump()
        assertEquals("local payload", h.record.draft); assertEquals("pending", h.record.submissions.single().state)
        assertTrue(h.record.submissions.single().dismissed); assertTrue(h.record.receiptRecoveryRequired.contains(h.record.submissions.single().id))
        assertEquals(1, h.native.writes.size)
    }
    @Test fun localEditRejectsComposerAndImportConflictsAndSaveFailureKeepsTheOriginalQueue() = Harness().use { h ->
        h.enqueueLocal(); val id = h.view().queue.single().id
        h.controller.draft("newer"); h.controller.restoreQueuedToComposer(id); h.pump()
        assertEquals("newer", h.record.draft); assertEquals("local", h.record.submissions.single().state)
        h.controller.draft(""); set(h.live, "view", h.view().copy(attachments = listOf(Attachment("busy", "file:///busy", "import", true))))
        h.controller.restoreQueuedToComposer(id); h.pump()
        assertEquals("", h.record.draft); assertEquals("local", h.record.submissions.single().state)
        assertTrue(h.view().error.orEmpty().contains("正在准备内容"))
        set(h.live, "view", h.view().copy(attachments = emptyList()))
        val original = h.blockStore(); h.controller.restoreQueuedToComposer(id); h.pump()
        assertEquals("", h.record.draft); assertEquals("local", h.record.submissions.single().state)
        assertFalse(h.record.submissions.single().dismissed); assertTrue(h.native.writes.isEmpty())
        h.restoreStore(original); h.controller.restoreQueuedToComposer(id); h.pump()
        assertEquals("local payload", h.record.draft); assertTrue(h.view().queue.isEmpty())
    }
    companion object {
        private fun field(o:Any,n:String):Any?=o.javaClass.getDeclaredField(n).apply{isAccessible=true}.get(o)
        private fun set(o:Any,n:String,v:Any?){o.javaClass.getDeclaredField(n).apply{isAccessible=true}.set(o,v)}
    }
}
