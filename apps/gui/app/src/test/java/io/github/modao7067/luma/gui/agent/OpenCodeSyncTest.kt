package io.github.modao7067.luma.gui.agent

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException

class OpenCodeSyncTest {
    @Test fun exactTenSecondBoundaryUsesElapsedTimeAcrossRepeatedFailures() {
        var nanos = 40_000_000_000L
        val outage = OpenCodeSyncOutage { nanos }
        outage.failed(EOFException("first EOF"))
        assertEquals(10_000L, outage.remainingMillis)
        nanos += 9_999_999_999
        outage.failed(SocketTimeoutException("second timeout"))
        assertNull(outage.notice)
        assertEquals(1L, outage.remainingMillis)
        nanos++
        outage.noticeIfDue()
        assertTrue(outage.notice!!.contains("second timeout"))
        assertEquals(0L, outage.remainingMillis)
    }

    @Test fun dismissedNoticeDoesNotReappearUntilACompleteRecoveryAndNewOutage() {
        var nanos = 0L
        val outage = OpenCodeSyncOutage { nanos }
        outage.failed(EOFException())
        nanos = 10_000_000_000L; outage.noticeIfDue()
        assertNotNull(outage.notice)
        outage.dismiss()
        repeat(20) { nanos += 1_000_000_000; outage.failed(IOException("still down")); outage.noticeIfDue(); assertNull(outage.notice) }
        outage.recovered()
        assertFalse(outage.active)
        assertNull(outage.notice)
        outage.failed(EOFException("new outage"))
        nanos += 10_000_000_000; outage.noticeIfDue()
        assertTrue(outage.notice!!.contains("new outage"))
    }

    @Test fun authConfigurationAndProtocolFailuresAreImmediateButServerFailuresRetry() {
        for (failure in listOf(OpenCodeRejected(400, "bad config"), OpenCodeRejected(401, "auth"),
            OpenCodeRejected(403, "forbidden"), OpenCodeRejected(404, "missing session"), OpenCodeRejected(422, "invalid config"),
            IllegalStateException("wrong session"), org.json.JSONException("wrong protocol"), SSLHandshakeException("certificate"))) {
            val outage = OpenCodeSyncOutage { 0L }
            outage.failed(failure)
            assertTrue("$failure", outage.terminal)
            assertNotNull(outage.notice)
        }
        for (status in listOf(408, 425, 429, 500, 502, 503, 504)) {
            val outage = OpenCodeSyncOutage { 0L }
            outage.failed(OpenCodeRejected(status, "retry"))
            assertFalse(outage.terminal); assertNull(outage.notice)
        }
    }

    @Test fun successfulRecoveryClearsAnUndismissedNotice() {
        var nanos = 0L
        val outage = OpenCodeSyncOutage { nanos }
        outage.failed(IOException("down")); nanos = 10_000_000_000L; outage.noticeIfDue()
        assertNotNull(outage.notice)
        outage.recovered()
        assertNull(outage.notice); assertFalse(outage.active)
    }

