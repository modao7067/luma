package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.session.Metric
import org.junit.Assert.*
import org.junit.Test

class NativeDashboardMetricsTest {
    @Test fun allNativeCacheScopesNormalizeToCacheHitWithCompactCounts() {
        for (label in listOf("累计 输入缓存命中", "当前分支 输入缓存命中", "最近请求输入缓存命中", "本回合输入缓存命中", "进程累计 · 输入缓存命中", "累计 · 输入缓存命中", "缓存命中")) {
            assertEquals(Metric("缓存命中", "6K / 10K · 60.0%"), dashboardMetric(Metric(label, "6000 / 10000 · 60.0%")))
        }
    }
    @Test fun recordedAndEstimatedCostsBothRenderAsFee() {
        assertEquals(Metric("费用", "USD 0.012345"), dashboardMetric(Metric("CLI 累计估算 · USD", "0.012345")))
        assertEquals(Metric("费用", "EUR 0.25"), dashboardMetric(Metric("CLI 记录费用 · EUR", "0.25")))
        assertEquals(Metric("费用", "USD 0.012346"), dashboardMetric(Metric("CLI 记录费用 · USD", "0.0123456789")))
        assertEquals(Metric("费用", "USD <0.000001"), dashboardMetric(Metric("CLI 累计估算 · USD", "0.00000001")))
    }
}
