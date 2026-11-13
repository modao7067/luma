package io.github.modao7067.luma.gui.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.modao7067.luma.gui.LumaTheme
import io.github.modao7067.luma.gui.session.ConversationItem

@Composable
private fun UserBubbleGallery(dark: Boolean) = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = dark) {
    ConversationTypography {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf(
                    ConversationItem("single", "user", "早"),
                    ConversationItem("line", "user", "嘿，早安"),
                    ConversationItem("multi", "user", "请检查这段多行消息。\n上方与下方应保留相同的内边距。\n状态不占气泡内部空间。"),
                    ConversationItem("pending", "user", "这条正在发送", delivery = "pending"),
                    ConversationItem("unknown", "user", "这条尚未收到确认", delivery = "unknown"),
                    ConversationItem("rejected", "user", "这条未发送", delivery = "rejected"),
                    ConversationItem("image", "user", "附件预览不可用时", images = listOf("file:///missing-preview.png")),
                    ConversationItem("image-only", "user", images = listOf("file:///missing-preview.png")),
                ).forEach { UserMessageBubble(it, previewActions) }
            }
        }
    }
}

@Preview(name = "User bubbles · compact body and external delivery", widthDp = 393, heightDp = 930)
@Composable internal fun UserMessageBubblePreview() = UserBubbleGallery(dark = false)

@Preview(name = "User bubbles · narrow large type", widthDp = 320, heightDp = 1120, fontScale = 1.3f)
@Composable internal fun UserMessageBubbleNarrowPreview() = UserBubbleGallery(dark = true)
