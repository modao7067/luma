package io.github.modao7067.luma.gui.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import io.github.modao7067.luma.gui.LumaTheme

internal data class CodeViewportFrame(val vertical: Int, val horizontal: Int)
internal class CodeViewportFrameProvider : PreviewParameterProvider<CodeViewportFrame> {
    override val values = sequenceOf(CodeViewportFrame(0, 0), CodeViewportFrame(240, 0), CodeViewportFrame(240, 160))
}
private val viewportCode = (1..28).joinToString("\n") { index ->
    "line ${index.toString().padStart(2, '0')}: val result = runtime.resolve(\"/workspace/source-${index}.kt\")"
}

@Preview(name = "Fixed code shell · two-axis body scroll", widthDp = 393, heightDp = 520)
@Composable
internal fun CodeViewportPreview(@PreviewParameter(CodeViewportFrameProvider::class) frame: CodeViewportFrame) =
    LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = true) { SessionTypography { ConversationTypography {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("终端", style = MaterialTheme.typography.labelLarge)
                CodeBlock(
                    viewportCode,
                    "output",
                    bodyVerticalScrollState = rememberScrollState(frame.vertical),
                    bodyHorizontalScrollState = rememberScrollState(frame.horizontal),
                )
                Text("下一条消息", style = MaterialTheme.typography.bodyLarge)
            }
        }
    } } }

@Preview(name = "Command and Markdown code use the same viewport", widthDp = 393, heightDp = 900)
@Composable
internal fun LongCodePathsPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) { SessionTypography { ConversationTypography {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CodeBlock((1..4).joinToString("\n") { "echo step-$it && ./gradlew :apps:gui:compileDebugKotlin" }, "shell")
            NativeMarkdown("### 长代码\n\n```kotlin\n$viewportCode\n```\n\n代码块之后的正文。")
        }
    }
} } }
