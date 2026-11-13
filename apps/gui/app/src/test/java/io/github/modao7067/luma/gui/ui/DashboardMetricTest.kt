package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.session.Metric
import org.junit.Assert.*
import org.junit.Test

class DashboardMetricTest {
    @Test fun decimalCountsUseTwoPlacesWithoutTrailingZeros() {
        for ((raw, short) in listOf("0" to "0", "999" to "999", "1000" to "1K", "1234" to "1.23K", "16200" to "16.2K", "1000000" to "1M", "999999" to "1M", "1234567" to "1.23M")) assertEquals(short, compactCount(raw))
    }
    @Test fun contextAndCacheKeepRatiosAndPercentages() {
        assertEquals(Metric("上下文", "16.2K / 258.4K · 6.3%"), dashboardMetric(Metric("最近请求 / 上下文窗口", "16200 / 258400 · 6.3%")))
        assertEquals(Metric("缓存命中", "811.48K / 893.42K · 90.8%"), dashboardMetric(Metric("累计输入缓存命中", "811482 / 893422 · 90.8%")))
        assertEquals(Metric("缓存命中", "305.28K / 306.27K · 99.7%"), dashboardMetric(Metric("缓存命中", "305280 / 306270 · 99.7%")))
    }
    @Test fun feesAndBalancesUseUnifiedFourMetricLabels() {
        assertEquals(Metric("费用", "$0.000123"), dashboardMetric(Metric("CLI 记录费用 · USD", "$0.000123")))
        assertEquals(Metric("费用", "USD 0.000123"), dashboardMetric(Metric("CLI 累计估算 · USD", "0.000123")))
        assertEquals(Metric("费用", "EUR 0.0012"), dashboardMetric(Metric("CLI 记录费用 · EUR", "0.0012")))
        assertEquals(Metric("账户余额 · CNY", "¥1.50"), dashboardMetric(Metric("账户余额 · CNY", "¥1.50")))
        assertEquals(Metric("账户余额 · CNY", "¥1.50"), dashboardMetric(Metric("账号余额 · CNY", "¥1.50")))
        assertEquals(Metric("API Key 余额 · CNY", "¥9.20"), dashboardMetric(Metric("API Key 余额 · CNY", "¥9.20")))
    }
    @Test fun dashboardMetricsFiltersToFourApprovedCategories() {
        val raw = listOf(
            Metric("上下文", "16200 / 258400 · 6.3%"),
            Metric("累计输入缓存命中", "8000 / 10000 · 80.0%"),
            Metric("CLI 累计估算 · USD", "0.05"),
            Metric("账户余额 · CNY", "¥1.50"),
            Metric("累计 token", "10000"),
            Metric("Codex · 5 小时 限额", "已用 6%"),
            Metric("原生待发消息", "2"),
        )
        assertEquals(
            listOf(
                Metric("上下文", "16.2K / 258.4K · 6.3%"),
                Metric("缓存命中", "8K / 10K · 80.0%"),
                Metric("费用", "USD 0.05"),
                Metric("账户余额 · CNY", "¥1.50"),
            ),
            dashboardMetrics(raw),
        )
    }
    @Test fun dashboardMetricsSynthesizesCacheRatioFromLegacyBridgeCounters() {
        val legacyAgy = listOf(
            Metric("本回合 · 输入", "2000"),
            Metric("本回合 · 输出", "500"),
            Metric("本回合 · 缓存读取", "8000"),
            Metric("本回合 · 缓存写入", "0"),
            Metric("本回合 · 总 token", "10500"),
        )
        assertEquals(
            listOf(Metric("缓存命中", "8K / 10K · 80.0%")),
            dashboardMetrics(legacyAgy),
        )
    }
}
