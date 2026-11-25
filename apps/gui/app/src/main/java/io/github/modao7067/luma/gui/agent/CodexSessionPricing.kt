package io.github.modao7067.luma.gui.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.max

data class CodexTieredRate(
    val thresholdTokens: Long,
    val inputPerM: Double,
    val outputPerM: Double,
    val cacheReadPerM: Double,
    val cacheWritePerM: Double,
)

data class CodexModelRate(
    val inputPerM: Double,
    val outputPerM: Double,
    val cacheReadPerM: Double,
    val cacheWritePerM: Double,
    val tiered: CodexTieredRate? = null,
)

data class CodexTokenSnapshot(
    val inputTokens: Long = 0L,
    val cachedInputTokens: Long = 0L,
    val cacheWriteInputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val reasoningOutputTokens: Long = 0L,
)

data class CodexSessionUsageState(
    val last: JSONObject? = null,
    val total: JSONObject? = null,
    val modelContextWindow: Long? = null,
    val costUsd: Double = 0.0,
    val priced: Boolean = false,
    val hasBillableTokens: Boolean = false,
)

class CodexPricingCatalog(
    val aliases: Map<String, String>,
    val rates: Map<String, CodexModelRate>,
) {
    private val sortedKeys: List<String> = rates.keys.sortedByDescending { it.length }
    private val sortedAliasKeys: List<String> = aliases.keys.sortedByDescending { it.length }

    private fun scaleRate(rate: CodexModelRate, factor: Double): CodexModelRate = CodexModelRate(
        inputPerM = rate.inputPerM * factor,
        outputPerM = rate.outputPerM * factor,
        cacheReadPerM = rate.cacheReadPerM * factor,
        cacheWritePerM = rate.cacheWritePerM * factor,
        tiered = rate.tiered?.let {
            CodexTieredRate(
                thresholdTokens = it.thresholdTokens,
                inputPerM = it.inputPerM * factor,
                outputPerM = it.outputPerM * factor,
                cacheReadPerM = it.cacheReadPerM * factor,
                cacheWritePerM = it.cacheWritePerM * factor,
            )
        },
    )

    private fun resolveAliasRate(aliasKey: String): CodexModelRate? {
        val target = aliases[aliasKey] ?: return null
        if (target.endsWith(":fast")) {
            return rates[target.dropLast(5)]?.let { scaleRate(it, 2.0) }
        }
        return rates[target]
    }

    fun lookupRate(modelId: String?): CodexModelRate? {
        var m = normalizeModelId(modelId)
        if (m.endsWith(":fast")) {
            m = m.dropLast(5)
        }
        if (m.isEmpty() || m in ignoredModels) return null

        fun resolve(candidate: String): CodexModelRate? {
            resolveAliasRate(candidate)?.let { return it }
            rates[candidate]?.let { return it }
            return null
        }

        fun resolveWithPreview(candidate: String): CodexModelRate? {
            resolve(candidate)?.let { return it }
            return rates["$candidate-preview"]
        }

        resolve(m)?.let { return it }
        var cleaned = m
        for (suffix in stripSuffixes) {
            if (cleaned.endsWith(suffix)) {
                cleaned = cleaned.dropLast(suffix.length)
                resolveWithPreview(cleaned)?.let { return it }
            }
        }
        resolveWithPreview(cleaned)?.let { return it }
        val altHyphen = cleaned.replace('.', '-')
        resolveWithPreview(altHyphen)?.let { return it }
        for (key in sortedKeys) {
            if (cleaned == key || cleaned.startsWith("$key-") || cleaned.startsWith("$key.")) {
                return rates[key]
            }
            if (altHyphen == key || altHyphen.startsWith("$key-")) {
                return rates[key]
            }
        }
        for (alias in sortedAliasKeys) {
            if (
                cleaned == alias ||
                cleaned.startsWith("$alias-") ||
                cleaned.startsWith("$alias.") ||
                altHyphen == alias ||
                altHyphen.startsWith("$alias-")
            ) {
                resolveAliasRate(alias)?.let { return it }
            }
        }
        return null
    }

    fun isExplicitFree(modelId: String?): Boolean {
        val normalized = normalizeModelId(modelId)
        return normalized.endsWith("-free") || normalized.endsWith(":free")
    }

    companion object {
        private val ignoredModels = setOf(
            "unknown",
            "<synthetic>",
            "synthetic",
            "gemini-default",
            "antigravity-unknown",
        )
        private val stripSuffixes = listOf(
            "-thinking",
            "-extra-low",
            "-xhigh",
            "-medium",
            "-high",
            "-low",
            "-max",
            "-preview",
            "-latest",
            "-trial",
            "-exp",
            "-canary",
            "-tiered",
        )
        private val claudeSwapRegex = Regex("""^claude-(\d+(?:\.\d+)?)-(sonnet|opus|haiku)(.*)$""")
        private val claudeDotRegex = Regex("""^(claude-(?:sonnet|opus|haiku)-\d+)\.(\d+)(.*)$""")
        private const val PRICING_ASSET_PATH = "cli_bridge/luma_cli/model_pricing.json"

        @Volatile
        private var cachedDefault: CodexPricingCatalog? = null

        fun normalizeModelId(raw: String?): String {
            if (raw == null) return "unknown"
            var m = raw.trim()
            if (m.isEmpty()) return "unknown"
            if ('/' in m) {
                m = m.substringAfterLast('/').trim()
            }
            val lowerBeforeColon = m.lowercase(Locale.ROOT)
            if (':' in m && !lowerBeforeColon.endsWith(":fast") && !lowerBeforeColon.endsWith(":free")) {
                m = m.substringAfterLast(':').trim()
            }
            if (m.isEmpty()) return "unknown"
            var low = m.lowercase(Locale.ROOT)
            if (low.startsWith("claude-")) {
                low = claudeSwapRegex.replace(low) { match ->
                    "claude-${match.groupValues[2]}-${match.groupValues[1]}${match.groupValues[3]}"
                }
                low = claudeDotRegex.replace(low) { match ->
                    "${match.groupValues[1]}-${match.groupValues[2]}${match.groupValues[3]}"
                }
            }
            return low
        }

        fun fromAssets(context: Context): CodexPricingCatalog {
            cachedDefault?.let { return it }
            val raw = runCatching {
                context.assets.open(PRICING_ASSET_PATH).bufferedReader().use { it.readText() }
            }.getOrNull() ?: return default()
            return fromJsonText(raw).also { cachedDefault = it }
        }

        fun default(): CodexPricingCatalog {
            cachedDefault?.let { return it }
            val raw = loadBuiltinPricingJsonFallback() ?: return CodexPricingCatalog(emptyMap(), emptyMap())
            return fromJsonText(raw).also { cachedDefault = it }
        }

        fun fromJsonText(raw: String): CodexPricingCatalog =
            runCatching { fromJson(JSONObject(raw)) }.getOrElse { CodexPricingCatalog(emptyMap(), emptyMap()) }

        fun fromJson(obj: JSONObject): CodexPricingCatalog {
            val aliasesObj = obj.optJSONObject("aliases")
            val aliases = buildMap {
                if (aliasesObj != null) {
                    val keys = aliasesObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = aliasesObj.optString(k, "").trim()
                        if (k.isNotBlank() && v.isNotEmpty()) put(k.lowercase(Locale.ROOT), v.lowercase(Locale.ROOT))
                    }
                }
            }
            val ratesObj = obj.optJSONObject("rates")
            val rates = buildMap {
                if (ratesObj != null) {
                    val keys = ratesObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val arr = ratesObj.optJSONArray(k) ?: continue
                        parseRateJsonArray(arr)?.let { put(k.lowercase(Locale.ROOT), it) }
                    }
                }
            }
            return CodexPricingCatalog(aliases, rates)
        }

        private fun parseRateJsonArray(arr: JSONArray): CodexModelRate? {
            if (arr.length() < 4) return null
            val inM = arr.optDouble(0)
            val outM = arr.optDouble(1)
            val readM = arr.optDouble(2)
            val writeM = arr.optDouble(3)
            if (inM.isNaN() || outM.isNaN() || readM.isNaN() || writeM.isNaN()) return null
            val tieredArr = if (arr.length() >= 5 && !arr.isNull(4)) arr.optJSONArray(4) else null
            val tiered = if (tieredArr != null && tieredArr.length() >= 5) {
                val threshold = tieredArr.optLong(0, -1L)
                val tIn = tieredArr.optDouble(1)
                val tOut = tieredArr.optDouble(2)
                val tRead = tieredArr.optDouble(3)
                val tWrite = tieredArr.optDouble(4)
                if (threshold >= 0L && !tIn.isNaN() && !tOut.isNaN() && !tRead.isNaN() && !tWrite.isNaN()) {
                    CodexTieredRate(threshold, tIn, tOut, tRead, tWrite)
                } else {
                    null
                }
            } else {
                null
            }
            return CodexModelRate(inM, outM, readM, writeM, tiered)
        }

        private fun loadBuiltinPricingJsonFallback(): String? {
            val stream = CodexPricingCatalog::class.java.classLoader?.getResourceAsStream(PRICING_ASSET_PATH)
            if (stream != null) {
                return stream.bufferedReader().use { it.readText() }
            }
            val candidates = listOf(
                "apps/gui/app/src/main/assets/$PRICING_ASSET_PATH",
                "src/main/assets/$PRICING_ASSET_PATH",
                "app/src/main/assets/$PRICING_ASSET_PATH",
            )
            var dir: File? = File("").absoluteFile
            repeat(7) {
                val current = dir ?: return@repeat
                for (rel in candidates) {
                    val file = File(current, rel)
                    if (file.isFile) {
                        return runCatching { file.readText() }.getOrNull()
                    }
                }
                dir = current.parentFile
            }
            return null
        }
    }
}

