package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.i18n.LumaI18n
import io.github.modao7067.luma.gui.i18n.tr

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import io.github.modao7067.luma.gui.runtime.AgentRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val summaryMemoryCache = ConcurrentHashMap<String, GlobalUsageSummary>()
private val limitsMemoryCache = ConcurrentHashMap<String, GlobalUsageLimits>()

private sealed interface UsageErrorState {
    data class Message(val text: String) : UsageErrorState
    data object SummaryLoadFailed : UsageErrorState
    data object LimitsLoadFailed : UsageErrorState
}

private fun JSONObject.safeString(key: String, fallback: String = ""): String =
    if (isNull(key)) fallback else optString(key, fallback)

private fun limitsCacheKey(environment: String, langCode: String): String =
    "$environment::$langCode"

internal const val SUMMARY_CACHE_PREF_PREFIX = "cached_summary_v5_"

private fun readCachedSummarySnapshot(
    prefs: android.content.SharedPreferences?,
    environment: String,
    periodId: String,
): GlobalUsageSummary? {
    val key = "$environment::$periodId"
    summaryMemoryCache[key]?.let { return it }
    val raw = prefs?.getString("$SUMMARY_CACHE_PREF_PREFIX$key", null)?.takeIf { it.isNotBlank() } ?: return null
    return runCatching { parseGlobalUsageSummary(JSONObject(raw)) }.getOrNull()?.also {
        summaryMemoryCache[key] = it
    }
}

private fun writeCachedSummarySnapshot(
    prefs: android.content.SharedPreferences?,
    environment: String,
    periodId: String,
    rawJson: JSONObject,
    parsed: GlobalUsageSummary,
) {
    val key = "$environment::$periodId"
    summaryMemoryCache[key] = parsed
    prefs?.edit()?.putString("$SUMMARY_CACHE_PREF_PREFIX$key", rawJson.toString())?.apply()
}

private fun readCachedLimitsSnapshot(
    prefs: android.content.SharedPreferences?,
    environment: String,
    langCode: String = LumaI18n.langCode(),
): GlobalUsageLimits? {
    val key = limitsCacheKey(environment, langCode)
    limitsMemoryCache[key]?.let { return it }
    val raw = prefs?.getString("cached_limits_v2_$key", null)?.takeIf { it.isNotBlank() } ?: return null
    return runCatching { parseGlobalUsageLimits(JSONObject(raw)) }.getOrNull()?.also {
        limitsMemoryCache[key] = it
    }
}

private fun writeCachedLimitsSnapshot(
    prefs: android.content.SharedPreferences?,
    environment: String,
    rawJson: JSONObject,
    parsed: GlobalUsageLimits,
    langCode: String = LumaI18n.langCode(),
) {
    val key = limitsCacheKey(environment, langCode)
    limitsMemoryCache[key] = parsed
    prefs?.edit()?.putString("cached_limits_v2_$key", rawJson.toString())?.apply()
}

internal enum class UsageMetricView(private val zh: String, private val en: String) {
    STATS("统计", "Stats"),
    LIMITS("限额", "Quotas"),
    ;
    val label: String get() = tr(zh, en)
}

internal enum class UsagePeriodOption(val periodId: String, private val zh: String, private val en: String) {
    TODAY("today", "今日", "Today"),
    WEEK("week", "本周", "Week"),
    MONTH("month", "本月", "Month"),
    ALL("all", "累计", "All time"),
    ;
    val label: String get() = tr(zh, en)
}

internal enum class UsagePace(private val zh: String, private val en: String) {
    AHEAD("消耗偏快", "Burning fast"),
    ON_TRACK("匀速", "On pace"),
    UNDER("余量充裕", "Plenty left"),
    ;
    val label: String get() = tr(zh, en)
}

internal data class CategoryCostBreakdown(
    val input: Double = 0.0,
    val cacheRead: Double = 0.0,
    val cacheWrite: Double = 0.0,
    val output: Double = 0.0,
) {
    val total: Double get() = input + cacheRead + cacheWrite + output
}

internal data class UsageTotals(
    val events: Int = 0,
    val uncachedInputTokens: Long = 0L,
    val cachedInputTokens: Long = 0L,
    val cacheCreationTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val reasoningTokens: Long = 0L,
    val totalTokens: Long = 0L,
    val costUsd: Double = 0.0,
    val providerReportedCostUsd: Double = 0.0,
    val modelPricedCostUsd: Double = 0.0,
    val unpricedEvents: Int = 0,
    val unpricedTokens: Long = 0L,
    val cacheSavingsUsd: Double = 0.0,
    val categoryCostUsd: CategoryCostBreakdown = CategoryCostBreakdown(),
)

internal data class UsageBucketCliValue(
    val totalTokens: Long = 0L,
    val costUsd: Double = 0.0,
)

internal data class UsagePeriodBucket(
    val date: String,
    val label: String,
    val totalTokens: Long,
    val costUsd: Double,
    val byCli: Map<String, UsageBucketCliValue>,
)

