package io.github.modao7067.luma.gui.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.modao7067.luma.gui.LumaTheme
import io.github.modao7067.luma.gui.agent.CodexAsyncQuestions
import io.github.modao7067.luma.gui.agent.CodexProjection
import io.github.modao7067.luma.gui.agent.obj
import org.json.JSONArray

@Preview(name = "Native async question", widthDp = 393, heightDp = 852)
@Composable internal fun CodexAsyncQuestionPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) {
    val item = CodexProjection.item(obj("type" to "agentMessage", "id" to "native-call", "delivery" to "async", "questions" to JSONArray()
        .put(obj("title" to "本次使用哪条验证路径？", "options" to JSONArray(listOf("先跑隔离验证", "先检查协议差异"))))))
    SessionTypography { RequestDialog(CodexAsyncQuestions.pending(listOf(item)).single(), previewActions) {} }
}
