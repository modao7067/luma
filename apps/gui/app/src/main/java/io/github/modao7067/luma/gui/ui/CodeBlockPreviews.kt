package io.github.modao7067.luma.gui.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import io.github.modao7067.luma.gui.LumaTheme
import io.github.modao7067.luma.gui.session.ConversationItem

internal data class CodeViewportFrame(val vertical: Int, val horizontal: Int)
internal class CodeViewportFrameProvider : PreviewParameterProvider<CodeViewportFrame> {
    override val values = sequenceOf(CodeViewportFrame(0, 0), CodeViewportFrame(240, 0), CodeViewportFrame(240, 160))
}
private val viewportCode = (1..28).joinToString("\n") { index ->
    "line ${index.toString().padStart(2, '0')}: val result = runtime.resolve(\"/workspace/source-${index}.kt\")"
}

private val syntaxKotlinSample = """
@Composable
fun HighlightCard(item: RenderItem, count: Int = 3) {
    val label = "step-${'$'}count: ${'$'}{item.title}\n"
    Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Text(label, maxLines = 1)
    }
}
""".trimIndent()

private val syntaxShellSample = """
ENV=prod git status -sb && ./gradlew --offline :apps:gui:testDebugUnitTest
python3 tools/check.py --output "${'$'}REPORT_DIR/out.json" # verify
""".trimIndent()

private val syntaxJsonSample = """
{
  "target": "https://example.test/api\n",
  "retries": 3,
  "enabled": true
}
""".trimIndent()

private val syntaxDiffFixture = listOf(
    ConversationItem(
        "syntax-diff-kt",
        "tool",
        kind = "tool",
        toolName = "edit",
        outcome = "succeeded",
        input = """{"path":"/project/src/Theme.kt","oldString":"old","newString":"new"}""",
        detail = "@@ -12,3 +12,4 @@\n fun applyTheme(scheme: CodePalette) {\n-    val accent = \"none\"\n+    val accent = \"clay-\${scheme.keyword}\"\n+    Modifier.padding(horizontal = 16.dp)\n }",
    ),
)

private val syntaxCommandFixture = listOf(
    ConversationItem(
        "syntax-cmd-exec",
        "tool",
        kind = "commandExecution",
        outcome = "succeeded",
        text = "python3 tools/verify.py --target /workspace/app/modules/feature/presentation/adaptive --offline",
        input = "python3 tools/verify.py --target /workspace/app/modules/feature/presentation/adaptive --offline",
        detail = "PASS static checks\n" + "verified-module=/workspace/app/modules/feature/presentation/render/adaptive/CodeBlockLayout.kt status=ok",
    ),
)

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
                    softWrap = false,
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

@Composable
private fun CodeSyntaxShowcase(dark: Boolean) = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = dark) {
    SessionTypography {
        ConversationTypography {
            CompositionLocalProvider(LocalProcessDotsPhase provides 0f) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CodeBlock(syntaxKotlinSample, "kotlin")
                        CodeBlock(syntaxShellSample, "bash")
                        CodeBlock(syntaxJsonSample, "json")
                        ProcessGroup(
                            syntaxCommandFixture,
                            cli = "Codex",
                            initiallyExpanded = true,
                            initiallyOpenItem = "syntax-cmd-exec",
                            previewDetailFraction = 1f,
                            previewGroupFraction = 1f,
                        )
                        ProcessEditCardGroup(
                            syntaxDiffFixture,
                            cli = "OpenCode",
                            entryState = rememberSaveableStateHolder(),
                            initiallyOpenItem = "syntax-diff-kt",
                            previewDetailClosing = false,
                            previewDetailFraction = 1f,
                        )
                    }
                }
            }
        }
    }
}

@Preview(name = "Syntax highlighting · light", widthDp = 412, heightDp = 1380)
@Composable
internal fun CodeSyntaxLightPreview() = CodeSyntaxShowcase(false)

@Preview(name = "Syntax highlighting · dark", widthDp = 412, heightDp = 1380)
@Composable
internal fun CodeSyntaxDarkPreview() = CodeSyntaxShowcase(true)

@Preview(name = "Syntax & wrap · tablet dark", widthDp = 760, heightDp = 1040)
@Composable
internal fun CodeSyntaxTabletDarkPreview() = CodeSyntaxShowcase(true)
