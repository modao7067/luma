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
        assertEquals("8 次 shell、read、glob、websearch · 2 次 思考 · 1 次 失败", processGroupCaption(entries, "OpenCode"))
        assertEquals(listOf("shell", "read", "glob", "websearch", "read", "glob", "websearch", "read", "Reasoning", "Reasoning"), entries.map { processEntryLabel(it, "OpenCode") })
        assertEquals("3 次 Read、read、mcp__server__WebSearch", processGroupCaption(listOf(call("Read"), call("read"), call("mcp__server__WebSearch")), "Claude Code"))
    }
    @Test fun updatesAreOneCallAndNonCallsAreNotToolInvocations() {
        val first = call("read").copy(outcome = "running", turnId = "t1")
        val done = first.copy(outcome = "failed")
        val otherTurn = first.copy(turnId = "t2", outcome = "succeeded")
        val events = listOf("reasoning", "compaction", "subAgentActivity", "futureEvent").map { first.copy(id = it, kind = it, outcome = "succeeded") }
        assertEquals("2 次 read · 1 次 思考 · 1 次 失败 · 1 次 子代理", processGroupCaption(listOf(first, done, otherTurn) + events, "pi"))
        assertEquals(listOf(done, otherTurn), processToolInvocations(listOf(first, done, otherTurn) + events))
    }
    @Test fun nativeFallbacksUseShellWithoutReplacingRealToolNames() {
        val entries = listOf("commandExecution", "fileChange", "webSearch", "imageView").mapIndexed { i, kind -> call("").copy(id = "$i", kind = kind, text = "cat file", input = "cat file") }
        assertEquals("4 次 shell、fileChange、webSearch、imageView", processGroupCaption(entries))
        assertEquals(listOf("shell", "fileChange", "webSearch", "imageView"), entries.map { processEntryLabel(it, "Codex") })
        assertEquals("Bash", processEntryLabel(entries.first().copy(toolName = "Bash"), "Codex"))
        assertEquals("1 次 Bash", processGroupCaption(listOf(entries.first().copy(toolName = "Bash"))))
        assertEquals("search", processEntryLabel(call("", toolKind = "search"), "Kimi Code"))
        assertEquals("Tool call", processEntryLabel(call(""), "unknown"))
    }
    @Test fun structuredSummaryBoldsLiteralLabelsButNeverCountPrefixesOrGroupSeparators() {
        val actual = processGroupSummary(toolLabelsFixture(), "OpenCode")
        assertEquals(listOf(ProcessSummaryPart("8 次 ", "shell、read、glob、websearch"), ProcessSummaryPart("2 次 ", "思考"), ProcessSummaryPart("1 次 ", "失败")), actual)
        fun check(parts: List<ProcessSummaryPart>) {
            val text = processSummaryText(parts)
            assertEquals(parts.joinToString(" · ") { it.text }, text.text)
            assertEquals(parts.map { it.label }, text.spanStyles.map { text.text.substring(it.start, it.end) })
            var cursor = 0
            parts.forEachIndexed { index, part ->
                if (index > 0) {
                    assertTrue(text.spanStyles.none { it.start < cursor + 3 && it.end > cursor })
                    cursor += 3
                }
                assertTrue(text.spanStyles.none { it.start < cursor + part.prefix.length && it.end > cursor })
                cursor += part.prefix.length + part.label.length
            }
            assertTrue(text.spanStyles.all { it.item.fontWeight == androidx.compose.ui.text.font.FontWeight.SemiBold })
        }
        check(actual)
        val literal = "v2.1 · 8 次 思考/read"
        check(processGroupSummary(listOf(call(literal))))
        assertEquals("1 次 $literal", processGroupCaption(listOf(call(literal))))
        val reasoning = (0..1).map { call("").copy(id = "r$it", kind = "reasoning") }
        val agents = (0..1).map { call("").copy(id = "a$it", kind = "subAgent") }
        val states = listOf(
            reasoning to "2 次 思考",
            listOf(reasoning.first().copy(outcome = "running")) to "思考中",
            listOf(reasoning.first().copy(outcome = "failed")) to "思考",
            agents to "2 次 子代理",
            (reasoning + agents) to "2 次 思考 · 2 次 子代理",
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