internal data class ProviderUsageTotal(
    val cli: String,
    val events: Int,
    val totalTokens: Long,
    val uncachedInputTokens: Long,
    val cachedInputTokens: Long,
    val cacheCreationTokens: Long,
    val outputTokens: Long,
    val reasoningTokens: Long,
    val costUsd: Double,
    val providerReportedCostUsd: Double,
    val modelPricedCostUsd: Double,
    val unpricedTokens: Long,
    val cacheSavingsUsd: Double = 0.0,
) {
    val totalInputTokens: Long get() = uncachedInputTokens + cachedInputTokens + cacheCreationTokens
    val cacheHitPercent: Int
        get() = if (totalInputTokens > 0L) {
            ((cachedInputTokens.toDouble() / totalInputTokens.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
        } else {
            0
        }
}

internal data class ModelUsageTotal(
    val cli: String,
    val model: String,
    val events: Int,
    val totalTokens: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val costUsd: Double,
    val costSource: String,
    val isVariantEndpoint: Boolean = false,
    val uiLabels: List<String> = emptyList(),
)

internal data class UsageActivityDay(
    val date: String,
    val totalTokens: Long,
    val costUsd: Double,
)

internal data class UsageActivitySummary(
    val days: List<UsageActivityDay> = emptyList(),
    val activeDays: Int = 0,
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val peakDayDate: String? = null,
    val peakDayTokens: Long = 0L,
)

internal data class PricingSourceItem(
    val id: String,
    val name: String,
    val repo: String,
    val url: String,
    val siteUrl: String = "",
    val active: Boolean = false,
)

internal fun defaultPricingSourceItems(): List<PricingSourceItem> = listOf(
    PricingSourceItem(
        id = "models.dev",
        name = "models.dev",
        repo = "anomalyco/models.dev",
        url = "https://github.com/anomalyco/models.dev",
        siteUrl = "https://models.dev",
        active = false,
    ),
    PricingSourceItem(
        id = "litellm",
        name = "LiteLLM",
        repo = "BerriAI/litellm",
        url = "https://github.com/BerriAI/litellm",
        siteUrl = "https://models.litellm.ai",
        active = false,
    ),
)

internal data class UsagePricingSource(
    val mode: String = "builtin",
    val updatedAt: String = "",
    val modelCount: Int = 0,
    val sources: List<PricingSourceItem> = defaultPricingSourceItems(),
)

internal data class GlobalUsageSummary(
    val generatedAt: String = "",
    val period: String = "month",
    val days: Int = 30,
    val granularity: String = "day",
    val totals: UsageTotals = UsageTotals(),
    val buckets: List<UsagePeriodBucket> = emptyList(),
    val byProvider: List<ProviderUsageTotal> = emptyList(),
    val byModel: List<ModelUsageTotal> = emptyList(),
    val rolling30dCostByCli: Map<String, Double> = emptyMap(),
    val hasRolling30dCost: Boolean = false,
    val activity: UsageActivitySummary = UsageActivitySummary(),
    val pricingSource: UsagePricingSource = UsagePricingSource(),
)

internal data class UsageLimitWindow(
    val id: String,
    val label: String,
    val usedPercent: Double,
    val remainingPercent: Double,
    val resetsAt: String? = null,
    val windowDurationMs: Long? = null,
    val groupLabel: String? = null,
)

internal data class UsageCreditsSnapshot(
    val label: String,
    val used: Double,
    val limit: Double? = null,
    val remaining: Double? = null,
    val unit: String = "USD",
)

internal data class ProviderLimitSnapshot(
    val cli: String,
    val planType: String? = null,
    val updatedAt: String = "",
    val source: String = "unavailable",
    val status: String = "unconfigured",
    val message: String? = null,
    val windows: List<UsageLimitWindow> = emptyList(),
    val credits: UsageCreditsSnapshot? = null,
)

internal data class GlobalUsageLimits(
    val generatedAt: String = "",
    val providers: List<ProviderLimitSnapshot> = emptyList(),
)

internal fun parseGlobalUsageSummary(json: JSONObject): GlobalUsageSummary {
    val t = json.optJSONObject("totals") ?: JSONObject()
    val cat = t.optJSONObject("categoryCostUsd") ?: JSONObject()
    val totals = UsageTotals(
        events = t.optInt("events", 0),
        uncachedInputTokens = t.optLong("uncachedInputTokens", 0L),
        cachedInputTokens = t.optLong("cachedInputTokens", 0L),
        cacheCreationTokens = t.optLong("cacheCreationTokens", 0L),
        outputTokens = t.optLong("outputTokens", 0L),
        reasoningTokens = t.optLong("reasoningTokens", 0L),
        totalTokens = t.optLong("totalTokens", 0L),
        costUsd = t.optDouble("costUsd", 0.0).takeIf { it.isFinite() } ?: 0.0,
        providerReportedCostUsd = t.optDouble("providerReportedCostUsd", 0.0).takeIf { it.isFinite() } ?: 0.0,
        modelPricedCostUsd = t.optDouble("modelPricedCostUsd", 0.0).takeIf { it.isFinite() } ?: 0.0,
        unpricedEvents = t.optInt("unpricedEvents", 0),
        unpricedTokens = t.optLong("unpricedTokens", 0L),
        cacheSavingsUsd = t.optDouble("cacheSavingsUsd", 0.0).takeIf { it.isFinite() } ?: 0.0,
        categoryCostUsd = CategoryCostBreakdown(
            input = cat.optDouble("input", 0.0).takeIf { it.isFinite() } ?: 0.0,
            cacheRead = cat.optDouble("cacheRead", 0.0).takeIf { it.isFinite() } ?: 0.0,
            cacheWrite = cat.optDouble("cacheWrite", 0.0).takeIf { it.isFinite() } ?: 0.0,
            output = cat.optDouble("output", 0.0).takeIf { it.isFinite() } ?: 0.0,
        ),
    )

    val bucketsArr = json.optJSONArray("buckets") ?: JSONArray()
    val buckets = buildList {
        for (i in 0 until bucketsArr.length()) {
            val b = bucketsArr.optJSONObject(i) ?: continue
            val byCliObj = b.optJSONObject("byCli") ?: JSONObject()
            val cliMap = mutableMapOf<String, UsageBucketCliValue>()
            val keys = byCliObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = byCliObj.optJSONObject(k) ?: continue
                cliMap[k] = UsageBucketCliValue(
                    totalTokens = v.optLong("totalTokens", 0L),
                    costUsd = v.optDouble("costUsd", 0.0).takeIf { it.isFinite() } ?: 0.0,
                )
            }
            add(
                UsagePeriodBucket(
                    date = b.safeString("date"),
                    label = b.safeString("label"),
                    totalTokens = b.optLong("totalTokens", 0L),
                    costUsd = b.optDouble("costUsd", 0.0).takeIf { it.isFinite() } ?: 0.0,
                    byCli = cliMap,
                ),
            )
        }
    }

    val provArr = json.optJSONArray("byProvider") ?: JSONArray()
    val providers = buildList {
        for (i in 0 until provArr.length()) {
            val p = provArr.optJSONObject(i) ?: continue
            add(
                ProviderUsageTotal(
                    cli = p.safeString("cli", "unknown").ifBlank { "unknown" },
                    events = p.optInt("events", 0),
                    totalTokens = p.optLong("totalTokens", 0L),
                    uncachedInputTokens = p.optLong("uncachedInputTokens", 0L),
                    cachedInputTokens = p.optLong("cachedInputTokens", 0L),
                    cacheCreationTokens = p.optLong("cacheCreationTokens", 0L),
                    outputTokens = p.optLong("outputTokens", 0L),
                    reasoningTokens = p.optLong("reasoningTokens", 0L),
                    costUsd = p.optDouble("costUsd", 0.0).takeIf { it.isFinite() } ?: 0.0,
                    providerReportedCostUsd = p.optDouble("providerReportedCostUsd", 0.0).takeIf { it.isFinite() } ?: 0.0,
                    modelPricedCostUsd = p.optDouble("modelPricedCostUsd", 0.0).takeIf { it.isFinite() } ?: 0.0,
                    unpricedTokens = p.optLong("unpricedTokens", 0L),
                    cacheSavingsUsd = p.optDouble("cacheSavingsUsd", 0.0).takeIf { it.isFinite() } ?: 0.0,
                ),
            )
        }
    }

    val modArr = json.optJSONArray("byModel") ?: JSONArray()
    val models = buildList {
        for (i in 0 until modArr.length()) {
            val m = modArr.optJSONObject(i) ?: continue
            val uiArr = m.optJSONArray("uiLabels")
            val uiLabels = if (uiArr != null) {
                buildList {
                    for (uIdx in 0 until uiArr.length()) {
                        val s = if (uiArr.isNull(uIdx)) "" else uiArr.optString(uIdx, "").trim()
                        if (s.isNotEmpty()) add(s)
                    }
                }
            } else {
                emptyList()
            }
            add(
                ModelUsageTotal(
                    cli = m.safeString("cli", "unknown").ifBlank { "unknown" },
                    model = m.safeString("model", "unknown").ifBlank { "unknown" },
                    events = m.optInt("events", 0),
                    totalTokens = m.optLong("totalTokens", 0L),
                    inputTokens = m.optLong("inputTokens", 0L),
                    outputTokens = m.optLong("outputTokens", 0L),
                    costUsd = m.optDouble("costUsd", 0.0).takeIf { it.isFinite() } ?: 0.0,
                    costSource = m.safeString("costSource", "unpriced").ifBlank { "unpriced" },
                    isVariantEndpoint = m.optBoolean("isVariantEndpoint", false),
                    uiLabels = uiLabels,
                ),
            )
        }
    }

    val hasRolling30dCost = json.has("rolling30dCostByCli") && !json.isNull("rolling30dCostByCli")
    val rollingCostObj = json.optJSONObject("rolling30dCostByCli") ?: JSONObject()
    val rollingCostMap = mutableMapOf<String, Double>()
    val rcKeys = rollingCostObj.keys()
    while (rcKeys.hasNext()) {
        val k = rcKeys.next()
        val v = rollingCostObj.optDouble(k, Double.NaN)
        if (v.isFinite() && v >= 0.0) rollingCostMap[k] = v
    }

    val actObj = json.optJSONObject("activity")
    val activity = if (actObj != null) {
        val actDaysArr = actObj.optJSONArray("days") ?: JSONArray()
        val actDays = buildList {
            for (i in 0 until actDaysArr.length()) {
                val d = actDaysArr.optJSONObject(i) ?: continue
                add(
                    UsageActivityDay(
                        date = d.safeString("date"),
                        totalTokens = d.optLong("totalTokens", 0L),
                        costUsd = d.optDouble("costUsd", 0.0).takeIf { it.isFinite() } ?: 0.0,
                    ),
                )
            }
        }
        UsageActivitySummary(
            days = actDays,
            activeDays = actObj.optInt("activeDays", 0),
            currentStreak = actObj.optInt("currentStreak", 0),
            longestStreak = actObj.optInt("longestStreak", 0),
            peakDayDate = actObj.safeString("peakDayDate").takeIf { it.isNotBlank() && it != "null" },
            peakDayTokens = actObj.optLong("peakDayTokens", 0L),
        )
    } else {
        UsageActivitySummary()
    }

    val psObj = json.optJSONObject("pricingSource")
    val pricingSource = if (psObj != null) {
        val srcArr = psObj.optJSONArray("sources")
        val parsedSources = if (srcArr != null && srcArr.length() > 0) {
            buildList {
                for (i in 0 until srcArr.length()) {
                    val s = srcArr.optJSONObject(i) ?: continue
                    val url = s.safeString("url").trim()
                    val name = s.safeString("name").trim()
                    if (name.isEmpty() || url.isEmpty()) continue
                    add(
                        PricingSourceItem(
                            id = s.safeString("id", name).ifBlank { name },
                            name = name,
                            repo = s.safeString("repo"),
                            url = url,
                            siteUrl = s.safeString("siteUrl"),
                            active = s.optBoolean("active", false),
                        ),
                    )
                }
            }.ifEmpty { defaultPricingSourceItems() }
        } else {
            defaultPricingSourceItems()
        }
        UsagePricingSource(
            mode = psObj.safeString("mode", "builtin").ifBlank { "builtin" },
            updatedAt = psObj.safeString("updatedAt"),
            modelCount = psObj.optInt("modelCount", 0),
            sources = parsedSources,
        )
    } else {
        UsagePricingSource()
    }

    return GlobalUsageSummary(
        generatedAt = json.safeString("generatedAt"),
        period = json.safeString("period", "custom").ifBlank { "custom" },
        days = json.optInt("days", 30),
        granularity = json.safeString("granularity", "day").ifBlank { "day" },
        totals = totals,
        buckets = buckets,
        byProvider = providers,
        byModel = models,
        rolling30dCostByCli = rollingCostMap,
        hasRolling30dCost = hasRolling30dCost,
        activity = activity,
        pricingSource = pricingSource,
    )
}

internal fun parseGlobalUsageLimits(json: JSONObject): GlobalUsageLimits {
    val arr = json.optJSONArray("providers") ?: JSONArray()
    val providers = buildList {
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val status = p.safeString("status", "unconfigured").ifBlank { "unconfigured" }
            if (status == "unconfigured") continue
            val winArr = p.optJSONArray("windows") ?: JSONArray()
            val windows = buildList {
                for (j in 0 until winArr.length()) {
                    val w = winArr.optJSONObject(j) ?: continue
                    val usedPct = w.optDouble("usedPercent", 0.0).takeIf { it.isFinite() }?.coerceIn(0.0, 100.0) ?: 0.0
                    val remPct = w.optDouble("remainingPercent", 100.0).takeIf { it.isFinite() }?.coerceIn(0.0, 100.0) ?: 100.0
                    add(
                        UsageLimitWindow(
                            id = w.safeString("id"),
                            label = w.safeString("label"),
                            usedPercent = usedPct,
                            remainingPercent = remPct,
                            resetsAt = w.safeString("resetsAt").takeIf { it.isNotBlank() && it != "null" },
                            windowDurationMs = if (w.isNull("windowDurationMs")) null else w.optLong("windowDurationMs", 0L).takeIf { it > 0L },
                            groupLabel = w.safeString("groupLabel").takeIf { it.isNotBlank() && it != "null" },
                        ),
                    )
                }
            }
            val credObj = p.optJSONObject("credits")
            val credits = credObj?.let {
                val credLabel = it.safeString("label").trim()
                UsageCreditsSnapshot(
                    label = credLabel.ifEmpty { tr("额度", "Quota") },
                    used = it.optDouble("used", 0.0).takeIf { v -> v.isFinite() } ?: 0.0,
                    limit = if (it.isNull("limit")) null else it.optDouble("limit", Double.NaN).takeIf { v -> v.isFinite() },
                    remaining = if (it.isNull("remaining")) null else it.optDouble("remaining", Double.NaN).takeIf { v -> v.isFinite() },
                    unit = it.safeString("unit", "USD").ifBlank { "USD" },
                )
            }
            add(
                ProviderLimitSnapshot(
                    cli = p.safeString("cli"),
                    planType = p.safeString("planType").takeIf { it.isNotBlank() && it != "null" },
                    updatedAt = p.safeString("updatedAt"),
                    source = p.safeString("source", "unavailable").ifBlank { "unavailable" },
                    status = status,
                    message = p.safeString("message").takeIf { it.isNotBlank() && it != "null" },
                    windows = windows,
                    credits = credits,
                ),
            )
        }
    }.sortedBy(::providerLimitSortRank)
    return GlobalUsageLimits(
        generatedAt = json.safeString("generatedAt"),
        providers = providers,
    )
}

