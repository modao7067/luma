package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.agent.OpenCodeProjection
import org.json.JSONArray
import org.json.JSONObject
import io.github.modao7067.luma.gui.session.Metric
import org.junit.Assert.*
import org.junit.Test

class OpenCodeDashboardMetricTest {
    @Test fun nativeMetricsReachDashboardAsCompactCountsAndPercentages() {
        val summary = JSONObject("""{"cost":0.012345,"tokens":{"input":100000,"output":20000,"reasoning":3000,"cache":{"read":800000,"write":100000}}}""")
        val messages = listOf(JSONObject("""{"id":"step","type":"assistant","model":{"providerID":"p","id":"m"},"tokens":{"input":1234,"output":1000,"reasoning":2000,"cache":{"read":12000,"write":1766}}}"""))
        val models = JSONArray("""[{"id":"m","providerID":"p","limit":{"context":200000}}]""")
        val metrics = dashboardMetrics(OpenCodeProjection.metrics(summary, messages, models))
        assertEquals(
            listOf(
                Metric("上下文", "18K / 200K · 9%"),
                Metric("缓存命中", "800K / 1M · 80.0%"),
                Metric("费用", "$0.012345"),
            ),
            metrics,
        )
        assertEquals("1.23K", compactCount("1234"))
    }
}
