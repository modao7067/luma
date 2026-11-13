package io.github.modao7067.luma.gui.agent

import kotlinx.coroutines.*
import org.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class OpenCodeUsageNativeTest {
    @Test fun nativeCountersCatalogAndReconnectMatchDashboard(): Unit = runBlocking {
        val url = System.getenv("LUMA_TEST_OPENCODE_USAGE_URL")
        assumeTrue("Run verification/gui-opencode-dashboard-20261104/native_probe.py", url != null)
        val api = OpenCodeClient(url!!, System.getenv("LUMA_TEST_OPENCODE_USAGE_PASSWORD")!!)
        val work = System.getenv("LUMA_TEST_OPENCODE_USAGE_WORK")!!
        val id = "ses_luma" + UUID.randomUUID().toString().replace("-", "")
        val path = "/api/session/$id"
        val evidence = JSONArray()
        try {
            api.call("/api/session", "POST", obj("id" to id, "title" to "Isolated usage fixture", "model" to OpenCodeProjection.modelRef("luma-fixture/fixture"), "location" to obj("directory" to work)))
            val models = api.call("/api/model?location[directory]=" + java.net.URLEncoder.encode(work,"UTF-8")).getJSONArray("data")
            repeat(2) { turn ->
                api.call(path + "/prompt", "POST", obj("id" to "msg_luma${UUID.randomUUID().toString().replace("-", "")}", "text" to "Report deterministic fixture usage ${turn + 1}.", "delivery" to "queue"))
                withTimeout(20_000) {
                    while (true) {
                        val rows = api.call(path + "/message?order=asc&limit=100").getJSONArray("data").objects()
                        val steps = rows.filter { it.optString("type") == "assistant" && it.optJSONObject("tokens") != null }
                        val active = api.call("/api/session/active").getJSONObject("data")
                        if (steps.size == turn + 1 && !active.has(id)) break
                        delay(50)
                    }
                }
                val native = api.call(path).getJSONObject("data")
                val messages = api.call(path + "/message?order=asc&limit=100").getJSONArray("data").objects()
                val step = messages.last { it.optString("type") == "assistant" }
                val tokens = step.getJSONObject("tokens")
                assertEquals(2000, tokens.getInt("input")); assertEquals(3000, tokens.getInt("output"))
                assertEquals(500, tokens.getInt("reasoning")); assertEquals(8000, tokens.getJSONObject("cache").getInt("read"))
                val metrics = OpenCodeProjection.metrics(native, messages, models)
                assertEquals("13500 / 20000 · 68%", metrics.first { it.label == "上下文" }.value)
                val multiplier = turn + 1
                assertEquals("${8000 * multiplier} / ${10000 * multiplier} · 80.0%", metrics.first { it.label == "累计输入缓存命中" }.value)
                evidence.put(obj("session" to native, "messages" to JSONArray(messages), "models" to models, "metrics" to JSONArray(metrics.map { obj("label" to it.label, "value" to it.value) })))
            }
            api.close()
            val restored = OpenCodeClient(url, System.getenv("LUMA_TEST_OPENCODE_USAGE_PASSWORD")!!)
            try {
                val after = OpenCodeProjection.metrics(restored.call(path).getJSONObject("data"), restored.call(path + "/message?order=asc&limit=100").getJSONArray("data").objects(), models)
                assertEquals("16000 / 20000 · 80.0%", after.first { it.label == "累计输入缓存命中" }.value)
                assertEquals("13500 / 20000 · 68%", after.first { it.label == "上下文" }.value)
                File(System.getenv("LUMA_TEST_OPENCODE_USAGE_EVIDENCE")!!).writeText(evidence.toString(2))
                restored.call(path, "DELETE")
            } finally { restored.close() }
        } finally { api.close() }
    }
}
