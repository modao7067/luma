package io.github.modao7067.luma.gui.agent

import io.github.modao7067.luma.gui.i18n.tr

import io.github.modao7067.luma.gui.session.*
import org.json.JSONObject
import org.json.JSONArray
import java.text.DateFormat
import java.util.Date
import java.util.Locale

object CodexCapabilities {
    private fun codexPermissionLabel(entry: JSONObject): String {
        entry.stringOrNull("name")?.takeIf(String::isNotBlank)?.let { return it }
        return when (val id = entry.getString("id")) {
            ":read-only" -> "Read-only"
            ":workspace" -> "Workspace"
            ":danger-full-access" -> "Full access"
            else -> id
        }
    }
    fun permissions(page: JSONObject) = page.optJSONArray("data")?.objects().orEmpty()
        .filter { it.optBoolean("allowed") }
        .map { RequestChoice(it.getString("id"), codexPermissionLabel(it), it.stringOrNull("description").orEmpty()) }

    private fun defaultContextWindow(model: String?): Long? {
        val m = model?.trim()?.lowercase(Locale.ROOT)?.substringAfterLast('/')?.removeSuffix(":fast") ?: return null
        return when {
            m.startsWith("gpt-5") || m.startsWith("gpt-6") || m.startsWith("codex-") -> 258400L
            m.startsWith("o3") || m.startsWith("o4-") -> 200000L
            else -> null
        }
    }

    fun tokenMetrics(
        usage: JSONObject,
        model: String? = null,
        speed: String? = null,
        catalog: CodexPricingCatalog = CodexPricingCatalog.default(),
    ): List<Metric> {
        val state = CodexSessionPricer(catalog).onTokenUsage(usage, model, speed)
        return formatTokenMetrics(state, model)
    }

    fun tokenMetrics(state: CodexSessionUsageState, model: String? = null): List<Metric> =
        formatTokenMetrics(state, model)

    private fun formatTokenMetrics(state: CodexSessionUsageState, model: String?): List<Metric> = buildList {
        val last = state.last
        val used = last?.count("totalTokens") ?: last?.count("total_tokens")
        val window = state.modelContextWindow?.takeIf { it > 0L } ?: defaultContextWindow(model)
        if (used != null) {
            add(Metric(tr("最近请求 / 上下文窗口", "Last request / Context window"), if (window == null) used.toString() else
                "$used / $window · ${String.format(Locale.ROOT, "%.1f", used * 100.0 / window)}%"))
        }
        state.total?.let { total ->
            val read = total.count("cachedInputTokens") ?: total.count("cached_input_tokens")
            val input = total.count("inputTokens") ?: total.count("input_tokens")
            if (read != null && input != null && input > 0 && read <= input) {
                add(Metric(tr("累计输入缓存命中", "Total input cache hit"), "$read / $input · ${String.format(Locale.ROOT, "%.1f", read * 100.0 / input)}%"))
            }
            if (state.priced && state.hasBillableTokens) {
                add(Metric(tr("CLI 累计估算 · USD", "CLI estimated cost · USD"), String.format(Locale.US, "$%.6f", state.costUsd)))
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
