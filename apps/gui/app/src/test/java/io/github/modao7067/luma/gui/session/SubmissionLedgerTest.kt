package io.github.modao7067.luma.gui.session

import io.github.modao7067.luma.gui.agent.withMediaContext
import org.junit.Assert.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test

class SubmissionLedgerTest {
    private fun record() = SessionRecord("local", "Title", "root", "/work", draft = "first",
        images = mutableListOf(StoredImage("old", "file:///old", "/old", "old")),
        skills = mutableListOf(DraftSkill("skill", "Skill", "/skill")))
    private fun view(r: SessionRecord) = SessionView(SessionSummary(r.id, r.title, r.environment, r.cwd))
    @Test fun eachSendOwnsAnIndependentPayloadAndComposerIsImmediatelyReusable() {
        val r = record(); SubmissionLedger.begin(r, "one")
        assertEquals("", r.draft); assertTrue(r.images.isEmpty()); assertTrue(r.skills.isEmpty())
        r.draft = "second"; r.draftRevision++
        SubmissionLedger.begin(r, "two")
        assertEquals(listOf("one", "two"), r.submissions.map { it.id })
        assertEquals(listOf("old"), r.submissions.first().images.map { it.id })
        assertTrue(r.submissions.last().images.isEmpty())
        assertEquals(2, SubmissionLedger.project(r, view(r)).messages.size)
    }
    @Test fun confirmationOnlySettlesItsExactIdAndKeepsNewInput() {
        val r = record(); SubmissionLedger.begin(r, "one"); r.draft = "second"; r.draftRevision++; SubmissionLedger.begin(r, "two")
        r.draft = "third"; r.draftRevision++
        SubmissionLedger.accepted(r, id = "two")
        assertEquals("pending", SubmissionLedger.find(r, "one")!!.state)
        assertEquals("accepted", SubmissionLedger.find(r, "two")!!.state)
        assertEquals("third", r.draft)
    }
    @Test fun elapsedTimeAndClockResetNeverRestoreOrFailUnknownInput() {
        val r = record(); SubmissionLedger.begin(r, "one", 100)
        for (time in listOf(30_100L, 86400000L, 0L)) assertFalse(SubmissionLedger.expire(r, time))
        SubmissionLedger.state(r, "one", "unknown")
        assertEquals("", r.draft); assertTrue(SubmissionLedger.pending(r))
        val projected = SubmissionLedger.project(r, view(r))
        assertEquals("pending", projected.messages.single().delivery); assertTrue(projected.sending)
        assertFalse(projected.uncertainSubmission)
        r.draft = "next"; r.draftRevision++; SubmissionLedger.begin(r, "two")
        assertEquals(2, r.submissions.size)
    }
    @Test fun lateQueuedOrUnknownSnapshotCannotUndoNativeAdmission() {
        val r = record(); SubmissionLedger.begin(r, "one"); SubmissionLedger.accepted(r, id = "one")
        SubmissionLedger.state(r, "one", "queued"); SubmissionLedger.state(r, "one", "unknown")
        assertEquals("accepted", r.submissions.single().state)
        SubmissionLedger.rejected(r, "one")
        assertEquals("", r.draft); assertEquals("accepted", r.submissions.single().state)
    }
    @Test fun hostQueueOwnsDisplayUntilDispatchAndCancellationDoesNotCreateBubble() {
        val r = record(); SubmissionLedger.begin(r, "one"); SubmissionLedger.state(r, "one", "queued")
        assertTrue(SubmissionLedger.project(r, view(r)).messages.isEmpty())
        SubmissionLedger.state(r, "one", "pending")
        assertEquals(1, SubmissionLedger.project(r, view(r)).messages.size)
        SubmissionLedger.state(r, "one", "unknown")
        assertEquals("pending", SubmissionLedger.project(r, view(r)).messages.single().delivery)
        r.draft = "cancel"; r.draftRevision++; SubmissionLedger.begin(r, "two")
        SubmissionLedger.state(r, "two", "queued"); SubmissionLedger.state(r, "two", "cancelled")
        assertEquals(listOf("one"), SubmissionLedger.project(r, view(r)).messages.map { it.id })
    }
    @Test fun hostQueueDoesNotBecomeStickyNativeQueueHistory() {
        val r = record(); SubmissionLedger.begin(r, "one"); SubmissionLedger.state(r, "one", "queued")
        SubmissionLedger.project(r, view(r).copy(queue = listOf(QueueEntry("one", "first", "待发", clientUserMessageId = "one", hostOwned = true))))
        assertFalse(r.submissions.single().nativeQueued)
        SubmissionLedger.state(r, "one", "unknown")
        assertEquals("pending", SubmissionLedger.project(r, view(r)).messages.single().delivery)
    }
    @Test fun definitiveRefusalRestoresOnlyUntouchedComposer() {
        val r = record(); SubmissionLedger.begin(r, "one"); SubmissionLedger.rejected(r, "one")
        assertEquals("first", r.draft); assertEquals(listOf("old"), r.images.map { it.id })
        val newer = record(); SubmissionLedger.begin(newer, "one"); newer.draft = "new"; newer.draftRevision++
        SubmissionLedger.rejected(newer, "one")
        assertEquals("new", newer.draft); assertEquals("first", newer.submissions.single().text)
    }
    @Test fun explicitRecoveryKeepsNewDraftAndRecoversOriginalAttachmentsWithoutSending() {
        val r = record(); val outbox = linkedMapOf<String, ConversationItem>()
        SubmissionLedger.begin(r, "one"); r.draft = "new"; r.draftRevision++
        SubmissionLedger.rejected(r, "one")
        assertEquals(listOf("one"), SubmissionLedger.project(r, view(r), outbox).messages.map { it.id })
        SubmissionLedger.recover(r, "one")
        assertEquals("new\nfirst", r.draft); assertEquals(listOf("old"), r.images.map { it.id })
        assertEquals(listOf("skill"), r.skills.map { it.id }); assertEquals("rejected", r.submissions.single().state)
        assertTrue(SubmissionLedger.project(r, view(r), outbox).messages.isEmpty())
        val reloaded = decodeSessionIndex(JSONObject(encodeSessionIndex(listOf(r)).toString())).single()
        assertTrue(SubmissionLedger.project(reloaded, view(reloaded)).messages.isEmpty())
    }
    @Test fun sameTextTypedAgainIsIndependentAndLateAckDoesNotEraseIt() {
        val r = record(); SubmissionLedger.begin(r, "one"); r.draft = "first"; r.draftRevision++
        SubmissionLedger.begin(r, "two"); r.draft = "first"; r.draftRevision++
        SubmissionLedger.accepted(r, id = "one")
        assertEquals("first", r.draft); assertEquals("pending", SubmissionLedger.find(r, "two")!!.state)
        assertThrows(IllegalStateException::class.java) { SubmissionLedger.begin(r, "two") }
    }
    @Test fun acceptedBridgeHistoryUsesNativeRowsWithoutRecreatingOptimisticTranscriptOnRestart() {
        for (cli in CliCatalog.bridged) {
            val r = SessionRecord("local", "title", "root", "/work", cli = cli, draft = "same text")
            SubmissionLedger.begin(r, "client"); SubmissionLedger.accepted(r, id = "client")
            val native = listOf(ConversationItem("native-history-id", "user", "same text"))
            assertEquals("Only native history for $cli", listOf("native-history-id"), SubmissionLedger.messages(r, native).map { it.id })
            r.draft = "same text"; SubmissionLedger.begin(r, "next")
            assertEquals(listOf("native-history-id", "next"), SubmissionLedger.messages(r, native).map { it.id })
        }
    }
    @Test fun disconnectAndExecutionErrorsDoNotChangeMessageDeliveryOrDraft() {
        val r = record(); SubmissionLedger.begin(r, "one"); SubmissionLedger.accepted(r, id = "one")
        val projected = SubmissionLedger.project(r, view(r).copy(error = "upstream EOF", activeTurnId = "running"))
        assertEquals("upstream EOF", projected.error); assertEquals("running", projected.activeTurnId)
        assertEquals("", projected.messages.single().delivery); assertEquals("", r.draft)
    }
    @Test fun unchangedNativeMessagesAndScopedMediaPreserveReferenceIdentity() {
        val emptyRecord = SessionRecord("idle", "Idle", "root", "/work", cli = "Grok Build")
        val itemPlain = ConversationItem("m1", "assistant", "hello")
        val itemMedia = ConversationItem("m2", "user", "pic", media = listOf(MediaResource("file:///a.png", "a.png", "image/png", sessionId = "idle", cwd = "/work")))
        val native = listOf(itemPlain, itemMedia)
        assertSame(native, SubmissionLedger.messages(emptyRecord, native))
        assertSame(itemPlain, itemPlain.withMediaContext("idle", "/work"))
        assertSame(itemMedia, itemMedia.withMediaContext("idle", "/work"))

        val bridged = SessionRecord("bg", "Bg", "root", "/work", cli = "Grok Build", draft = "done")
        SubmissionLedger.begin(bridged, "sub-done"); SubmissionLedger.accepted(bridged, id = "sub-done")
        assertSame(native, SubmissionLedger.messages(bridged, native))
    }
    @Test fun liveBridgedAcceptanceHoldsOptimisticBubbleUntilNativeUserRowAppearsAndRestoredRejectionWithdrawsBubble() {
        val r = SessionRecord("live-bg", "Live", "root", "/work", cli = "Antigravity", draft = "hello")
        val outbox = linkedMapOf<String, ConversationItem>()
        val history = listOf(ConversationItem("hist-1", "user", "earlier", nativeInputEvidence = true))
        SubmissionLedger.begin(r, "sub-1")
        val pendingView = SubmissionLedger.project(r, view(r).copy(messages = history), outbox)
        assertTrue(pendingView.awaitingHandshake)
        assertEquals(listOf("hist-1", "sub-1"), pendingView.messages.map { it.id })
        SubmissionLedger.accepted(r, id = "sub-1")
        val beforeNativeRow = SubmissionLedger.project(r, view(r).copy(messages = history), outbox)
        assertFalse(beforeNativeRow.awaitingHandshake)
        assertEquals(listOf("hist-1", "sub-1"), beforeNativeRow.messages.map { it.id })
        assertEquals("", beforeNativeRow.messages.last().delivery)
        val afterNativeRow = SubmissionLedger.project(
            r,
            view(r).copy(messages = history + ConversationItem("native-new-user", "user", "hello", nativeInputEvidence = true)),
            outbox,
        )
        assertEquals(listOf("hist-1", "native-new-user"), afterNativeRow.messages.map { it.id })

        val failRecord = record()
        val failOutbox = linkedMapOf<String, ConversationItem>()
        SubmissionLedger.begin(failRecord, "fail-1")
        assertEquals(1, SubmissionLedger.project(failRecord, view(failRecord), failOutbox).messages.size)
        SubmissionLedger.rejected(failRecord, "fail-1")
        val afterReject = SubmissionLedger.project(failRecord, view(failRecord), failOutbox)
        assertTrue(afterReject.messages.isEmpty())
        assertEquals("first", failRecord.draft)
        assertEquals(listOf("old"), failRecord.images.map { it.id })
        assertEquals(listOf("skill"), failRecord.skills.map { it.id })
    }
    @Test fun secondAcceptedBridgedSubmissionStaysVisibleWhenFirstUncorrelatedNativeUserRowArrives() {
        val r = SessionRecord("live-bg-2", "Live", "root", "/work", cli = "Antigravity", draft = "msg A")
        val outbox = linkedMapOf<String, ConversationItem>()
        val history = listOf(ConversationItem("hist-1", "user", "earlier", nativeInputEvidence = true))

        SubmissionLedger.begin(r, "sub-a")
        SubmissionLedger.project(r, view(r).copy(messages = history), outbox)
        SubmissionLedger.accepted(r, id = "sub-a")
        SubmissionLedger.project(r, view(r).copy(messages = history), outbox)

        r.draft = "msg B"; r.draftRevision++
        SubmissionLedger.begin(r, "sub-b")
        SubmissionLedger.project(r, view(r).copy(messages = history), outbox)
        SubmissionLedger.accepted(r, id = "sub-b")
        val bothOptimistic = SubmissionLedger.project(r, view(r).copy(messages = history), outbox)
        assertEquals(listOf("hist-1", "sub-a", "sub-b"), bothOptimistic.messages.map { it.id })

        val afterFirstNative = history + ConversationItem("native-u1", "user", "msg A", nativeInputEvidence = true)
        SubmissionLedger.consumed(r, "sub-a")
        repeat(3) {
            val projected = SubmissionLedger.project(r, view(r).copy(messages = afterFirstNative), outbox)
            assertEquals(listOf("hist-1", "native-u1", "sub-b"), projected.messages.map { it.id })
        }

        val afterSecondNative = afterFirstNative + ConversationItem("native-u2", "user", "msg B", nativeInputEvidence = true)
        val settled = SubmissionLedger.project(r, view(r).copy(messages = afterSecondNative), outbox)
        assertEquals(listOf("hist-1", "native-u1", "native-u2"), settled.messages.map { it.id })
    }

