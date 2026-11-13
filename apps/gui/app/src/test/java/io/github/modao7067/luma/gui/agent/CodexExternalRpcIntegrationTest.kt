package io.github.modao7067.luma.gui.agent

import kotlinx.coroutines.*
import io.github.modao7067.luma.gui.session.CodexConnectionSetup
import io.github.modao7067.luma.gui.session.ManagedSessionPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

class CodexExternalRpcIntegrationTest {
    @Test fun twoConnectionsControlSameLoadedOwnerAndProxyDetachDoesNotStopIt() = runBlocking {
        val socket = System.getenv("LUMA_TEST_EXTERNAL_CODEX_SOCKET")
        assumeTrue("Run tools/tests/cli/codex_external_native.py", socket != null)
        val binary = System.getenv("LUMA_TEST_EXTERNAL_CODEX_BINARY")!!
        val work = System.getenv("LUMA_TEST_EXTERNAL_CODEX_WORK")!!
        val eventsA = CopyOnWriteArrayList<JSONObject>(); val eventsB = CopyOnWriteArrayList<JSONObject>()
        val proxyB = AtomicReference<Process>()
        fun transport(capture: AtomicReference<Process>? = null) = CodexProcessTransport {
            ProcessBuilder(binary, "app-server", "proxy", "--sock", socket!!).start().also { capture?.set(it) }
        }
        val a = CodexRpc("unix://$socket", null, { eventsA += it }, {}, transport())
        val b = CodexRpc("unix://$socket", null, { eventsB += it }, {}, transport(proxyB))
        try {
            a.connect()
            val start = a.request("thread/start", obj("cwd" to work, "approvalPolicy" to "never", "sandbox" to "read-only", "historyMode" to "paginated"))
            val id = start.getJSONObject("thread").getString("id")
            a.request("thread/name/set", obj("threadId" to id, "name" to "External owner fixture"))
            val before = a.request("thread/resume", obj("threadId" to id, "excludeTurns" to true))
            b.connect()
            val r = ManagedSessionPolicy.newRecord("local", "Codex", "Local alias", "root", work, "", id, externalSocket = socket!!)
            val wire = mutableListOf<String>()
            val config = CodexConnectionSetup.prepare(r, "/must-not-register", { error("must not change external environment") }, { method, params ->
                wire += method; b.request(method, params)
            }, {})
            assertNull(config); assertEquals(listOf("thread/loaded/list"), wire)
            val joined = b.request("thread/resume", CodexConnectionSetup.resumeParams(r, config))
            assertEquals(id, joined.getJSONObject("thread").getString("id"))
            for (field in listOf("model", "reasoningEffort", "approvalPolicy", "sandbox", "serviceTier")) {
                assertEquals("external join must preserve $field", before.opt(field)?.toString(), joined.opt(field)?.toString())
            }
            fun input(text: String) = JSONArray().put(obj("type" to "text", "text" to text))
            a.request("thread/queue/add", obj("threadId" to id, "clientUserMessageId" to "external-owner-message", "input" to input("Fixture original client")))
            withTimeout(20000) { while (eventsB.none { it.optString("method") == "turn/started" }) delay(25) }
            b.request("thread/queue/add", obj("threadId" to id, "clientUserMessageId" to "external-gui-message", "input" to input("Fixture GUI client")))
            assertEquals("external-gui-message", a.request("thread/queue/list", obj("threadId" to id)).getJSONArray("data").getJSONObject(0).getString("clientUserMessageId"))
            URL(System.getenv("LUMA_TEST_EXTERNAL_MODEL_URL") + "/release").readText()
            withTimeout(30000) { while (eventsA.count { it.optString("method") == "turn/completed" } < 2 || eventsB.count { it.optString("method") == "turn/completed" } < 2) delay(25) }
            assertTrue(eventsB.any { it.optString("method") == "item/agentMessage/delta" })
            b.close()
            withTimeout(5000) { while (proxyB.get().isAlive) delay(25) }
            assertTrue(a.connected)
            assertTrue(CodexConnectionSetup.loadedThreads(a::request).contains(id))
            val items = a.request("thread/items/list", obj("threadId" to id, "sortDirection" to "asc", "limit" to 100)).getJSONArray("data").objects()
            assertEquals(2, items.count { it.getJSONObject("item").getString("type") == "userMessage" })
            val reconnect = CodexRpc("unix://$socket", null, {}, {}, transport())
            try {
                reconnect.connect()
                CodexConnectionSetup.prepare(r, "/unused", { error("unused") }, reconnect::request, {})
                assertEquals(id, reconnect.request("thread/resume", CodexConnectionSetup.resumeParams(r, null)).getJSONObject("thread").getString("id"))
            } finally { reconnect.close() }
        } finally { a.close(); b.close() }
    }
    @Test fun droppedProxyMutationIsUncertainAndNeverRetried() = runBlocking {
        val socket = System.getenv("LUMA_TEST_EXTERNAL_CODEX_DROP_SOCKET")
        assumeTrue("Requires isolated drop fixture", socket != null)
        val binary = System.getenv("LUMA_TEST_EXTERNAL_CODEX_BINARY")!!
        val proxy = AtomicReference<Process>()
        val rpc = CodexRpc("unix://$socket", null, {}, {}, CodexProcessTransport {
            ProcessBuilder(binary, "app-server", "proxy", "--sock", socket!!).start().also { proxy.set(it) }
        })
        try {
            rpc.connect()
            try { rpc.request("thread/queue/add", obj("clientUserMessageId" to "single-dispatch")); fail("Lost response must remain uncertain") }
            catch (_: RpcDisconnected) {}
            assertFalse(rpc.connected)
            withTimeout(5000) { while (proxy.get().isAlive) delay(25) }
        } finally { rpc.close() }
    }
    @Test fun proxyStartFailureIsBoundedAndDoesNotExposeProcessErrors() = runBlocking {
        var starts = 0
        val rpc = CodexRpc("unix:///not-started", null, {}, {}, CodexProcessTransport {
            starts++; throw java.io.IOException("fixture-private-detail")
        })
        try {
            try { withTimeout(5000) { rpc.connect() }; fail("Must reject missing proxy") }
            catch (failure: RpcDisconnected) { assertFalse(failure.message!!.contains("fixture-private-detail")) }
            assertEquals(1, starts); assertFalse(rpc.connected)
        } finally { rpc.close() }
    }

