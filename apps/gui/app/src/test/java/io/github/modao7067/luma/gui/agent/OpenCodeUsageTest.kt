package io.github.modao7067.luma.gui.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OpenCodeUsageTest {
    private fun tokens(input: Int = 1000, output: Int = 200, reasoning: Int = 300, read: Int = 6000, write: Int = 3000) =
        obj("input" to input, "output" to output, "reasoning" to reasoning, "cache" to obj("read" to read, "write" to write))
    private fun assistant(id: String = "a", provider: String = "p", model: String = "m", usage: JSONObject? = tokens()) =
        obj("id" to id, "type" to "assistant", "model" to obj("providerID" to provider, "id" to model)).apply { if (usage != null) put("tokens", usage) }
    private fun catalog(provider: String = "p", model: String = "m", context: Int = 200000, input: Int = 100000) =
        JSONArray().put(obj("providerID" to provider, "id" to model, "enabled" to true, "limit" to obj("context" to context, "input" to input)))
    private fun session(usage: JSONObject = tokens()) = obj("tokens" to usage)
    private fun context(messages: List<JSONObject>, models: JSONArray = catalog(), summary: JSONObject = session()) =
        OpenCodeProjection.metrics(summary, messages, models).firstOrNull { it.label == "上下文" }?.value

    @Test fun contextUsesAllFiveNativeCountsAndContextNotInputLimit() {
        assertEquals("10500 / 200000 · 5%", context(listOf(assistant())))
    }
    @Test fun latestStepNotSessionCumulativeNorSumOfSteps() {
        val messages = listOf(assistant("a", usage = tokens(input = 90000)), assistant("b"))
        assertEquals("10500 / 200000 · 5%", context(messages, summary = session(tokens(input = 999999))))
    }
    @Test fun modelIdentityComesFromLatestAssistantNotNewSelection() {
        val summary = session().put("model", obj("providerID" to "p", "id" to "other"))
        val models = catalog("other-provider", context = 1000).put(catalog().getJSONObject(0)).put(catalog(model = "other", context = 500000).getJSONObject(0))
        assertEquals("10500 / 200000 · 5%", context(listOf(assistant()), models, summary))
    }
    @Test fun missingWindowKeepsReportedUsageWithoutInventedPercentage() {
        assertEquals("10500", context(listOf(assistant()), JSONArray()))
        assertEquals("10500", context(listOf(assistant()), catalog(context = 0)))
    }
    @Test fun contextRoundingMatchesNativeAndDoesNotClampRealOverage() {
        assertEquals("10500 / 20000 · 53%", context(listOf(assistant()), catalog(context = 20000)))
        assertEquals("10500 / 10000 · 105%", context(listOf(assistant()), catalog(context = 10000)))
    }
    @Test fun completedCompactionClearsOldUsageUntilNewStep() {
        val compact = obj("id" to "compact", "type" to "compaction", "status" to "completed")
        assertNull(context(listOf(assistant(), compact)))
        assertNotNull(context(listOf(assistant(), compact, assistant("new"))))
        assertNotNull(context(listOf(assistant(), compact.put("status", "running"))))
    }
    @Test fun revertUsesArrayBoundaryNotLexicalMessageIds() {
        val messages = listOf(assistant("msg_z", usage = tokens(input = 2000)), assistant("msg_a"))
        assertEquals("11500 / 200000 · 6%", context(messages, summary = session().put("revert", obj("messageID" to "msg_a"))))
        assertNull(context(messages, summary = session().put("revert", obj("messageID" to "missing"))))
    }
    @Test fun pendingAssistantWithoutUsageRetainsLastMeasuredStep() {
        assertEquals("10500 / 200000 · 5%", context(listOf(assistant(), assistant("starting", usage = null))))
    }
    @Test fun cacheInputExcludesOutputAndIncludesBothReadAndWrite() {
        val metrics = OpenCodeProjection.metrics(session())
        assertEquals("6000 / 10000 · 60.0%", metrics.first { it.label == "累计输入缓存命中" }.value)
        assertFalse(metrics.any { it.label == "累计缓存读取 token" })
    }
    @Test fun missingCountersDoNotBecomeFalseZerosOrPercentages() {
        assertTrue(OpenCodeProjection.metrics(JSONObject()).isEmpty())
        val partial = obj("tokens" to obj("input" to 10, "cache" to obj("read" to 2)))
        val metrics = OpenCodeProjection.metrics(partial)
        assertTrue(metrics.isEmpty())
        assertNull(context(listOf(assistant(usage = obj("input" to 1)))))
    }
    @Test fun zeroIsShownOnlyWhenNativeReportsAValidDenominator() {
        val zeroRead = session(tokens(input = 10, read = 0, write = 0))
        assertEquals("0 / 10 · 0.0%", OpenCodeProjection.metrics(zeroRead).first { it.label == "累计输入缓存命中" }.value)
        assertFalse(OpenCodeProjection.metrics(session(tokens(input = 0, read = 0, write = 0))).any { it.label == "累计输入缓存命中" })
    }
    @Test fun replacementSnapshotsDoNotAccumulateOrLeakAcrossSessions() {
        val first = session()
        val expected = OpenCodeProjection.metrics(first, listOf(assistant()), catalog())
        repeat(3) { assertEquals(expected, OpenCodeProjection.metrics(first, listOf(assistant()), catalog())) }
        assertTrue(OpenCodeProjection.metrics(JSONObject(), emptyList(), catalog()).isEmpty())
    }
    @Test fun incompleteOrInvalidCounterIsNotPresentedAsMeasuredContext() {
        assertNull(context(listOf(assistant(usage = tokens().put("reasoning", JSONObject.NULL)))))
        assertNull(context(listOf(assistant(usage = tokens().put("input", -1)))))
        assertTrue(OpenCodeProjection.metrics(obj("cost" to -1)).isEmpty())
    }
}