    @Test fun coldStartPendingNormalizesToUnknownAndIdleReconciliationOrManualRecoveryUnblocksUser() = runBlocking {
        val cold = record()
        SubmissionLedger.begin(cold, "orphan-pending")
        assertTrue(SubmissionLedger.project(cold, view(cold)).awaitingHandshake)
        val reloaded = decodeSessionIndex(JSONObject(encodeSessionIndex(listOf(cold)).toString())).single()
        assertEquals("unknown", reloaded.submissions.single().state)
        val idleView = SubmissionLedger.project(reloaded, view(reloaded))
        assertFalse(idleView.awaitingHandshake)
        assertEquals("pending", idleView.messages.single().delivery)
        assertTrue(idleView.messages.single().canRecover)
        val runningView = SubmissionLedger.project(reloaded, view(reloaded).copy(activeTurnId = "turn-1"))
        assertFalse(runningView.messages.single().canRecover)

        val cancelledRecord = record()
        try {
            SubmissionLedger.admit<Unit>(
                record = cancelledRecord,
                id = "cancel-mid-flight",
                persist = {},
                busy = false,
                setSending = {},
                isRejected = { false },
            ) { throw CancellationException("transport cancelled") }
            fail("Expected CancellationException")
        } catch (_: CancellationException) {}
        assertEquals("unknown", cancelledRecord.submissions.single().state)
        assertFalse(SubmissionLedger.project(cancelledRecord, view(cancelledRecord)).awaitingHandshake)

        val oc = SessionRecord("oc", "OC", "noroot", "/work", cli = "OpenCode", draft = "hello oc")
        SubmissionLedger.begin(oc, "msg_missing"); SubmissionLedger.state(oc, "msg_missing", "unknown")
        assertEquals(setOf("msg_missing"), reconcileOpenCodeIdleSubmissions(oc, historyIds = emptySet(), queuedIds = emptySet(), admitting = emptySet()))
        assertEquals("rejected", oc.submissions.single().state)
        assertEquals("hello oc", oc.draft)

        val codexResumable = SessionRecord("cx", "CX", "noroot", "/work", cli = "Codex", draft = "uncertain text")
        codexResumable.images += StoredImage("img1", "file:///img1", "/img1", "img1")
        SubmissionLedger.begin(codexResumable, "cx-sub"); SubmissionLedger.state(codexResumable, "cx-sub", "unknown")
        val uncorrelatedItems = mapOf("native-rollout-u1" to ConversationItem("native-rollout-u1", "user", "uncertain text"))
        assertFalse(reconcileCodexIdleSubmissions(codexResumable, historyResumable = true, items = uncorrelatedItems, nativeToClientId = emptyMap(), queueRaw = emptyList(), admitting = emptySet()))
        assertEquals("unknown", codexResumable.submissions.single().state)

        SubmissionLedger.recover(codexResumable, "cx-sub")
        assertEquals("uncertain text", codexResumable.draft)
        assertEquals(listOf("img1"), codexResumable.images.map { it.id })
        assertTrue(codexResumable.submissions.single().dismissed)
        assertTrue("cx-sub" in codexResumable.receiptRecoveryRequired)
        assertEquals("unknown", codexResumable.submissions.single().state)
        assertEquals(listOf("native-rollout-u1"), SubmissionLedger.project(codexResumable, view(codexResumable).copy(messages = uncorrelatedItems.values.toList())).messages.map { it.id })

        val codexEmpty = SessionRecord("cx2", "CX2", "noroot", "/work", cli = "Codex", draft = "never arrived")
        SubmissionLedger.begin(codexEmpty, "cx-absent"); SubmissionLedger.state(codexEmpty, "cx-absent", "unknown")
        assertTrue(reconcileCodexIdleSubmissions(codexEmpty, historyResumable = true, items = emptyMap(), nativeToClientId = emptyMap(), queueRaw = emptyList(), admitting = emptySet()))
        assertEquals("rejected", codexEmpty.submissions.single().state)
        assertEquals("never arrived", codexEmpty.draft)
    }

