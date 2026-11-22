package io.github.modao7067.luma.gui.browser

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class BrowserWorkflowTest {
    private fun json(text: String) = JSONObject(text)
    private fun ok() = JSONObject().put("ok", true)

    @Test fun legacyCallsAndNewWaitBoundsAreValidated() {
        listOf("selector" to "\"#ordinary-div\"", "index" to "0", "index" to "\"1\"", "ref" to "\"@1\"").forEach { (key, value) ->
            BrowserProtocol.validate(json("""{"action":"click","snapshot":"old", "$key":$value} """))
        }
        assertEquals(3000L, BrowserProtocol.budget(json("""{"action":"wait"}""")))
        assertEquals(10000L, BrowserProtocol.budget(json("""{"action":"wait","timeoutMs":10000}""")))
        assertEquals(15000L, BrowserProtocol.budget(json("""{"action":"batch","steps":[{"action":"read"}]}""")))
        listOf(
            """{"action":"wait","timeoutMs":10001}""",
            """{"action":"wait","timeoutMs":1.5}""",
            """{"action":"wait","timeoutMs":"100"}""",
            """{"action":"wait","selector":"#a","state":"url","url":"https://example.com"}""",
            """{"action":"click","snapshot":"s","selector":"#a","index":0}""",
            """{"action":"click","snapshot":"LAST","index":0}""".replace("LAST", "\$last"),
            """{"action":"navigate","url":"file:///private"}""",
        ).forEach { assertThrows(it, IllegalArgumentException::class.java) { BrowserProtocol.validate(json(it)) } }
    }

    @Test fun invalidLaterStepNeverDispatchesAnyEarlierStep() = runBlocking {
        var calls = 0
        val runner = BrowserWorkflow({ calls++; ok() })
        listOf(
            """{"action":"batch","steps":[{"action":"read"},{"action":"evaluate","js":"alert(1)"}]}""",
            """{"action":"batch","steps":[{"action":"read"},{"action":"read","tab":"other"}]}""",
            """{"action":"batch","steps":[{"action":"read"},{"action":"navigate","url":"http://example.com"}]}""",
            """{"action":"batch","timeoutMs":15001,"steps":[{"action":"read"}]}""",
        ).forEach { request ->
            try { runner.run(json(request)); fail("request should fail") } catch (_: IllegalArgumentException) {}
        }
        assertEquals(0, calls)
    }

    @Test fun explicitLastSnapshotIsConsumedAndBatchStopsWithoutReplay() = runBlocking {
        val calls = mutableListOf<JSONObject>()
        val runner = BrowserWorkflow({ step ->
            calls.add(step)
            when (step.getString("action")) {
                "read" -> ok().put("snapshot", "fixture-snapshot")
                else -> ok().put("dispatched", true)
            }
        })
        val result = runner.run(json("""{"action":"batch","steps":[{"action":"read"},{"action":"fill","snapshot":"LAST","selector":"#q","text":"Ada"},{"action":"click","snapshot":"LAST","selector":"#send"},{"action":"read"}]}""".replace("LAST", "\$last")))
        assertFalse(result.getBoolean("ok"))
        assertEquals(2, result.getInt("completed"))
        assertEquals(2, result.getInt("failedStep"))
        assertFalse(result.getBoolean("dispatched"))
        assertTrue(result.getJSONArray("results").getJSONObject(1).getBoolean("dispatched"))
        assertEquals(2, calls.size)
        assertEquals("fixture-snapshot", calls[1].getString("snapshot"))
    }

    @Test fun callbackFailureReportsDispatchedAndDoesNotRepeatMutation() = runBlocking {
        var clicks = 0
        val runner = BrowserWorkflow({ step ->
            if (step.getString("action") == "read") ok().put("snapshot", "s")
            else { clicks++; error("navigation changed after dispatch") }
        })
        val result = runner.run(json("""{"action":"batch","steps":[{"action":"read"},{"action":"click","snapshot":"s","index":0},{"action":"read"}]}"""))
        assertEquals(1, clicks)
        assertEquals(1, result.getInt("completed"))
        assertEquals(1, result.getInt("failedStep"))
        assertTrue(result.getBoolean("dispatched"))
    }

    @Test fun waitsPollOnlyObservationsAndHonorConditionTimeout() = runBlocking {
        var time = 0L
        var probes = 0
        val runner = BrowserWorkflow({ probes++; ok().put("matched", probes >= 3) }, { time }, { time += it })
        val result = runner.run(json("""{"action":"wait","selector":"#ready","timeoutMs":400}"""))
        assertTrue(result.getBoolean("ok")); assertEquals(3, probes); assertEquals(200L, time)
        val timeout = BrowserWorkflow({ ok().put("matched", false).put("reason", "DISABLED") }, { time }, { time += it })
            .run(json("""{"action":"wait","selector":"#disabled","timeoutMs":250}"""))
        assertFalse(timeout.getBoolean("ok")); assertEquals("WAIT_TIMEOUT", timeout.getString("code"))
        assertEquals("DISABLED", timeout.getString("reason"))
    }

    @Test fun totalDeadlineKeepsCompletedMutationsAndStopsOnPendingProbe() = runBlocking {
        var clicks = 0
        val result = BrowserWorkflow({ step ->
            when (step.getString("action")) {
                "read" -> ok().put("snapshot", "s")
                "click" -> { clicks++; ok().put("dispatched", true) }
                else -> { delay(2000); ok().put("matched", true) }
            }
        }).run(json("""{"action":"batch","timeoutMs":40,"steps":[{"action":"read"},{"action":"click","snapshot":"s","index":0},{"action":"wait","selector":"#later"},{"action":"read"}]}"""))
        assertFalse(result.getBoolean("ok")); assertEquals(1, clicks)
        assertEquals(2, result.getInt("completed")); assertEquals(2, result.getInt("failedStep"))
        assertFalse(result.getBoolean("dispatched"))
    }

    @Test fun timeoutDuringMutationIsNotReportedAsUndispatched() = runBlocking {
        var calls = 0
        val result = BrowserWorkflow({ calls++; delay(1000); ok() }).run(
            json("""{"action":"click","snapshot":"s","selector":"#submit"}"""), 30)
        assertFalse(result.getBoolean("ok")); assertTrue(result.getBoolean("dispatched")); assertEquals(1, calls)
    }
    @Test fun queueTimeoutDispatchesNothingAndKeepsTheTabLockUsable() = runBlocking {
        val lock = Mutex(locked = true)
        var calls = 0
        val result = runLockedBrowserWorkflow(lock, json("""{"action":"batch","timeoutMs":25,"steps":[{"action":"read"}]}"""), { calls++; ok() })
        assertEquals("TAB_BUSY", result.getString("code")); assertFalse(result.getBoolean("dispatched"))
        assertEquals(0, calls)
        lock.unlock()
        assertTrue(lock.tryLock()); lock.unlock()
    }

    @Test fun lockQueueTimeIsNotAddedAgainAfterAcquisition() = runBlocking {
        var time = 0L
        val lock = Mutex(locked = true)
        val pending = async {
            runLockedBrowserWorkflow(lock, json("""{"action":"batch","timeoutMs":100,"steps":[{"action":"read"}]}"""), { fail("expired request dispatched"); ok() }, { time })
        }
        delay(10)
        time = 101L
        lock.unlock()
        val result = pending.await()
        assertFalse(result.getBoolean("ok")); assertFalse(result.getBoolean("dispatched"))
        assertEquals("DEADLINE_EXCEEDED", result.getString("code"))
        assertTrue(lock.tryLock()); lock.unlock()
    }

    @Test fun newActionsCoordinatesAndReuseSnapshotBatchAreValidatedAndExecuted() = runBlocking {
        listOf(
            """{"action":"click","x":120,"y":260}""",
            """{"action":"fill","x":80,"y":140,"text":"hello","submit":true}""",
            """{"action":"type","ref":"@2","text":"world"}""",
            """{"action":"select","ref":"@3","text":"Pro Plan"}""",
            """{"action":"check","ref":"@4"}""",
            """{"action":"uncheck","selector":"#agree"}""",
            """{"action":"scroll","direction":"down","amount":360}""",
            """{"action":"swipe","fromX":300,"fromY":500,"toX":80,"toY":500,"durationMs":240}""",
            """{"action":"back"}""",
            """{"action":"forward"}""",
            """{"action":"reload"}""",
            """{"action":"open","url":"https://example.com","background":true}""",
            """{"action":"read","interactiveOnly":true,"includeUrls":true,"scopeSelector":"#modal"}""",
            """{"action":"diff","scopeSelector":"#main","includeUrls":true}""",
            """{"action":"screenshot","annotate":true}""",
            """{"action":"hover","ref":"@1"}""",
            """{"action":"press","key":"Escape"}""",
            """{"action":"press","key":"Enter","selector":"label=Email"}""",
            """{"action":"console","clear":true,"limit":25}""",
            """{"action":"errors","clear":false,"limit":50}""",
            """{"action":"dialog","decision":"accept","promptText":"ok"}""",
        ).forEach { BrowserProtocol.validate(json(it)) }

        listOf(
            """{"action":"click","x":120,"y":260,"ref":"@1"}""",
            """{"action":"select","ref":"@1"}""",
            """{"action":"scroll","direction":"diagonal"}""",
            """{"action":"swipe","fromX":10,"fromY":10,"toX":20}""",
            """{"action":"press","key":""}""",
            """{"action":"dialog","decision":"unknown"}""",
            """{"action":"console","limit":500}""",
            """{"action":"read","scopeSelector":"   "}""",
        ).forEach { assertThrows(it, IllegalArgumentException::class.java) { BrowserProtocol.validate(json(it)) } }

        val dispatchedSteps = mutableListOf<JSONObject>()
        val runner = BrowserWorkflow({ step ->
            dispatchedSteps.add(step)
            when (step.getString("action")) {
                "read" -> ok().put("snapshot", "live-snap")
                else -> ok().put("dispatched", true)
            }
        })
        val batchRes = runner.run(
            json(
                """{"action":"batch","reuseSnapshot":true,"steps":[
                    {"action":"read"},
                    {"action":"fill","snapshot":"LAST","ref":"@1","text":"Ada"},
                    {"action":"check","snapshot":"LAST","ref":"@2"},
                    {"action":"select","snapshot":"LAST","ref":"@3","text":"Pro"}
                ]}""".replace("LAST", "\$last"),
            ),
        )
        assertTrue(batchRes.getBoolean("ok"))
        assertEquals(4, batchRes.getInt("completed"))
        assertEquals("live-snap", dispatchedSteps[1].getString("snapshot"))
        assertEquals("live-snap", dispatchedSteps[2].getString("snapshot"))
        assertEquals("live-snap", dispatchedSteps[3].getString("snapshot"))
    }

    @Test fun foregroundTakeoverOverRealSocketWritesCompleteUserControlActiveJsonForBothDispatchedStates() = runBlocking {
        for (shouldMarkDispatched in listOf(false, true)) {
            ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { server ->
                val trackedJob = CompletableDeferred<Job>()
                val serverDone = launch(Dispatchers.IO) {
                    val accepted = server.accept()
                    serveBrowserSocket(
                        socket = accepted,
                        dispatcher = Dispatchers.Default,
                        timeoutMs = 25000L,
                        readRequest = { sock -> sock.getInputStream().bufferedReader().readLine() ?: "" },
                        resolveOwner = { "owner-1" },
                        isForegroundBlocked = { _, _ -> false },
                        shouldTrackForeground = { _, _ -> true },
                        trackJob = { _, job -> trackedJob.complete(job) },
                        untrackJob = { _, _ -> },
                        runAction = { _, _, markDispatched ->
                            if (shouldMarkDispatched) markDispatched()
                            delay(10000L)
                            ok()
                        },
                    )
                }
                Socket("127.0.0.1", server.localPort).use { client ->
                    client.soTimeout = 5000
                    client.getOutputStream().write("{\"action\":\"click\",\"token\":\"tok\"}\n".toByteArray())
                    client.getOutputStream().flush()
                    val job = trackedJob.await()
                    delay(20)
                    job.cancel(AgentBrowser.UserTakeoverException("User took over"))
                    val line = client.getInputStream().bufferedReader().readLine()
                    assertNotNull(line)
                    val response = JSONObject(line!!)
                    assertFalse(response.getBoolean("ok"))
                    assertEquals("USER_CONTROL_ACTIVE", response.getString("code"))
                    assertEquals(shouldMarkDispatched, response.getBoolean("dispatched"))
                }
                serverDone.join()
            }
        }
    }

}
