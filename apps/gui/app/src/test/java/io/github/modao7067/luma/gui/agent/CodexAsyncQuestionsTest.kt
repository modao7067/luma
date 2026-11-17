package io.github.modao7067.luma.gui.agent

import io.github.modao7067.luma.gui.session.*
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class CodexAsyncQuestionsTest {
    private fun item(id: String = "call", count: Int = 2) = CodexProjection.item(obj("id" to id, "type" to "agentMessage", "delivery" to "async",
        "text" to "Fixture questions", "questions" to JSONArray().apply { repeat(count) { put(obj("title" to "Question $it?", "options" to JSONArray(listOf("A", "B")))) } }))
    private fun user(text: String) = CodexProjection.item(obj("id" to "user", "type" to "userMessage", "content" to JSONArray().put(obj("type" to "text", "text" to text))))
    @Test fun nativeAsyncFieldsAndStableZeroBasedIdentitiesSurviveProjection() {
        val item = item("native-call")
        assertEquals("assistant", item.role)
        val q = CodexAsyncQuestions.pending(listOf(item)).single().questions
        assertEquals("[\"request_user_input_async\",\"native-call\",0]", q[0].id)
        assertEquals("[\"request_user_input_async\",\"native-call\",1]", q[1].id)
        assertEquals(listOf("A", "B"), q[0].options); assertEquals(listOf("A"), q[0].defaultSelection)
        assertTrue(q.all { it.allowOther })
        assertTrue(CodexAsyncQuestions.questions(obj("id" to "not-async", "type" to "agentMessage", "questions" to JSONArray())).isEmpty())
    }
    @Test fun partialReplyRetiresOnlyItsExactQuestionInLiveAndRebuiltHistory() {
        val item = item(); val request = CodexAsyncQuestions.pending(listOf(item)).single()
        val text = CodexAsyncQuestions.reply(request, mapOf(request.questions[0].id to listOf("自填 @/not-a-mention")))
        val raw = CodexAsyncQuestions.replies(text).single()
        assertEquals(request.questions[0].id, raw.questionId); assertEquals("Question 0?", raw.question)
        assertEquals(listOf(request.questions[1]), CodexAsyncQuestions.pending(listOf(item, user(text))).single().questions)
        assertTrue(CodexAsyncQuestions.pending(listOf(item, user("Unrelated message"))).isEmpty())
        assertEquals(request.questions, CodexAsyncQuestions.pending(listOf(item, user(text).copy(nativeInputEvidence = false))).single().questions)
        assertEquals(1, CodexAsyncQuestions.pending(listOf(item), listOf(text)).single().questions.size)
        val second = CodexAsyncQuestions.pending(listOf(item, user(text))).single()
        val final = CodexAsyncQuestions.reply(second, mapOf(second.questions.single().id to listOf("B")))
        assertTrue(CodexAsyncQuestions.pending(listOf(item, user(text), user(final))).isEmpty())
        assertTrue(CodexAsyncQuestions.displayReply(text).contains("自填 @/not-a-mention"))
        assertFalse(user(text).text.contains("send_user_message_question_reply")); assertEquals(text, user(text).userContent!!.text)
    }
    @Test fun durableUnknownOrAcceptedAnswerCannotBeSentAgainButRejectedRetryHasNewIdentity() {
        val item = item(count = 1);val request = CodexAsyncQuestions.pending(listOf(item)).single();val q = request.questions.single()
        val first = CodexAsyncQuestions.reply(request, mapOf(q.id to listOf("A")))
        val second = CodexAsyncQuestions.reply(request, mapOf(q.id to listOf("B")))
        val id = CodexAsyncQuestions.submissionId(first, emptyList())
        assertEquals(id, CodexAsyncQuestions.submissionId(first, emptyList()))
        for (state in listOf("local", "pending", "unknown", "accepted")) {
            val old = listOf(CodexAsyncQuestions.Submission(first, state))
            assertTrue(CodexAsyncQuestions.pending(listOf(item), submissions = old).single().answering)
            assertThrows(IllegalStateException::class.java) { CodexAsyncQuestions.submissionId(second, old) }
        }
        val rejected = listOf(CodexAsyncQuestions.Submission(first, "rejected"))
        assertFalse(CodexAsyncQuestions.pending(listOf(item), submissions = rejected).single().answering)
        assertNotEquals(id, CodexAsyncQuestions.submissionId(second, rejected))
        assertThrows(IllegalArgumentException::class.java) { CodexAsyncQuestions.reply(request, mapOf("other-question" to listOf("A"))) }
    }
    @Test fun protocolEnvelopeNeverAppearsInBubbleQueueOrFallbackTitleButWireTextStaysExact() {
        val request = CodexAsyncQuestions.pending(listOf(item(count = 1))).single()
        val text = CodexAsyncQuestions.reply(request, mapOf(request.questions.single().id to listOf("Answer")))
        val record = SessionRecord("local", "", "root", "/work", cli = "Codex")
        SubmissionLedger.begin(record, "answer", externalText = text, busy = true)
        var view = SubmissionLedger.project(record, SessionView(SessionSummary("local", "", "root", "/work")))
        assertFalse(view.queue.single().text.contains("send_user_message_question_reply"));assertEquals(text, record.submissions.single().text)
        SubmissionLedger.rejected(record, "answer")
        view = SubmissionLedger.project(record, SessionView(SessionSummary("local", "", "root", "/work")))
        assertFalse(view.messages.single().text.contains("send_user_message_question_reply"))
        SessionTitlePolicy.firstMessage(record, UserMessageContent(text))
        assertTrue(record.title.contains("Answer"));assertFalse(record.title.contains("send_user_message_question_reply"))
    }
    @Test fun ordinaryEvidencedUserMessageRetiresEarlierAsyncQuestionsWhilePreservingUnconfirmedAndLaterOnes() {
        val first = item("call-1", count = 2)
        val second = item("call-2", count = 1)
        assertTrue(CodexAsyncQuestions.pending(listOf(first, user("Switch topic"))).isEmpty())
        assertEquals(2, CodexAsyncQuestions.pending(listOf(first, user("Pending").copy(nativeInputEvidence = false))).single().questions.size)
        assertEquals(2, CodexAsyncQuestions.pending(listOf(first, user("Pending").copy(delivery = "pending"))).single().questions.size)
        assertEquals(2, CodexAsyncQuestions.pending(listOf(first, user("Rejected").copy(delivery = "rejected"))).single().questions.size)
        assertEquals(2, CodexAsyncQuestions.pending(listOf(first, ConversationItem("tool-1", "tool", "ls"))).single().questions.size)
        val persistedDismissal = CodexAsyncQuestions.pending(listOf(first, second), dismissedItemIds = setOf(first.id))
        assertEquals(listOf("codex-async:call-2"), persistedDismissal.map { it.id })
        val laterQuestion = CodexAsyncQuestions.pending(listOf(first, user("Switch topic"), second))
        assertEquals(listOf("codex-async:call-2"), laterQuestion.map { it.id })
    }
}