    @Test fun fullSnapshotIsRetriedWithoutANewNativeEvent() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var live = false
        var attachments = 0
        var reads = 0
        val firstFailure = CompletableDeferred<Unit>()
        val recovered = CompletableDeferred<Unit>()
        val states = mutableListOf<OpenCodeSync.State>()
        val sync = OpenCodeSync(scope, { live }, { attachments++; live = true }, { all, _, catalog ->
            assertTrue(all)
            reads++
            if (reads == 1) { assertTrue(catalog); throw EOFException("truncated snapshot") }
            assertTrue("failed catalog hydration must also retry", catalog)
        }, { state -> states += state; if (state.connected) recovered.complete(Unit) else firstFailure.complete(Unit) })
        try {
            sync.start()
            withTimeout(2_000) { firstFailure.await() }
            assertTrue(sync.recovering)
            assertNull(states.last().notice)
            withTimeout(3_000) { recovered.await() }
            assertEquals(2, reads); assertEquals(1, attachments)
            assertFalse(sync.recovering)
            assertTrue(states.last().connected); assertNull(states.last().notice)
        } finally { sync.close(); scope.cancel() }
    }

    @Test fun streamReattachmentAloneCannotResetTheOutage() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var live = false
        var disconnect: ((Throwable) -> Unit)? = null
        var reads = 0
        var attachments = 0
        val snapshots = mutableListOf<Boolean>()
        val states = mutableListOf<OpenCodeSync.State>()
        val healthy = ChannelSignal()
        val failed = ChannelSignal()
        val permitSnapshot = CompletableDeferred<Unit>()
        val sync = OpenCodeSync(scope, { live }, { callback -> live = true; attachments++; disconnect = callback }, { all, _, _ ->
            snapshots += all
            reads++
            if (reads > 1) permitSnapshot.await()
        }, { state -> states += state; if (state.connected) healthy.send() else failed.send() })
        try {
            sync.start(); healthy.await()
            live = false; disconnect!!(EOFException("stream EOF")); failed.await()
            delay(180)
            assertEquals(2, attachments)
            assertTrue(live)
            assertTrue(sync.recovering)
            assertFalse(states.last().connected)
            permitSnapshot.complete(Unit); healthy.await()
            assertEquals(listOf(true, true), snapshots)
            assertFalse(sync.recovering)
        } finally { sync.close(); scope.cancel() }
    }

    @Test fun failedDeltaReadIsReconciledByAFullSnapshot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var live = false
        val healthy = ChannelSignal()
        val failed = ChannelSignal()
        val snapshots = mutableListOf<Pair<Boolean, List<String>>>()
        val sync = OpenCodeSync(scope, { live }, { live = true }, { all, ids, _ ->
            snapshots += all to ids
            if (!all) throw EOFException("delta snapshot EOF")
        }, { if (it.connected) healthy.send() else failed.send() })
        try {
            sync.start(); healthy.await()
            sync.invalidate(ids = listOf("assistant-message")); failed.await()
            assertTrue(sync.recovering)
            healthy.await()
            assertEquals(listOf(true to emptyList<String>(), false to listOf("assistant-message"), true to emptyList<String>()), snapshots)
            assertFalse(sync.recovering)
        } finally { sync.close(); scope.cancel() }
    }

    @Test fun streamLossDuringASuccessfulRestReadIsStillDisconnected() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var live = false
        val failed = ChannelSignal()
        val states = mutableListOf<OpenCodeSync.State>()
        val sync = OpenCodeSync(scope, { live }, { live = true }, { _, _, _ -> live = false }, { states += it; failed.send() })
        try {
            sync.start(); failed.await()
            assertTrue(sync.recovering)
            assertTrue(states.none { it.connected })
        } finally { sync.close(); scope.cancel() }
    }

    @Test fun criticalFailureDoesNotRetry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var attachments = 0
        val failed = ChannelSignal()
        val states = mutableListOf<OpenCodeSync.State>()
        val sync = OpenCodeSync(scope, { false }, { attachments++; throw OpenCodeRejected(401, "auth") }, { _, _, _ -> fail("must not read") }, { states += it; failed.send() })
        try {
            sync.start(); failed.await(); delay(1_200)
            assertEquals(1, attachments); assertFalse(sync.recovering)
            assertNotNull(states.single().notice)
        } finally { sync.close(); scope.cancel() }
    }

    @Test fun closingDuringRecoveryStopsRetriesAndCallbacks() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var reads = 0
        val failed = ChannelSignal()
        val states = mutableListOf<OpenCodeSync.State>()
        val sync = OpenCodeSync(scope, { true }, { fail("already attached") }, { _, _, _ -> reads++; throw EOFException() }, { states += it; failed.send() })
        try {
            sync.start(); failed.await(); sync.close(); delay(1_200)
            assertEquals(1, reads); assertEquals(1, states.size)
            assertFalse(sync.recovering)
        } finally { scope.cancel() }
    }

    @Test fun noticeDeadlineDoesNotWaitForASlowRetryToFinish() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val notice = CompletableDeferred<OpenCodeSync.State>()
        val pending = CompletableDeferred<Unit>()
        var reads = 0
        val sync = OpenCodeSync(scope, { true }, {}, { _, _, _ ->
            reads++
            if (reads == 1) throw EOFException("first failure")
            pending.await()
        }, { if (it.notice != null) notice.complete(it) })
        try {
            sync.start()
            val state = withTimeout(15_000) { notice.await() }
            assertEquals(2, reads)
            assertFalse(pending.isCompleted)
            assertFalse(state.connected); assertTrue(state.recovering)
        } finally { sync.close(); scope.cancel() }
    }

    @Test fun idleLongRunningToolDoesNotTriggerAnyTimeoutOrSnapshotRetry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var live = false
        var nanos = 0L
        var reads = 0
        val healthy = ChannelSignal()
        val states = mutableListOf<OpenCodeSync.State>()
        val sync = OpenCodeSync(scope, { live }, { live = true }, { _, _, _ -> reads++ }, { states += it; healthy.send() }, OpenCodeSyncOutage { nanos })
        try {
            sync.start(); healthy.await(); nanos += 60_000_000_000L; delay(1_200)
            assertEquals(1, reads)
            assertTrue(states.single().connected); assertNull(states.single().notice)
        } finally { sync.close(); scope.cancel() }
    }

    @Test fun switchingToAnotherSessionCannotResetOrDismissThisSessionsOutage() {
        var nanos = 0L
        val first = OpenCodeSyncOutage { nanos }
        val second = OpenCodeSyncOutage { nanos }
        first.failed(IOException("first session"))
        nanos = 9_000_000_000L
        second.failed(IOException("second session"))
        second.recovered()
        nanos = 10_000_000_000L; first.noticeIfDue()
        assertNotNull(first.notice); assertNull(second.notice)
        first.dismiss()
        second.failed(IOException("new second outage"))
        nanos = 20_000_000_000L; second.noticeIfDue(); first.failed(IOException("same first outage"))
        assertNull(first.notice); assertNotNull(second.notice)
    }

    @Test fun pausedSyncSuppressesBackgroundRetriesAndResumesImmediately() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var live = false
        var reads = 0
        var failReads = true
        val failed = ChannelSignal()
        val recovered = ChannelSignal()
        val sync = OpenCodeSync(scope, { live }, { live = true }, { _, _, _ ->
            reads++
            if (failReads) throw EOFException("offline")
        }, { state -> if (state.connected) recovered.send() else failed.send() })
        try {
            sync.start()
            failed.await()
            assertEquals(1, reads)
            sync.setPaused(true)
            delay(1_300)
            assertEquals("Paused sync must not retry in background", 1, reads)
            failReads = false
            sync.setPaused(false)
            recovered.await()
            assertEquals(2, reads)
        } finally { sync.close(); scope.cancel() }
    }

    private class ChannelSignal {
        private val signals = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        fun send() { signals.trySend(Unit) }
        suspend fun await() { withTimeout(3_000) { signals.receive() } }
    }
}
