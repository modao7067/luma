package io.github.modao7067.luma.gui.ui

import com.termux.terminal.LumaLang
import io.github.modao7067.luma.gui.i18n.LumaI18n
import io.github.modao7067.luma.gui.session.Metric
import java.util.Locale
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class NativeDashboardMetricsTest {
    private lateinit var previousLocale: Locale
    private var previousLangProp: String? = null

    @Before fun setUp() {
        previousLocale = Locale.getDefault()
        previousLangProp = System.getProperty("luma.lang")
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        System.clearProperty("luma.lang")
        LumaI18n.resetForTest()
        LumaLang.setOverride(null)
    }

    @After fun tearDown() {
        LumaI18n.resetForTest()
        LumaLang.setOverride(null)
        Locale.setDefault(previousLocale)
        if (previousLangProp == null) System.clearProperty("luma.lang")
        else System.setProperty("luma.lang", previousLangProp)
    }

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
    @Test fun duplicateNativeAndAcpContextRowsCollapseToLastAuthoritativeWindow() {
        val mixed = listOf(
            Metric("上下文", "42000 / 1048576 · 4.0%"),
            Metric("本回合 · 输入缓存命中", "30000 / 42000 · 71.4%"),
            Metric("最近请求 / 上下文窗口", "42000 / 256000 · 16.4%"),
            Metric("CLI 累计估算 · USD", "1.31"),
        )
        assertEquals(
            listOf(
                Metric("缓存命中", "30K / 42K · 71.4%"),
                Metric("上下文", "42K / 256K · 16.4%"),
                Metric("费用", "USD 1.31"),
            ),
            dashboardMetrics(mixed),
        )
    }
    @Test fun englishLocaleNormalizesNativeCacheCostAndContextMetrics() {
        Locale.setDefault(Locale.US)
        LumaI18n.overrideLanguage = "en"

        for (label in listOf("Total input cache hit", "Last request input cache hit", "Cache hit", "Cache")) {
            assertEquals(Metric("Cache hit", "6K / 10K · 60.0%"), dashboardMetric(Metric(label, "6000 / 10000 · 60.0%")))
        }
        assertEquals(Metric("Cost", "USD 0.012345"), dashboardMetric(Metric("CLI estimated cost · USD", "0.012345")))
        assertEquals(Metric("Cost", "EUR 0.25"), dashboardMetric(Metric("CLI recorded cost · EUR", "0.25")))
        val mixed = listOf(
            Metric("Context", "42000 / 1048576 · 4.0%"),
            Metric("Turn · input cache hit", "30000 / 42000 · 71.4%"),
            Metric("Last request / Context window", "42000 / 256000 · 16.4%"),
            Metric("CLI estimated cost · USD", "1.31"),
        )
        assertEquals(
            listOf(
                Metric("Cache hit", "30K / 42K · 71.4%"),
                Metric("Context", "42K / 256K · 16.4%"),
                Metric("Cost", "USD 1.31"),
            ),
            dashboardMetrics(mixed),
        )
    }
}
