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
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.modao7067.luma.gui.runtime.AgentRuntime
import io.github.modao7067.luma.gui.ui.shapes.addSquircleRect
import io.github.modao7067.luma.gui.ui.thirdparty.miuix.overScrollVertical
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

internal fun computeResetTimeFraction(
    resetsAtIso: String?,
    windowDurationMs: Long?,
    nowMs: Long = System.currentTimeMillis(),
): Float? {
    val totalMs = windowDurationMs?.takeIf { it > 0L } ?: return null
    val resetMs = parseIsoInstantMs(resetsAtIso) ?: return null
    val leftMs = resetMs - nowMs
    if (leftMs <= 0L || leftMs >= totalMs) return null
    return (leftMs.toDouble() / totalMs.toDouble()).toFloat()
}

internal fun formatCompactTokens(tokens: Long): String = when {
    tokens >= 1_000_000_000L -> String.format(Locale.US, "%.2fB", tokens / 1_000_000_000.0)
    tokens >= 1_000_000L -> String.format(Locale.US, "%.1fM", tokens / 1_000_000.0)
    tokens >= 1_000L -> String.format(Locale.US, "%.1fK", tokens / 1_000_000.0 * 1_000.0)
    else -> tokens.toString()
}

internal fun formatUsageLimitLabel(label: String): String {
    val raw = label.trim()
    if (raw.isEmpty()) return ""
    val session = tr("短期", "Session")
    val weekly = tr("每周", "Weekly")
    val monthly = tr("每月", "Monthly")
    val current = tr("当前", "Current")
    return raw
        .replace(Regex("(?i)(?:5-?hour|5\\s*小时|\\b5h)(?:\\s*(?:窗口|限额|订阅额度|订阅限额|配额)|\\s+(?:windows?|limits?|quotas?|subscription))?"), session)
        .replace(Regex("(?i)\\bSession\\b|短期|主窗口"), session)
        .replace(Regex("(?i)(?:\\b7d|每周)(?:\\s*(?:总窗口|窗口|订阅额度|订阅限额|限额|配额)|\\s+(?:overall|windows?|subscription|limits?|quotas?))"), weekly)
        .replace(Regex("(?i)\\b7d\\s+(Sonnet|Opus)\\b"), "$weekly · $1")
        .replace(Regex("(?i)\\b7d\\b"), weekly)
        .replace(Regex("(?i)\\bWeekly(?:\\s+(?:limit|quota|window|subscription))?\\b|每周|总窗口"), weekly)
        .replace(Regex("(?i)(?:月度|每月)(?:订阅额度|订阅限额|额度|限额|配额|窗口)?|\\bMonthly(?:\\s+(?:subscription|credits|limit|quota|window))?\\b"), monthly)
        .replace(Regex("(?i)\\bCurrent\\b|当前"), current)
        .replace(Regex("订阅额度|订阅限额|限额|窗口|配额"), "")
        .replace(Regex("(?i)\\b(?:subscription|windows?|limits?|quotas?)\\b"), "")
        .replace(Regex("\\s*·\\s*·\\s*"), " · ")
        .replace(Regex("^[\\s·]+|[\\s·]+$"), "")
        .replace(Regex("\\s{2,}"), " ")
        .trim()
        .ifEmpty { current }
}

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

internal fun providerUsageColor(cli: String, darkTheme: Boolean = false): Color = when (cli) {
    "Codex" -> if (darkTheme) Color(0xFFD0D5DD) else Color(0xFF475569)
    "Claude Code" -> Color(0xFFD47B5A)
    "Antigravity" -> Color(0xFF8B7EC8)
    "OpenCode" -> Color(0xFF5698B4)
    "pi" -> Color(0xFF529E88)
    "DeepSeek Harness" -> Color(0xFF647CC4)
    "Kimi Code" -> Color(0xFFC47A96)
    "Grok Build" -> if (darkTheme) Color(0xFF9CA3AF) else Color(0xFF6B7280)
    "Qoder CLI" -> Color(0xFF5A9A96)
    "CodeBuddy Code" -> Color(0xFF7B80BD)
    else -> Color(0xFF788292)
}

private fun isDarkScheme(colors: ColorScheme): Boolean = colors.background.luminance() < 0.5f

private fun usageAccentTone(colors: ColorScheme, fraction: Float): Color =
    lerp(colors.surfaceContainerHighest, colors.primary, fraction.coerceIn(0f, 1f))

private fun usageTrackColor(colors: ColorScheme): Color =
    colors.surfaceContainerHighest

private fun usageHairlineColor(colors: ColorScheme): Color {
    val isDark = isDarkScheme(colors)
    return colors.outlineVariant.copy(alpha = if (isDark) 0.24f else 0.30f)
}

private fun resolvedProviderColor(cli: String, colors: ColorScheme): Color = when (cli) {
    "Codex" -> colors.primary
    "Grok Build" -> usageAccentTone(colors, 0.54f)
    else -> providerUsageColor(cli, darkTheme = isDarkScheme(colors))
}

private fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = TABULAR_NUMS_FEATURE)

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

@OptIn(ExperimentalMaterial3Api::class)
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
    var manualRefreshing by remember(environment) { mutableStateOf(false) }
    var limitsFetchedInSession by remember(environment, langCode, suppliedLimits) {
        mutableStateOf(suppliedLimits != null)
    }
    var summaryRequestSeq by remember { mutableIntStateOf(0) }
    var limitsRequestSeq by remember { mutableIntStateOf(0) }
    var summaryJob by remember { mutableStateOf<Job?>(null) }
    var limitsJob by remember { mutableStateOf<Job?>(null) }
    var errorState by remember { mutableStateOf<UsageErrorState?>(null) }

    DisposableEffect(environment) {
        onDispose {
            summaryJob?.cancel()
            summaryJob = null
            limitsJob?.cancel()
            limitsJob = null
            manualRefreshing = false
            loadingSummary = false
            loadingLimits = false
        }
    }

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

        summaryJob?.cancel()
        summaryJob = scope.launch {
            try {
                val raw = rt.readGlobalUsageSummary(
                    environment = requestEnv,
                    timeZone = tz,
                    refresh = forceRefresh,
                    period = periodArg,
                )
                val parsed = parseGlobalUsageSummary(raw)
                writeCachedSummarySnapshot(prefs, requestEnv, periodArg, raw, parsed)
                if (summaryRequestSeq == reqId && environment == requestEnv && parsed.hasRolling30dCost) {
                    lastKnownRolling30dCost = parsed.rolling30dCostByCli
                }
                if (summaryRequestSeq == reqId && environment == requestEnv && currentSelectedPeriodId() == periodArg) {
                    summary = parsed
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (err: Exception) {
                if (summaryRequestSeq == reqId && environment == requestEnv && currentSelectedPeriodId() == periodArg) {
                    val activeStatsSummary = resolveStatsSummary(summary, suppliedSummary, periodArg)
                    if (forceRefresh || activeStatsSummary == null) {
                        errorState = err.message?.takeIf { it.isNotBlank() }?.let(UsageErrorState::Message)
                            ?: UsageErrorState.SummaryLoadFailed
                    }
                }
            } finally {
                if (summaryRequestSeq == reqId && environment == requestEnv) {
                    loadingSummary = false
                    if (!loadingLimits) {
                        manualRefreshing = false
                    }
                }
            }
        }

        if (needLimits) {
            limitsJob?.cancel()
            limitsJob = scope.launch {
                try {
                    val raw = rt.readGlobalUsageLimits(requestEnv, fresh = forceRefresh)
                    val parsed = parseGlobalUsageLimits(raw)
                    writeCachedLimitsSnapshot(prefs, requestEnv, raw, parsed, requestLang)
                    if (limitsRequestSeq == limitsReqId && environment == requestEnv && LumaI18n.langCode() == requestLang) {
                        limits = parsed
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (err: Exception) {
                    if (limitsRequestSeq == limitsReqId && environment == requestEnv && LumaI18n.langCode() == requestLang) {
                        if (forceRefresh || (metricView == UsageMetricView.LIMITS && limits == null)) {
                            if (errorState == null) {
                                errorState = err.message?.takeIf { it.isNotBlank() }?.let(UsageErrorState::Message)
                                    ?: UsageErrorState.LimitsLoadFailed
                            }
                        }
                    }
                } finally {
                    if (limitsRequestSeq == limitsReqId && environment == requestEnv && LumaI18n.langCode() == requestLang) {
                        loadingLimits = false
                        if (!loadingSummary) {
                            manualRefreshing = false
                        }
                    }
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
    val isDark = isDarkScheme(colors)
    val pullToRefreshState = rememberPullToRefreshState()
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = colors.background,
    ) {
        LiquidScaffold(
            wrapTop = false,
            wrapBottom = false,
            overlay = { overlayPadding ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(top = (overlayPadding.calculateTopPadding() - 28.dp).coerceAtLeast(0.dp)),
                ) {
                    PullToRefreshDefaults.Indicator(
                        state = pullToRefreshState,
                        isRefreshing = manualRefreshing,
                        containerColor = if (isDark) colors.surfaceContainerHigh else colors.surfaceBright,
                        color = colors.onSurface,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
            },
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
            val statsScroll = rememberScrollState()
            val limitsScroll = rememberScrollState()
            val layoutDirection = LocalLayoutDirection.current
            val tabBarHeight = 38.dp
            val topBasePadding = padding.calculateTopPadding() + 6.dp
            val statsTopPadding = topBasePadding + tabBarHeight + 10.dp
            val limitsTopPadding = topBasePadding + tabBarHeight + 16.dp
            val bottomContentPadding = padding.calculateBottomPadding() + 28.dp

            val interpolatedScrollPx by remember(pagerState, statsScroll, limitsScroll) {
                derivedStateOf {
                    val progress = (pagerState.currentPage + pagerState.currentPageOffsetFraction)
                        .coerceIn(0f, 1f)
                    (statsScroll.value * (1f - progress) + limitsScroll.value * progress).roundToInt()
                }
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .pullToRefresh(
                        isRefreshing = manualRefreshing,
                        state = pullToRefreshState,
                        onRefresh = {
                            if (!manualRefreshing) refreshData(forceRefresh = true)
                        },
                    )
                    .overScrollVertical(),
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.Top,
                ) { page ->
                    val pageView = UsageMetricView.entries.getOrElse(page) { UsageMetricView.STATS }
                    if (pageView == UsageMetricView.LIMITS) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(limitsScroll)
                                .padding(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = limitsTopPadding,
                                    bottom = bottomContentPadding,
                                ),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            UsageErrorBanner(errorState) { errorState = null }
                            if (!manualRefreshing && limits == null && loadingLimits) {
                                UsageLoadingPlaceholder(tr("正在读取限额…", "Loading quotas…"))
                            } else {
                                Column(
                                    Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(20.dp),
                                ) {
                                    UsageLimitsContent(
                                        limits = limits,
                                        rolling30dCostByCli = lastKnownRolling30dCost,
                                        loading = loadingLimits,
                                    )
                                }
                            }
                        }
                    } else {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(statsScroll)
                                .padding(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = statsTopPadding,
                                    bottom = bottomContentPadding,
                                ),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
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
                                modifier = Modifier.fillMaxWidth(),
                            )
                            UsageErrorBanner(errorState) { errorState = null }
                            if (!manualRefreshing && statsSummary == null && loadingSummary) {
                                UsageLoadingPlaceholder(tr("正在读取用量…", "Loading usage…"))
                            } else {
                                Column(
                                    Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(20.dp),
                                ) {
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

                val activeScrollState = if (pagerState.currentPage == 0) statsScroll else limitsScroll
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .offset { IntOffset(0, -interpolatedScrollPx) }
                        .padding(start = 16.dp, end = 16.dp, top = topBasePadding),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .scrollable(
                                state = activeScrollState,
                                orientation = Orientation.Vertical,
                                reverseDirection = ScrollableDefaults.reverseDirection(
                                    layoutDirection,
                                    Orientation.Vertical,
                                    reverseScrolling = false,
                                ),
                            ),
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
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageErrorBanner(
    errorState: UsageErrorState?,
    onDismiss: () -> Unit,
) {
    errorState?.let { err ->
        val msg = when (err) {
            is UsageErrorState.Message -> err.text
            UsageErrorState.SummaryLoadFailed -> tr("用量读取失败", "Failed to load usage")
            UsageErrorState.LimitsLoadFailed -> tr("限额读取失败", "Failed to load quotas")
        }
        ErrorNotice(msg, onDismiss)
    }
}

@Composable
private fun UsageLoadingPlaceholder(label: String) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = colors.primary,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun UsageSection(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.2.sp,
                ),
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            trailing?.invoke()
        }
        Surface(
            shape = LumaCardShape(22.dp),
            color = colors.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.fillMaxWidth(),
                content = content,
            )
        }
    }
}

@Composable
private fun UsageHairlineDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        thickness = 0.5.dp,
        color = usageHairlineColor(MaterialTheme.colorScheme),
    )
}

@Composable
private fun UsageMetricTabBar(
    pagerState: PagerState,
    metricView: UsageMetricView,
    onSelectMetric: (UsageMetricView) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val tabShape = LumaCardShape(11.dp)
    val indicatorColor = colors.secondaryContainer

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
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
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
    val itemShape = LumaCardShape(9.dp)

    Surface(
        shape = LumaCardShape(12.dp),
        color = colors.surfaceContainerLow,
        modifier = modifier,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UsagePeriodOption.entries.forEach { option ->
                val active = selectedPeriod == option
                Box(
                    Modifier
                        .weight(1f)
                        .height(28.dp)
                        .clip(itemShape)
                        .background(if (active) colors.secondaryContainer else Color.Transparent)
                        .clickable(role = Role.Tab) { onSelectPeriod(option) }
                        .semantics { selected = active }
                        .testTag("usage-window-${option.periodId}"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        option.label,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        ),
                        color = if (active) colors.onSecondaryContainer else colors.onSurfaceVariant,
                    )
                }
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
            shape = LumaCardShape(22.dp),
            color = colors.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    tr("暂无限额数据", "No quota data"),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                )
                Text(
                    tr("登录订阅账号后刷新", "Sign in to a subscription, then refresh"),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        return
    }

    val inspection = LocalInspectionMode.current
    val nowMs by produceState(initialValue = System.currentTimeMillis(), limits?.generatedAt, inspection) {
        value = System.currentTimeMillis()
        if (!inspection) {
            while (true) {
                delay(30_000L)
                value = System.currentTimeMillis()
            }
        }
    }
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
    val accent = resolvedProviderColor(provider.cli, colors)

    Surface(
        shape = LumaCardShape(22.dp),
        color = colors.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    ProviderIcon(provider.cli, selected = hasWindows, size = 24.dp)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f, fill = false),
                    ) {
                        Text(
                            cliDisplayLabel(provider.cli),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!provider.planType.isNullOrBlank()) {
                            Text(
                                "· ${provider.planType}",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                val statusHint = when {
                    provider.source == "session_fallback" -> tr("历史", "History") to colors.onSurfaceVariant
                    provider.status == "error" -> tr("读取失败", "Unavailable") to colors.error
                    !hasWindows -> tr("未配置", "Not configured") to colors.onSurfaceVariant
                    else -> null
                }
                if (statusHint != null) {
                    Text(
                        statusHint.first,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusHint.second,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            if (provider.windows.isNotEmpty()) {
                UsageHairlineDivider(Modifier.padding(horizontal = 16.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    provider.windows.forEach { window ->
                        LimitWindowRow(
                            window = window,
                            accentColor = accent,
                            nowMs = nowMs,
                        )
                    }
                }
            }

            if (hasWindows) {
                UsageHairlineDivider(Modifier.padding(horizontal = 16.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    provider.credits?.let { cred ->
                        val credLabel = when (cred.label.trim()) {
                            "", "额度", "Quota" -> tr("额度", "Quota")
                            else -> cred.label
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                credLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                            Text(
                                formatUsageCreditsValue(cred),
                                style = (if (provider.windows.isEmpty()) {
                                    MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                                } else {
                                    MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                                }).tabular(),
                                color = colors.onSurface,
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
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
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold).tabular(),
                            color = colors.onSurface.copy(alpha = 0.85f),
                        )
                    }
                }
            }

            if (!hasWindows && !provider.message.isNullOrBlank()) {
                UsageHairlineDivider(Modifier.padding(horizontal = 16.dp))
                Text(
                    provider.message,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
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
    val isDark = isDarkScheme(colors)
    val langCode = LumaI18n.langCode()
    val remaining = (window.remainingPercent.takeIf { it.isFinite() } ?: 100.0).coerceIn(0.0, 100.0)
    val resetTimeFraction = remember(window.resetsAt, window.windowDurationMs, nowMs) {
        computeResetTimeFraction(window.resetsAt, window.windowDurationMs, nowMs)
    }
    val countdown = remember(window.resetsAt, nowMs, langCode) { formatResetCountdown(window.resetsAt, nowMs) }
    val displayLabel = remember(window.label, langCode) { formatUsageLimitLabel(window.label) }

    val lowAlertColor = if (isDark) Color(0xFFE07476) else Color(0xFFC95154)
    val warnColor = if (isDark) Color(0xFFD9A354) else Color(0xFFC78B36)
    val barFillColor = when {
        remaining <= 10.0 -> lowAlertColor.copy(alpha = if (isDark) 0.82f else 0.78f)
        remaining <= 25.0 -> warnColor.copy(alpha = if (isDark) 0.74f else 0.68f)
        else -> accentColor.copy(alpha = if (isDark) 0.72f else 0.62f)
    }
    val trackColor = usageTrackColor(colors)
    val surfaceColor = colors.surfaceContainerLow
    val tickColor = colors.onSurface.copy(alpha = 0.78f)
    val percentStyle = MaterialTheme.typography.titleLarge.copy(
        fontSize = 22.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.4).sp,
        fontWeight = FontWeight.Bold,
    ).tabular()

    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    displayLabel,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (countdown != null) {
                    Text(
                        countdown,
                        style = MaterialTheme.typography.labelSmall.tabular(),
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                "${remaining.roundToInt()}%",
                style = percentStyle,
                color = if (remaining <= 10.0) lowAlertColor else colors.onSurface,
            )
        }

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(12.dp),
        ) {
            val w = size.width
            val h = size.height
            val clip = Path().apply {
                addSquircleRect(w, h, 5.dp.toPx())
            }

            clipPath(clip) {
                drawRect(color = trackColor, size = size)
                val rawFill = (w * (remaining.toFloat() / 100f)).coerceIn(0f, w)
                val fillWidth = if (remaining > 0.0) max(rawFill, 4.dp.toPx()).coerceAtMost(w) else 0f
                if (fillWidth > 0.5f) {
                    drawRect(color = barFillColor, size = Size(fillWidth, h))
                }
            }

            if (resetTimeFraction != null && resetTimeFraction in 0.02f..0.98f) {
                val inset = 4.dp.toPx()
                val markerX = (w * resetTimeFraction).coerceIn(inset, max(inset, w - inset))
                val gapHalf = 2.dp.toPx()
                val markerW = 1.6.dp.toPx()
                val markerVPad = 1.5.dp.toPx()
                drawRect(
                    color = surfaceColor,
                    topLeft = Offset(max(0f, markerX - gapHalf), 0f),
                    size = Size(min(w, gapHalf * 2f), h),
                )
                drawRoundRect(
                    color = tickColor,
                    topLeft = Offset(max(0f, markerX - markerW / 2f), markerVPad),
                    size = Size(markerW, (h - markerVPad * 2f).coerceAtLeast(2f)),
                    cornerRadius = CornerRadius(markerW / 2f, markerW / 2f),
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
                shape = LumaCardShape(22.dp),
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
    var selectedBucketIdx by remember(summary.period, summary.buckets.size) { mutableStateOf<Int?>(null) }
    val activeBucket = selectedBucketIdx?.let { summary.buckets.getOrNull(it) }
    var expandedClis by remember { mutableStateOf(setOf<String>()) }

    Surface(
        shape = LumaCardShape(22.dp),
        color = colors.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (activeBucket != null) tr("${activeBucket.date} 明细", "${activeBucket.date} details")
                    else tr("${selectedPeriod.label}总览", "${selectedPeriod.label} overview"),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (activeBucket != null) colors.onSurface else colors.onSurfaceVariant,
                )
                if (activeBucket != null) {
                    Text(
                        tr("返回总览", "Overview"),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                        color = colors.primary,
                        modifier = Modifier
                            .clip(LumaCardShape(6.dp))
                            .clickable(role = Role.Button) { selectedBucketIdx = null }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
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
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontSize = 34.sp,
                            lineHeight = 38.sp,
                            letterSpacing = (-0.8).sp,
                            fontWeight = FontWeight.Bold,
                        ).tabular(),
                        color = colors.onSurface,
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
                            fontSize = 20.sp,
                            lineHeight = 26.sp,
                            letterSpacing = (-0.3).sp,
                            fontWeight = FontWeight.SemiBold,
                        ).tabular(),
                        color = colors.onSurface.copy(alpha = 0.78f),
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
                    .sortedByDescending { it.value.totalTokens }
                if (cliItems.isEmpty()) {
                    Text(
                        tr("暂无记录", "No activity"),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                } else {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        cliItems.forEach { (cli, v) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Box(
                                    Modifier
                                        .size(6.dp)
                                        .background(resolvedProviderColor(cli, colors), CircleShape),
                                )
                                Text(
                                    "${cliDisplayLabel(cli)} ${formatCompactTokens(v.totalTokens)} (${formatUsdCost(v.costUsd, approximate = true)})",
                                    style = MaterialTheme.typography.labelSmall.tabular(),
                                    color = colors.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
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
                    style = MaterialTheme.typography.bodySmall.tabular(),
                    color = colors.onSurfaceVariant,
                )
            }

            if (summary.buckets.isNotEmpty()) {
                UsageStackedBarChart(
                    buckets = summary.buckets,
                    providers = summary.byProvider.map { it.cli },
                    selectedIndex = selectedBucketIdx,
                    onSelectBucket = { idx -> selectedBucketIdx = idx },
                )
            }
        }
    }

    if (summary.byProvider.isNotEmpty()) {
        UsageSection(title = tr("CLI 用量", "By CLI")) {
            val totalProviderTokens = summary.byProvider.sumOf { it.totalTokens }.toDouble().takeIf { it > 0.0 } ?: 1.0
            val trackColor = usageTrackColor(colors)
            summary.byProvider.forEachIndexed { idx, p ->
                if (idx > 0) {
                    UsageHairlineDivider(Modifier.padding(start = 16.dp))
                }
                val share = (p.totalTokens.toDouble() / totalProviderTokens).coerceIn(0.0, 1.0)
                val pColor = resolvedProviderColor(p.cli, colors)
                val isExpanded = p.cli in expandedClis
                val cliModels = remember(summary.byModel, p.cli) {
                    summary.byModel
                        .filter { it.cli == p.cli }
                        .sortedWith(compareByDescending<ModelUsageTotal> { it.totalTokens }.thenByDescending { it.costUsd })
                }

                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            expandedClis = if (isExpanded) expandedClis - p.cli else expandedClis + p.cli
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .background(pColor, CircleShape),
                            )
                            Text(
                                cliDisplayLabel(p.cli),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                ),
                                color = colors.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${(share * 100.0).roundToInt()}%",
                                style = MaterialTheme.typography.labelSmall.tabular(),
                                color = colors.onSurfaceVariant,
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                formatCompactTokens(p.totalTokens),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                ).tabular(),
                                color = colors.onSurface,
                            )
                            Text(
                                if (isExpanded) "▴" else "▾",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant.copy(alpha = 0.65f),
                            )
                        }
                    }

                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(trackColor),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(share.toFloat().coerceIn(0.015f, 1f))
                                .fillMaxHeight()
                                .clip(CircleShape)
                                .background(pColor),
                        )
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            tr("${p.events} 次 · 缓存命中 ${p.cacheHitPercent}%", "${p.events} req · Cache ${p.cacheHitPercent}%"),
                            style = MaterialTheme.typography.labelSmall.tabular(),
                            color = colors.onSurfaceVariant,
                        )
                        Text(
                            formatUsdCost(p.costUsd, approximate = p.modelPricedCostUsd > 0.0),
                            style = MaterialTheme.typography.labelSmall.tabular(),
                            color = colors.onSurfaceVariant,
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
                                .padding(top = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            UsageHairlineDivider()
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(IntrinsicSize.Min),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Box(
                                    Modifier
                                        .width(1.5.dp)
                                        .fillMaxHeight()
                                        .clip(CircleShape)
                                        .background(pColor.copy(alpha = 0.45f)),
                                )
                                Column(
                                    Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
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
                                        style = MaterialTheme.typography.labelSmall.tabular(),
                                        color = colors.onSurfaceVariant,
                                    )

                                    cliModels.forEachIndexed { mIdx, m ->
                                        if (mIdx > 0) {
                                            UsageHairlineDivider()
                                        }
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            Column(
                                                Modifier.weight(1f),
                                                verticalArrangement = Arrangement.spacedBy(2.dp),
                                            ) {
                                                Text(
                                                    m.model,
                                                    style = MaterialTheme.typography.bodySmall.copy(
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.Medium,
                                                    ),
                                                    color = colors.onSurface,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                val uiHint = if (m.uiLabels.isNotEmpty()) {
                                                    "${m.uiLabels.joinToString(" / ")} · "
                                                } else {
                                                    ""
                                                }
                                                Text(
                                                    tr(
                                                        "${uiHint}输入 ${formatCompactTokens(m.inputTokens)} · 输出 ${formatCompactTokens(m.outputTokens)}",
                                                        "${uiHint}In ${formatCompactTokens(m.inputTokens)} · Out ${formatCompactTokens(m.outputTokens)}",
                                                    ),
                                                    style = MaterialTheme.typography.labelSmall.tabular(),
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
                                            Column(
                                                horizontalAlignment = Alignment.End,
                                                verticalArrangement = Arrangement.spacedBy(2.dp),
                                            ) {
                                                Text(
                                                    formatCompactTokens(m.totalTokens),
                                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold).tabular(),
                                                    color = colors.onSurface,
                                                )
                                                Text(
                                                    mCostTag,
                                                    style = MaterialTheme.typography.labelSmall.tabular(),
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
    }

    TotalsMetricGrid(summary = summary)

    if (totals.categoryCostUsd.total > 0.0) {
        CostCategoryBreakdownCard(totals.categoryCostUsd)
    }

    if (summary.activity.days.isNotEmpty()) {
        UsageActivityHeatmapCard(activity = summary.activity)
    }

    if (summary.byModel.isNotEmpty()) {
        UsageSection(
            title = tr("模型排行", "Top models"),
            trailing = {
                Text(
                    tr("* API 费率估算", "* API estimate"),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant.copy(alpha = 0.8f),
                )
            },
        ) {
            val displayedModels = remember(summary.byModel) {
                summary.byModel
                    .sortedWith(compareByDescending<ModelUsageTotal> { it.totalTokens }.thenByDescending { it.costUsd })
                    .take(24)
            }
            displayedModels.forEachIndexed { index, m ->
                if (index > 0) {
                    UsageHairlineDivider(Modifier.padding(start = 16.dp))
                }
                val mColor = resolvedProviderColor(m.cli, colors)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(mColor, CircleShape),
                    )
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            m.model,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val uiSuffix = if (m.uiLabels.isNotEmpty()) " · ${m.uiLabels.first()}" else ""
                        Text(
                            tr(
                                "${cliDisplayLabel(m.cli)}$uiSuffix · 输入 ${formatCompactTokens(m.inputTokens)} · 输出 ${formatCompactTokens(m.outputTokens)}",
                                "${cliDisplayLabel(m.cli)}$uiSuffix · In ${formatCompactTokens(m.inputTokens)} · Out ${formatCompactTokens(m.outputTokens)}",
                            ),
                            style = MaterialTheme.typography.labelSmall.tabular(),
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        val costTag = when (m.costSource) {
                            "unpriced" -> tr("未定价", "Unpriced")
                            "modelPriced", "mixed" -> formatUsdCost(m.costUsd) + "*"
                            else -> formatUsdCost(m.costUsd)
                        }
                        Text(
                            formatCompactTokens(m.totalTokens),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold).tabular(),
                            color = colors.onSurface,
                        )
                        Text(
                            costTag,
                            style = MaterialTheme.typography.labelSmall.tabular(),
                            color = colors.onSurfaceVariant,
                        )
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
            .padding(top = 2.dp, start = 4.dp, end = 4.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
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
            style = MaterialTheme.typography.labelSmall.tabular(),
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
                                    if (src.active) colors.primary else colors.outline.copy(alpha = 0.55f),
                                    CircleShape,
                                ),
                        )
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
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
            color = colors.onSurfaceVariant.copy(alpha = 0.72f),
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

    val emptyCell = colors.surfaceContainerHighest.copy(alpha = 0.72f)
    val highlightBorder = colors.primary

    UsageSection(title = tr("活跃记录", "Activity")) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(88.dp)
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
                        emptyCell
                    } else {
                        val ratio = (ln(d.totalTokens.toDouble() + 1.0) / maxLog).toFloat().coerceIn(0.24f, 1f)
                        usageAccentTone(colors, ratio)
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
                            style = Stroke(width = 1.4.dp.toPx()),
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
                style = MaterialTheme.typography.labelSmall.tabular(),
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UsageStackedBarChart(
    buckets: List<UsagePeriodBucket>,
    providers: List<String>,
    selectedIndex: Int? = null,
    onSelectBucket: (Int?) -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val maxBucketVal = remember(buckets) {
        buckets.maxOfOrNull { it.totalTokens.toDouble() }?.takeIf { it > 0.0 } ?: 1.0
    }
    val providerColors = providers.associateWith { resolvedProviderColor(it, colors) }
    val baselineColor = usageHairlineColor(colors)
    val selectedSlotTint = colors.primary.copy(alpha = 0.09f)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(172.dp)
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
            val gap = (slotW * 0.18f).coerceIn(1.5f, 4.dp.toPx())
            val barW = max(2f, slotW - gap)
            val topRadius = CornerRadius(3.dp.toPx().coerceAtMost(barW / 2f), 3.dp.toPx().coerceAtMost(barW / 2f))

            buckets.forEachIndexed { idx, bucket ->
                val x = idx * slotW + gap / 2f
                val isSelected = selectedIndex == idx
                val alphaFactor = if (selectedIndex == null || isSelected) 1f else 0.30f
                if (isSelected) {
                    drawRoundRect(
                        color = selectedSlotTint,
                        topLeft = Offset(idx * slotW, 0f),
                        size = Size(slotW, totalH),
                        cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx()),
                    )
                }
                val bucketTotal = bucket.totalTokens.toDouble()
                if (bucketTotal > 0.0) {
                    val barTotalH = ((bucketTotal / maxBucketVal).toFloat().coerceIn(0.03f, 1f)) * (totalH - 2.dp.toPx())
                    val barTop = (totalH - barTotalH).coerceAtLeast(0f)
                    val barPath = Path().apply {
                        addRoundRect(
                            RoundRect(
                                rect = Rect(x, barTop, x + barW, totalH),
                                topLeft = topRadius,
                                topRight = topRadius,
                                bottomLeft = CornerRadius.Zero,
                                bottomRight = CornerRadius.Zero,
                            ),
                        )
                    }
                    clipPath(barPath) {
                        var currentBottom = totalH
                        providers.forEach { cli ->
                            val cliSlot = bucket.byCli[cli] ?: return@forEach
                            val cliVal = cliSlot.totalTokens.toDouble()
                            if (cliVal <= 0.0) return@forEach
                            val segH = ((cliVal / bucketTotal).toFloat() * barTotalH).coerceAtLeast(1.5f)
                            val top = max(barTop, currentBottom - segH)
                            val baseC = providerColors[cli] ?: usageAccentTone(colors, 0.5f)
                            drawRect(
                                color = baseC.copy(alpha = alphaFactor),
                                topLeft = Offset(x, top),
                                size = Size(barW, (currentBottom - top).coerceAtLeast(0f)),
                            )
                            currentBottom = top
                        }
                    }
                }
            }

            drawLine(
                color = baselineColor,
                start = Offset(0f, totalH - 0.5.dp.toPx()),
                end = Offset(totalW, totalH - 0.5.dp.toPx()),
                strokeWidth = 0.5.dp.toPx(),
            )
        }

        if (buckets.size >= 2) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    buckets.first().label,
                    style = MaterialTheme.typography.labelSmall.tabular(),
                    color = colors.onSurfaceVariant.copy(alpha = 0.78f),
                )
                Text(
                    buckets[buckets.size / 2].label,
                    style = MaterialTheme.typography.labelSmall.tabular(),
                    color = colors.onSurfaceVariant.copy(alpha = 0.78f),
                )
                Text(
                    buckets.last().label,
                    style = MaterialTheme.typography.labelSmall.tabular(),
                    color = colors.onSurfaceVariant.copy(alpha = 0.78f),
                )
            }
        }

        if (providers.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                providers.forEach { cli ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .background(providerColors[cli] ?: usageAccentTone(colors, 0.5f), CircleShape),
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
        Triple(tr("输出与思考", "Output & reasoning"), breakdown.output, usageAccentTone(colors, 0.94f)),
        Triple(tr("缓存写入", "Cache write"), breakdown.cacheWrite, usageAccentTone(colors, 0.68f)),
        Triple(tr("未缓存输入", "Uncached input"), breakdown.input, usageAccentTone(colors, 0.44f)),
        Triple(tr("缓存读取", "Cache read"), breakdown.cacheRead, usageAccentTone(colors, 0.22f)),
    )
    val visible = items.filter { it.second > 0.0 }
    if (visible.isEmpty()) return

    UsageSection(title = tr("费用构成", "Cost breakdown")) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                visible.forEach { (_, value, color) ->
                    val frac = (value / total).toFloat().coerceAtLeast(0.015f)
                    Box(
                        Modifier
                            .weight(frac)
                            .fillMaxHeight()
                            .clip(LumaCardShape(3.dp))
                            .background(color),
                    )
                }
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                visible.forEach { (label, value, color) ->
                    val pct = ((value / total) * 100.0).roundToInt()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(LumaCardShape(2.5.dp))
                                .background(color),
                        )
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                        )
                        Text(
                            "${formatUsdCost(value, approximate = true)} ($pct%)",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold).tabular(),
                            color = colors.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TotalsMetricGrid(summary: GlobalUsageSummary) {
    val colors = MaterialTheme.colorScheme
    val totals = summary.totals
    val totalInput = totals.uncachedInputTokens + totals.cachedInputTokens + totals.cacheCreationTokens
    val cacheHitPct = if (totalInput > 0L) ((totals.cachedInputTokens.toDouble() / totalInput.toDouble()) * 100.0).roundToInt() else 0
    val unpricedPct = if (totals.totalTokens > 0L) ((totals.unpricedTokens.toDouble() / totals.totalTokens.toDouble()) * 100.0).roundToInt() else 0

    val activeBuckets = summary.buckets.count { it.totalTokens > 0L }
    val avgPerActive = if (activeBuckets > 0) totals.totalTokens / activeBuckets else 0L
    val avgTitle = when (summary.granularity) {
        "hour" -> tr("时均 Token", "Per active hour")
        "month" -> tr("月均 Token", "Per active month")
        else -> tr("日均 Token", "Per active day")
    }
    val avgSub = if (activeBuckets > 0) {
        when (summary.granularity) {
            "hour" -> tr("$activeBuckets 个活跃小时", "$activeBuckets active hours")
            "month" -> tr("$activeBuckets 个活跃月", "$activeBuckets active months")
            else -> tr("$activeBuckets 个活跃天", "$activeBuckets active days")
        }
    } else {
        tr("${totals.events} 次请求", "${totals.events} requests")
    }
    val cacheSavingsSub = if (totals.costUsd > 0.0 && totals.cacheSavingsUsd > 0.0) {
        tr(
            "约为费用 ${String.format(Locale.US, "%.1f", totals.cacheSavingsUsd / totals.costUsd)}x",
            "${String.format(Locale.US, "%.1f", totals.cacheSavingsUsd / totals.costUsd)}x raw cost",
        )
    } else {
        tr("相对未缓存输入费率", "vs. uncached input rate")
    }

    val cells = listOf(
        Triple(avgTitle, formatCompactTokens(avgPerActive), avgSub),
        Triple(tr("缓存节省", "Cache savings"), formatUsdCost(totals.cacheSavingsUsd, approximate = totals.cacheSavingsUsd > 0.0), cacheSavingsSub),
        Triple(tr("缓存输入", "Cached input"), formatCompactTokens(totals.cachedInputTokens), tr("命中率 $cacheHitPct%", "$cacheHitPct% hit rate")),
        Triple(tr("未缓存输入", "Uncached input"), formatCompactTokens(totals.uncachedInputTokens), if (totals.cacheCreationTokens > 0) tr("含写入 ${formatCompactTokens(totals.cacheCreationTokens)}", "incl. ${formatCompactTokens(totals.cacheCreationTokens)} write") else tr("直接输入", "Direct input")),
        Triple(tr("输出与思考", "Output & reasoning"), formatCompactTokens(totals.outputTokens + totals.reasoningTokens), if (totals.reasoningTokens > 0) tr("思考 ${formatCompactTokens(totals.reasoningTokens)}", "${formatCompactTokens(totals.reasoningTokens)} reasoning") else tr("模型输出", "Model output")),
        Triple(tr("未定价占比", "Unpriced share"), "$unpricedPct%", "${formatCompactTokens(totals.unpricedTokens)} tok"),
    )

    val hairline = usageHairlineColor(colors)
    UsageSection(title = tr("汇总指标", "Totals")) {
        for (rowIdx in 0 until 3) {
            if (rowIdx > 0) {
                UsageHairlineDivider()
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
            ) {
                val left = cells[rowIdx * 2]
                val right = cells[rowIdx * 2 + 1]
                MetricGridCell(
                    title = left.first,
                    value = left.second,
                    subtitle = left.third,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(0.5.dp)
                        .background(hairline),
                )
                MetricGridCell(
                    title = right.first,
                    value = right.second,
                    subtitle = right.third,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
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
    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 14.dp),
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
                fontSize = 19.sp,
                lineHeight = 24.sp,
                letterSpacing = (-0.3).sp,
                fontWeight = FontWeight.SemiBold,
            ).tabular(),
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.labelSmall.tabular(),
            color = colors.onSurfaceVariant.copy(alpha = 0.80f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