class CodexSessionPricer(private var catalog: CodexPricingCatalog = CodexPricingCatalog.default()) {
    private data class LiveDeltaStep(
        val before: CodexTokenSnapshot,
        val after: CodexTokenSnapshot,
        val costUsd: Double,
        val priced: Boolean,
        val hasBillableTokens: Boolean,
    )

    private var prevTotals: CodexTokenSnapshot? = null
    private var accumulatedCostUsd: Double = 0.0
    private var hasPricedEvent: Boolean = false
    private var hasAnyBillableTokens: Boolean = false
    private var hasRolloutBaseline: Boolean = false
    private var latestLastUsage: JSONObject? = null
    private var latestTotalUsage: JSONObject? = null
    private var latestModelContextWindow: Long? = null
    private val liveSteps = mutableListOf<LiveDeltaStep>()

    fun reset() {
        prevTotals = null
        accumulatedCostUsd = 0.0
        hasPricedEvent = false
        hasAnyBillableTokens = false
        hasRolloutBaseline = false
        latestLastUsage = null
        latestTotalUsage = null
        latestModelContextWindow = null
        liveSteps.clear()
    }

    fun updateCatalog(newCatalog: CodexPricingCatalog) {
        if (newCatalog.rates.isNotEmpty() || catalog.rates.isEmpty()) {
            catalog = newCatalog
        }
    }

