package io.github.modao7067.luma.gui.session

import io.github.modao7067.luma.gui.agent.CodexAsyncQuestions

internal object SessionTitlePolicy {
    const val unnamed = "未命名会话"
    private const val messageLimit = 40
    private val openCodeDefault = Regex("^(New session|Child session) - \\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$")

    fun validNativeTitle(cli: String, title: String?): String? = title?.trim()?.takeIf {
        it.isNotEmpty() && !(cli == "OpenCode" && openCodeDefault.matches(it))
    }

    fun refresh(record: SessionRecord) {
        record.title = record.manualTitle ?: record.nativeTitle ?: record.messageTitle ?: unnamed
    }

    fun nativeTitle(record: SessionRecord, title: String?): Boolean {
        val native = validNativeTitle(record.cli, title) ?: return false
        if (record.nativeTitle == native) return false
        record.nativeTitle = native
        refresh(record)
        return true
    }

    fun rename(record: SessionRecord, title: String) {
        val manual = title.trim()
        require(manual.isNotEmpty()) { "会话标题不能为空" }
        record.manualTitle = manual
        refresh(record)
    }

    fun firstMessage(record: SessionRecord, content: UserMessageContent): Boolean {
        if (record.messageTitle != null) return false
        val source = (if (record.cli == "Codex") CodexAsyncQuestions.displayReply(content.text) else content.text).takeIf { it.isNotBlank() }
            ?: content.attachments.firstOrNull { it.isNotBlank() } ?: return false
        val text = source.replace(Regex("[\\s\\p{Z}\\p{Cc}]+"), " ").trim()
        if (text.isEmpty()) return false
        record.messageTitle = if (text.codePointCount(0, text.length) <= messageLimit) text
            else text.substring(0, text.offsetByCodePoints(0, messageLimit)).trimEnd() + "…"
        refresh(record)
        return true
    }

    fun history(record: SessionRecord, messages: Collection<ConversationItem>, ready: Boolean): Boolean {
        if (!ready || record.messageTitle != null) return false
        fun content(message: ConversationItem) = if (message.id == record.submissionId)
            UserMessageContent(record.submissionText.orEmpty(),
                record.submissionImageData.map { attachment("图片", it.path, it.name) } +
                    record.submissionSkillData.map { attachment("Skill", it.path, it.name) })
            else message.userContent ?: UserMessageContent(message.text, message.images.map { attachment("图片", it) })
        val first = messages.firstOrNull {
            it.role == "user" && it.delivery.isEmpty() && content(it).let { input ->
                input.text.isNotBlank() || input.attachments.any(String::isNotBlank)
            }
        } ?: return false
        if (first.status in setOf("running", "in_progress", "pending")) return false
        return firstMessage(record, content(first))
    }

    fun attachment(type: String, path: String?, name: String? = null): String {
        val filename = name?.takeIf { it.isNotBlank() } ?: path?.takeIf {
            it.startsWith('/') || it.startsWith("file://")
        }?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        return if (filename == null) type else "$type · $filename"
    }
}
