package io.github.modao7067.luma.gui.ui

import com.termux.terminal.LumaLang
import io.github.modao7067.luma.gui.i18n.LumaI18n
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GlobalUsageTest {
    private lateinit var previousLocale: Locale
    private var previousLangProp: String? = null

    @Before fun setUp() {
        previousLocale = Locale.getDefault()
        previousLangProp = System.getProperty("luma.lang")
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        System.clearProperty("luma.lang")
        LumaI18n.resetForTest()
        LumaLang.setOverride(null)
    }

    @After fun tearDown() {
        LumaI18n.resetForTest()
        LumaLang.setOverride(null)
        Locale.setDefault(previousLocale)
        if (previousLangProp == null) System.clearProperty("luma.lang")
        else System.setProperty("luma.lang", previousLangProp)
    }

    @Test fun quotaLabelsKeepOnlyPeriodAndModelNames() {
        val labels = mapOf(
            "5h 窗口" to "5h",
            "7d 窗口" to "7d",
            "7d 总窗口" to "7d 总计",
            "Gemini · 5h 窗口" to "Gemini · 5h",
            "Gemini · 主窗口" to "Gemini · 常规",
            "5h window" to "5h",
            "Gemini · 7d Window" to "Gemini · 7d",
            "7d overall" to "7d overall",
            "Monthly credits" to "Monthly credits",
            "每周 · Claude Sonnet" to "每周 · Claude Sonnet",
        )
        labels.forEach { (source, expected) ->
            assertEquals(expected, formatUsageLimitLabel(source))
        }
    }

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
        assertEquals("cached_summary_v5_", SUMMARY_CACHE_PREF_PREFIX)
        assertEquals(
            "剩余 ¥2.73",
            formatUsageCreditsValue(UsageCreditsSnapshot(label = "账户余额", used = 0.0, limit = null, remaining = 2.73, unit = "CNY")),
        )
        assertEquals(
            "剩余 $12.50 / $50.00",
            formatUsageCreditsValue(UsageCreditsSnapshot(label = "可用额度", used = 37.5, limit = 50.0, remaining = 12.5, unit = "USD")),
        )
        assertEquals(
            "已用 $4.20",
            formatUsageCreditsValue(UsageCreditsSnapshot(label = "额外用量", used = 4.2, limit = null, remaining = null, unit = "USD")),
        )
        val fallbackColor = providerUsageColor("Unknown CLI")
        val catalogColors = io.github.modao7067.luma.gui.session.CliCatalog.ids.map { cli ->
            val color = providerUsageColor(cli)
            assertNotEquals("Expected dedicated usage color for $cli", fallbackColor, color)
            color
        }
        assertEquals(io.github.modao7067.luma.gui.session.CliCatalog.ids.size, catalogColors.toSet().size)

        val legacyRaw = JSONObject()
            .put("period", "month")
            .put("days", 30)
            .put("granularity", "day")
            .put("totals", JSONObject().put("totalTokens", 1_000L))
        val legacySummary = parseGlobalUsageSummary(legacyRaw)
        assertFalse(legacySummary.hasRolling30dCost)
        assertTrue(legacySummary.rolling30dCostByCli.isEmpty())
        assertEquals("builtin", legacySummary.pricingSource.mode)
        assertEquals(2, legacySummary.pricingSource.sources.size)
        assertEquals("anomalyco/models.dev", legacySummary.pricingSource.sources[0].repo)
        assertEquals("BerriAI/litellm", legacySummary.pricingSource.sources[1].repo)
        assertEquals("—", formatRolling30dCost(null, "Codex"))

        val zeroCostRaw = JSONObject()
            .put("period", "month")
            .put("days", 30)
            .put("granularity", "day")
            .put("rolling30dCostByCli", JSONObject().put("Codex", 0.0).put("Bad", -1.5))
            .put(
                "pricingSource",
                JSONObject()
                    .put("mode", "online")
                    .put("updatedAt", "2026-11-14T07:10:00Z")
                    .put("modelCount", 2852)
                    .put(
                        "sources",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("id", "models.dev")
                                    .put("name", "models.dev")
                                    .put("repo", "anomalyco/models.dev")
                                    .put("url", "https://github.com/anomalyco/models.dev")
                                    .put("active", true),
                            )
                            .put(
                                JSONObject()
                                    .put("id", "litellm")
                                    .put("name", "LiteLLM")
                                    .put("repo", "BerriAI/litellm")
                                    .put("url", "https://github.com/BerriAI/litellm")
                                    .put("active", true),
                            ),
                    ),
            )
        val zeroCostSummary = parseGlobalUsageSummary(zeroCostRaw)
        assertTrue(zeroCostSummary.hasRolling30dCost)
        assertEquals("online", zeroCostSummary.pricingSource.mode)
        assertEquals(2852, zeroCostSummary.pricingSource.modelCount)
        assertTrue(zeroCostSummary.pricingSource.sources.all { it.active })
        assertEquals("2026-11-14 07:10", formatPricingSyncTime("2026-11-14T07:10:00Z", java.time.ZoneOffset.UTC))
        assertNull(formatPricingSyncTime(""))
        assertEquals(0.0, zeroCostSummary.rolling30dCostByCli.getValue("Codex"), 1e-6)
        assertFalse(zeroCostSummary.rolling30dCostByCli.containsKey("Bad"))
        assertEquals("$0.00", formatRolling30dCost(zeroCostSummary.rolling30dCostByCli, "Codex"))
        assertEquals("$0.00", formatRolling30dCost(zeroCostSummary.rolling30dCostByCli, "Antigravity"))
        assertEquals("~$42.00", formatRolling30dCost(mapOf("Codex" to 42.0), "Codex"))

        assertSame(zeroCostSummary, resolveStatsSummary(zeroCostSummary, suppliedSummary = null, selectedPeriodId = "month"))
        assertNull(resolveStatsSummary(zeroCostSummary, suppliedSummary = null, selectedPeriodId = "week"))
        assertSame(zeroCostSummary, resolveStatsSummary(zeroCostSummary, suppliedSummary = zeroCostSummary, selectedPeriodId = "week"))
    }

    @Test fun englishLocaleFormatsUsageLabelsCountdownsAndCredits() {
        Locale.setDefault(Locale.US)
        LumaI18n.overrideLanguage = "en"

        assertEquals(listOf("Stats", "Quotas"), UsageMetricView.entries.map { it.label })
        assertEquals(listOf("Today", "Week", "Month", "All time"), UsagePeriodOption.entries.map { it.label })

        val nowMs = 1_700_000_000_000L
        val in95Mins = java.time.Instant.ofEpochMilli(nowMs + 95L * 60_000L).toString()
        assertEquals("Resets in 1h 35m", formatResetCountdown(in95Mins, nowMs))
        assertEquals("Resetting soon", formatResetCountdown(java.time.Instant.ofEpochMilli(nowMs - 1000L).toString(), nowMs))

        assertEquals(
            "¥2.73 left",
            formatUsageCreditsValue(UsageCreditsSnapshot(label = "Balance", used = 0.0, limit = null, remaining = 2.73, unit = "CNY")),
        )
        assertEquals(
            "$12.50 left / $50.00",
            formatUsageCreditsValue(UsageCreditsSnapshot(label = "Quota", used = 37.5, limit = 50.0, remaining = 12.5, unit = "USD")),
        )
        assertEquals(
            "$4.20 used",
            formatUsageCreditsValue(UsageCreditsSnapshot(label = "Extra", used = 4.2, limit = null, remaining = null, unit = "USD")),
        )

        val omittedCredLabelJson = JSONObject().put(
            "providers",
            JSONArray().put(
                JSONObject()
                    .put("cli", "Codex")
                    .put("status", "ok")
                    .put("source", "live")
                    .put("windows", JSONArray())
                    .put("credits", JSONObject().put("used", 1.0).put("unit", "USD")),
            ),
        )
        assertEquals("Quota", parseGlobalUsageLimits(omittedCredLabelJson).providers.single().credits?.label)
    }

    @Test fun guardsNonFiniteNumbersAndJsonNullsInLimitsAndFormatters() {
        assertNull(computeUsagePace(Double.NaN, 0.5))
        assertNull(computeUsagePace(Double.POSITIVE_INFINITY, 0.5))
        assertNull(computeUsagePace(Double.NEGATIVE_INFINITY, 0.5))
        assertNull(computeUsagePace(20.0, Double.NaN))
        assertNull(computeUsagePace(20.0, Double.POSITIVE_INFINITY))
        assertNull(computePaceRemainingPercent(Double.NaN))
        assertNull(computePaceRemainingPercent(Double.NEGATIVE_INFINITY))

        assertEquals("$0.00", formatUsdCost(Double.NaN))
        assertEquals("$0.00", formatUsdCost(Double.POSITIVE_INFINITY))
        assertEquals("$0.00", formatUsdCost(Double.NEGATIVE_INFINITY))

        val nonFiniteCredits = UsageCreditsSnapshot(
            label = "额度",
            used = Double.NaN,
            limit = Double.POSITIVE_INFINITY,
            remaining = Double.NEGATIVE_INFINITY,
            unit = "USD",
        )
        assertEquals("已用 $0.00", formatUsageCreditsValue(nonFiniteCredits))

        val rawWithNullsAndNonFinite = JSONObject()
            .put("generatedAt", JSONObject.NULL)
            .put(
                "providers",
                JSONArray().put(
                    JSONObject()
                        .put("cli", "Codex")
                        .put("planType", JSONObject.NULL)
                        .put("updatedAt", JSONObject.NULL)
                        .put("status", "ok")
                        .put("source", "live")
                        .put("message", JSONObject.NULL)
                        .put(
                            "windows",
                            JSONArray().put(
                                JSONObject()
                                    .put("id", "primary")
                                    .put("label", JSONObject.NULL)
                                    .put("usedPercent", "NaN")
                                    .put("remainingPercent", "Infinity")
                                    .put("resetsAt", JSONObject.NULL)
                                    .put("windowDurationMs", JSONObject.NULL)
                                    .put("groupLabel", JSONObject.NULL),
                            ),
                        )
                        .put(
                            "credits",
                            JSONObject()
                                .put("label", JSONObject.NULL)
                                .put("used", "NaN")
                                .put("limit", "Infinity")
                                .put("remaining", "-Infinity")
                                .put("unit", JSONObject.NULL),
                        ),
                ),
            )

        val limits = parseGlobalUsageLimits(rawWithNullsAndNonFinite)
        assertEquals("", limits.generatedAt)
        val provider = limits.providers.single()
        assertNull(provider.planType)
        assertEquals("", provider.updatedAt)
        assertNull(provider.message)
        val window = provider.windows.single()
        assertEquals("", window.label)
        assertTrue(window.usedPercent.isFinite())
        assertTrue(window.remainingPercent.isFinite())
        assertEquals(0.0, window.usedPercent, 1e-6)
        assertEquals(100.0, window.remainingPercent, 1e-6)
        assertNull(window.resetsAt)
        assertNull(window.windowDurationMs)
        assertNull(window.groupLabel)
        val credits = provider.credits!!
        assertEquals("额度", credits.label)
        assertEquals(0.0, credits.used, 1e-6)
        assertNull(credits.limit)
        assertNull(credits.remaining)
        assertEquals("USD", credits.unit)

        val nonFiniteWindowJson = object : JSONObject() {
            override fun opt(key: String?): Any? = when (key) {
                "id" -> "primary"
                "usedPercent" -> Double.NaN
                "remainingPercent" -> Double.NEGATIVE_INFINITY
                else -> null
            }
        }
        val nonFiniteCreditsJson = object : JSONObject() {
            override fun opt(key: String?): Any? = when (key) {
                "used" -> Double.NaN
                "limit" -> Double.POSITIVE_INFINITY
                "remaining" -> Double.NEGATIVE_INFINITY
                "unit" -> "USD"
                else -> null
            }
        }
        val directNonFiniteRaw = JSONObject().put(
            "providers",
            JSONArray().put(
                JSONObject()
                    .put("cli", "Codex")
                    .put("status", "ok")
                    .put("source", "live")
                    .put("windows", JSONArray().put(nonFiniteWindowJson))
                    .put("credits", nonFiniteCreditsJson),
            ),
        )
        val directParsed = parseGlobalUsageLimits(directNonFiniteRaw).providers.single()
        assertEquals(0.0, directParsed.windows.single().usedPercent, 1e-6)
        assertEquals(100.0, directParsed.windows.single().remainingPercent, 1e-6)
        assertEquals(0.0, directParsed.credits!!.used, 1e-6)
        assertNull(directParsed.credits!!.limit)
        assertNull(directParsed.credits!!.remaining)
    }
}
