package io.github.modao7067.luma.gui.ui

import java.math.BigDecimal
import java.math.RoundingMode
import io.github.modao7067.luma.gui.i18n.tr
import io.github.modao7067.luma.gui.session.Metric

internal fun compactCount(value: String): String {
    val number = value.toBigDecimalOrNull() ?: return value
    if (number.abs() < BigDecimal(1000)) return number.stripTrailingZeros().toPlainString()
    var divisor = if (number.abs() >= BigDecimal(1_000_000)) BigDecimal(1_000_000) else BigDecimal(1000)
    var rounded = number.divide(divisor, 2, RoundingMode.HALF_UP)
    if (divisor == BigDecimal(1000) && rounded.abs() >= BigDecimal(1000)) {
        divisor = BigDecimal(1_000_000)
        rounded = number.divide(divisor, 2, RoundingMode.HALF_UP)
    }
    return rounded.stripTrailingZeros().toPlainString() + if (divisor == BigDecimal(1000)) "K" else "M"
}

private fun compactMoney(value: String): String {
    val prefix = if (value.startsWith("$")) "$" else ""
    val amount = value.removePrefix("$").toBigDecimalOrNull() ?: return value
    if (amount.scale() <= 6) return value
    val rounded = amount.setScale(6, RoundingMode.HALF_UP)
    if (amount.signum() > 0 && rounded.signum() == 0) return "<$prefix" + "0.000001"
    return prefix + rounded.stripTrailingZeros().toPlainString()
}

private val countToken = Regex("(?<![\\w.])[0-9]+(?:\\.[0-9]+)?(?![\\w.%])")

private fun isContextLabel(label: String) =
    label == "上下文" || label == "Context"

private fun isCacheHitLabel(label: String) =
    label == "缓存命中" || label == "Cache hit"

internal fun isAllowedDashboardMetric(metric: Metric): Boolean {
    val label = metric.label
    return isContextLabel(label) ||
        isCacheHitLabel(label) ||
        label == "费用" || label == "Cost" ||
        label.startsWith("账户余额") || label.startsWith("Account balance") ||
        label.startsWith("API Key 余额") || label.startsWith("API key balance") ||
        label == "余额" || label == "Balance"
}

internal fun dashboardMetrics(metrics: List<Metric>): List<Metric> {
    val mapped = metrics.map(::dashboardMetric).filter(::isAllowedDashboardMetric)
    val lastContext = mapped.indexOfLast { isContextLabel(it.label) }
    val allowed = mapped.filterIndexed { index, metric ->
        !isContextLabel(metric.label) || index == lastContext
    }.toMutableList()
    if (allowed.none { isCacheHitLabel(it.label) }) {
        synthesizeLegacyCacheMetric(metrics)?.let { synthesized ->
            val insertAt = allowed.indexOfFirst { isContextLabel(it.label) }.let { if (it >= 0) it + 1 else 0 }
            allowed.add(insertAt, synthesized)
        }
    }
    return allowed
}

private fun synthesizeLegacyCacheMetric(metrics: List<Metric>): Metric? {
    var input: BigDecimal? = null
    var cacheRead: BigDecimal? = null
    var cacheWrite: BigDecimal = BigDecimal.ZERO
    var inclusiveInput = false
    for (m in metrics) {
        val label = m.label.trim()
        val num = m.value.trim().toBigDecimalOrNull()?.takeIf { it.signum() >= 0 } ?: continue
        when {
            label.endsWith("输入 token") || label.endsWith("input tokens") || label.endsWith("· 输入") || label.endsWith("· Input") || label.endsWith(" input") || label == "输入" || label == "Input" -> {
                input = num
                if (label.startsWith("最近请求") || label.startsWith("Last request") || label.startsWith("进程累计") || label.startsWith("Process total")) inclusiveInput = true
            }
            label.endsWith("缓存读取 token") || label.endsWith("cache read tokens") || label.endsWith("· 缓存读取") || label.endsWith("· Cache read") || label.endsWith(" cacheRead") || label == "缓存读取" || label == "Cache read" -> {
                cacheRead = num
            }
            label.endsWith("缓存写入 token") || label.endsWith("cache write tokens") || label.endsWith("· 缓存写入") || label.endsWith("· Cache write") || label.endsWith(" cacheWrite") || label == "缓存写入" || label == "Cache write" -> {
                cacheWrite = num
            }
        }
    }
    val read = cacheRead ?: return null
    val inp = input ?: return null
    val total = if (inclusiveInput && inp >= read) inp else inp + read + cacheWrite
    if (total.signum() <= 0 || read > total) return null
    val pct = read.multiply(BigDecimal(100)).divide(total, 1, RoundingMode.HALF_UP).toPlainString()
    return Metric(tr("缓存命中", "Cache hit"), "${compactCount(read.toPlainString())} / ${compactCount(total.toPlainString())} · $pct%")
}

internal fun dashboardMetric(metric: Metric): Metric {
    val label = metric.label
    if (label.startsWith("账号余额") || label.startsWith("账户余额") || label.startsWith("Account balance")) {
        val suffix = label.removePrefix("账号余额").removePrefix("账户余额").removePrefix("Account balance")
        return Metric(tr("账户余额", "Account balance") + suffix, metric.value)
    }
    if (label.startsWith("API Key 余额") || label.startsWith("API key balance")) {
        val suffix = label.removePrefix("API Key 余额").removePrefix("API key balance")
        return Metric(tr("API Key 余额", "API key balance") + suffix, metric.value)
    }
    if (label == "余额" || label == "Balance") {
        return Metric(tr("余额", "Balance"), metric.value)
    }
    if (label.startsWith("CLI 记录费用") || label.startsWith("CLI 累计估算") || label.startsWith("费用") ||
        label.startsWith("CLI recorded cost") || label.startsWith("CLI estimated cost") || label.startsWith("Cost")) {
        val currency = label.substringAfter("·", "").trim()
        val amount = compactMoney(metric.value)
        val value = if (currency.isEmpty() || (currency == "USD" && (amount.startsWith("$") || amount.startsWith("<$")))) amount
            else "$currency $amount"
        return Metric(tr("费用", "Cost"), value)
    }
    if (isContextLabel(label) || label == "最近请求 / 上下文窗口" || label == "Last request / Context window") {
        return Metric(tr("上下文", "Context"), countToken.replace(metric.value) { compactCount(it.value) })
    }
    if (isCacheHitLabel(label) || label == "缓存" || label == "Cache" || label.endsWith("输入缓存命中") || label.endsWith("input cache hit")) {
        return Metric(tr("缓存命中", "Cache hit"), countToken.replace(metric.value) { compactCount(it.value) })
    }
    return metric
}
