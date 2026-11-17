package io.github.modao7067.luma.gui.agent

import io.github.modao7067.luma.gui.i18n.tr

import io.github.modao7067.luma.gui.session.ConversationItem
import io.github.modao7067.luma.gui.session.RequestQuestion
import io.github.modao7067.luma.gui.session.UserRequest
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object CodexAsyncQuestions {
    private const val open = "<send_user_message_question_reply>"
    private const val close = "</send_user_message_question_reply>"
    data class Reply(val questionId: String, val question: String, val answer: String)
    data class Submission(val text: String, val state: String)

    fun questions(item: JSONObject): List<RequestQuestion> {
        if (item.optString("type") != "agentMessage" || item.optString("delivery") != "async") return emptyList()
        val itemId = item.stringOrNull("id") ?: return emptyList()
        return item.optJSONArray("questions")?.objects()?.mapIndexed { index, q ->
            val options = q.optJSONArray("options")?.strings().orEmpty()
            RequestQuestion(JSONArray(listOf("request_user_input_async", itemId, index)).toString(),
                tr("问题 ${index + 1}", "Question ${index + 1}"), q.getString("title"), options,
                defaultSelection = options.take(1))
        }.orEmpty()
    }

    fun replies(text: String): List<Reply> {
        val raw = text.trim()
        if (!raw.startsWith(open) || !raw.endsWith(close)) return emptyList()
        return try {
            JSONArray(raw.removePrefix(open).removeSuffix(close).trim()).objects().map { row ->
                Reply(row.getString("questionItemId"), row.getString("question"), row.getString("answer"))
            }
        } catch (_: Exception) { emptyList() }
    }

    fun displayReply(text: String): String = replies(text).takeIf { it.isNotEmpty() }
        ?.joinToString("\n\n") { tr("回复：${it.question}\n${it.answer}", "Reply: ${it.question}\n${it.answer}") } ?: text

    fun pending(
        items: Collection<ConversationItem>,
        nativeQueuedTexts: List<String> = emptyList(),
        submissions: List<Submission> = emptyList(),
        dismissedItemIds: Set<String> = emptySet(),
    ): List<UserRequest> {
        val openByItem = linkedMapOf<String, List<RequestQuestion>>()
        for (item in items) {
            if (item.asyncQuestions.isNotEmpty()) {
                if (item.id !in dismissedItemIds) {
                    openByItem[item.id] = item.asyncQuestions
                }
            } else if (item.role == "user" && item.nativeInputEvidence && item.delivery !in setOf("pending", "rejected")) {
                val text = item.userContent?.text ?: item.text
                val replyIds = replies(text).map { it.questionId }.toSet()
                if (replyIds.isNotEmpty()) {
                    val iterator = openByItem.entries.iterator()
                    while (iterator.hasNext()) {
                        val entry = iterator.next()
                        val remaining = entry.value.filter { it.id !in replyIds }
                        if (remaining.isEmpty()) iterator.remove() else entry.setValue(remaining)
                    }
                } else {
                    openByItem.clear()
                }
            }
        }
        val queuedReplyIds = nativeQueuedTexts.flatMap(::replies).map { it.questionId }.toSet()
        val sending = submissions.filter { it.state !in setOf("rejected", "cancelled") }.flatMap { replies(it.text) }.map { it.questionId }.toSet()
        return openByItem.entries.mapNotNull { (itemId, questions) ->
            val remaining = questions.filter { it.id !in queuedReplyIds }
            if (remaining.isEmpty()) null else UserRequest(
                "codex-async:$itemId",
                "Answer",
                "",
                questions = remaining,
                answering = remaining.any { it.id in sending },
            )
        }
    }

    fun reply(request: UserRequest, answers: Map<String, List<String>>): String {
        require(!request.answering) { tr("答复结果尚待确认，不会再次发送", "Reply confirmation is still pending; will not resend") }
        require(answers.isNotEmpty() && answers.keys.all { key -> request.questions.any { it.id == key } }) { tr("问题已答复或不属于此请求", "Question already answered or does not belong to this request") }
        val rows = JSONArray()
        request.questions.filter { it.id in answers }.forEach { q ->
            val answer = answers.getValue(q.id)
            require(answer.size == 1 && answer.single().isNotBlank()) { tr("请选择选项或填写回答", "Select an option or enter a reply") }
            rows.put(obj("answer" to answer.single(), "question" to q.prompt, "questionItemId" to q.id))
        }
        return "$open\n$rows\n$close"
    }

    fun submissionId(text: String, previous: List<Submission>): String {
        val ids = replies(text).map { it.questionId }.toSet()
        require(ids.isNotEmpty()) { tr("答复缺少原生问题标识", "Reply is missing native question ID") }
        val related = previous.filter { old -> replies(old.text).any { it.questionId in ids } }
        check(related.none { it.state !in setOf("rejected", "cancelled") }) { tr("答复已发送，等待原生确认", "Reply already sent; awaiting native confirmation") }
        return UUID.nameUUIDFromBytes(("luma-codex-async\n" + ids.sorted().joinToString("\n") + "\nattempt:${related.size}").toByteArray(Charsets.UTF_8)).toString()
    }
}