    @Test fun durableCheckpointPropagatesDiskFailureAndRollsBackAdmitState() { runBlocking {
        val dir = Files.createTempDirectory("luma-store-test").toFile()
        try {
            var failDisk = false
            val written = mutableListOf<String>()
            val store = SessionStore(File(dir, "sessions.json")) { target, bytes ->
                if (failDisk) throw IOException("No space left on device")
                target.writeBytes(bytes)
                written += String(bytes, Charsets.UTF_8)
            }
            val r = record()
            store.saveDurable(listOf(r))
            assertEquals(1, written.size)

            failDisk = true
            try {
                SubmissionLedger.admit<Unit>(
                    record = r,
                    id = "disk-full-send",
                    persist = { store.saveDurable(listOf(r)) },
                    busy = false,
                    setSending = {},
                    isRejected = { false },
                ) { fail("send must not execute when durable checkpoint fails") }
                fail("Expected SubmissionPersistenceFailure")
            } catch (_: SubmissionPersistenceFailure) {}
            assertEquals("first", r.draft)
            assertEquals(listOf("old"), r.images.map { it.id })
            assertEquals(listOf("skill"), r.skills.map { it.id })
            assertTrue(r.submissions.isEmpty())

            store.save(listOf(r))
            assertThrows(IOException::class.java) { store.flush() }
        } finally {
            dir.deleteRecursively()
        }
    } }

