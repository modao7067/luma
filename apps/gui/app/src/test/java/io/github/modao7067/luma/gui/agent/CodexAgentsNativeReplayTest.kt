package io.github.modao7067.luma.gui.agent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CodexAgentsNativeReplayTest {
    @Test fun realProviderFailureKeepsBothChildrenFailedWhileParentAndSteerComplete() {
        val fixture = JSONObject(javaClass.getResourceAsStream("/native/codex-agents-provider-failure.json")!!.bufferedReader().use { it.readText() })
        val parent = fixture.getString("parentThreadId")
        val agents = CodexSubAgents { parent }
        val other = CodexSubAgents { "unrelated-session" }
        var concurrent = false
        var parentStatus = ""
        val accepted = mutableListOf<String>()
        val queuedClient = fixture.getJSONArray("queue").getJSONObject(0).getString("clientUserMessageId")
        for (event in fixture.getJSONArray("events").objects()) {
            agents.event(event); other.event(event)
            val p = event.optJSONObject("params") ?: continue
            if (p.optString("threadId") == parent) {
                p.optJSONObject("item")?.let { item ->
                    agents.item(item, p.optString("turnId"))
                    if (item.optString("type") == "userMessage") CodexProjection.clientMessageId(item)?.let(accepted::add)
                }
                if (event.optString("method") == "turn/completed") parentStatus = p.getJSONObject("turn").optString("status")
            }
            if (agents.values().count { it.status == "running" } == 2) concurrent = true
        }
        assertTrue("Actual native sequence contained two simultaneously running children", concurrent)
        assertEquals("completed", parentStatus)
        assertEquals(2, agents.values().size)
        assertTrue(agents.values().all { it.status == "failed" && it.nativeStatus == "errored" && it.detail.contains("cached_tokens") })
        assertTrue(agents.values().all { it.itemId.isNotBlank() && it.turnId.isNotBlank() })
        assertEquals(1, accepted.size)
        assertTrue(accepted.single().startsWith("luma-agents-steer-"))
        assertFalse(accepted.contains(queuedClient))
        assertTrue(other.values().isEmpty())
    }
}