internal fun providerLimitSortRank(provider: ProviderLimitSnapshot): Int {
    val hasData = provider.windows.isNotEmpty() || provider.credits != null
    return when {
        hasData -> 0
        provider.status == "error" -> 1
        else -> 2
    }
}

internal fun parseIsoInstantMs(iso: String?): Long? {
    if (iso.isNullOrBlank()) return null
    return try {
        Instant.parse(iso.trim()).toEpochMilli()
    } catch (_: Exception) {
        try {
            OffsetDateTime.parse(iso.trim()).toInstant().toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }
}

internal fun computeElapsedShare(resetsAtIso: String?, windowDurationMs: Long?, nowMs: Long = System.currentTimeMillis()): Double? {
    val dur = windowDurationMs?.takeIf { it > 0L } ?: return null
    val resetMs = parseIsoInstantMs(resetsAtIso) ?: return null
    val remainingMs = resetMs - nowMs
    if (remainingMs < 0L || remainingMs > dur) return null
    return ((dur - remainingMs).toDouble() / dur.toDouble()).coerceIn(0.0, 1.0)
}

internal fun computePaceRemainingPercent(elapsedShare: Double?): Int? =
    elapsedShare?.takeIf { it.isFinite() }?.let { ((1.0 - it.coerceIn(0.0, 1.0)) * 100.0).roundToInt().coerceIn(0, 100) }

internal fun computeUsagePace(usedPercent: Double, elapsedShare: Double?): UsagePace? {
    if (!usedPercent.isFinite()) return null
    val share = elapsedShare?.takeIf { it.isFinite() } ?: return null
    val gap = usedPercent - share * 100.0
    return when {
        gap > 5.0 -> UsagePace.AHEAD
        gap < -5.0 -> UsagePace.UNDER
        else -> UsagePace.ON_TRACK
    }
}

internal fun formatCompactTokens(tokens: Long): String = when {
    tokens >= 1_000_000_000L -> String.format(Locale.US, "%.2fB", tokens / 1_000_000_000.0)
    tokens >= 1_000_000L -> String.format(Locale.US, "%.1fM", tokens / 1_000_000.0)
    tokens >= 1_000L -> String.format(Locale.US, "%.1fK", tokens / 1_000_000.0 * 1_000.0)
    else -> tokens.toString()
}

internal fun formatUsageLimitLabel(label: String): String = label
    .replace("主窗口", "常规")
    .replace("总窗口", "总计")
    .replace("窗口", "")
    .replace(Regex("\\s+windows?\\b", RegexOption.IGNORE_CASE), "")
    .trim()

internal fun formatUsdCost(usd: Double, approximate: Boolean = false): String {
    if (!usd.isFinite() || usd <= 0.0) return "$0.00"
    val prefix = if (approximate) "~$" else "$"
    if (usd < 0.01) return "<$prefix" + "0.01"
    return prefix + String.format(Locale.US, "%.2f", usd)
}

internal fun formatRolling30dCost(rolling30dCostByCli: Map<String, Double>?, cli: String): String {
    val usd = rolling30dCostByCli?.let { it[cli] ?: 0.0 } ?: return "—"
    return formatUsdCost(usd, approximate = usd > 0.0)
}

internal fun formatUsageCreditsValue(cred: UsageCreditsSnapshot): String {
    val sym = when (cred.unit.trim().uppercase(Locale.US)) {
        "CNY", "RMB" -> "¥"
        "EUR" -> "€"
        else -> "$"
    }
    val safeLimit = cred.limit?.takeIf { it.isFinite() }
    val safeRemaining = cred.remaining?.takeIf { it.isFinite() }
    val safeUsed = cred.used.takeIf { it.isFinite() } ?: 0.0
    return when {
        safeLimit != null -> {
            val rem = String.format(Locale.US, "%.2f", safeRemaining ?: 0.0)
            val lim = String.format(Locale.US, "%.2f", safeLimit)
            tr("剩余 $sym$rem / $sym$lim", "$sym$rem left / $sym$lim")
        }
        safeRemaining != null -> {
            val rem = String.format(Locale.US, "%.2f", safeRemaining)
            tr("剩余 $sym$rem", "$sym$rem left")
        }
        else -> {
            val used = String.format(Locale.US, "%.2f", safeUsed)
            tr("已用 $sym$used", "$sym$used used")
        }
    }
}

internal fun resolveStatsSummary(
    summary: GlobalUsageSummary?,
    suppliedSummary: GlobalUsageSummary?,
    selectedPeriodId: String,
): GlobalUsageSummary? = summary?.takeIf { suppliedSummary != null || it.period == selectedPeriodId }

internal fun formatResetCountdown(resetsAtIso: String?, nowMs: Long = System.currentTimeMillis()): String? {
    val resetMs = parseIsoInstantMs(resetsAtIso) ?: return null
    val diffMs = resetMs - nowMs
    if (diffMs <= 0L) return tr("即将重置", "Resetting soon")
    val totalMins = (diffMs / 60_000L).toInt()
    val days = totalMins / (24 * 60)
    val hours = (totalMins % (24 * 60)) / 60
    val mins = totalMins % 60
    return when {
        days > 0 -> tr("${days}d ${hours}h 后重置", "Resets in ${days}d ${hours}h")
        hours > 0 -> tr("${hours}h ${mins}m 后重置", "Resets in ${hours}h ${mins}m")
        else -> tr("${max(1, mins)}m 后重置", "Resets in ${max(1, mins)}m")
    }
}

internal fun providerUsageColor(cli: String): Color = when (cli) {
    "Codex" -> Color(0xFF3B82F6)
    "Claude Code" -> Color(0xFFD97757)
    "Antigravity" -> Color(0xFF8B5CF6)
    "OpenCode" -> Color(0xFF10B981)
    "pi" -> Color(0xFFF59E0B)
    "DeepSeek Harness" -> Color(0xFF4D6BFE)
    "Kimi Code" -> Color(0xFFEC4899)
    "Grok Build" -> Color(0xFF06B6D4)
    "Qoder CLI" -> Color(0xFF14B8A6)
    "CodeBuddy Code" -> Color(0xFF6366F1)
    else -> Color(0xFF64748B)
}

@Composable
internal fun GlobalUsageDialog(
    environment: String = io.github.modao7067.luma.gui.GuiProduct.environment,
    onDismiss: () -> Unit,
    suppliedSummary: GlobalUsageSummary? = null,
    suppliedLimits: GlobalUsageLimits? = null,
) {
    GuiPageDialog(onDismissRequest = onDismiss) {
        GlobalUsageScreen(
            environment = environment,
            onDismiss = onDismiss,
            suppliedSummary = suppliedSummary,
            suppliedLimits = suppliedLimits,
        )
    }
}

@Composable
internal fun GlobalUsageScreen(
    environment: String = io.github.modao7067.luma.gui.GuiProduct.environment,
    onDismiss: () -> Unit,
    suppliedSummary: GlobalUsageSummary? = null,
    suppliedLimits: GlobalUsageLimits? = null,
) {
    val context = LocalContext.current
    val inspection = LocalInspectionMode.current
    val runtime = remember(context, inspection) { if (inspection) null else AgentRuntime(context) }
    val prefs = remember(context, inspection) {
        if (inspection) null else context.getSharedPreferences("luma_usage_prefs", Context.MODE_PRIVATE)
    }
    val scope = rememberCoroutineScope()

    val pagerState = rememberPagerState(initialPage = 0) { UsageMetricView.entries.size }
    val metricView = UsageMetricView.entries.getOrElse(pagerState.targetPage) { UsageMetricView.STATS }
    var selectedPeriodName by rememberSaveable { mutableStateOf(UsagePeriodOption.MONTH.name) }
    val selectedPeriod = runCatching { UsagePeriodOption.valueOf(selectedPeriodName) }.getOrDefault(UsagePeriodOption.MONTH)

    val initialSummary = remember(environment) {
        suppliedSummary ?: readCachedSummarySnapshot(prefs, environment, selectedPeriod.periodId)
    }
    var summary by remember(environment) {
        mutableStateOf(initialSummary)
    }
    var lastKnownRolling30dCost by remember(environment, suppliedSummary) {
        mutableStateOf(
            when {
                suppliedSummary != null -> suppliedSummary.rolling30dCostByCli.takeIf { suppliedSummary.hasRolling30dCost }
                initialSummary?.hasRolling30dCost == true -> initialSummary.rolling30dCostByCli
                else -> UsagePeriodOption.entries.firstNotNullOfOrNull { option ->
                    readCachedSummarySnapshot(prefs, environment, option.periodId)
                        ?.takeIf { it.hasRolling30dCost }
                        ?.rolling30dCostByCli
                }
            }
        )
    }
    val langCode = LumaI18n.langCode()
    var limits by remember(environment, langCode, suppliedLimits) {
        mutableStateOf(suppliedLimits ?: readCachedLimitsSnapshot(prefs, environment, langCode))
    }
    val statsSummary = resolveStatsSummary(summary, suppliedSummary, selectedPeriod.periodId)
    var loadingSummary by remember { mutableStateOf(statsSummary == null && !inspection) }
    var loadingLimits by remember(environment, langCode, suppliedLimits) {
        mutableStateOf((suppliedLimits ?: limits) == null && !inspection)
    }
    var manualRefreshing by remember { mutableStateOf(false) }
    var limitsFetchedInSession by remember(environment, langCode, suppliedLimits) {
        mutableStateOf(suppliedLimits != null)
    }
    var summaryRequestSeq by remember { mutableIntStateOf(0) }
    var limitsRequestSeq by remember { mutableIntStateOf(0) }
    var errorState by remember { mutableStateOf<UsageErrorState?>(null) }

    LaunchedEffect(langCode) {
        if (errorState is UsageErrorState.Message) errorState = null
    }

    fun currentSelectedPeriodId(): String =
        runCatching { UsagePeriodOption.valueOf(selectedPeriodName) }
            .getOrDefault(UsagePeriodOption.MONTH)
            .periodId

    fun refreshData(forceRefresh: Boolean) {
        val rt = runtime ?: return
        val tz = TimeZone.getDefault().id.ifBlank { "UTC" }
        val periodArg = currentSelectedPeriodId()
        val requestEnv = environment
        val requestLang = LumaI18n.langCode()

        if (forceRefresh) {
            manualRefreshing = true
            errorState = null
        } else {
            readCachedSummarySnapshot(prefs, requestEnv, periodArg)?.let { cached ->
                summary = cached
                if (cached.hasRolling30dCost) {
                    lastKnownRolling30dCost = cached.rolling30dCostByCli
                }
            }
        }

        val needLimits = forceRefresh || !limitsFetchedInSession || limits == null
        val reqId = ++summaryRequestSeq
        val limitsReqId = if (needLimits) ++limitsRequestSeq else 0
        loadingSummary = true
        if (needLimits) {
            loadingLimits = true
            limitsFetchedInSession = true
        }

        scope.launch {
            runCatching {
                val raw = rt.readGlobalUsageSummary(
                    environment = requestEnv,
                    timeZone = tz,
                    refresh = forceRefresh,
                    period = periodArg,
                )
                raw to parseGlobalUsageSummary(raw)
            }.onSuccess { (raw, parsed) ->
                writeCachedSummarySnapshot(prefs, requestEnv, periodArg, raw, parsed)
                if (environment == requestEnv && parsed.hasRolling30dCost) {
                    lastKnownRolling30dCost = parsed.rolling30dCostByCli
                }
                if (summaryRequestSeq == reqId && environment == requestEnv && currentSelectedPeriodId() == periodArg) {
                    summary = parsed
                }
            }.onFailure { err ->
                if (summaryRequestSeq == reqId && environment == requestEnv && currentSelectedPeriodId() == periodArg) {
                    val activeStatsSummary = resolveStatsSummary(summary, suppliedSummary, periodArg)
                    if (forceRefresh || activeStatsSummary == null) {
                        errorState = err.message?.takeIf { it.isNotBlank() }?.let(UsageErrorState::Message)
                            ?: UsageErrorState.SummaryLoadFailed
                    }
                }
            }
            if (summaryRequestSeq == reqId && environment == requestEnv) {
                loadingSummary = false
                if (!loadingLimits) manualRefreshing = false
            }
        }

        if (needLimits) {
            scope.launch {
                runCatching {
                    val raw = rt.readGlobalUsageLimits(requestEnv, fresh = forceRefresh)
                    raw to parseGlobalUsageLimits(raw)
                }.onSuccess { (raw, parsed) ->
                    writeCachedLimitsSnapshot(prefs, requestEnv, raw, parsed, requestLang)
                    if (limitsRequestSeq == limitsReqId && environment == requestEnv && LumaI18n.langCode() == requestLang) {
                        limits = parsed
                    }
                }.onFailure { err ->
                    if (limitsRequestSeq == limitsReqId && environment == requestEnv && LumaI18n.langCode() == requestLang) {
                        if (forceRefresh || (metricView == UsageMetricView.LIMITS && limits == null)) {
                            if (errorState == null) {
                                errorState = err.message?.takeIf { it.isNotBlank() }?.let(UsageErrorState::Message)
                                    ?: UsageErrorState.LimitsLoadFailed
                            }
                        }
                    }
                }
                if (limitsRequestSeq == limitsReqId && environment == requestEnv && LumaI18n.langCode() == requestLang) {
                    loadingLimits = false
                    if (!loadingSummary) manualRefreshing = false
                }
            }
        }
    }

    LaunchedEffect(environment, selectedPeriod, langCode) {
        if (suppliedSummary == null && !inspection) {
            refreshData(forceRefresh = false)
        }
    }

    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = colors.background,
    ) {
        LiquidScaffold(
            wrapTop = false,
            wrapBottom = false,
            topBar = {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val backElastic = rememberElasticPress()
                    val refreshElastic = rememberElasticPress()
                    LiquidGlass(
                        Modifier
                            .size(44.dp)
                            .testTag("usage-back")
                            .semantics { contentDescription = tr("关闭用量", "Close usage") }
                            .liquidClickable(press = backElastic, onClick = onDismiss),
                        cornerRadius = 22.dp,
                        press = backElastic,
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            ComposerGlyph("back")
                        }
                    }
                    LiquidGlass(
                        Modifier
                            .weight(1f)
                            .height(44.dp),
                        cornerRadius = 22.dp,
                    ) {
                        Row(
                            Modifier
                                .fillMaxSize()
                                .padding(horizontal = 18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                tr("用量", "Usage"),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            )
                            Text(
                                environmentLabel(environment),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                    LiquidGlass(
                        Modifier
                            .size(44.dp)
                            .testTag("usage-refresh")
                            .semantics { contentDescription = tr("刷新用量", "Refresh usage") }
                            .liquidClickable(press = refreshElastic) {
                                if (!manualRefreshing) refreshData(forceRefresh = true)
                            },
                        cornerRadius = 22.dp,
                        press = refreshElastic,
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Box(Modifier.glyphPulse(manualRefreshing)) {
                                MenuIcon(MenuSymbol.REFRESH, size = 19.dp)
                            }
                        }
                    }
                }
            },
            bottomBar = {},
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding() + 6.dp),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    UsageMetricTabBar(
                        pagerState = pagerState,
                        metricView = metricView,
                        onSelectMetric = { tab ->
                            scope.launch {
                                pagerState.animateScrollToPage(tab.ordinal)
                            }
                        },
                    )

                    if (manualRefreshing) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp)
                                .clip(CircleShape),
                        )
                    }

                    errorState?.let { err ->
                        val msg = when (err) {
                            is UsageErrorState.Message -> err.text
                            UsageErrorState.SummaryLoadFailed -> tr("用量读取失败", "Failed to load usage")
                            UsageErrorState.LimitsLoadFailed -> tr("限额读取失败", "Failed to load quotas")
                        }
                        ErrorNotice(msg) { errorState = null }
                    }
                }

                Spacer(Modifier.height(10.dp))

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) { page ->
                    val pageView = UsageMetricView.entries.getOrElse(page) { UsageMetricView.STATS }
                    if (pageView == UsageMetricView.LIMITS) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(
                                    start = 16.dp,
                                    end = 16.dp,
                                    bottom = padding.calculateBottomPadding() + 28.dp,
                                ),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (!manualRefreshing && limits == null && loadingLimits) {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 4.dp)
                                        .clip(CircleShape),
                                )
                            }
                            UsageLimitsContent(
                                limits = limits,
                                rolling30dCostByCli = lastKnownRolling30dCost,
                                loading = loadingLimits,
                            )
                        }
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            UsagePeriodSelector(
                                selectedPeriod = selectedPeriod,
                                onSelectPeriod = { nextPeriod ->
                                    errorState = null
                                    readCachedSummarySnapshot(prefs, environment, nextPeriod.periodId)?.let { cached ->
                                        summary = cached
                                        if (cached.hasRolling30dCost) {
                                            lastKnownRolling30dCost = cached.rolling30dCostByCli
                                        }
                                    }
                                    selectedPeriodName = nextPeriod.name
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                            )
                            Column(
                                Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .padding(
                                        start = 16.dp,
                                        end = 16.dp,
                                        bottom = padding.calculateBottomPadding() + 28.dp,
                                    ),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                if (!manualRefreshing && statsSummary == null && loadingSummary) {
                                    LinearProgressIndicator(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 4.dp)
                                            .clip(CircleShape),
                                    )
                                }
                                UsageStatsContent(
                                    summary = statsSummary,
                                    selectedPeriod = selectedPeriod,
                                    loading = loadingSummary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageMetricTabBar(
    pagerState: PagerState,
    metricView: UsageMetricView,
    onSelectMetric: (UsageMetricView) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val tabShape = LumaCardShape(11.dp)
    Surface(
        shape = LumaCardShape(14.dp),
        color = colors.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(3.dp)
                .height(32.dp),
        ) {
            val indicatorColor = colors.secondaryContainer
            Canvas(Modifier.matchParentSize()) {
                val count = UsageMetricView.entries.size
                if (count <= 0) return@Canvas
                val gapPx = 4.dp.toPx()
                val tabWidth = (size.width - gapPx * (count - 1)) / count.toFloat()
                val progress = (pagerState.currentPage + pagerState.currentPageOffsetFraction)
                    .coerceIn(0f, (count - 1).toFloat())
                val left = progress * (tabWidth + gapPx)
                val outline = tabShape.createOutline(Size(tabWidth, size.height), layoutDirection, this)
                translate(left = left, top = 0f) {
                    drawOutline(outline = outline, color = indicatorColor)
                }
            }
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                UsageMetricView.entries.forEach { tab ->
                    val selected = tab == metricView
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(tabShape)
                            .clickable(role = Role.Tab) { onSelectMetric(tab) }
                            .semantics { this.selected = selected }
                            .testTag("usage-tab-${tab.name.lowercase()}"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            tab.label,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            ),
                            color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UsagePeriodSelector(
    selectedPeriod: UsagePeriodOption,
    onSelectPeriod: (UsagePeriodOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UsagePeriodOption.entries.forEach { option ->
            val active = selectedPeriod == option
            Box(
                Modifier
                    .weight(1f)
                    .height(32.dp)
                    .clip(CircleShape)
                    .background(if (active) colors.primaryContainer else colors.surfaceContainerLow)
                    .clickable(role = Role.Tab) { onSelectPeriod(option) }
                    .semantics { selected = active }
                    .testTag("usage-window-${option.periodId}"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option.label,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    ),
                    color = if (active) colors.onPrimaryContainer else colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun UsageLimitsContent(
    limits: GlobalUsageLimits?,
    rolling30dCostByCli: Map<String, Double>?,
    loading: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val providers = remember(limits) {
        limits?.providers.orEmpty()
            .filter { it.status != "unconfigured" }
            .sortedBy(::providerLimitSortRank)
    }
    if (providers.isEmpty() && !loading) {
        Surface(
            shape = LumaCardShape(20.dp),
            color = colors.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(tr("暂无限额数据", "No quota data"), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                Text(
                    tr("登录订阅账号后刷新", "Sign in to a subscription, then refresh"),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        return
    }

    val nowMs = remember(limits?.generatedAt) { System.currentTimeMillis() }
    providers.forEach { provider ->
        ProviderLimitCard(
            provider = provider,
            rolling30dCostByCli = rolling30dCostByCli,
            nowMs = nowMs,
        )
    }
}

@Composable
private fun ProviderLimitCard(
    provider: ProviderLimitSnapshot,
    rolling30dCostByCli: Map<String, Double>?,
    nowMs: Long,
) {
    val colors = MaterialTheme.colorScheme
    val hasWindows = provider.windows.isNotEmpty() || provider.credits != null
    val accent = providerUsageColor(provider.cli)

    Surface(
        shape = LumaCardShape(22.dp),
        color = colors.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    ProviderIcon(provider.cli, selected = hasWindows, size = 32.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            cliDisplayLabel(provider.cli),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        )
                        if (!provider.planType.isNullOrBlank()) {
                            Text(
                                provider.planType,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                val badgeText = when (provider.source) {
                    "live" -> tr("实时", "Live")
                    "session_fallback" -> tr("历史", "History")
                    else -> if (provider.status == "error") tr("读取失败", "Unavailable") else tr("未配置", "Not configured")
                }
                Surface(
                    shape = LumaCardShape(10.dp),
                    color = if (hasWindows) colors.secondaryContainer.copy(alpha = 0.7f) else colors.surfaceContainerHighest,
                ) {
                    Text(
                        badgeText,
                        Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (hasWindows) colors.onSecondaryContainer else colors.onSurfaceVariant,
                    )
                }
            }

            if (hasWindows) {
                Surface(
                    shape = LumaCardShape(12.dp),
                    color = colors.surfaceContainerHighest.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            tr("近 30 天 API 估算", "30-day API estimate"),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                        )
                        Text(
                            formatRolling30dCost(rolling30dCostByCli, provider.cli),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = colors.onSurface,
                        )
                    }
                }
            }

            if (provider.windows.isNotEmpty()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    provider.windows.forEach { window ->
                        LimitWindowRow(window = window, accentColor = accent, nowMs = nowMs)
                    }
                }
            }

            provider.credits?.let { cred ->
                HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.35f))
                val credLabel = when (cred.label.trim()) {
                    "", "额度", "Quota" -> tr("额度", "Quota")
                    else -> cred.label
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(credLabel, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    val valueStr = formatUsageCreditsValue(cred)
                    Text(
                        valueStr,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                }
            }

            if (!hasWindows && !provider.message.isNullOrBlank()) {
                Text(
                    provider.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (provider.status == "error") colors.error else colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LimitWindowRow(
    window: UsageLimitWindow,
    accentColor: Color,
    nowMs: Long,
) {
    val colors = MaterialTheme.colorScheme
    val langCode = LumaI18n.langCode()
    val used = (window.usedPercent.takeIf { it.isFinite() } ?: 0.0).coerceIn(0.0, 100.0)
    val remaining = (window.remainingPercent.takeIf { it.isFinite() } ?: 100.0).coerceIn(0.0, 100.0)
    val elapsedShare = remember(window.resetsAt, window.windowDurationMs, nowMs) {
        computeElapsedShare(window.resetsAt, window.windowDurationMs, nowMs)
    }
    val paceRemaining = remember(elapsedShare) { computePaceRemainingPercent(elapsedShare) }
    val pace = remember(used, elapsedShare) { computeUsagePace(used, elapsedShare) }
    val countdown = remember(window.resetsAt, nowMs, langCode) { formatResetCountdown(window.resetsAt, nowMs) }

    val barColor = when {
        remaining <= 10.0 -> Color(0xFFE5484D)
        remaining <= 30.0 -> Color(0xFFF5A623)
        else -> accentColor
    }

    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                formatUsageLimitLabel(window.label),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                tr("剩余 ${remaining.roundToInt()}%", "${remaining.roundToInt()}% left"),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                ),
                color = if (remaining <= 10.0) Color(0xFFE5484D) else colors.onSurface,
            )
        }

        val trackColor = colors.surfaceContainerHighest
        val surfaceColor = colors.surfaceContainerLow
        val tickColor = colors.onSurface.copy(alpha = 0.72f)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(9.dp),
        ) {
            val w = size.width
            val h = size.height
            val r = CornerRadius(h / 2f, h / 2f)
            drawRoundRect(color = trackColor, cornerRadius = r, size = size)

            val fillWidth = (w * (remaining.toFloat() / 100f)).coerceIn(0f, w)
            if (fillWidth > 0.5f) {
                drawRoundRect(
                    color = barColor,
                    cornerRadius = r,
                    size = Size(fillWidth, h),
                )
            }

            if (paceRemaining != null && paceRemaining in 2..98) {
                val tickX = w * (paceRemaining / 100f)
                val gapHalf = 2.5.dp.toPx()
                val tickWidth = 2.dp.toPx()
                drawRect(
                    color = surfaceColor,
                    topLeft = Offset(max(0f, tickX - gapHalf), 0f),
                    size = Size(min(w, gapHalf * 2f), h),
                )
                drawRect(
                    color = tickColor,
                    topLeft = Offset(max(0f, tickX - tickWidth / 2f), 0f),
                    size = Size(tickWidth, h),
                )
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    tr("已用 ${String.format(Locale.US, "%.1f", used)}%", "${String.format(Locale.US, "%.1f", used)}% used"),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
                if (pace != null) {
                    val paceColor = when (pace) {
                        UsagePace.AHEAD -> Color(0xFFE5484D)
                        UsagePace.ON_TRACK -> colors.onSurfaceVariant
                        UsagePace.UNDER -> Color(0xFF10B981)
                    }
                    Text(
                        "· ${pace.label}",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = paceColor,
                    )
                }
            }
            if (countdown != null) {
                Text(
                    countdown,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun UsageStatsContent(
    summary: GlobalUsageSummary?,
    selectedPeriod: UsagePeriodOption,
    loading: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    if (summary == null) {
        if (!loading) {
            Surface(
                shape = LumaCardShape(20.dp),
                color = colors.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    tr("暂无用量数据", "No usage data"),
                    Modifier.padding(20.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        return
    }

    val totals = summary.totals
    val hasApproximate = totals.modelPricedCostUsd > 0.0
    var chartByCost by rememberSaveable { mutableStateOf(false) }
    var selectedBucketIdx by remember(summary.period, summary.buckets.size) { mutableStateOf<Int?>(null) }
    val activeBucket = selectedBucketIdx?.let { summary.buckets.getOrNull(it) }
    var expandedClis by remember { mutableStateOf(setOf<String>()) }

    Surface(
        shape = LumaCardShape(22.dp),
        color = colors.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (activeBucket != null) tr("${activeBucket.date} 明细", "${activeBucket.date} details") else tr("${selectedPeriod.label}总览", "${selectedPeriod.label} overview"),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (activeBucket != null) {
                        Box(
                            Modifier
                                .clip(CircleShape)
                                .background(colors.secondaryContainer)
                                .clickable(role = Role.Button) { selectedBucketIdx = null }
                                .padding(horizontal = 9.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                tr("总览", "Overview"),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = colors.onSecondaryContainer,
                            )
                        }
                    }
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .background(colors.surfaceContainerHighest)
                            .clickable(role = Role.Button) { chartByCost = !chartByCost }
                            .padding(horizontal = 9.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (chartByCost) tr("按费用 ⇅", "By cost ⇅") else tr("按 Token ⇅", "By tokens ⇅"),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                val heroTokens = activeBucket?.totalTokens ?: totals.totalTokens
                val heroCost = activeBucket?.costUsd ?: totals.costUsd
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        formatCompactTokens(heroTokens),
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                    Text(
                        "Token",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        formatUsdCost(heroCost, approximate = hasApproximate && heroCost > 0.0),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = colors.primary,
                    )
                    Text(
                        tr("API 估算", "API estimate"),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }

            if (activeBucket != null) {
                val cliItems = activeBucket.byCli.entries
                    .filter { it.value.totalTokens > 0L || it.value.costUsd > 0.0 }
                    .sortedByDescending { if (chartByCost) it.value.costUsd else it.value.totalTokens.toDouble() }
                val breakdownText = if (cliItems.isEmpty()) {
                    tr("暂无记录", "No activity")
                } else {
                    cliItems.joinToString(" · ") { (cli, v) ->
                        "${cliDisplayLabel(cli)} ${formatCompactTokens(v.totalTokens)} (${formatUsdCost(v.costUsd, approximate = true)})"
                    }
                }
                Text(
                    breakdownText,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            } else {
                val subParts = buildList {
                    add(tr("${totals.events} 次请求", "${totals.events} requests"))
                    if (totals.cacheSavingsUsd > 0.0) {
                        add(tr("缓存省 ${formatUsdCost(totals.cacheSavingsUsd, approximate = true)}", "Cache saved ${formatUsdCost(totals.cacheSavingsUsd, approximate = true)}"))
                    }
                    if (totals.providerReportedCostUsd > 0.0 && totals.modelPricedCostUsd > 0.0) {
                        add(tr("实报 ${formatUsdCost(totals.providerReportedCostUsd)}", "Reported ${formatUsdCost(totals.providerReportedCostUsd)}"))
                    }
                }
                Text(
                    subParts.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }

            if (summary.buckets.isNotEmpty()) {
                UsageStackedBarChart(
                    buckets = summary.buckets,
                    providers = summary.byProvider.map { it.cli },
                    costMode = chartByCost,
                    selectedIndex = selectedBucketIdx,
                    onSelectBucket = { idx -> selectedBucketIdx = idx },
                )
            }
        }
    }

    if (summary.activity.days.isNotEmpty()) {
        UsageActivityHeatmapCard(activity = summary.activity)
    }

    if (summary.byProvider.isNotEmpty()) {
        Surface(
            shape = LumaCardShape(22.dp),
            color = colors.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tr("CLI 用量", "By CLI"),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        tr("展开明细", "Expand details"),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                    )
                }
                val maxDenominator = if (chartByCost) {
                    summary.byProvider.sumOf { it.costUsd }.takeIf { it > 0.0 } ?: 1.0
                } else {
                    summary.byProvider.sumOf { it.totalTokens }.toDouble().takeIf { it > 0.0 } ?: 1.0
                }
                summary.byProvider.forEach { p ->
                    val metricVal = if (chartByCost) p.costUsd else p.totalTokens.toDouble()
                    val share = (metricVal / maxDenominator).coerceIn(0.0, 1.0)
                    val pColor = providerUsageColor(p.cli)
                    val isExpanded = p.cli in expandedClis
                    val cliModels = remember(summary.byModel, p.cli, chartByCost) {
                        summary.byModel
                            .filter { it.cli == p.cli }
                            .sortedWith(
                                if (chartByCost) {
                                    compareByDescending<ModelUsageTotal> { it.costUsd }.thenByDescending { it.totalTokens }
                                } else {
                                    compareByDescending<ModelUsageTotal> { it.totalTokens }.thenByDescending { it.costUsd }
                                },
                            )
                    }

                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(LumaCardShape(14.dp))
                            .clickable {
                                expandedClis = if (isExpanded) expandedClis - p.cli else expandedClis + p.cli
                            }
                            .padding(vertical = 3.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            ProviderIcon(p.cli, selected = isExpanded, size = 28.dp)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    cliDisplayLabel(p.cli),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                )
                                Text(
                                    tr("${p.events} 次 · ${(share * 100.0).roundToInt()}% · 缓存命中 ${p.cacheHitPercent}%", "${p.events} req · ${(share * 100.0).roundToInt()}% · Cache ${p.cacheHitPercent}%"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                val primaryStr = if (chartByCost) {
                                    formatUsdCost(p.costUsd, approximate = p.modelPricedCostUsd > 0.0)
                                } else {
                                    formatCompactTokens(p.totalTokens)
                                }
                                val secondaryStr = if (chartByCost) {
                                    "${formatCompactTokens(p.totalTokens)} · ${if (isExpanded) "▴" else "▾"}"
                                } else {
                                    "${formatUsdCost(p.costUsd, approximate = p.modelPricedCostUsd > 0.0)} · ${if (isExpanded) "▴" else "▾"}"
                                }
                                Text(
                                    primaryStr,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                )
                                Text(
                                    secondaryStr,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(CircleShape)
                                .background(colors.surfaceContainerHighest),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(share.toFloat().coerceIn(0.02f, 1f))
                                    .fillMaxHeight()
                                    .clip(CircleShape)
                                    .background(pColor),
                            )
                        }

                        AnimatedVisibility(
                            visible = isExpanded,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut(),
                        ) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp)
                                    .clip(LumaCardShape(12.dp))
                                    .background(colors.surfaceContainerHighest.copy(alpha = 0.42f))
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                val detailMeta = buildList {
                                    add(tr("输入缓存命中 ${p.cacheHitPercent}%", "Input cache ${p.cacheHitPercent}%"))
                                    if (p.cacheSavingsUsd > 0.0) {
                                        add(tr("省 ${formatUsdCost(p.cacheSavingsUsd, approximate = true)}", "Saved ${formatUsdCost(p.cacheSavingsUsd, approximate = true)}"))
                                    }
                                    if (p.reasoningTokens > 0L) {
                                        add(tr("思考 ${formatCompactTokens(p.reasoningTokens)}", "Reasoning ${formatCompactTokens(p.reasoningTokens)}"))
                                    }
                                }.joinToString(" · ")
                                Text(
                                    detailMeta,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.onSurfaceVariant,
                                )

                                cliModels.forEachIndexed { mIdx, m ->
                                    if (mIdx > 0) {
                                        HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.25f))
                                    }
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                m.model,
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontFamily = FontFamily.Monospace,
                                                ),
                                            )
                                            val uiHint = if (m.uiLabels.isNotEmpty()) {
                                                tr("面板: ${m.uiLabels.joinToString(" / ")} · ", "UI: ${m.uiLabels.joinToString(" / ")} · ")
                                            } else {
                                                ""
                                            }
                                            Text(
                                                tr("${uiHint}输入 ${formatCompactTokens(m.inputTokens)} · 输出 ${formatCompactTokens(m.outputTokens)}", "${uiHint}In ${formatCompactTokens(m.inputTokens)} · Out ${formatCompactTokens(m.outputTokens)}"),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = colors.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        val mCostTag = when (m.costSource) {
                                            "unpriced" -> tr("未定价", "Unpriced")
                                            "modelPriced", "mixed" -> formatUsdCost(m.costUsd) + "*"
                                            else -> formatUsdCost(m.costUsd)
                                        }
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(
                                                formatCompactTokens(m.totalTokens),
                                                style = MaterialTheme.typography.labelMedium.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = FontFamily.Monospace,
                                                ),
                                            )
                                            Text(
                                                mCostTag,
                                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                                color = colors.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (totals.categoryCostUsd.total > 0.0) {
        CostCategoryBreakdownCard(totals.categoryCostUsd)
    }

    TotalsMetricGrid(totals = totals)

    if (summary.byModel.isNotEmpty()) {
        Surface(
            shape = LumaCardShape(22.dp),
            color = colors.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tr("模型排行", "Top models"),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        tr("* API 费率估算", "* API estimate"),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f),
                    )
                }
                val displayedModels = remember(summary.byModel, chartByCost) {
                    if (chartByCost) {
                        summary.byModel.sortedWith(compareByDescending<ModelUsageTotal> { it.costUsd }.thenByDescending { it.totalTokens })
                    } else {
                        summary.byModel.sortedWith(compareByDescending<ModelUsageTotal> { it.totalTokens }.thenByDescending { it.costUsd })
                    }.take(24)
                }
                displayedModels.forEachIndexed { index, m ->
                    if (index > 0) {
                        HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.28f))
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ProviderIcon(m.cli, selected = false, size = 26.dp)
                        Column(Modifier.weight(1f)) {
                            Text(
                                m.model,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val uiSuffix = if (m.uiLabels.isNotEmpty()) {
                                " · ${m.uiLabels.first()}"
                            } else {
                                ""
                            }
                            Text(
                                tr("${cliDisplayLabel(m.cli)}$uiSuffix · 输入 ${formatCompactTokens(m.inputTokens)} · 输出 ${formatCompactTokens(m.outputTokens)}", "${cliDisplayLabel(m.cli)}$uiSuffix · In ${formatCompactTokens(m.inputTokens)} · Out ${formatCompactTokens(m.outputTokens)}"),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            val costTag = when (m.costSource) {
                                "unpriced" -> tr("未定价", "Unpriced")
                                "modelPriced", "mixed" -> formatUsdCost(m.costUsd) + "*"
                                else -> formatUsdCost(m.costUsd)
                            }
                            Text(
                                if (chartByCost) costTag else formatCompactTokens(m.totalTokens),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                ),
                            )
                            Text(
                                if (chartByCost) "${formatCompactTokens(m.totalTokens)} tok" else costTag,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    PricingSourceFooter(pricingSource = summary.pricingSource)
}

internal fun formatPricingSyncTime(iso: String, zoneId: ZoneId = ZoneId.systemDefault()): String? {
    val trimmed = iso.trim()
    if (trimmed.isEmpty()) return null
    val instant = runCatching { Instant.parse(trimmed) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(trimmed).toInstant() }.getOrNull()
        ?: return null
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US).withZone(zoneId)
    return formatter.format(instant)
}

@Composable
private fun PricingSourceFooter(pricingSource: UsagePricingSource) {
    val colors = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current
    val syncTime = remember(pricingSource.updatedAt) { formatPricingSyncTime(pricingSource.updatedAt) }
    val isRemoteActive = pricingSource.mode == "online" || pricingSource.mode == "cached"

    Column(
        Modifier
            .fillMaxWidth()
            .testTag("usage-pricing-source-footer")
            .padding(top = 4.dp, start = 4.dp, end = 4.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val headerText = if (isRemoteActive) {
            val detailParts = buildList {
                if (syncTime != null) add(tr("更新于 $syncTime", "updated $syncTime"))
                if (pricingSource.modelCount > 0) add(tr("${pricingSource.modelCount} 个模型", "${pricingSource.modelCount} models"))
            }
            if (detailParts.isNotEmpty()) {
                tr(
                    "费率（${detailParts.joinToString(" · ")}）",
                    "Rates (${detailParts.joinToString(" · ")})",
                )
            } else {
                tr("费率来源", "Rate sources")
            }
        } else {
            tr(
                "离线费率 · 刷新以更新",
                "Offline rates · Refresh to update",
            )
        }
        Text(
            headerText,
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
        )

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            pricingSource.sources.forEach { src ->
                val label = "${src.name} ↗"
                Surface(
                    shape = CircleShape,
                    color = colors.surfaceContainerLow,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(role = Role.Button) {
                            val trimmedUrl = src.url.trim()
                            val lowerUrl = trimmedUrl.lowercase(Locale.US)
                            if (lowerUrl.startsWith("https://") || lowerUrl.startsWith("http://")) {
                                runCatching { uriHandler.openUri(trimmedUrl) }
                            }
                        },
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .background(
                                    if (src.active) Color(0xFF10B981) else colors.outline.copy(alpha = 0.6f),
                                    CircleShape,
                                ),
                        )
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        Text(
            tr(
                "按公开 API 费率估算",
                "Estimated at public API rates",
            ),
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant.copy(alpha = 0.78f),
        )
    }
}

@Composable
private fun UsageActivityHeatmapCard(activity: UsageActivitySummary) {
    val colors = MaterialTheme.colorScheme
    val days = activity.days
    if (days.isEmpty()) return

    var selectedDayIdx by remember(days.size) { mutableStateOf<Int?>(null) }
    val inspectedDay = selectedDayIdx?.let { days.getOrNull(it) }
    val cols = (days.size + 6) / 7
    val maxLog = remember(days) {
        val peak = days.maxOfOrNull { it.totalTokens }?.coerceAtLeast(1L) ?: 1L
        ln(peak.toDouble() + 1.0).coerceAtLeast(1.0)
    }

    Surface(
        shape = LumaCardShape(22.dp),
        color = colors.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    tr("活跃记录", "Activity"),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(0.85f),
                )
                Text(
                    tr("活跃 ${activity.activeDays} 天 · 连续 ${activity.currentStreak} 天 · 最长 ${activity.longestStreak} 天", "${activity.activeDays} active days · ${activity.currentStreak}d streak · best ${activity.longestStreak}d"),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1.15f),
                )
            }

            val baseEmpty = colors.surfaceContainerHighest.copy(alpha = 0.65f)
            val accent = colors.primary
            val highlightBorder = colors.onSurface

            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(86.dp)
                    .pointerInput(days.size, cols) {
                        detectTapGestures { pos ->
                            if (cols <= 0) return@detectTapGestures
                            val c = ((pos.x / size.width) * cols).toInt().coerceIn(0, cols - 1)
                            val r = ((pos.y / size.height) * 7).toInt().coerceIn(0, 6)
                            val idx = c * 7 + r
                            if (idx in days.indices) {
                                selectedDayIdx = if (selectedDayIdx == idx) null else idx
                            }
                        }
                    },
            ) {
                if (cols <= 0) return@Canvas
                val gap = 2.5.dp.toPx()
                val cellW = max(2f, (size.width - gap * (cols - 1)) / cols.toFloat())
                val cellH = max(2f, (size.height - gap * 6f) / 7f)
                val radius = CornerRadius(2.5.dp.toPx(), 2.5.dp.toPx())

                days.forEachIndexed { idx, d ->
                    val col = idx / 7
                    val row = idx % 7
                    val x = col * (cellW + gap)
                    val y = row * (cellH + gap)
                    val fillColor = if (d.totalTokens <= 0L) {
                        baseEmpty
                    } else {
                        val ratio = (ln(d.totalTokens.toDouble() + 1.0) / maxLog).toFloat().coerceIn(0.18f, 1f)
                        accent.copy(alpha = ratio)
                    }
                    drawRoundRect(
                        color = fillColor,
                        topLeft = Offset(x, y),
                        size = Size(cellW, cellH),
                        cornerRadius = radius,
                    )
                    if (selectedDayIdx == idx) {
                        drawRoundRect(
                            color = highlightBorder,
                            topLeft = Offset(x, y),
                            size = Size(cellW, cellH),
                            cornerRadius = radius,
                            style = Stroke(width = 1.3.dp.toPx()),
                        )
                    }
                }
            }

            val footerText = if (inspectedDay != null) {
                "${inspectedDay.date} · ${formatCompactTokens(inspectedDay.totalTokens)} · ${formatUsdCost(inspectedDay.costUsd, approximate = inspectedDay.costUsd > 0.0)}"
            } else if (activity.peakDayDate != null && activity.peakDayTokens > 0L) {
                tr("峰值 ${activity.peakDayDate} · ${formatCompactTokens(activity.peakDayTokens)}", "Peak ${activity.peakDayDate} · ${formatCompactTokens(activity.peakDayTokens)}")
            } else {
                tr("保留历史日度记录", "Daily history retained")
            }
            Text(
                footerText,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UsageStackedBarChart(
    buckets: List<UsagePeriodBucket>,
    providers: List<String>,
    costMode: Boolean,
    selectedIndex: Int? = null,
    onSelectBucket: (Int?) -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val maxBucketVal = remember(buckets, costMode) {
        buckets.maxOfOrNull { if (costMode) it.costUsd else it.totalTokens.toDouble() }?.takeIf { it > 0.0 } ?: 1.0
    }
    val providerColors = remember(providers) {
        providers.associateWith { providerUsageColor(it) }
    }
    val emptyBarColor = colors.surfaceContainerHighest.copy(alpha = 0.55f)
    val selectedSlotTint = colors.onSurface.copy(alpha = 0.07f)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(132.dp)
                .pointerInput(buckets.size, selectedIndex) {
                    detectTapGestures { offset ->
                        if (buckets.isEmpty() || size.width <= 0) return@detectTapGestures
                        val idx = ((offset.x / size.width) * buckets.size).toInt().coerceIn(0, buckets.lastIndex)
                        onSelectBucket(if (selectedIndex == idx) null else idx)
                    }
                },
        ) {
            val count = buckets.size
            if (count == 0) return@Canvas
            val totalW = size.width
            val totalH = size.height
            val slotW = totalW / count.toFloat()
            val gap = (slotW * 0.22f).coerceIn(1.5f, 6f)
            val barW = max(2f, slotW - gap)

            buckets.forEachIndexed { idx, bucket ->
                val x = idx * slotW + gap / 2f
                val isSelected = selectedIndex == idx
                val alphaFactor = if (selectedIndex == null || isSelected) 1f else 0.35f
                if (isSelected) {
                    drawRoundRect(
                        color = selectedSlotTint,
                        topLeft = Offset(idx * slotW, 0f),
                        size = Size(slotW, totalH),
                        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                    )
                }
                val bucketTotal = if (costMode) bucket.costUsd else bucket.totalTokens.toDouble()
                if (bucketTotal <= 0.0) {
                    val minH = 3.dp.toPx()
                    drawRoundRect(
                        color = emptyBarColor.copy(alpha = emptyBarColor.alpha * alphaFactor),
                        topLeft = Offset(x, totalH - minH),
                        size = Size(barW, minH),
                        cornerRadius = CornerRadius(2f, 2f),
                    )
                } else {
                    val barTotalH = ((bucketTotal / maxBucketVal).toFloat().coerceIn(0.03f, 1f)) * totalH
                    var currentBottom = totalH
                    providers.forEach { cli ->
                        val cliSlot = bucket.byCli[cli] ?: return@forEach
                        val cliVal = if (costMode) cliSlot.costUsd else cliSlot.totalTokens.toDouble()
                        if (cliVal <= 0.0) return@forEach
                        val segH = ((cliVal / bucketTotal).toFloat() * barTotalH).coerceAtLeast(1.5f)
                        val top = max(0f, currentBottom - segH)
                        val baseC = providerColors[cli] ?: Color(0xFF64748B)
                        drawRect(
                            color = baseC.copy(alpha = alphaFactor),
                            topLeft = Offset(x, top),
                            size = Size(barW, currentBottom - top),
                        )
                        currentBottom = top
                    }
                }
            }
        }

        if (buckets.size >= 2) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(buckets.first().label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                Text(buckets[buckets.size / 2].label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                Text(buckets.last().label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }

        if (providers.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                providers.take(5).forEach { cli ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(providerUsageColor(cli), CircleShape),
                        )
                        Text(
                            cliDisplayLabel(cli),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CostCategoryBreakdownCard(breakdown: CategoryCostBreakdown) {
    val colors = MaterialTheme.colorScheme
    val total = breakdown.total.takeIf { it > 0.0 } ?: return
    val items = listOf(
        Triple(tr("未缓存输入", "Uncached input"), breakdown.input, Color(0xFF3B82F6)),
        Triple(tr("缓存读取", "Cache read"), breakdown.cacheRead, Color(0xFF10B981)),
        Triple(tr("缓存写入", "Cache write"), breakdown.cacheWrite, Color(0xFFF59E0B)),
        Triple(tr("输出与思考", "Output & reasoning"), breakdown.output, Color(0xFF8B5CF6)),
    )

    Surface(
        shape = LumaCardShape(22.dp),
        color = colors.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                tr("费用构成", "Cost breakdown"),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceContainerHighest),
            ) {
                items.forEach { (_, value, color) ->
                    val frac = (value / total).toFloat()
                    if (frac > 0.005f) {
                        Box(
                            Modifier
                                .weight(frac)
                                .fillMaxHeight()
                                .background(color),
                        )
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items.forEach { (label, value, color) ->
                    if (value > 0.0) {
                        val pct = ((value / total) * 100.0).roundToInt()
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(Modifier.size(8.dp).background(color, CircleShape))
                                Text(label, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            }
                            Text(
                                "${formatUsdCost(value, approximate = true)} ($pct%)",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TotalsMetricGrid(totals: UsageTotals) {
    val totalInput = totals.uncachedInputTokens + totals.cachedInputTokens + totals.cacheCreationTokens
    val cacheHitPct = if (totalInput > 0L) ((totals.cachedInputTokens.toDouble() / totalInput.toDouble()) * 100.0).roundToInt() else 0
    val unpricedPct = if (totals.totalTokens > 0L) ((totals.unpricedTokens.toDouble() / totals.totalTokens.toDouble()) * 100.0).roundToInt() else 0

    val cells = listOf(
        Triple(tr("总 Token", "Total tokens"), formatCompactTokens(totals.totalTokens), tr("${totals.events} 次请求", "${totals.events} requests")),
        Triple(tr("缓存节省", "Cache savings"), formatUsdCost(totals.cacheSavingsUsd, approximate = totals.cacheSavingsUsd > 0.0), tr("相对未缓存输入费率", "vs. uncached input rate")),
        Triple(tr("缓存输入", "Cached input"), formatCompactTokens(totals.cachedInputTokens), tr("命中率 $cacheHitPct%", "$cacheHitPct% hit rate")),
        Triple(tr("未缓存输入", "Uncached input"), formatCompactTokens(totals.uncachedInputTokens), if (totals.cacheCreationTokens > 0) tr("含写入 ${formatCompactTokens(totals.cacheCreationTokens)}", "incl. ${formatCompactTokens(totals.cacheCreationTokens)} write") else tr("直接输入", "direct input")),
        Triple(tr("输出与思考", "Output & reasoning"), formatCompactTokens(totals.outputTokens + totals.reasoningTokens), if (totals.reasoningTokens > 0) tr("思考 ${formatCompactTokens(totals.reasoningTokens)}", "${formatCompactTokens(totals.reasoningTokens)} reasoning") else tr("模型输出", "model output")),
        Triple(tr("未定价占比", "Unpriced share"), "$unpricedPct%", "${formatCompactTokens(totals.unpricedTokens)} tok"),
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (rowIdx in 0 until 3) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (colIdx in 0 until 2) {
                    val (title, value, sub) = cells[rowIdx * 2 + colIdx]
                    MetricGridCell(
                        title = title,
                        value = value,
                        subtitle = sub,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricGridCell(
    title: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = LumaCardShape(18.dp),
        color = colors.surfaceContainerLow,
        modifier = modifier,
    ) {
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                value,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 17.sp,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