    @Test fun cancelledSubmissionCannotBeOverwrittenByLateRejection() {
        val r = record()
        SubmissionLedger.begin(r, "cancelled-sub")
        SubmissionLedger.state(r, "cancelled-sub", "cancelled")
        SubmissionLedger.rejected(r, "cancelled-sub")
        assertEquals("cancelled", r.submissions.single().state)
        assertEquals("", r.draft)
    }

    @Test fun sessionStoreDrainWritesIsPerKeyAndCallbacksFireOnlyOnSuccess() {
        val dir = Files.createTempDirectory("luma-store-isolation").toFile()
        val slowEntered = java.util.concurrent.CountDownLatch(1)
        val releaseSlow = java.util.concurrent.CountDownLatch(1)
        val committed = java.util.concurrent.CountDownLatch(1)
        val failedCallbackFired = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            val fastStore = SessionStore(File(dir, "fast.json")) { target, bytes -> target.writeBytes(bytes) }
            val slowStore = SessionStore(File(dir, "slow.json")) { target, bytes ->
                slowEntered.countDown()
                releaseSlow.await(5, java.util.concurrent.TimeUnit.SECONDS)
                target.writeBytes(bytes)
            }
            val failStore = SessionStore(File(dir, "fail.json")) { _, _ -> throw IOException("disk full") }

            fastStore.save(listOf(record())) { committed.countDown() }
            fastStore.flush()
            assertTrue(committed.await(2, java.util.concurrent.TimeUnit.SECONDS))

            slowStore.save(listOf(record()))
            assertTrue(slowEntered.await(2, java.util.concurrent.TimeUnit.SECONDS))

            val started = System.nanoTime()
            fastStore.flush()
            val elapsedMs = (System.nanoTime() - started) / 1_000_000L
            assertTrue("fastStore.flush blocked on slowStore (${elapsedMs}ms)", elapsedMs < 1_000L)

            releaseSlow.countDown()
            slowStore.flush()

            failStore.save(listOf(record())) { failedCallbackFired.set(true) }
            assertThrows(IOException::class.java) { failStore.flush() }
            assertFalse(failedCallbackFired.get())
        } finally {
            releaseSlow.countDown()
            dir.deleteRecursively()
        }
    }

    @Test fun commandStoreSupportsDurableAndAsyncBackgroundWritesWithRollback() = runBlocking {
        val dir = Files.createTempDirectory("luma-cmd-store").toFile()
        try {
            val targetFile = File(dir, "commands.json")
            var failNext = false
            val reader: (File) -> ByteArray? = { f -> if (f.isFile) f.readBytes() else null }
            val writer: (File, ByteArray) -> Unit = { f, bytes ->
                if (failNext) throw IOException("write error")
                f.writeBytes(bytes)
            }
            val store = SessionCommandStore(targetFile, reader, writer)
            val req = JSONObject("""{"action":"create","cli":"Codex","cwd":"/work","requestId":"req_1"}""")
            val entry = store.reserveDurable(req, "sess-1")
            assertEquals("reserved", entry.state)
            store.updateAsync(entry, "starting")
            store.flush()

            val reloaded = SessionCommandStore(targetFile, reader, writer)
            assertEquals("starting", reloaded.entries["req_1"]?.state)

            failNext = true
            val req2 = JSONObject("""{"action":"create","cli":"Codex","cwd":"/work","requestId":"req_2"}""")
            try {
                reloaded.reserveDurable(req2, "sess-2")
                fail("Expected IOException")
            } catch (_: IOException) {
            }
            assertNull(reloaded.entries["req_2"])
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun coalescedCommandStoreFailureNotifiesEveryConcurrentWaiter() = runBlocking {
        val dir = Files.createTempDirectory("luma-cmd-coalesce").toFile()
        try {
            val targetFile = File(dir, "commands.json")
            val firstStarted = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val writeCalls = AtomicInteger(0)
            val reader: (File) -> ByteArray? = { f -> if (f.isFile) f.readBytes() else null }
            val writer: (File, ByteArray) -> Unit = { f, bytes ->
                when (writeCalls.incrementAndGet()) {
                    1 -> {
                        firstStarted.countDown()
                        releaseFirst.await(5, TimeUnit.SECONDS)
                        f.writeBytes(bytes)
                    }
                    else -> throw IOException("coalesced disk failure")
                }
            }
            val store = SessionCommandStore(targetFile, reader, writer)
            val req0 = JSONObject("""{"action":"create","cli":"Codex","cwd":"/work","requestId":"req_0"}""")
            val firstEntry = store.entries.getOrPut("req_0") {
                SessionCommandStore.Entry("req_0", SessionManagementProtocol.canonical(req0), "sess-0", null)
            }
            store.updateAsync(firstEntry, "starting")
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS))

            val reqA = JSONObject("""{"action":"create","cli":"Codex","cwd":"/work","requestId":"req_a"}""")
            val reqB = JSONObject("""{"action":"create","cli":"Codex","cwd":"/work","requestId":"req_b"}""")
            val aFailed = CompletableDeferred<Throwable?>()
            val bFailed = CompletableDeferred<Throwable?>()

            val jobA = launch(Dispatchers.Default) {
                val caught = try {
                    store.reserveDurable(reqA, "sess-a")
                    null
                } catch (e: IOException) {
                    e
                }
                aFailed.complete(caught)
            }
            val jobB = launch(Dispatchers.Default) {
                val caught = try {
                    store.reserveDurable(reqB, "sess-b")
                    null
                } catch (e: IOException) {
                    e
                }
                bFailed.complete(caught)
            }

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while ((store.entries["req_a"] == null || store.entries["req_b"] == null) && System.nanoTime() < deadline) {
                Thread.sleep(5)
            }
            assertNotNull(store.entries["req_a"])
            assertNotNull(store.entries["req_b"])
            releaseFirst.countDown()
            jobA.join()
            jobB.join()

            assertTrue(aFailed.await() is IOException)
            assertTrue(bFailed.await() is IOException)
            assertNull(store.entries["req_a"])
            assertNull(store.entries["req_b"])
            assertEquals(2, writeCalls.get())
        } finally {
            dir.deleteRecursively()
        }
    }
}
