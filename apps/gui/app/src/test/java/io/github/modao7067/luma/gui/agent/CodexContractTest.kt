package io.github.modao7067.luma.gui.agent

import io.github.modao7067.luma.gui.session.ConversationItem
import io.github.modao7067.luma.gui.session.acceptCodexRequest
import io.github.modao7067.luma.gui.session.putCanonicalCodexUserItem
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CodexContractTest {
    @Test fun zeroRequestIdAndStringRequestIdStayDistinct() {
        val p = obj("threadId" to "thread", "turnId" to "turn")
        val number = PendingApproval(0, "item/commandExecution/requestApproval", p)
        val string = PendingApproval("0", number.method, p)
        assertEquals("number:0", number.id)
        assertNotEquals(number.id, string.id)
        assertEquals("accept", number.response("0", emptyMap()).getString("decision"))
    }
    @Test fun nativeRuleDecisionIsNotFlattenedOrExpanded() {
        val rule = obj("acceptWithExecpolicyAmendment" to obj("execpolicy_amendment" to JSONArray(listOf("git", "status"))))
        val approval = PendingApproval(0, "item/commandExecution/requestApproval", obj("availableDecisions" to JSONArray().put("decline").put(rule)))
        assertEquals(2, approval.view().choices.size)
        assertEquals(rule.toString(), approval.response("1", emptyMap()).getJSONObject("decision").toString())
        assertTrue(approval.view().choices[1].label.contains("保存命令规则"))
    }
    @Test fun permissionsGrantOnlyRequestedProfileForCurrentTurn() {
        val requested = obj("fileSystem" to obj("write" to JSONArray(listOf("/owned"))))
        val approval = PendingApproval(3, "item/permissions/requestApproval", obj("permissions" to requested))
        assertEquals(requested.toString(), approval.response("allow", emptyMap()).getJSONObject("permissions").toString())
        assertEquals("turn", approval.response("allow", emptyMap()).getString("scope"))
        assertEquals(0, approval.response("decline", emptyMap()).getJSONObject("permissions").length())
    }
    @Test fun questionsKeepNativeQuestionIdsAndAnswers() {
        val approval = PendingApproval(7, "item/tool/requestUserInput", obj("questions" to JSONArray().put(obj("id" to "choice", "question" to "Which?", "options" to JSONArray().put(obj("label" to "A"))))))
        assertEquals("choice", approval.view().questions.single().id)
        assertEquals("A", approval.response("submit", mapOf("choice" to listOf("A"))).getJSONObject("answers").getJSONObject("choice").getJSONArray("answers").getString(0))
    }
    @Test fun textMentionsAndImagesUseNativeTypes() {
        val input = CodexProjection.inputs("Inspect @\"a b.txt\" and @/work/x @/work/x", listOf("/private/image.png"), "/work")
        assertEquals(listOf("text", "mention", "mention", "localImage"), input.objects().map { it.getString("type") })
        assertEquals("/work/a b.txt", input.getJSONObject(1).getString("path"))
        assertEquals("/private/image.png", input.getJSONObject(3).getString("path"))
    }
    @Test fun toolOutputAndSummaryProjectWithoutHiddenReasoning() {
        val command = CodexProjection.item(obj("id" to "cmd", "type" to "commandExecution", "command" to "pwd", "aggregatedOutput" to "/work\n", "status" to "completed"))
        assertEquals("tool", command.role); assertEquals("/work\n", command.detail)
        val reasoning = CodexProjection.item(obj("id" to "reason", "type" to "reasoning", "summary" to JSONArray(listOf("Public summary")), "content" to JSONArray(listOf("private"))))
        assertEquals("Public summary", reasoning.detail); assertFalse(reasoning.detail.contains("private"))
        val streamed = ConversationItem("reason", "tool", "推理摘要", detail = "Streamed summary", kind = "reasoning", outcome = "running")
        val completedEmptySummary = CodexProjection.item(obj("id" to "reason", "type" to "reasoning", "status" to "completed"), streamed)
        assertEquals("Streamed summary", completedEmptySummary.detail)
    }
    @Test fun commandExitFailureHasExplicitOutcomeAndInput() {
        val item = CodexProjection.item(obj("id" to "bad", "type" to "commandExecution", "status" to "completed", "command" to "false", "exitCode" to 1, "aggregatedOutput" to "failed"))
        assertEquals("commandExecution", item.kind); assertEquals("false", item.input); assertEquals("failed", item.outcome)
    }
    @Test fun mcpToolNameProjectsInHistoryAndSurvivesStatusUpdates() {
        val item = CodexProjection.item(obj("id" to "mcp", "type" to "mcpToolCall", "server" to "native-server", "tool" to "native/custom_tool", "status" to "inProgress"))
        assertEquals("native/custom_tool", item.toolName); assertEquals("mcpToolCall", item.kind)
        assertEquals("native-server · native/custom_tool", item.text); assertEquals("", item.toolKind)
        val completed = CodexProjection.item(obj("id" to "mcp", "type" to "mcpToolCall", "status" to "completed"), item)
        assertEquals("native/custom_tool", completed.toolName); assertEquals("succeeded", completed.outcome)
        val delta = item.copy(detail = item.detail + "output")
        assertEquals(item.toolName, delta.toolName)
    }
    @Test fun missingMcpToolNameStaysEmptyAndCannotLeakFromAnotherItem() {
        val raw = obj("id" to "mcp", "type" to "mcpToolCall", "server" to "read", "arguments" to obj("command" to "cat x"))
        assertEquals("", CodexProjection.item(raw).toolName)
        val other = CodexProjection.item(obj("id" to "other", "type" to "mcpToolCall", "tool" to "bash"))
        assertEquals("", CodexProjection.item(raw, other).toolName)
        val command = CodexProjection.item(obj("id" to "cmd", "type" to "commandExecution", "command" to "cat x"))
        assertEquals("", command.toolName); assertEquals("", command.toolKind)
    }
    @Test fun nativeClientIdCanReconcileUnknownSubmission() {
        assertEquals("sent-id", CodexProjection.clientMessageId(obj("id" to "native-item", "clientId" to "sent-id")))
        assertNull(CodexProjection.clientMessageId(obj("id" to "unrelated")))
    }
    @Test fun echoedClientUserIdentityKeepsOptimisticAndNativeRowStable() {
        val native = obj("id" to "native-item", "clientUserMessageId" to "submission", "type" to "userMessage", "content" to JSONArray().put(obj("type" to "text", "text" to "你好")))
        assertEquals("submission", CodexProjection.item(native).id)
        native.remove("clientUserMessageId")
        assertEquals("native-item", CodexProjection.item(native).id)
    }
    @Test fun imageSupportComesFromModelCapabilities() {
        val models = CodexProjection.models(JSONArray().put(obj("id" to "a", "model" to "text", "inputModalities" to JSONArray(listOf("text")))).put(obj("id" to "b", "model" to "vision", "inputModalities" to JSONArray(listOf("text", "image")))))
        assertFalse(models[0].supportsImages); assertTrue(models[1].supportsImages)
    }
    @Test fun subThreadApprovalsAndThreadlessElicitationAreAcceptedWhileForeignThreadsAreBlocked() {
        val subAgents = CodexSubAgents { "parent-thread" }
        subAgents.item(obj("type" to "collabAgentToolCall", "id" to "c1", "senderThreadId" to "parent-thread", "receiverThreadIds" to JSONArray(listOf("child-thread"))), "t1")
        val parentApproval = PendingApproval(1, "item/commandExecution/requestApproval", obj("threadId" to "parent-thread"))
        val childApproval = PendingApproval(2, "item/commandExecution/requestApproval", obj("threadId" to "child-thread"))
        val elicitation = PendingApproval(3, "mcpServer/elicitation/request", obj("message" to "Confirm", "threadId" to JSONObject.NULL))
        val foreignApproval = PendingApproval(4, "item/commandExecution/requestApproval", obj("threadId" to "foreign-thread"))
        assertTrue(acceptCodexRequest("parent-thread", subAgents, parentApproval))
        assertTrue(acceptCodexRequest("parent-thread", subAgents, childApproval))
        assertTrue(acceptCodexRequest("parent-thread", subAgents, elicitation))
        assertFalse(acceptCodexRequest("parent-thread", subAgents, foreignApproval))
        assertFalse(acceptCodexRequest(null, subAgents, elicitation))
        assertFalse(subAgents.metadata(obj("id" to "child-thread", "parentThreadId" to "someone-else")))
        assertFalse(acceptCodexRequest("parent-thread", subAgents, childApproval))
    }
    @Test fun adjacentRelativeAndDotDotMentionsNormalizeAndDeduplicate() {
        val input = CodexProjection.inputs(
            "See @/tmp/a.txt@/tmp/b.txt @\"sub/../rel.md\" @\"/tmp/../tmp/a.txt\" @\"   \" @\"bad\u0000path\"",
            listOf("/private/image.png"),
            "/work/proj",
        )
        val mentions = input.objects().filter { it.optString("type") == "mention" }
        assertEquals(listOf("/tmp/a.txt", "/tmp/b.txt", "/work/proj/rel.md"), mentions.map { it.getString("path") })
        assertEquals(listOf("a.txt", "b.txt", "rel.md"), mentions.map { it.getString("name") })
        assertEquals("/private/image.png", input.objects().last().getString("path"))
    }
    @Test fun userMessageCanonicalizationDeduplicatesBothDirectionsInPlace() {
        val itemsA = linkedMapOf<String, ConversationItem>()
        val mapA = HashMap<String, String>()
        putCanonicalCodexUserItem(itemsA, mapA, obj("id" to "native-1", "clientId" to "sub-1", "type" to "userMessage", "content" to JSONArray().put(obj("type" to "text", "text" to "hi"))), completed = false, turnId = "t1")
        itemsA["tool-1"] = ConversationItem("tool-1", "tool", "pwd")
        putCanonicalCodexUserItem(itemsA, mapA, obj("id" to "native-1", "type" to "userMessage", "content" to JSONArray().put(obj("type" to "text", "text" to "hi"))), completed = true, turnId = "t1")
        assertEquals(listOf("sub-1", "tool-1"), itemsA.keys.toList())

        val itemsB = linkedMapOf<String, ConversationItem>()
        val mapB = HashMap<String, String>()
        putCanonicalCodexUserItem(itemsB, mapB, obj("id" to "native-2", "type" to "userMessage", "content" to JSONArray().put(obj("type" to "text", "text" to "early"))), completed = false, turnId = "t2")
        itemsB["tool-2"] = ConversationItem("tool-2", "tool", "ls")
        itemsB["asst-2"] = ConversationItem("asst-2", "assistant", "done")
        putCanonicalCodexUserItem(itemsB, mapB, obj("id" to "native-2", "clientUserMessageId" to "sub-2", "type" to "userMessage", "content" to JSONArray().put(obj("type" to "text", "text" to "early"))), completed = true, turnId = "t2")
        assertEquals(listOf("sub-2", "tool-2", "asst-2"), itemsB.keys.toList())
        assertEquals("early", itemsB["sub-2"]?.text)
    }
}
