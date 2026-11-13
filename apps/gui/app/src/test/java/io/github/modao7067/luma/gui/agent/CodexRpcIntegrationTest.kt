package io.github.modao7067.luma.gui.agent

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

class CodexRpcIntegrationTest {
    @Test fun sameThreadQueueStreamingHistoryAndReconnect() = runBlocking {
        val url = System.getenv("LUMA_TEST_CODEX_URL")
        assumeTrue("Run tools/tests/verify-gui-protocol.py to start the isolated backend", url != null)
        val work = System.getenv("LUMA_TEST_CODEX_WORK")!!
        val events = CopyOnWriteArrayList<JSONObject>()
        val otherEvents = CopyOnWriteArrayList<JSONObject>()
        val a = CodexRpc(url!!, null, { events.add(it) }, {})
        val b = CodexRpc(url, null, { otherEvents.add(it) }, {})
        try {
            a.connect(); b.connect()
            val started = a.request("thread/start", obj("cwd" to work, "approvalPolicy" to "never", "sandbox" to "read-only", "historyMode" to "paginated"))
            val id = started.getJSONObject("thread").getString("id")
            a.request("thread/name/set", obj("threadId" to id, "name" to "Luma production transport fixture"))
            val resumed = b.request("thread/resume", obj("threadId" to id, "excludeTurns" to true))
            assertEquals(id, resumed.getJSONObject("thread").getString("id"))
            assertEquals(0, a.request("thread/turns/list", obj("threadId" to id)).getJSONArray("data").length())
            val clientId = "fixture-message-one"
            a.request("thread/queue/add", obj("threadId" to id, "clientUserMessageId" to clientId,
                "input" to CodexProjection.inputs("Inspect @\"$work/example.txt\"", listOf("$work/image.png"), work)))
            awaitEvent(events, "turn/started")
            a.request("thread/queue/add", obj("threadId" to id, "clientUserMessageId" to "fixture-message-two", "input" to JSONArray().put(obj("type" to "text", "text" to "Second queued message"))))
            val queued = b.request("thread/queue/list", obj("threadId" to id)).getJSONArray("data")
            assertEquals(1, queued.length())
            assertEquals("fixture-message-two", queued.getJSONObject(0).getString("clientUserMessageId"))
            URL(System.getenv("LUMA_TEST_MODEL_URL") + "/release").readText()
            withTimeout(30000) { while (events.count { it.optString("method") == "turn/completed" } < 2) delay(30) }
            assertTrue(events.any { it.optString("method") == "item/agentMessage/delta" && it.getJSONObject("params").optString("delta").isNotBlank() })
            assertTrue(otherEvents.any { it.optString("method") == "turn/completed" })
            val items = b.request("thread/items/list", obj("threadId" to id, "sortDirection" to "asc", "limit" to 100)).getJSONArray("data").objects().map { it.getJSONObject("item") }
            assertEquals(2, items.count { it.getString("type") == "userMessage" })
            assertEquals(2, items.count { it.getString("type") == "agentMessage" })
            assertTrue(items.any { it.optString("type") == "agentMessage" && it.optString("text") == "Fixture streamed answer." })
            a.close()
            val reconnect = CodexRpc(url, null, {}, {})
            try {
                reconnect.connect()
                assertEquals(id, reconnect.request("thread/resume", obj("threadId" to id, "excludeTurns" to true)).getJSONObject("thread").getString("id"))
                assertEquals(0, reconnect.request("thread/queue/list", obj("threadId" to id)).getJSONArray("data").length())
                assertEquals(2, reconnect.request("thread/turns/list", obj("threadId" to id)).getJSONArray("data").length())
            } finally { reconnect.close() }
        } finally { a.close(); b.close() }
    }
    @Test fun connectionLossDoesNotRetryMutation() = runBlocking {
        val url = System.getenv("LUMA_TEST_DROP_URL")
        assumeTrue("Isolated dropped-response fixture required", url != null)
        val rpc = CodexRpc(url!!, null, {}, {})
        try {
            rpc.connect()
            try { rpc.request("thread/queue/add", obj("clientUserMessageId" to "not-an-idempotency-key")); fail("response must be uncertain") }
            catch (_: RpcDisconnected) { }
            delay(150)
            assertFalse(rpc.connected)
        } finally { rpc.close() }
    }
    private suspend fun awaitEvent(events: List<JSONObject>, method: String) {
        withTimeout(20000) { while (events.none { it.optString("method") == method }) delay(25) }
    }
}
