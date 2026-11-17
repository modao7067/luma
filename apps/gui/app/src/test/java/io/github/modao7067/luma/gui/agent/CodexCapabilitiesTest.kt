package io.github.modao7067.luma.gui.agent
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
class CodexCapabilitiesTest {
    @Test fun permissionListOnlyUsesNativeAllowedProfiles() {
        val values = CodexCapabilities.permissions(obj("data" to JSONArray()
            .put(obj("id" to "safe", "allowed" to true))
            .put(obj("id" to ":read-only", "allowed" to true, "description" to "Read files only"))
            .put(obj("id" to ":workspace", "allowed" to true))
            .put(obj("id" to ":danger-full-access", "allowed" to true))
            .put(obj("id" to "denied", "allowed" to false))))
        assertEquals(listOf("safe", ":read-only", ":workspace", ":danger-full-access"), values.map { it.id })
        assertEquals(listOf("safe", "Read-only", "Workspace", "Full access"), values.map { it.label })
        assertEquals("Read files only", values[1].description)
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
        assertEquals("default", model.speeds.first().id)
        assertEquals("Default", model.speeds.first().label)
        assertEquals("priority", model.speeds.last().id)
    }
    @Test fun mcpProjectionDoesNotExposeSecrets() {
        val entry = CodexCapabilities.mcp(obj("name" to "local", "env" to obj("SECRET" to "hidden"), "headers" to obj("Authorization" to "hidden"), "tools" to obj("read" to obj("secret" to "hidden"))))
        assertFalse(entry.detail.contains("hidden")); assertTrue(entry.detail.contains("read"))
        assertFalse(CodexCapabilities.redact("API_KEY=sk-privateplaceholdervalue\nAuthorization: Bearer not-a-real-token").contains("not-a-real-token"))
    }
    @Test fun pricingCatalogParsesGlobalUsageSourceAndAvoidsCoarseFamilyPrefixPricing() {
        val catalog = CodexPricingCatalog.default()
        val sol = catalog.lookupRate("gpt-6.1-sol")!!
        val family = catalog.lookupRate("gpt-6")!!
        val luna = catalog.lookupRate("gpt-6-luna")!!
        assertEquals(2.0, sol.inputPerM, 1e-9)
        assertEquals(10.0, sol.outputPerM, 1e-9)
        assertEquals(0.1, sol.cacheReadPerM, 1e-9)
        assertEquals(CodexTieredRate(272_000L, 4.0, 15.0, 0.2, 5.0), sol.tiered)
        assertEquals(2.5, family.inputPerM, 1e-9)
        assertEquals(15.0, family.outputPerM, 1e-9)
        assertEquals(0.25, family.cacheReadPerM, 1e-9)
        assertEquals(0.1, luna.inputPerM, 1e-9)
        assertEquals(0.5, luna.outputPerM, 1e-9)
        assertEquals(0.01, luna.cacheReadPerM, 1e-9)
        val usage = obj("total" to obj("inputTokens" to 100_000, "cachedInputTokens" to 50_000, "outputTokens" to 10_000))
        val cost = CodexCapabilities.tokenMetrics(usage, model = "gpt-6.1-sol").last()
        assertEquals("$0.205000", cost.value)
    }
    @Test fun restoredPricingSkipsCoveredReplayWithoutDroppingNewUsageOrLiveCounterResets() {
        val pricer = CodexSessionPricer(CodexPricingCatalog.default())
        fun usage(total: Int, last: Int, output: Int = 0) = obj(
            "total" to obj("inputTokens" to total, "outputTokens" to output),
            "last" to obj("inputTokens" to last, "outputTokens" to output),
        )
        val baseline = pricer.applyRolloutBaseline(obj(
            "costUsd" to 0.60,
            "priced" to true,
            "totals" to obj("inputTokens" to 300_000, "outputTokens" to 0),
            "last" to obj("input_tokens" to 150_000),
            "modelContextWindow" to 475_000,
        ))
        assertEquals(baseline, pricer.onReplayedTokenUsage(usage(150_000, 150_000), "gpt-6.1-sol"))
        assertEquals(baseline, pricer.onReplayedTokenUsage(usage(300_000, 150_000), "gpt-6.1-sol"))

        val advanced = pricer.onReplayedTokenUsage(usage(450_000, 150_000), "gpt-6.1-sol")
        assertEquals(0.90, advanced.costUsd, 1e-9)
        assertEquals(450_000L, advanced.total!!.getLong("inputTokens"))
        assertEquals(advanced, pricer.onReplayedTokenUsage(usage(300_000, 150_000), "gpt-6.1-sol"))
        assertEquals(0.91, pricer.onReplayedTokenUsage(usage(450_000, 150_000, 1_000), "gpt-6.1-sol").costUsd, 1e-9)

        val reset = pricer.onTokenUsage(usage(100_000, 100_000), "gpt-6.1-sol")
        assertEquals(1.11, reset.costUsd, 1e-9)
        assertEquals(100_000L, reset.total!!.getLong("inputTokens"))
        assertEquals(1.31, pricer.onTokenUsage(usage(200_000, 100_000), "gpt-6.1-sol").costUsd, 1e-9)
    }
    @Test fun replayWithoutHistoricalPricingRetainsExistingCounterResetBehavior() {
        val pricer = CodexSessionPricer(CodexPricingCatalog.default())
        fun usage(total: Int) = obj("total_token_usage" to obj("input_tokens" to total, "output_tokens" to 0))
        assertEquals(0.40, pricer.onReplayedTokenUsage(usage(200_000), "gpt-6.1-sol").costUsd, 1e-9)
        assertEquals(0.60, pricer.onReplayedTokenUsage(usage(100_000), "gpt-6.1-sol").costUsd, 1e-9)
    }
    @Test fun sessionPricerAppliesPerRequestLongContextCliffAndDoesNotRetroactivelyRepriceHistory() {
        val pricer = CodexSessionPricer(CodexPricingCatalog.default())
        var state = pricer.onTokenUsage(
            obj(
                "total" to obj("inputTokens" to 150_000, "cachedInputTokens" to 0, "outputTokens" to 0),
                "last" to obj("inputTokens" to 150_000, "cachedInputTokens" to 0, "outputTokens" to 0, "totalTokens" to 150_000),
            ),
            model = "gpt-6.1-sol",
            speed = null,
        )
        assertEquals(0.30, state.costUsd, 1e-9)
        state = pricer.onTokenUsage(
            obj(
                "total" to obj("inputTokens" to 300_000, "cachedInputTokens" to 0, "outputTokens" to 0),
                "last" to obj("inputTokens" to 150_000, "cachedInputTokens" to 0, "outputTokens" to 0, "totalTokens" to 150_000),
            ),
            model = "gpt-6.1-sol",
            speed = null,
        )
        assertEquals(0.60, state.costUsd, 1e-9)
        state = pricer.onTokenUsage(
            obj(
                "total" to obj("inputTokens" to 600_000, "cachedInputTokens" to 0, "outputTokens" to 0),
                "last" to obj("inputTokens" to 300_000, "cachedInputTokens" to 0, "outputTokens" to 0, "totalTokens" to 300_000),
            ),
            model = "gpt-6.1-sol",
            speed = null,
        )
        assertEquals(1.80, state.costUsd, 1e-9)
        state = pricer.onTokenUsage(
            obj(
                "total" to obj("inputTokens" to 600_000, "cachedInputTokens" to 0, "outputTokens" to 0),
                "last" to obj("inputTokens" to 300_000, "cachedInputTokens" to 0, "outputTokens" to 0, "totalTokens" to 300_000),
            ),
            model = "gpt-6-luna",
            speed = "fast",
        )
        assertEquals(1.80, state.costUsd, 1e-9)
        state = pricer.onTokenUsage(
            obj(
                "total" to obj("inputTokens" to 610_000, "cachedInputTokens" to 0, "outputTokens" to 0),
                "last" to obj("inputTokens" to 10_000, "cachedInputTokens" to 0, "outputTokens" to 0, "totalTokens" to 10_000),
            ),
            model = "gpt-6-luna",
            speed = "fast",
        )
        assertEquals(1.802, state.costUsd, 1e-9)
        state = pricer.onTokenUsage(
            obj(
                "total" to obj("inputTokens" to 620_000, "cachedInputTokens" to 0, "outputTokens" to 0),
                "last" to obj("inputTokens" to 10_000, "cachedInputTokens" to 0, "outputTokens" to 0, "totalTokens" to 10_000),
            ),
            model = "gpt-6-luna",
            speed = null,
        )
        assertEquals(1.803, state.costUsd, 1e-9)

        val reconcilePricer = CodexSessionPricer(CodexPricingCatalog.default())
        reconcilePricer.onTokenUsage(
            obj(
                "total" to obj("inputTokens" to 600_000, "cachedInputTokens" to 0, "outputTokens" to 0),
                "last" to obj("inputTokens" to 150_000, "cachedInputTokens" to 0, "outputTokens" to 0, "totalTokens" to 150_000),
            ),
            model = "gpt-6-luna",
            speed = "fast",
        )
        reconcilePricer.onTokenUsage(
            obj(
                "total" to obj("inputTokens" to 610_000, "cachedInputTokens" to 0, "outputTokens" to 0),
                "last" to obj("inputTokens" to 10_000, "cachedInputTokens" to 0, "outputTokens" to 0, "totalTokens" to 10_000),
            ),
            model = "gpt-6-luna",
            speed = "fast",
        )
        val reconciled = reconcilePricer.reconcileSessionPricing(
            obj(
                "rollout" to obj(
                    "hasUsage" to true,
                    "costUsd" to 14.0579852,
                    "priced" to true,
                    "totals" to obj("inputTokens" to 600_000, "cachedInputTokens" to 0, "cacheWriteInputTokens" to 0, "outputTokens" to 0, "reasoningOutputTokens" to 0),
                    "last" to obj("total_tokens" to 150_000),
                ),
            ),
        )
        assertEquals(14.0599852, reconciled.costUsd, 1e-9)
    }
}
