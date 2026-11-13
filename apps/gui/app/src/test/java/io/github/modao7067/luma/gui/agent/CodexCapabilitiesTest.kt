package io.github.modao7067.luma.gui.agent
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
class CodexCapabilitiesTest {
    @Test fun permissionListOnlyUsesNativeAllowedProfiles() {
        val values = CodexCapabilities.permissions(obj("data" to JSONArray().put(obj("id" to "safe", "allowed" to true)).put(obj("id" to "denied", "allowed" to false))))
        assertEquals(listOf("safe"), values.map { it.id })
        assertTrue(CodexCapabilities.permissions(obj()).isEmpty())
    }
    @Test fun absentTelemetryDoesNotBecomeZeroOrCost() {
        assertTrue(CodexCapabilities.tokenMetrics(obj()).isEmpty())
        assertTrue(CodexCapabilities.limitMetrics(obj()).isEmpty())
        val metrics = CodexCapabilities.tokenMetrics(obj("last" to obj("totalTokens" to 250), "modelContextWindow" to 1000,
            "total" to obj("totalTokens" to 500, "inputTokens" to 200, "cachedInputTokens" to 100)))
        assertEquals("250 / 1000 · 25.0%", metrics.first().value)
        assertEquals("100 / 200 · 50.0%", metrics.last().value)
        assertFalse(metrics.any { "金额" in it.label })
    }
    @Test fun usageSnapshotReplacementAndMissingWindowKeepTheirNativeMeaning() {
        val usage = obj("last" to obj("totalTokens" to 2000), "modelContextWindow" to 10000,
            "total" to obj("totalTokens" to 999999, "inputTokens" to 10000, "cachedInputTokens" to 8000))
        val rows = CodexCapabilities.tokenMetrics(usage)
        assertEquals("2000 / 10000 · 20.0%", rows.first().value)
        assertEquals("8000 / 10000 · 80.0%", rows.last().value)
        repeat(3) { assertEquals(rows, CodexCapabilities.tokenMetrics(usage)) }
        usage.remove("modelContextWindow")
        assertEquals("2000", CodexCapabilities.tokenMetrics(usage).first().value)
    }
    @Test fun malformedCountersAndImpossibleCacheDoNotBecomeZeroTelemetry() {
        assertTrue(CodexCapabilities.tokenMetrics(obj("last" to obj("totalTokens" to -1), "total" to obj("totalTokens" to "unknown"))).isEmpty())
        assertTrue(CodexCapabilities.tokenMetrics(obj("total" to obj("inputTokens" to 100, "cachedInputTokens" to 101))).isEmpty())
        assertTrue(CodexCapabilities.tokenMetrics(obj("total" to obj("inputTokens" to 0, "cachedInputTokens" to 0))).isEmpty())
        assertTrue(CodexCapabilities.limitMetrics(obj("rateLimits" to obj("primary" to obj("usedPercent" to "unknown")))).isEmpty())
    }
    @Test fun speedChoicesAreFromTheModelNotHardcoded() {
        val model = CodexProjection.models(JSONArray().put(obj("model" to "fixture", "serviceTiers" to JSONArray().put(obj("id" to "priority", "name" to "Fast"))))).single()
        assertEquals("priority", model.speeds.last().id)
    }
    @Test fun mcpProjectionDoesNotExposeSecrets() {
        val entry = CodexCapabilities.mcp(obj("name" to "local", "env" to obj("SECRET" to "hidden"), "headers" to obj("Authorization" to "hidden"), "tools" to obj("read" to obj("secret" to "hidden"))))
        assertFalse(entry.detail.contains("hidden")); assertTrue(entry.detail.contains("read"))
        assertFalse(CodexCapabilities.redact("API_KEY=sk-privateplaceholdervalue\nAuthorization: Bearer not-a-real-token").contains("not-a-real-token"))
    }
}
