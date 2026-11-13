package io.github.modao7067.luma.gui.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.modao7067.luma.gui.LumaTheme

private val pipelineFixture = """
    # 快照解析，文字逐帧显现

    已经显示的 **文字** 可以选择，`code` 与 [链接](https://example.invalid) 保持原样。

    1. 第一项
    2. 第二项与中文 🦋

    | 类型 | 状态 |
    |---|---|
    | 静态缓存 | 有界 |
    | 流式解析 | 串行合并 |

    ```kotlin
    val message = "快照，而不是逐帧子串"
    println(message)
    ```

    $$
    E = mc^2
    $$
""".trimIndent()

private val handoverConversationMarkdown = buildString {
    appendLine("下面是一份可以直接复制发给另一个模型的交接说明：")
    appendLine()
    appendLine("---")
    appendLine()
    appendLine("```markdown")
    appendLine("# 任务交接：优化 Luma GUI 模型选择器")
    appendLine()
    appendLine("## 1. 背景与现状")
    appendLine("工作目录：`/workspace/luma`")
    appendLine()
    appendLine("当前模型弹出菜单 `ModelMenu` 无条件固定渲染三行：`模型`、`思考强度`、`速度`：")
    appendLine("- UI 入口：`apps/gui/app/src/main/java/io/github/modao7067/luma/gui/ui/ModelMenu.kt`（第 44–111 行）")
    appendLine("  ```kotlin")
    appendLine("  listOf(\"模型\" to (model?.label ?: view.summary.model),")
    appendLine("      \"思考强度\" to view.effort, \"速度\" to view.speed)")
    appendLine("  ```")
    appendLine("- 数据契约：`CockpitContract.kt`")
    appendLine("  - 第 9 行：`ModelOption(..., val speeds: List<RequestChoice> = emptyList())`")
    appendLine("  - 第 80 行：`SessionView(..., val speed: String = \"\")`")
    appendLine("- 构建与覆盖安装流程：")
    appendLine("  ```bash")
    appendLine("  cd /workspace/luma && ./luma assets check")
    appendLine("  ./luma test-ui io.github.modao7067.luma.gui.ui.MenuDropdownOffscreenBenchmark")
    appendLine("  ```")
    appendLine("```")
    appendLine()
    appendLine("复制上面整段即可在新会话中直接开干。")
}.trimEnd()

@Preview(name = "Markdown snapshot · full", widthDp = 393, heightDp = 900)
@Composable
internal fun MarkdownPipelineFullPreview() = PipelineFrame(pipelineFixture, Int.MAX_VALUE)

@Preview(name = "Markdown actual text prefix", widthDp = 320, heightDp = 220)
@Composable
internal fun MarkdownPipelinePrefixPreview() = PipelineFrame("已显示文字 SECRET_FUTURE", 6)

@Preview(name = "Markdown Card · Collapsed (Scheme 2) Light", widthDp = 393, heightDp = 420)
@Composable
internal fun MarkdownCardCollapsedLightPreview() = MarkdownCardSample(darkTheme = false, initiallyExpanded = false)

@Preview(name = "Markdown Card · Expanded (Scheme 1) Light", widthDp = 393, heightDp = 920)
@Composable
internal fun MarkdownCardExpandedLightPreview() = MarkdownCardSample(darkTheme = false, initiallyExpanded = true)

@Preview(name = "Markdown Card · Expanded (Scheme 1) Dark", widthDp = 393, heightDp = 920)
@Composable
internal fun MarkdownCardExpandedDarkPreview() = MarkdownCardSample(darkTheme = true, initiallyExpanded = true)

@Composable
private fun MarkdownCardSample(darkTheme: Boolean, initiallyExpanded: Boolean) = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = darkTheme) {
    SessionTypography { ConversationTypography {
        CompositionLocalProvider(LocalMarkdownCardInitiallyExpanded provides initiallyExpanded) {
            Surface(color = MaterialTheme.colorScheme.background) {
                val document = remember { MarkdownDocumentParser().parse(handoverConversationMarkdown, true) }
                Column(Modifier.fillMaxSize().padding(18.dp)) {
                    NativeMarkdown(handoverConversationMarkdown, presentation = MarkdownPresentation(document, Int.MAX_VALUE))
                }
            }
        }
    } }
}

@Composable
private fun PipelineFrame(source: String, budget: Int) = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = true) {
    SessionTypography { ConversationTypography {
        Surface(color = MaterialTheme.colorScheme.background) {
            val document = remember(source) { MarkdownDocumentParser().parse(source, true) }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
                NativeMarkdown(source, presentation = MarkdownPresentation(document, budget))
            }
        }
    } }
}
