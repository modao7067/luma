package io.github.modao7067.luma.gui.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GlobalUsageTest {
    @Test fun parsesSummaryAndDistinguishesCostSources() {
        val raw = JSONObject()
            .put("generatedAt", "2026-11-06T19:00:00Z")
            .put("days", 7)
            .put("granularity", "day")
            .put(
                "totals",
                JSONObject()
                    .put("events", 42)
                    .put("uncachedInputTokens", 120_000L)
                    .put("cachedInputTokens", 880_000L)
                    .put("cacheCreationTokens", 10_000L)
                    .put("outputTokens", 45_000L)
                    .put("reasoningTokens", 5_000L)
                    .put("totalTokens", 1_060_000L)
                    .put("costUsd", 3.75)
                    .put("providerReportedCostUsd", 2.50)
                    .put("modelPricedCostUsd", 1.25)
                    .put("unpricedEvents", 2)
                    .put("unpricedTokens", 15_000L)
                    .put("cacheSavingsUsd", 1.80)
                    .put(
                        "categoryCostUsd",
                        JSONObject()
                            .put("input", 1.50)
                            .put("cacheRead", 0.75)
                            .put("cacheWrite", 0.25)
                            .put("output", 1.25),
                    ),
            )
            .put(
                "buckets",
                JSONArray().put(
                    JSONObject()
                        .put("date", "2026-11-06")
                        .put("label", "11-06")
                        .put("totalTokens", 1_060_000L)
                        .put("costUsd", 3.75)
                        .put(
                            "byCli",
                            JSONObject().put(
                                "Codex",
                                JSONObject().put("totalTokens", 1_060_000L).put("costUsd", 3.75),
                            ),
                        ),
                ),
            )
            .put(
                "byProvider",
                JSONArray().put(
                    JSONObject()
                        .put("cli", "Codex")
                        .put("events", 42)
                        .put("totalTokens", 1_060_000L)
                        .put("uncachedInputTokens", 120_000L)
                        .put("cachedInputTokens", 880_000L)
                        .put("cacheCreationTokens", 10_000L)
                        .put("outputTokens", 45_000L)
                        .put("reasoningTokens", 5_000L)
                        .put("costUsd", 3.75)
                        .put("providerReportedCostUsd", 2.50)
                        .put("modelPricedCostUsd", 1.25)
                        .put("unpricedTokens", 15_000L),
                ),
            )
            .put(
                "byModel",
                JSONArray().put(
                    JSONObject()
                        .put("cli", "Codex")
                        .put("model", "gpt-5.4")
                        .put("events", 40)
                        .put("totalTokens", 1_045_000L)
                        .put("inputTokens", 1_000_000L)
                        .put("outputTokens", 45_000L)
                        .put("costUsd", 3.75)
                        .put("costSource", "modelPriced"),
                ),
            )

        val summary = parseGlobalUsageSummary(raw)
        assertEquals(7, summary.days)
        assertEquals("day", summary.granularity)
        assertEquals(1_060_000L, summary.totals.totalTokens)
        assertEquals(3.75, summary.totals.costUsd, 1e-6)
        assertEquals(2.50, summary.totals.providerReportedCostUsd, 1e-6)
        assertEquals(1.25, summary.totals.modelPricedCostUsd, 1e-6)
        assertEquals(3.75, summary.totals.categoryCostUsd.total, 1e-6)
        assertEquals(1, summary.buckets.size)
        assertEquals(1_060_000L, summary.buckets[0].byCli.getValue("Codex").totalTokens)
        assertEquals("gpt-5.4", summary.byModel.single().model)
        assertEquals("modelPriced", summary.byModel.single().costSource)
    }

    @Test fun computesPaceGapAndRemainingTickMatchesT3CodeSemantics() {
        val nowMs = 1_700_000_000_000L
        val fiveHoursMs = 5L * 3_600_000L
        val resetIso = java.time.Instant.ofEpochMilli(nowMs + 2L * 3_600_000L).toString()
        val elapsed = computeElapsedShare(resetIso, fiveHoursMs, nowMs)
        assertNotNull(elapsed)
        assertEquals(0.60, elapsed!!, 1e-6)
        assertEquals(40, computePaceRemainingPercent(elapsed))

        assertEquals(UsagePace.AHEAD, computeUsagePace(75.0, elapsed))
        assertEquals(UsagePace.ON_TRACK, computeUsagePace(62.0, elapsed))
        assertEquals(UsagePace.UNDER, computeUsagePace(30.0, elapsed))
    }

    @Test fun formatsTokensCostAndResetCountdownCleanly() {
        assertEquals("950", formatCompactTokens(950L))
        assertEquals("12.5K", formatCompactTokens(12_500L))
        assertEquals("4.2M", formatCompactTokens(4_200_000L))
        assertEquals("5.64B", formatCompactTokens(5_639_446_293L))

        assertEquals("$0.00", formatUsdCost(0.0))
        assertEquals("<$0.01", formatUsdCost(0.004))
        assertEquals("<~$0.01", formatUsdCost(0.004, approximate = true))
        assertEquals("~$38.19", formatUsdCost(38.1924, approximate = true))
        assertEquals("$159.81", formatUsdCost(159.8109, approximate = false))

        val nowMs = 1_700_000_000_000L
        val in95Mins = java.time.Instant.ofEpochMilli(nowMs + 95L * 60_000L).toString()
        assertEquals("1h 35m 后重置", formatResetCountdown(in95Mins, nowMs))
    }

    @Test fun filtersUnconfiguredNonOAuthProvidersAndSortsErrorsBelowLiveLimits() {
        val raw = JSONObject()
            .put("generatedAt", "2026-11-07T02:00:00Z")
            .put(
                "providers",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("cli", "Claude Code")
                            .put("status", "unconfigured")
                            .put("source", "unavailable")
                            .put("windows", JSONArray()),
                    )
                    .put(
                        JSONObject()
                            .put("cli", "Grok Build")
                            .put("status", "error")
                            .put("source", "unavailable")
                            .put("message", "实时限额探测超时，请检查网络后刷新重试")
                            .put("windows", JSONArray()),
                    )
                    .put(
                        JSONObject()
                            .put("cli", "Codex")
                            .put("status", "ok")
                            .put("source", "live")
                            .put(
                                "windows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("id", "primary")
                                        .put("label", "5h 窗口")
                                        .put("usedPercent", 15.0)
                                        .put("remainingPercent", 85.0),
                                ),
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("cli", "Antigravity")
                            .put("status", "ok")
                            .put("source", "live")
                            .put(
                                "windows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("id", "gemini-5h")
                                        .put("label", "Gemini · 5h 窗口")
                                        .put("usedPercent", 11.5)
                                        .put("remainingPercent", 88.5),
                                ),
                            ),
                    ),
            )

        val limits = parseGlobalUsageLimits(raw)
        assertEquals(
            listOf("Codex", "Antigravity", "Grok Build"),
            limits.providers.map { it.cli },
        )
        assertFalse(limits.providers.any { it.status == "unconfigured" })
    }

    @Test fun parsesTwoTabModelVariantEndpointsActivityAndRolling30dCost() {
        assertEquals(UsageMetricView.STATS, UsageMetricView.entries.first())
        assertEquals(listOf("统计", "限额"), UsageMetricView.entries.map { it.label })
        assertEquals(listOf("today", "week", "month", "all"), UsagePeriodOption.entries.map { it.periodId })

        val summaryRaw = JSONObject()
            .put("generatedAt", "2026-11-11T09:00:00Z")
            .put("period", "month")
            .put("days", 30)
            .put("granularity", "day")
            .put("totals", JSONObject().put("totalTokens", 19_500L).put("costUsd", 1.32))
            .put("rolling30dCostByCli", JSONObject().put("Antigravity", 68.4).put("Codex", 42.0))
            .put(
                "byProvider",
                JSONArray().put(
                    JSONObject()
                        .put("cli", "Antigravity")
                        .put("events", 3)
                        .put("totalTokens", 19_500L)
                        .put("uncachedInputTokens", 2_000L)
                        .put("cachedInputTokens", 8_000L)
                        .put("cacheCreationTokens", 0L)
                        .put("outputTokens", 1_500L)
                        .put("reasoningTokens", 200L)
                        .put("costUsd", 1.32)
                        .put("cacheSavingsUsd", 0.48),
                ),
            )
            .put(
                "byModel",
                JSONArray().put(
                    JSONObject()
                        .put("cli", "Antigravity")
                        .put("model", "gemini-4-argon-trial")
                        .put("events", 2)
                        .put("totalTokens", 15_000L)
                        .put("inputTokens", 10_000L)
                        .put("outputTokens", 5_000L)
                        .put("costUsd", 1.15)
                        .put("costSource", "modelPriced")
                        .put("isVariantEndpoint", true)
                        .put("uiLabels", JSONArray().put("Gemini 4 Argon (High)")),
                ),
            )
            .put(
                "activity",
                JSONObject()
                    .put("activeDays", 12)
                    .put("currentStreak", 5)
                    .put("longestStreak", 9)
                    .put("peakDayDate", "2026-11-10")
                    .put("peakDayTokens", 45_000_000L)
                    .put(
                        "days",
                        JSONArray().put(
                            JSONObject()
                                .put("date", "2026-11-11")
                                .put("totalTokens", 19_500L)
                                .put("costUsd", 1.32),
                        ),
                    ),
            )

        val summary = parseGlobalUsageSummary(summaryRaw)
        assertEquals("month", summary.period)
        assertEquals(68.4, summary.rolling30dCostByCli.getValue("Antigravity"), 1e-6)
        assertEquals(42.0, summary.rolling30dCostByCli.getValue("Codex"), 1e-6)
        assertEquals(80, summary.byProvider.single().cacheHitPercent)
        assertEquals(0.48, summary.byProvider.single().cacheSavingsUsd, 1e-6)

        val model = summary.byModel.single()
        assertEquals("gemini-4-argon-trial", model.model)
        assertTrue(model.isVariantEndpoint)
        assertEquals(listOf("Gemini 4 Argon (High)"), model.uiLabels)

        assertEquals(12, summary.activity.activeDays)
        assertEquals(5, summary.activity.currentStreak)
        assertEquals(9, summary.activity.longestStreak)
        assertEquals("2026-11-10", summary.activity.peakDayDate)
        assertEquals(1, summary.activity.days.size)
        assertTrue(summary.hasRolling30dCost)
    }

    @Test fun distinguishesMissingVsZeroRolling30dCostAndGuardsStalePeriodSummary() {
        assertEquals("cached_summary_v2_", SUMMARY_CACHE_PREF_PREFIX)

        val legacyRaw = JSONObject()
            .put("period", "month")
            .put("days", 30)
            .put("granularity", "day")
            .put("totals", JSONObject().put("totalTokens", 1_000L))
        val legacySummary = parseGlobalUsageSummary(legacyRaw)
        assertFalse(legacySummary.hasRolling30dCost)
        assertTrue(legacySummary.rolling30dCostByCli.isEmpty())
        assertEquals("—", formatRolling30dCost(null, "Codex"))

        val zeroCostRaw = JSONObject()
            .put("period", "month")
            .put("days", 30)
            .put("granularity", "day")
            .put("rolling30dCostByCli", JSONObject().put("Codex", 0.0).put("Bad", -1.5))
        val zeroCostSummary = parseGlobalUsageSummary(zeroCostRaw)
        assertTrue(zeroCostSummary.hasRolling30dCost)
        assertEquals(0.0, zeroCostSummary.rolling30dCostByCli.getValue("Codex"), 1e-6)
        assertFalse(zeroCostSummary.rolling30dCostByCli.containsKey("Bad"))
        assertEquals("$0.00", formatRolling30dCost(zeroCostSummary.rolling30dCostByCli, "Codex"))
        assertEquals("$0.00", formatRolling30dCost(zeroCostSummary.rolling30dCostByCli, "Antigravity"))
        assertEquals("~$42.00", formatRolling30dCost(mapOf("Codex" to 42.0), "Codex"))

        assertSame(zeroCostSummary, resolveStatsSummary(zeroCostSummary, suppliedSummary = null, selectedPeriodId = "month"))
        assertNull(resolveStatsSummary(zeroCostSummary, suppliedSummary = null, selectedPeriodId = "week"))
        assertSame(zeroCostSummary, resolveStatsSummary(zeroCostSummary, suppliedSummary = zeroCostSummary, selectedPeriodId = "week"))
    }
}