    fun snapshot(): CodexSessionUsageState = CodexSessionUsageState(
        last = latestLastUsage,
        total = latestTotalUsage,
        modelContextWindow = latestModelContextWindow,
        costUsd = accumulatedCostUsd,
        priced = hasPricedEvent,
        hasBillableTokens = hasAnyBillableTokens,
    )

    fun reconcileSessionPricing(
        payload: JSONObject,
        fallbackModel: String? = null,
        fallbackSpeed: String? = null,
    ): CodexSessionUsageState {
        payload.optJSONObject("catalog")?.let { catJson ->
            updateCatalog(CodexPricingCatalog.fromJson(catJson))
        }
        val rollout = payload.optJSONObject("rollout")
        if (rollout != null && rollout.optBoolean("hasUsage")) {
            applyRolloutBaseline(rollout, fallbackModel, fallbackSpeed)
        }
        return snapshot()
    }

    fun applyRolloutBaseline(
        rollout: JSONObject,
        fallbackModel: String? = null,
        fallbackSpeed: String? = null,
    ): CodexSessionUsageState {
        val rolloutTotals = rollout.optJSONObject("totals")?.let(::parseSnapshot)
        val rolloutLast = rollout.optJSONObject("last")
        val rolloutWindow = countLong(rollout, "modelContextWindow", "model_context_window")?.takeIf { it > 0L }
        if (rolloutWindow != null) {
            latestModelContextWindow = rolloutWindow
        }
        if (rolloutTotals != null && rolloutTotals.totalAnyTokens() > 0L) {
            val rolloutCost = rollout.optDouble("costUsd", 0.0).let { if (it.isNaN()) 0.0 else it }
            val rolloutPriced = rollout.optBoolean("priced", false)
            val tailSteps = if (prevTotals != null && isAheadOf(prevTotals!!, rolloutTotals)) {
                liveSteps.filter { step ->
                    step.before.inputTokens >= rolloutTotals.inputTokens &&
                        step.before.outputTokens >= rolloutTotals.outputTokens &&
                        isAheadOf(step.after, rolloutTotals)
                }
            } else {
                emptyList()
            }
            val latestAfter = tailSteps.lastOrNull()?.after ?: rolloutTotals
            prevTotals = latestAfter
            accumulatedCostUsd = rolloutCost + tailSteps.sumOf { it.costUsd }
            hasPricedEvent = rolloutPriced || tailSteps.any { it.priced }
            hasAnyBillableTokens = latestAfter.totalAnyTokens() > 0L || tailSteps.any { it.hasBillableTokens }
            hasRolloutBaseline = true
            if (tailSteps.isEmpty()) {
                if (rolloutLast != null) latestLastUsage = rolloutLast
                latestTotalUsage = snapshotToJson(rolloutTotals)
            }
            liveSteps.clear()
            liveSteps.addAll(tailSteps)
        } else if (!rollout.has("totals") && (rollout.has("total_token_usage") || rollout.has("total"))) {
            onTokenUsage(rollout, fallbackModel, fallbackSpeed)
            hasRolloutBaseline = true
        }
        return snapshot()
    }

