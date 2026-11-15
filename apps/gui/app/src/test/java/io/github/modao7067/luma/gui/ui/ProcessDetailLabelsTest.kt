package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.i18n.LumaI18n
import io.github.modao7067.luma.gui.session.ConversationItem
import io.github.modao7067.luma.gui.session.SubAgentState
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class ProcessDetailLabelsTest {
    private fun withLocale(locale: Locale, block: () -> Unit) {
        val savedLocale = Locale.getDefault()
        val savedOverride = LumaI18n.overrideLanguage
        val savedProp = System.getProperty("luma.lang")
        try {
            System.clearProperty("luma.lang")
            Locale.setDefault(locale)
            LumaI18n.resetForTest()
            block()
        } finally {
            Locale.setDefault(savedLocale)
            if (savedProp == null) System.clearProperty("luma.lang") else System.setProperty("luma.lang", savedProp)
            LumaI18n.resetForTest()
            LumaI18n.overrideLanguage = savedOverride
        }
    }

    @Test fun generatedCaptionsUseEnglishSentenceCaseWithoutChangingNativeContent() {
        fun item(kind: String, outcome: String = "succeeded") = ConversationItem("synthetic", "tool", kind = kind, outcome = outcome)
        val reasoning = item("reasoning")
        val native = item("tool").copy(toolName = "ReadJSON_URL_v2", input = "{\"参数\":\"用户原文\"}", detail = "输出原文")
        val diff = item("fileChange")
        val activity = item("subAgentActivity").copy(id = "activity-1", turnId = "turn-1")
        val activities = listOf(activity, activity.copy(id = "activity-2"), activity.copy(detail = "updated"))
        val wait = item("collabAgentToolCall").copy(id = "wait-1", toolName = "wait")
        val mixed = listOf(activity, reasoning, wait, wait.copy(id = "wait-2"))
        val ownedMixed = mixed.map { it.copy(turnId = "turn-1") }
        val agent = SubAgentState("child-1", "Ptolemy", "explorer", "turn-1", "wait-1", "Synthetic task", "running", "Synthetic result")
        val rows = conversationRows(ownedMixed, listOf(agent, agent.copy(status = "succeeded", detail = "Latest result")))
        val merged = processGroupEntries(rows.single())
        val projected = merged.single { it.kind == "subAgent" }
        val screenshotRows = listOf(reasoning) + activities + projected + projected.copy(detail = "status update")
        val entity = item("subAgent")

        withLocale(Locale.CHINA) {
            assertEquals("Reasoning", processEntryLabel(reasoning, "Codex"))
            assertEquals("Thinking", processEntryLabel(reasoning.copy(outcome = "running"), "Codex"))
            assertEquals("Subagent activity", processEntryLabel(item("subAgentActivity"), "Codex"))
            assertEquals("Compaction", processEntryLabel(item("compaction"), "OpenCode"))
            assertEquals("Tool call", processEntryLabel(item("tool"), "unknown"))
            assertEquals("Tool event", processEntryLabel(item(""), "Codex"))
            assertEquals("Error", processEntryLabel(item("", "failed"), "OpenCode"))
            assertEquals("ReadJSON_URL_v2", processEntryLabel(native, "Claude Code"))
            assertEquals("Parameters", processInputCaption(native, "Claude Code"))
            assertEquals("Output", processOutputCaption(native))
            assertEquals("Error", processOutputCaption(native.copy(outcome = "failed")))
            assertEquals("Diff", processOutputCaption(diff))
            assertEquals("diff", processOutputLanguage(diff))
            assertEquals("shell", processInputLanguage(item("commandExecution"), "Codex"))
            assertEquals("shell", processEntryLabel(item("commandExecution"), "Codex"))
            assertEquals("Parameters", processInputCaption(item("commandExecution"), "Codex"))
            assertEquals("", processEntryContext(item("subAgentActivity").copy(text = "子代理活动"), "Codex"))
            assertEquals("", processEntryContext(item("compaction").copy(text = "上下文压缩"), "OpenCode"))
            assertEquals("Spawn subagent", processEntryContext(item("collabAgentToolCall").copy(toolName = "spawnAgent", text = "分派子代理"), "Codex"))
            assertEquals("用户自定义中文标题", processEntryContext(native.copy(text = "用户自定义中文标题"), "Claude Code"))
            assertEquals("{\"参数\":\"用户原文\"}", native.input)
            assertEquals("输出原文", native.detail)
            val activityParts = processGroupSummary(activities)
            assertEquals(listOf(ProcessSummaryPart("2 次 ", "子代理")), activityParts)
            val activityText = processSummaryText(activityParts)
            assertEquals("2 次 子代理", activityText.text)
            assertEquals(listOf("子代理"), activityText.spanStyles.map { activityText.text.substring(it.start, it.end) })
            assertTrue(processToolInvocations(activities).isEmpty())
            assertEquals("3 次 子代理", processGroupCaption(activities + activity.copy(turnId = "turn-2")))
            assertEquals("1 次 shell · 2 次 子代理", processGroupCaption(listOf(item("commandExecution")) + activities))
            assertEquals("2 次 wait · 1 次 思考 · 1 次 子代理", processGroupCaption(mixed))
            assertEquals(2, processToolInvocations(mixed).size)
            val mixedText = processSummaryText(processGroupSummary(mixed))
            assertEquals(listOf("wait", "思考", "子代理"), mixedText.spanStyles.map { mixedText.text.substring(it.start, it.end) })
            assertEquals(1, rows.size)
            assertEquals(1, rows.single().agents.size)
            assertEquals("2 次 wait · 1 次 思考 · 2 次 子代理", processGroupCaption(merged))
            assertEquals(2, processToolInvocations(merged).size)
            assertEquals("subagent:child-1", projected.id)
            assertEquals("Ptolemy", projected.text)
            assertEquals("succeeded", projected.outcome)
            assertEquals("Latest result", projected.detail)
            assertEquals("Synthetic task", projected.input)
            assertEquals("child-1", projected.toolKind)
            assertEquals(projected.id, subAgentProcessItem(agent.copy(status = "closed")).id)
            val screenshotParts = processGroupSummary(screenshotRows)
            assertEquals("1 次 思考 · 3 次 子代理", processGroupCaption(screenshotRows))
            assertEquals(listOf(ProcessSummaryPart("1 次 ", "思考"), ProcessSummaryPart("3 次 ", "子代理")), screenshotParts)
            val screenshotText = processSummaryText(screenshotParts)
            assertEquals(listOf("思考", "子代理"), screenshotText.spanStyles.map { screenshotText.text.substring(it.start, it.end) })
            assertTrue(screenshotText.spanStyles.all { it.item.fontWeight == androidx.compose.ui.text.font.FontWeight.SemiBold })
            assertTrue(processToolInvocations(screenshotRows).isEmpty())
            assertEquals("1 次 子代理", processGroupCaption(listOf(projected, projected.copy(outcome = "running"))))
            val split = listOf(ownedMixed[2], item("assistant").copy(id = "answer", role = "assistant", turnId = "turn-1"), ownedMixed[3])
            val exactRows = conversationRows(split, listOf(agent))
            assertEquals(listOf("process:wait-1", "message:answer", "process:wait-2"), exactRows.map { it.id })
            assertEquals(1, exactRows.first().agents.size)
            assertTrue(exactRows.last().agents.isEmpty())
            val ambiguous = conversationRows(split, listOf(agent.copy(itemId = "missing")))
            assertTrue(ambiguous.filter { it.entries.isNotEmpty() }.all { it.agents.isEmpty() })
            assertEquals(1, ambiguous.sumOf { it.agents.size })
            val otherTurn = conversationRows(ownedMixed, listOf(agent.copy(turnId = "turn-2")))
            assertTrue(otherTurn.first().agents.isEmpty())
            assertEquals("1 次 子代理", processGroupCaption(listOf(entity)))
            assertEquals("1 次 思考", processGroupCaption(listOf(reasoning)))
            assertEquals(listOf("shell、read、glob、websearch", "思考", "失败"), processGroupSummary(toolLabelsFixture(), "OpenCode").map { it.label })
        }

        withLocale(Locale.US) {
            assertEquals("2× sub-agent", processGroupCaption(activities))
            assertEquals("2× wait · 1× thought · 2× sub-agent", processGroupCaption(merged))
            assertEquals(listOf("shell, read, glob, websearch", "thought", "failed"), processGroupSummary(toolLabelsFixture(), "OpenCode").map { it.label })
        }
    }

    @Test fun antigravitySwarmToolContextsCleanUpAcpTitlesInZhAndEn() {
        fun swarmItem(toolName: String, text: String, input: String) = ConversationItem(
            id = "call-$toolName",
            role = "tool",
            text = text,
            input = input,
            kind = "tool",
            toolName = toolName,
            toolKind = "other",
            outcome = "succeeded",
        )
        withLocale(Locale.CHINA) {
            assertEquals("添加 reviewer", processEntryContext(swarmItem("swarm", "Run swarm", """{"action":"add","name":"reviewer","brief":"audit"}"""), "Antigravity"))
            assertEquals("添加 batch ×2", processEntryContext(swarmItem("swarm", "swarm", """{"action":"add","name":"batch","tasks":["a.py","b.py"]}"""), "Antigravity"))
            assertEquals("查看状态", processEntryContext(swarmItem("swarm", "Run swarm", """{"action":"status"}"""), "Antigravity"))
            assertEquals("切换模型 · reviewer · gemini-3.8-flash-low", processEntryContext(swarmItem("swarm", "Run swarm", """{"action":"model","name":"reviewer","model":"gemini-3.8-flash-low"}"""), "Antigravity"))
            assertEquals("结束 reviewer", processEntryContext(swarmItem("swarm", "Run swarm", """{"action":"kill","name":"reviewer"}"""), "Antigravity"))
            assertEquals("标题 · Alpha", processEntryContext(swarmItem("swarm", "Run swarm", """{"action":"title","title":"Alpha"}"""), "Antigravity"))
            assertEquals("@reviewer please inspect", processEntryContext(swarmItem("post_message", "Run post_message", """{"text":"@reviewer  please\ninspect"}"""), "Antigravity"))
            assertEquals("since #3", processEntryContext(swarmItem("read_board", "Run read_board", """{"since":3}"""), "Antigravity"))
            assertEquals("成员状态", processEntryContext(swarmItem("read_board", "Run read_board", """{"status":true}"""), "Antigravity"))
            assertEquals("since #4 · 全部消息", processEntryContext(swarmItem("swarm_wait", "Run swarm_wait", """{"since":4,"mentions_only":false}"""), "Antigravity"))
            assertEquals("", processEntryContext(swarmItem("swarm", "Run swarm", ""), "Antigravity"))
        }
        withLocale(Locale.US) {
            assertEquals("Add reviewer", processEntryContext(swarmItem("swarm", "Run swarm", """{"action":"add","name":"reviewer"}"""), "Antigravity"))
            assertEquals("Add batch ×2", processEntryContext(swarmItem("swarm", "swarm", """{"action":"add","name":"batch","n":2}"""), "Antigravity"))
            assertEquals("Status", processEntryContext(swarmItem("swarm", "Run swarm", """{"action":"status"}"""), "Antigravity"))
            assertEquals("Switch model · reviewer · gemini-3.8-flash-low", processEntryContext(swarmItem("swarm", "Run swarm", """{"action":"model","name":"reviewer","model":"gemini-3.8-flash-low"}"""), "Antigravity"))
            assertEquals("Kill reviewer", processEntryContext(swarmItem("swarm", "Run swarm", """{"action":"kill","name":"reviewer"}"""), "Antigravity"))
            assertEquals("Title · Alpha", processEntryContext(swarmItem("swarm", "Run swarm", """{"action":"title","title":"Alpha"}"""), "Antigravity"))
            assertEquals("@reviewer please inspect", processEntryContext(swarmItem("post_message", "Run post_message", """{"text":"@reviewer  please\ninspect"}"""), "Antigravity"))
            assertEquals("since #3", processEntryContext(swarmItem("read_board", "Run read_board", """{"since":3}"""), "Antigravity"))
            assertEquals("Roster status", processEntryContext(swarmItem("read_board", "Run read_board", """{"status":true}"""), "Antigravity"))
            assertEquals("since #4 · all messages", processEntryContext(swarmItem("swarm_wait", "Run swarm_wait", """{"since":4,"mentions_only":false}"""), "Antigravity"))
            assertEquals("", processEntryContext(swarmItem("swarm", "Run swarm", ""), "Antigravity"))
        }
    }
}
