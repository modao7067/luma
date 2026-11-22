package io.github.modao7067.luma.gui.session

import io.github.modao7067.luma.gui.agent.obj
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CodexReconnectBusyStateTest {
    private fun connectedView() = SessionView(
        SessionSummary("s1", "Codex", "root", "/work", cli = "Codex"),
        connected = true,
    )

    @Test fun reconnectClearsConfiguringAndOldCoroutineCannotClearNewConfigureState() = runBlocking {
        var generation = 1
        var view = connectedView()
        var settingsWaiter: CompletableDeferred<JSONObject>? = null
        var settingsExpected: JSONObject? = null

        val oldStarted = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val oldEpoch = generation
        view = view.copy(configuring = true)
        val oldJob = launch(Dispatchers.Unconfined) {
            runGenerationScopedSettings(
                setWaiter = { waiter, expected -> settingsWaiter = waiter; settingsExpected = expected },
                clearWaiterIfSame = { waiter ->
                    if (settingsWaiter === waiter) {
                        settingsWaiter = null
                        settingsExpected = null
                    }
                },
                clearConfiguring = { view = clearConfiguringIfSameGeneration(view, generation, oldEpoch) },
            ) { assignWaiter ->
                assignWaiter(obj("permissions" to ":workspace"))
                oldStarted.complete(Unit)
                releaseOld.await()
            }
        }
        oldStarted.await()
        assertTrue(view.configuring)
        val oldWaiter = settingsWaiter
        assertNotNull(oldWaiter)

        generation += 1
        settingsWaiter?.cancel()
        settingsWaiter = null
        settingsExpected = null
        view = resetConnectionView(view, loading = true)
        assertFalse(view.configuring)
        assertFalse(view.queueBusy)
        assertFalse(view.stopping)
        assertTrue(oldWaiter!!.isCancelled)
        assertNull(settingsWaiter)

        view = view.copy(connected = true, loading = false)
        val newStarted = CompletableDeferred<Unit>()
        val releaseNew = CompletableDeferred<Unit>()
        val newEpoch = generation
        view = view.copy(configuring = true)
        val newJob = launch(Dispatchers.Unconfined) {
            runGenerationScopedSettings(
                setWaiter = { waiter, expected -> settingsWaiter = waiter; settingsExpected = expected },
                clearWaiterIfSame = { waiter ->
                    if (settingsWaiter === waiter) {
                        settingsWaiter = null
                        settingsExpected = null
                    }
                },
                clearConfiguring = { view = clearConfiguringIfSameGeneration(view, generation, newEpoch) },
            ) { assignWaiter ->
                assignWaiter(obj("permissions" to ":full"))
                newStarted.complete(Unit)
                releaseNew.await()
            }
        }
        newStarted.await()
        assertTrue(view.configuring)
        val newWaiter = settingsWaiter
        assertNotNull(newWaiter)

        releaseOld.complete(Unit)
        oldJob.join()
        assertTrue(view.configuring)
        assertSame(newWaiter, settingsWaiter)
        assertEquals(":full", settingsExpected?.optString("permissions"))

        releaseNew.complete(Unit)
        newJob.join()
        assertFalse(view.configuring)
        assertNull(settingsWaiter)
        assertNull(settingsExpected)
    }

    @Test fun reconnectClearsQueueBusyAndOldCoroutineCannotClearNewQueueMutation() = runBlocking {
        var generation = 1
        var view = connectedView()

        val oldStarted = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val oldEpoch = generation
        view = view.copy(queueBusy = true)
        val oldJob = launch(Dispatchers.Unconfined) {
            try {
                oldStarted.complete(Unit)
                releaseOld.await()
            } finally {
                view = clearQueueBusyIfSameGeneration(view, generation, oldEpoch)
            }
        }
        oldStarted.await()
        assertTrue(view.queueBusy)

        generation += 1
        view = resetConnectionView(view, loading = true)
        assertFalse(view.queueBusy)
        assertFalse(view.configuring)

        view = view.copy(connected = true, loading = false)
        val newStarted = CompletableDeferred<Unit>()
        val releaseNew = CompletableDeferred<Unit>()
        val newEpoch = generation
        view = view.copy(queueBusy = true)
        val newJob = launch(Dispatchers.Unconfined) {
            try {
                newStarted.complete(Unit)
                releaseNew.await()
            } finally {
                view = clearQueueBusyIfSameGeneration(view, generation, newEpoch)
            }
        }
        newStarted.await()
        assertTrue(view.queueBusy)

        releaseOld.complete(Unit)
        oldJob.join()
        assertTrue(view.queueBusy)

        releaseNew.complete(Unit)
        newJob.join()
        assertFalse(view.queueBusy)
    }
}
