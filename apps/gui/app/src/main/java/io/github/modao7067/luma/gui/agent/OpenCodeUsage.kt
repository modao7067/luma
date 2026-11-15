package io.github.modao7067.luma.gui.agent

import io.github.modao7067.luma.gui.i18n.tr

import io.github.modao7067.luma.gui.session.Metric
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/* Context selection adapted from OpenCode v2.2.5:
 * packages/client/src/solid/usage.ts and packages/tui/src/util/session.ts,
 * revision 9bbb8ce18c80f099e0e448775f59d965ead40a21.
 * MIT License
 * 
 * Copyright (c) 2025 opencode
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
internal object OpenCodeUsage {
    fun metrics(session: JSONObject, messages: List<JSONObject>, models: JSONArray): List<Metric> = buildList {
        val latest = lastAssistantWithUsage(messages, session.optJSONObject("revert")?.stringOrNull("messageID"))
        latest?.optJSONObject("tokens")?.let { tokens ->
            total(tokens)?.takeIf { it.signum() > 0 }?.let { used ->
                val ref = latest.optJSONObject("model")
                val model = models.objects().firstOrNull { ref != null && it.optString("providerID") == ref.optString("providerID") && it.optString("id") == ref.optString("id") }
                val limit = model?.optJSONObject("limit")?.count("context")?.takeIf { it.signum() > 0 }
                add(Metric(tr("上下文", "Context"), if (limit == null) used.countText() else
                    "${used.countText()} / ${limit.countText()} · ${percent(used, limit, 0)}%"))
            }
        }
        val tokens = session.optJSONObject("tokens")
        val cache = tokens?.optJSONObject("cache")
        val input = tokens?.count("input")
        val read = cache?.count("read")
        val write = cache?.count("write")
        val allInput = if (input != null && read != null && write != null) input + read + write else null
        if (allInput != null && allInput.signum() > 0) {
            add(Metric(tr("累计输入缓存命中", "Total input cache hit"), "${read!!.countText()} / ${allInput.countText()} · ${percent(read, allInput, 1)}%"))
        }
        session.count("cost")?.let { add(Metric(tr("CLI 记录费用 · USD", "CLI recorded cost · USD"), String.format(Locale.US, "$%.6f", it))) }
    }

    internal fun lastAssistantWithUsage(messages: List<JSONObject>, boundary: String?): JSONObject? {
        val boundaryIndex = boundary?.let { id -> messages.indexOfFirst { it.optString("id") == id } } ?: -1
        if (boundary != null && boundaryIndex == -1) return null
        val end = if (boundaryIndex == -1) messages.size else boundaryIndex
        val compaction = (0 until end).lastOrNull { messages[it].optString("type") == "compaction" && messages[it].optString("status") == "completed" } ?: -1
        return (compaction + 1 until end).lastOrNull { messages[it].optString("type") == "assistant" && messages[it].optJSONObject("tokens") != null }?.let(messages::get)
    }

    private fun total(tokens: JSONObject): BigDecimal? {
        val cache = tokens.optJSONObject("cache") ?: return null
        val parts = listOf(tokens.count("input"), tokens.count("output"), tokens.count("reasoning"), cache.count("read"), cache.count("write"))
        if (parts.any { it == null }) return null
        return parts.filterNotNull().fold(BigDecimal.ZERO, BigDecimal::add)
    }
    private fun JSONObject.count(key: String): BigDecimal? = (opt(key) as? Number)?.toString()?.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }
    private fun BigDecimal.countText() = stripTrailingZeros().toPlainString()
    private fun percent(part: BigDecimal, total: BigDecimal, scale: Int) = part.multiply(BigDecimal(100)).divide(total, scale, RoundingMode.HALF_UP).toPlainString()
}
