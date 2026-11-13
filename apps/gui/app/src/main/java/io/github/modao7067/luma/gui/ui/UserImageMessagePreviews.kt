package io.github.modao7067.luma.gui.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.modao7067.luma.gui.LumaTheme
import io.github.modao7067.luma.gui.agent.CodexProjection
import io.github.modao7067.luma.gui.agent.obj
import org.json.JSONArray

@Preview(name = "User image body · mixed, image only, literal", widthDp = 393, heightDp = 1010)
@Composable internal fun UserImageMessagePreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) {
    ConversationTypography {
        val bitmap = remember { Bitmap.createBitmap(mediaFixtureBitmap(), 0, 0, 600, 360) }
        CompositionLocalProvider(LocalMediaFixtureFrame provides MediaFrame(bitmap)) {
            MediaViewerHost("fixture", previewActions, MediaContext("fixture", "/tmp")) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Text("图片与正文", style = MaterialTheme.typography.titleLarge)
                        listOf("图文" to "看一下这张图。", "单图" to "", "用户输入的占位符字样" to "[图片]\n[image]")
                            .forEachIndexed { index, (label, body) ->
                                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                val inputs = JSONArray().apply {
                                    if (body.isNotEmpty()) put(obj("type" to "text", "text" to body))
                                    put(obj("type" to "localImage", "path" to "/fixture-$index.png"))
                                }
                                UserMessageBubble(CodexProjection.item(obj("id" to "image-$index", "type" to "userMessage", "content" to inputs)), previewActions)
                            }
                    }
                }
            }
        }
    }
}