    @Test fun handshakeCancellationClosesOnlyTheHungProxyWithoutBlockingTheCaller() = runBlocking {
        val proxy = AtomicReference<Process>()
        val rpc = CodexRpc("unix:///isolated-hung-proxy", null, {}, {}, CodexProcessTransport {
            ProcessBuilder("python3", "-I", "-u", "-c", "import time; time.sleep(60)").start().also { proxy.set(it) }
        })
        val started = System.nanoTime()
        try {
            try { withTimeout(500) { rpc.connect() }; fail("Handshake must be cancelled") }
            catch (_: TimeoutCancellationException) {}
            assertTrue((System.nanoTime() - started) / 1_000_000 < 2000)
            withTimeout(5000) { while (proxy.get()?.isAlive == true) delay(25) }
            assertFalse(rpc.connected)
        } finally { rpc.close() }
    }

    @Test fun missingNativeSocketHasASafePersistentReason() = runBlocking {
        val binary = System.getenv("LUMA_TEST_EXTERNAL_CODEX_BINARY")
        assumeTrue("Requires isolated native binary fixture", binary != null)
        val missing = System.getenv("LUMA_TEST_EXTERNAL_CODEX_SOCKET")!! + "-missing"
        val lines = CopyOnWriteArrayList<String>()
        val rpc = CodexRpc("unix://$missing", null, {}, {}, CodexProcessTransport(diagnostic = { lines += it }) {
            ProcessBuilder(binary!!, "app-server", "proxy", "--sock", missing).start()
        })
        try {
            try { rpc.connect(); fail("Missing socket must reject") }
            catch (failure: RpcDisconnected) { assertTrue(failure.message!!.contains("socket 不存在")); assertNotNull(failure.cause) }
            assertTrue(lines.any { it.contains("reason=socket-missing") })
            assertFalse(lines.joinToString().contains(missing))
        } finally { rpc.close() }
    }

}
