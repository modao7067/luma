package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.agent.CodexProjection
import io.github.modao7067.luma.gui.agent.obj
import io.github.modao7067.luma.gui.session.ConversationItem
import org.junit.Assert.*
import org.junit.Test

internal fun toolLabelsFixture(): List<ConversationItem> = listOf("", "read", "glob", "websearch", "read", "glob", "websearch", "read").mapIndexed { index, name ->
    ConversationItem("call-$index", "tool", text = if (name.isBlank()) "printf synthetic" else name,
        kind = if (name.isBlank()) "commandExecution" else "tool", toolName = name,
        outcome = if (index == 5) "failed" else "succeeded", input = if (name.isBlank()) "printf synthetic" else "{\"pattern\":\"synthetic\"}", detail = "synthetic result $index")
} + (0..1).map { ConversationItem("reason-$it", "tool", kind = "reasoning", outcome = "succeeded", detail = "Synthetic public summary $it") }

class ProcessToolLabelsTest {
    private fun call(name: String, kind: String = "tool", toolKind: String = "") = ConversationItem(name, "tool", text = name, kind = kind, toolName = name, toolKind = toolKind, outcome = "succeeded")

    @Test fun bothLevelsShareLiteralFirstSeenNamesAndCountEachNativeCall() {
        val entries = toolLabelsFixture()
        assertEquals("10 步", processGroupCaption(entries, "OpenCode"))
        assertEquals("10 步 · 2m3s", processGroupCaption(entries, "OpenCode", durationMs = 123_000L))
        assertEquals(listOf("shell", "read", "glob", "websearch", "read", "glob", "websearch", "read", "thought", "thought"), entries.map { processEntryLabel(it, "OpenCode") })
        assertEquals("3 步", processGroupCaption(listOf(call("Read"), call("read"), call("mcp__server__WebSearch")), "Claude Code"))
    }
    @Test fun updatesAreOneCallAndNonCallsAreNotToolInvocations() {
        val first = call("read").copy(outcome = "running", turnId = "t1")
        val done = first.copy(outcome = "failed")
        val otherTurn = first.copy(turnId = "t2", outcome = "succeeded")
        val events = listOf("reasoning", "compaction", "subAgentActivity", "futureEvent").map { first.copy(id = it, kind = it, outcome = "succeeded") }
        assertEquals("5 步", processGroupCaption(listOf(first, done, otherTurn) + events, "pi"))
        assertEquals(listOf(done, otherTurn), processToolInvocations(listOf(first, done, otherTurn) + events))
    }
    @Test fun structuredCommandLabelsPreserveNativeToolSummaryNames() {
        val entries = listOf("commandExecution", "fileChange", "webSearch", "imageView").mapIndexed { i, kind -> call("").copy(id = "$i", kind = kind, text = "cat file", input = "cat file") }
        assertEquals("4 步 · 修改 1 处", processGroupCaption(entries))
        assertEquals(listOf("read", "fileChange", "webSearch", "imageView"), entries.map { processEntryLabel(it, "Codex") })
        assertEquals("read", processEntryLabel(entries.first().copy(toolName = "Bash"), "Codex"))
        assertEquals("1 步", processGroupCaption(listOf(entries.first().copy(toolName = "Bash"))))
        val shell = entries.first().copy(toolName = "Bash", text = "printf synthetic", input = "printf synthetic")
        assertEquals("Bash", processEntryLabel(shell, "Codex"))
        assertEquals("1 步", processGroupCaption(listOf(shell)))
        assertEquals("search", processEntryLabel(call("", toolKind = "search"), "Kimi Code"))
        assertEquals("Tool call", processEntryLabel(call(""), "unknown"))
    }
    @Test fun summaryCountsStepsAndOnlyEmphasizesEdits() {
        val actual = processGroupSummary(toolLabelsFixture(), "OpenCode")
        assertEquals(listOf(ProcessSummaryPart("10 步")), actual)
        val edits = listOf(call("").copy(kind = "fileChange"), call("").copy(id = "x", kind = "commandExecution", input = "printf x", text = "printf x"))
        assertEquals(listOf(ProcessSummaryPart("2 步"), ProcessSummaryPart("修改 1 处", emphasized = true)), processGroupSummary(edits))
        fun check(parts: List<ProcessSummaryPart>) {
            val text = processSummaryText(parts, androidx.compose.ui.graphics.Color.Red)
            assertEquals(parts.joinToString(" · ") { it.text }, text.text)
            assertEquals(parts.filter { it.emphasized }.map { it.text }, text.spanStyles.map { text.text.substring(it.start, it.end) })
            assertTrue(text.spanStyles.all { it.item.fontWeight == null })
        }
        check(actual)
        check(processGroupSummary(edits))
        val literal = "v2.1 · 8 次 思考/read"
        check(processGroupSummary(listOf(call(literal))))
        assertEquals("1 步", processGroupCaption(listOf(call(literal))))
        val reasoning = (0..1).map { call("").copy(id = "r$it", kind = "reasoning") }
        val agents = (0..1).map { call("").copy(id = "a$it", kind = "subAgent") }
        val states = listOf(
            reasoning to "已思考",
            listOf(reasoning.first().copy(outcome = "running")) to "正在思考…",
            listOf(reasoning.first().copy(outcome = "failed")) to "已思考",
            agents to "2 步",
            listOf(agents.first().copy(outcome = "pending")) to "正在召唤子代理…",
            (reasoning + agents) to "4 步",
            listOf(call("").copy(kind = "futureEvent")) to "执行过程",
            emptyList<ConversationItem>() to "",
        )
        states.forEach { (entries, expected) ->
            assertEquals(expected, processGroupCaption(entries))
            check(processGroupSummary(entries))
        }
    }
    @Test fun onlyKnownWebIdentityOverridesGenericSearch() {
        listOf("OpenCode" to "websearch", "Claude Code" to "WebSearch").forEach { (cli, name) ->
            assertEquals(ProcessCategory.WebSearch, processCategory(call(name), cli))
            assertEquals(ProcessCategory.WebSearch, processCategory(call(name, toolKind = "search"), cli))
            assertEquals(ProcessCategory.Read, processCategory(call(name, toolKind = "read"), cli))
        }
        listOf("glob", "grep").forEach { assertEquals(ProcessCategory.Search, processCategory(call(it), "OpenCode")) }
        listOf("grep", "find").forEach { assertEquals(ProcessCategory.Search, processCategory(call(it), "pi")) }
        assertEquals(ProcessCategory.WebSearch, processCategory(call("", "webSearch")))
        assertEquals(ProcessCategory.Browse, processCategory(call("webfetch"), "OpenCode"))
        assertEquals(ProcessCategory.Tool, processCategory(call("WebSearch", "mcpToolCall", "search"), "Claude Code"))
        listOf("WebSearch", "web_search", "mcp__server__WebSearch", "custom_websearch").forEach { name ->
            assertEquals(ProcessCategory.Tool, processCategory(call(name), "unknown"))
            assertEquals(ProcessCategory.Search, processCategory(call(name, toolKind = "search"), "unknown"))
        }
    }
    @Test fun duplicateLabelsDisappearButDistinctContextSurvives() {
        val read = call("read")
        assertEquals("", processEntryContext(read, "pi"))
        assertEquals("src/Main.kt", processEntryContext(read.copy(text = "read · src/Main.kt"), "pi"))
        assertEquals("readiness report", processEntryContext(read.copy(text = "readiness report"), "pi"))
        assertEquals("", processEntryContext(call("NativeTool", "dynamicToolCall").copy(text = "工具"), "Codex"))
        assertEquals("", processEntryContext(call("NativeTool", "mcpToolCall").copy(text = " · "), "Codex"))
        assertEquals("server", processEntryContext(call("Read", "mcpToolCall").copy(text = "server · Read"), "Claude Code"))
        assertEquals("cats", processEntryContext(call("", "webSearch").copy(text = "搜索 · cats"), "Codex"))
    }
    @Test fun codexDynamicIdentitySurvivesOnlyItsOwnUpdates() {
        val first = CodexProjection.item(obj("id" to "dynamic", "type" to "dynamicToolCall", "tool" to "Custom/LongMixedCaseTool", "status" to "inProgress"))
        val update = obj("id" to "dynamic", "type" to "dynamicToolCall", "status" to "completed")
        assertEquals("Custom/LongMixedCaseTool", first.toolName)
        assertEquals(first.toolName, CodexProjection.item(update, first).toolName)
        assertEquals("", CodexProjection.item(update, first.copy(id = "other")).toolName)
        assertEquals("", CodexProjection.item(update, first.copy(kind = "mcpToolCall")).toolName)
    }
}
