package io.github.modao7067.luma.gui.ui

import java.math.BigDecimal
import java.math.RoundingMode
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

internal fun isAllowedDashboardMetric(metric: Metric): Boolean {
    val label = metric.label
    return label == "上下文" ||
        label == "缓存命中" ||
        label == "费用" ||
        label.startsWith("账户余额") ||
        label.startsWith("API Key 余额") ||
        label == "余额"
}

internal fun dashboardMetrics(metrics: List<Metric>): List<Metric> {
    val allowed = metrics.map(::dashboardMetric).filter(::isAllowedDashboardMetric).toMutableList()
    if (allowed.none { it.label == "缓存命中" }) {
        synthesizeLegacyCacheMetric(metrics)?.let { synthesized ->
            val insertAt = allowed.indexOfFirst { it.label == "上下文" }.let { if (it >= 0) it + 1 else 0 }
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
            label.endsWith("输入 token") || label.endsWith("· 输入") || label.endsWith(" input") || label == "输入" -> {
                input = num
                if (label.startsWith("最近请求") || label.startsWith("进程累计")) inclusiveInput = true
            }
            label.endsWith("缓存读取 token") || label.endsWith("· 缓存读取") || label.endsWith(" cacheRead") || label == "缓存读取" -> {
                cacheRead = num
            }
            label.endsWith("缓存写入 token") || label.endsWith("· 缓存写入") || label.endsWith(" cacheWrite") || label == "缓存写入" -> {
                cacheWrite = num
            }
        }
    }
    val read = cacheRead ?: return null
    val inp = input ?: return null
    val total = if (inclusiveInput && inp >= read) inp else inp + read + cacheWrite
    if (total.signum() <= 0 || read > total) return null
    val pct = read.multiply(BigDecimal(100)).divide(total, 1, RoundingMode.HALF_UP).toPlainString()
    return Metric("缓存命中", "${compactCount(read.toPlainString())} / ${compactCount(total.toPlainString())} · $pct%")
}

internal fun dashboardMetric(metric: Metric): Metric {
    val label = metric.label
    if (label.startsWith("账号余额")) {
        return Metric(label.replaceFirst("账号余额", "账户余额"), metric.value)
    }
    if (label.startsWith("账户余额") || label.startsWith("API Key 余额") || label == "余额") {
        return metric
    }
    if (label.startsWith("CLI 记录费用") || label.startsWith("CLI 累计估算") || label.startsWith("费用")) {
        val currency = label.substringAfter("·", "").trim()
        val amount = compactMoney(metric.value)
        val value = if (currency.isEmpty() || (currency == "USD" && (amount.startsWith("$") || amount.startsWith("<$")))) amount
            else "$currency $amount"
        return Metric("费用", value)
    }
    if (label == "上下文" || label == "最近请求 / 上下文窗口") {
        return Metric("上下文", countToken.replace(metric.value) { compactCount(it.value) })
    }
    if (label == "缓存命中" || label == "缓存" || label.endsWith("输入缓存命中")) {
        return Metric("缓存命中", countToken.replace(metric.value) { compactCount(it.value) })
    }
    return metric
}
