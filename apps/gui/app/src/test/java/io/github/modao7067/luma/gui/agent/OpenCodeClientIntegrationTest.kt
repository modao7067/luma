package io.github.modao7067.luma.gui.agent

import kotlinx.coroutines.*
import org.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class OpenCodeClientIntegrationTest {
    @Test fun nativeStreamingToolSkillAndReconnect() = runBlocking {
        val url = System.getenv("LUMA_TEST_OPENCODE_URL")
        assumeTrue("Run tools/tests/ui/opencode-stream-check.py", url != null)
        val password = System.getenv("LUMA_TEST_OPENCODE_PASSWORD")!!
        val work = System.getenv("LUMA_TEST_OPENCODE_WORK")!!
        val events = CopyOnWriteArrayList<JSONObject>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val api = OpenCodeClient(url!!, password)
        val id = "ses_luma" + UUID.randomUUID().toString().replace("-", "")
        try {
            api.subscribe(scope, { events.add(it) }, {})
            val models = OpenCodeProjection.models(api.call("/api/model").getJSONArray("data"))
            assertTrue(models.any { it.id == "luma-fixture/fixture" })
            api.call("/api/session", "POST", obj("id" to id, "title" to "Kotlin fixture", "model" to OpenCodeProjection.modelRef("luma-fixture/fixture"), "location" to obj("directory" to work), "permissions" to JSONArray().put(obj("action" to "shell", "resource" to "*", "effect" to "ask"))))
            val root = "/api/session/$id"
            api.call(root + "/model", "POST", obj("model" to OpenCodeProjection.modelRef("luma-fixture/fixture", "high")))
            assertEquals("high",api.call(root).getJSONObject("data").getJSONObject("model").getString("variant"))
            val skill = api.call("/api/skill?location%5Bdirectory%5D=" + java.net.URLEncoder.encode(work,"UTF-8")).getJSONArray("data").objects().first { it.getString("name") == "fixture-skill" }
            val mid = "msg_luma" + UUID.randomUUID().toString().replace("-", "")
            api.call(root + "/prompt", "POST", obj("id" to mid, "text" to "Run the isolated print fixture.", "delivery" to "queue", "skills" to JSONArray().put(obj("id" to skill.getString("id")))))
            var approved = false
            withTimeout(45000) {
                while (events.none { it.optString("type") == "session.execution.succeeded" && it.optJSONObject("data")?.optString("sessionID") == id }) {
                    val failure = events.firstOrNull { it.optString("type") == "session.execution.failed" && it.optJSONObject("data")?.optString("sessionID") == id }
                    check(failure == null) { "Native fixture execution failed: $failure" }
                    if (!approved && events.any { it.optString("type") == "permission.asked" }) {
                        val pending = api.call(root + "/permission").getJSONArray("data").getJSONObject(0)
                        assertTrue(OpenCodeProjection.permission(pending).choices.any { it.id == "once" })
                        api.call(root + "/permission/" + pending.getString("id") + "/reply", "POST", obj("decision" to "once"))
                        approved = true
                    }
                    delay(50)
                }
            }
            assertTrue(approved)
            assertTrue(events.any { it.optString("type") == "session.text.delta" })
            assertTrue(events.any { it.optString("type") == "session.tool.success" })
            val messages = api.call(root + "/message?order=asc&limit=100").getJSONArray("data").objects()
            val projected = messages.flatMap(OpenCodeProjection::message)
            assertTrue(projected.any { it.role == "assistant" && it.text == "OpenCode fixture stream completed." })
            assertTrue(projected.any { it.role == "tool" && it.outcome == "succeeded" && it.detail.contains("luma-fixture-tool") })
            assertTrue(messages.any { it.getString("id") == mid && it.optJSONArray("skills")?.length() == 1 })
            api.close()
            val reconnected = OpenCodeClient(url, password)
            assertEquals(id, reconnected.call(root).getJSONObject("data").getString("id"))
            assertEquals(messages.size, reconnected.call(root + "/message?order=asc&limit=100").getJSONArray("data").length())
            reconnected.call(root, "DELETE"); reconnected.close()
        } finally { api.close(); scope.cancel() }
    }
}