    fun onReplayedTokenUsage(
        usage: JSONObject,
        model: String? = null,
        speed: String? = null,
    ): CodexSessionUsageState {
        val totals = (usage.optJSONObject("total") ?: usage.optJSONObject("total_token_usage"))?.let(::parseSnapshot)
        val previous = prevTotals
        if (hasRolloutBaseline && totals != null && previous != null && !isAheadOf(totals, previous)) {
            return snapshot()
        }
        return onTokenUsage(usage, model, speed)
    }

    fun onTokenUsage(
        usage: JSONObject,
        model: String? = null,
        speed: String? = null,
    ): CodexSessionUsageState {
        val totObj = usage.optJSONObject("total") ?: usage.optJSONObject("total_token_usage")
        val lastObj = usage.optJSONObject("last") ?: usage.optJSONObject("last_token_usage")
        val window = countLong(usage, "modelContextWindow", "model_context_window")?.takeIf { it > 0L }
        if (window != null) {
            latestModelContextWindow = window
        }
        if (lastObj != null) {
            latestLastUsage = lastObj
        }
        if (totObj != null) {
            latestTotalUsage = totObj
        }

        val curTot = totObj?.let(::parseSnapshot)
        val prev = prevTotals
        if (curTot != null && prev != null && curTot == prev) {
            return snapshot()
        }

        val dIn: Long
        val dCached: Long
        val dWrite: Long
        val dOut: Long
        val tierPromptTokens: Long
        val lastSnap = lastObj?.let(::parseSnapshot)
        if (curTot != null) {
            if (prev != null && curTot.inputTokens >= prev.inputTokens && curTot.outputTokens >= prev.outputTokens) {
                dIn = max(0L, curTot.inputTokens - prev.inputTokens)
                dCached = max(0L, curTot.cachedInputTokens - prev.cachedInputTokens)
                dWrite = max(0L, curTot.cacheWriteInputTokens - prev.cacheWriteInputTokens)
                dOut = max(0L, curTot.outputTokens - prev.outputTokens)
                tierPromptTokens = lastSnap?.inputTokens?.takeIf { it > 0L && it <= dIn } ?: dIn
            } else {
                dIn = curTot.inputTokens
                dCached = curTot.cachedInputTokens
                dWrite = curTot.cacheWriteInputTokens
                dOut = curTot.outputTokens
                tierPromptTokens = lastSnap?.inputTokens?.takeIf { it > 0L && curTot.inputTokens > it } ?: dIn
            }
        } else if (lastSnap != null) {
            dIn = lastSnap.inputTokens
            dCached = lastSnap.cachedInputTokens
            dWrite = lastSnap.cacheWriteInputTokens
            dOut = lastSnap.outputTokens
            tierPromptTokens = dIn
        } else {
            return snapshot()
        }

        if (curTot != null) {
            prevTotals = curTot
        }

        val stepUncached = max(0L, dIn - dCached - dWrite)
        val stepBillable = (stepUncached + dCached + dWrite + dOut) > 0L
        val stepCost = priceDelta(
            uncached = stepUncached,
            cached = dCached,
            cacheWrite = dWrite,
            output = dOut,
            tierPromptTokens = tierPromptTokens,
            model = model,
            speed = speed,
        )
        if (stepCost != null) {
            accumulatedCostUsd += stepCost.costUsd
            if (stepCost.priced) hasPricedEvent = true
        }
        if (stepBillable) {
            hasAnyBillableTokens = true
        }
        if (curTot != null && stepBillable && !hasRolloutBaseline) {
            liveSteps.add(
                LiveDeltaStep(
                    before = prev ?: CodexTokenSnapshot(),
                    after = curTot,
                    costUsd = stepCost?.costUsd ?: 0.0,
                    priced = stepCost?.priced ?: false,
                    hasBillableTokens = stepBillable,
                )
            )
        } else if (curTot != null && stepBillable && hasRolloutBaseline && prev != null) {
            liveSteps.add(
                LiveDeltaStep(
                    before = prev,
                    after = curTot,
                    costUsd = stepCost?.costUsd ?: 0.0,
                    priced = stepCost?.priced ?: false,
                    hasBillableTokens = stepBillable,
                )
            )
        }
        return snapshot()
    }

