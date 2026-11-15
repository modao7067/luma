package io.github.modao7067.luma.gui.agent

import io.github.modao7067.luma.gui.i18n.tr

import io.github.modao7067.luma.gui.session.*
import org.json.JSONObject
import org.json.JSONArray
import java.text.DateFormat
import java.util.Date
import java.util.Locale

object CodexCapabilities {
    fun permissions(page: JSONObject) = page.optJSONArray("data")?.objects().orEmpty()
        .filter { it.optBoolean("allowed") }.map { RequestChoice(it.getString("id"), it.stringOrNull("description") ?: it.getString("id")) }
    private val codexRatesPerM = listOf(
        "gpt-6" to Triple(2.50, 0.25, 15.00),
        "gpt-5.4-mini" to Triple(0.40, 0.04, 1.60),
        "gpt-5.4" to Triple(2.50, 0.25, 15.00),
        "gpt-5.3-codex-spark" to Triple(0.40, 0.04, 1.60),
        "gpt-5.3-codex" to Triple(1.75, 0.175, 14.00),
        "gpt-5.2" to Triple(1.75, 0.175, 14.00),
        "gpt-5.1-codex-max" to Triple(2.50, 0.25, 15.00),
        "gpt-5.1-codex-mini" to Triple(0.30, 0.03, 1.20),
        "gpt-5.1" to Triple(1.50, 0.15, 12.00),
        "gpt-5-mini" to Triple(0.25, 0.025, 2.00),
        "gpt-5-nano" to Triple(0.05, 0.005, 0.40),
        "gpt-5" to Triple(1.25, 0.125, 10.00),
        "codex-mini" to Triple(1.50, 0.375, 6.00),
        "codex-" to Triple(0.40, 0.04, 1.60),
        "o3" to Triple(2.00, 0.50, 8.00),
        "o4-mini" to Triple(1.10, 0.275, 4.40),
    )

    private fun lookupCodexRate(model: String?): Triple<Double, Double, Double>? {
        val m = model?.trim()?.lowercase(Locale.ROOT)?.substringAfterLast('/')?.removeSuffix(":fast") ?: return null
        if (m.isEmpty()) return null
        return codexRatesPerM.firstOrNull { (prefix, _) -> m == prefix || m.startsWith(prefix) }?.second
    }

    private fun defaultContextWindow(model: String?): Long? {
        val m = model?.trim()?.lowercase(Locale.ROOT)?.substringAfterLast('/')?.removeSuffix(":fast") ?: return null
        return when {
            m.startsWith("gpt-5") || m.startsWith("gpt-6") || m.startsWith("codex-") -> 258400L
            m.startsWith("o3") || m.startsWith("o4-") -> 200000L
            else -> null
        }
    }

    fun tokenMetrics(usage: JSONObject, model: String? = null, speed: String? = null): List<Metric> = buildList {
        val last = usage.optJSONObject("last") ?: usage.optJSONObject("last_token_usage")
        val used = last?.count("totalTokens") ?: last?.count("total_tokens")
        val window = (usage.count("modelContextWindow") ?: usage.count("model_context_window"))?.takeIf { it > 0 }
            ?: defaultContextWindow(model)
        if (used != null) {
            add(Metric(tr("最近请求 / 上下文窗口", "Last request / Context window"), if (window == null) used.toString() else
                "$used / $window · ${String.format(Locale.ROOT, "%.1f", used * 100.0 / window)}%"))
        }
        (usage.optJSONObject("total") ?: usage.optJSONObject("total_token_usage"))?.let { total ->
            val read = total.count("cachedInputTokens") ?: total.count("cached_input_tokens")
            val input = total.count("inputTokens") ?: total.count("input_tokens")
            if (read != null && input != null && input > 0 && read <= input) {
                add(Metric(tr("累计输入缓存命中", "Total input cache hit"), "$read / $input · ${String.format(Locale.ROOT, "%.1f", read * 100.0 / input)}%"))
            }
            val rate = lookupCodexRate(model)
            if (rate != null && input != null) {
                val cached = (read ?: 0L).coerceAtMost(input)
                val uncached = (input - cached).coerceAtLeast(0L)
                val out = total.count("outputTokens") ?: total.count("output_tokens") ?: 0L
                if (uncached + cached + out > 0L) {
                    val mult = if (speed == "fast" || speed == "priority" || model?.lowercase(Locale.ROOT)?.endsWith(":fast") == true) 2.0 else 1.0
                    val costUsd = ((uncached * rate.first + cached * rate.second + out * rate.third) / 1_000_000.0) * mult
                    add(Metric(tr("CLI 累计估算 · USD", "CLI estimated cost · USD"), String.format(Locale.US, "$%.6f", costUsd)))
                }
            }
        }
    }
    private fun JSONObject.count(key: String): Long? = (opt(key) as? Number)?.let {
        it.toString().toBigDecimalOrNull()?.takeIf { n -> n.signum() >= 0 }?.runCatching { longValueExact() }?.getOrNull()
    }
    fun limitMetrics(response: JSONObject): List<Metric> = buildList {
        val buckets = response.optJSONObject("rateLimitsByLimitId")
        val snapshots = if (buckets != null) buckets.keys().asSequence().mapNotNull { buckets.optJSONObject(it) }.toList()
            else listOfNotNull(response.optJSONObject("rateLimits"))
        snapshots.forEach { snapshot ->
            val name = snapshot.stringOrNull("limitName") ?: snapshot.stringOrNull("limitId") ?: tr("账户", "Account")
            for (key in listOf("primary", "secondary")) snapshot.optJSONObject(key)?.let { window ->
                if (window.count("usedPercent") != null) {
                    val minutes = window.count("windowDurationMins")
                    val label = if (minutes == null) key else if (minutes % 1440L == 0L) tr("${minutes / 1440} 天", "${minutes / 1440}d") else tr("${minutes} 分钟", "${minutes}m")
                    val reset = if (window.count("resetsAt") != null) tr(" · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(window.getLong("resetsAt") * 1000))} 重置", " · Resets ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(window.getLong("resetsAt") * 1000))}") else ""
                    add(Metric(tr("$name · $label 限额", "$name · $label quota"), tr("已用 ${window.getInt("usedPercent")}%$reset", "${window.getInt("usedPercent")}% used$reset")))
                }
            }
        }
    }
    fun mcp(server: JSONObject): ReadOnlyEntry {
        val safe = JSONObject().put("name", server.optString("name")).put("authStatus", server.optString("authStatus"))
        for (key in listOf("runtimeStatus", "httpOrigin")) if (!server.isNull(key)) safe.put(key, server.get(key))
        val tools = server.optJSONObject("tools")
        safe.put("tools", JSONArray(tools?.keys()?.asSequence()?.toList().orEmpty()))
        safe.put("resources", JSONArray(server.optJSONArray("resources")?.objects()?.map { it.optString("name") }.orEmpty()))
        return ReadOnlyEntry(server.optString("name"), safe.toString(2))
    }
    fun redact(text: String): String = text
        .replace(Regex("(?im)^([^\\n]*(?:api[_-]?key|password|secret|authorization|access[_-]?token|bearer[_-]?token)[^:=\\n]*[:=])[^\\n]*"), tr("$1 [已隐藏]", "$1 [redacted]"))
        .replace(Regex("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+"), tr("Bearer [已隐藏]", "Bearer [redacted]"))
        .replace(Regex("sk-[A-Za-z0-9_-]{12,}"), tr("[已隐藏]", "[redacted]"))
}
