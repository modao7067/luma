package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.agent.OpenCodeProjection
import io.github.modao7067.luma.gui.i18n.LumaI18n
import io.github.modao7067.luma.gui.session.RequestQuestion
import io.github.modao7067.luma.gui.session.UserRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestQuestionActionsTest {
    @Test
    fun indexBasedSelectionSeparatesDuplicateLabelsAndCustomOtherTextForOpenCodeForms() {
        val raw = JSONObject(
            """{"id":"form_dup","fields":[
                {"key":"decision","type":"string","required":true,"custom":true,"options":[{"value":"allow","label":"确认"},{"value":"deny","label":"确认"}]},
                {"key":"tags","type":"multiselect","required":true,"custom":true,"options":[{"value":"v1","label":"同名"},{"value":"v2","label":"同名"}]}
            ]}""",
        )
        val request = OpenCodeProjection.form(raw)

        val secondSelected = formatEnteredQuestionAnswers(
            questions = request.questions,
            selectedIndices = mapOf("decision" to listOf(1), "tags" to listOf(1)),
            freeText = mapOf("tags" to "同名"),
            otherSelected = mapOf("tags" to true),
        )
        val reply = OpenCodeProjection.formReply(raw, secondSelected).getJSONObject("answer")
        assertEquals("deny", reply.getString("decision"))
        assertEquals(listOf("v2", "同名"), (0 until reply.getJSONArray("tags").length()).map { reply.getJSONArray("tags").getString(it) })

        val customSingle = formatEnteredQuestionAnswers(
            questions = request.questions,
            selectedIndices = mapOf("decision" to emptyList(), "tags" to listOf(0)),
            freeText = mapOf("decision" to "确认"),
            otherSelected = mapOf("decision" to true),
        )
        val customReply = OpenCodeProjection.formReply(raw, customSingle).getJSONObject("answer")
        assertEquals("确认", customReply.getString("decision"))
    }

    @Test
    fun plainOptionQuestionsWithoutEncodedValuesContinueEmittingDisplayStrings() {
        val q = RequestQuestion(
            id = "q1",
            header = "路径",
            prompt = "选择一项",
            options = listOf("选项A", "选项B"),
            defaultSelection = listOf("选项B"),
        )
        assertEquals(listOf(1), defaultQuestionIndices(q))
        val defaults = formatEnteredQuestionAnswers(listOf(q), emptyMap(), emptyMap(), emptyMap())
        assertEquals(listOf("选项B"), defaults["q1"])
    }

    @Test
    fun dshEscalationPayloadPromotesJustificationAndSandboxWithoutExposingBareToolName() {
        val rawBody = JSONObject()
            .put("command", "echo test > /workspace/.approval-test.txt")
            .put("description", "Write test file with escalation")
            .put("justification", "Verify sandbox escalation prompt rendering.")
            .put("sandbox_permissions", "workspace-write")
            .toString()
        val req = UserRequest(id = "perm-1", title = "bash", body = rawBody)
        try {
            LumaI18n.overrideLanguage = "zh"
            val (zhTitle, zhBody) = parseStructuredApprovalBody(req.title, req.body)
            assertEquals("Write test file with escalation", zhTitle)
            assertEquals("echo test > /workspace/.approval-test.txt", zhBody.codeBlock)
            assertTrue(zhBody.leadText.contains("Verify sandbox escalation prompt rendering."))
            assertTrue(zhBody.leadText.contains("请求沙箱权限：workspace-write"))
            assertEquals("", zhBody.extraCode)
            assertEquals("Write test file with escalation", requestBannerTitle(req))

            LumaI18n.overrideLanguage = "en"
            val (enTitle, enBody) = parseStructuredApprovalBody(req.title, req.body)
            assertEquals("Write test file with escalation", enTitle)
            assertTrue(enBody.leadText.contains("Requested sandbox: workspace-write"))
            assertEquals("", enBody.extraCode)
        } finally {
            LumaI18n.resetForTest()
        }
    }

    @Test
    fun kimiApprovalChoicesAndDiffPayloadNormalizeForCompactLayoutAndFileView() {
        try {
            LumaI18n.overrideLanguage = "zh"
            assertEquals("允许一次", normalizeChoiceDisplayLabel("Approve once"))
            assertEquals("本会话允许", normalizeChoiceDisplayLabel("Approve for this session"))
            assertEquals("拒绝", normalizeChoiceDisplayLabel("Reject"))

            LumaI18n.overrideLanguage = "en"
            assertEquals("Allow once", normalizeChoiceDisplayLabel("Approve once"))
            assertEquals("Allow for session", normalizeChoiceDisplayLabel("Approve for this session"))
            assertEquals("Reject", normalizeChoiceDisplayLabel("Reject"))

            val diffBody = """{"path":"/tmp/a.py"}
--- /tmp/a.py
+++ /tmp/a.py
@@ -1 +1 @@
-old
+new"""
            val (title, parsed) = parseStructuredApprovalBody("Edit", diffBody)
            assertEquals("file", parsed.kindHint)
            assertEquals("/tmp/a.py", parsed.codeBlock)
            assertTrue(title.contains("a.py"))
            assertTrue(parsed.extraCode.contains("-old"))
            assertTrue(parsed.extraCode.contains("+new"))
        } finally {
            LumaI18n.resetForTest()
        }
    }
}