    private data class StepPriceResult(val costUsd: Double, val priced: Boolean)

    private fun priceDelta(
        uncached: Long,
        cached: Long,
        cacheWrite: Long,
        output: Long,
        tierPromptTokens: Long,
        model: String?,
        speed: String?,
    ): StepPriceResult? {
        if (uncached + cached + cacheWrite + output <= 0L) return null
        val isFast = speed == "fast" || speed == "priority" || speed == "codex:fast" ||
            model?.trim()?.lowercase(Locale.ROOT)?.endsWith(":fast") == true
        val multiplier = if (isFast) 2.0 else 1.0
        val rate = catalog.lookupRate(model)
        val explicitFree = catalog.isExplicitFree(model)
        if (rate == null) {
            return if (explicitFree) StepPriceResult(0.0, true) else null
        }
        var inM = rate.inputPerM
        var outM = rate.outputPerM
        var readM = rate.cacheReadPerM
        var writeM = rate.cacheWritePerM
        val tiered = rate.tiered
        if (tiered != null && tierPromptTokens > tiered.thresholdTokens) {
            inM = tiered.inputPerM
            outM = tiered.outputPerM
            readM = tiered.cacheReadPerM
            writeM = tiered.cacheWritePerM
        }
        val cost = ((uncached * inM + cached * readM + cacheWrite * writeM + output * outM) / 1_000_000.0) * multiplier
        return StepPriceResult(cost, true)
    }

    private fun isAheadOf(candidate: CodexTokenSnapshot, baseline: CodexTokenSnapshot): Boolean =
        candidate.inputTokens > baseline.inputTokens || candidate.outputTokens > baseline.outputTokens

    private fun CodexTokenSnapshot.totalAnyTokens(): Long =
        inputTokens + outputTokens

    private fun parseSnapshot(obj: JSONObject): CodexTokenSnapshot = CodexTokenSnapshot(
        inputTokens = countLong(obj, "inputTokens", "input_tokens") ?: 0L,
        cachedInputTokens = countLong(obj, "cachedInputTokens", "cached_input_tokens") ?: 0L,
        cacheWriteInputTokens = countLong(obj, "cacheWriteInputTokens", "cache_write_input_tokens") ?: 0L,
        outputTokens = countLong(obj, "outputTokens", "output_tokens") ?: 0L,
        reasoningOutputTokens = countLong(obj, "reasoningOutputTokens", "reasoning_output_tokens") ?: 0L,
    )

    private fun snapshotToJson(snap: CodexTokenSnapshot): JSONObject = JSONObject().apply {
        put("inputTokens", snap.inputTokens)
        put("cachedInputTokens", snap.cachedInputTokens)
        put("cacheWriteInputTokens", snap.cacheWriteInputTokens)
        put("outputTokens", snap.outputTokens)
        put("reasoningOutputTokens", snap.reasoningOutputTokens)
    }

    private fun countLong(obj: JSONObject, camelKey: String, snakeKey: String): Long? {
        if (obj.has(camelKey) && !obj.isNull(camelKey)) return obj.optLong(camelKey)
        if (obj.has(snakeKey) && !obj.isNull(snakeKey)) return obj.optLong(snakeKey)
        return null
    }
}
