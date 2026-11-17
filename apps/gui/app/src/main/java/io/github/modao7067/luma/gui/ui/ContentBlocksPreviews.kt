package io.github.modao7067.luma.gui.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.modao7067.luma.gui.LumaTheme
import io.github.modao7067.luma.gui.session.ConversationItem
import io.github.modao7067.luma.gui.session.CommandAction
import io.github.modao7067.luma.gui.session.MediaResource

internal val contentBlockEditFixture = listOf(
    ConversationItem(
        "edit-title", "tool", kind = "tool", toolName = "edit", outcome = "succeeded",
        input = """{"path":"/project/src/Message.kt","oldString":"old","newString":"new"}""",
        detail = "--- a/Message.kt\n+++ b/Message.kt\n@@ -28,3 +28,3 @@\n fun title() {\n-    renderCard(message)\n+    renderMessage(message)\n }\n@@ -82,2 +82,3 @@\n val spacing = 12.dp\n+val shape = MessageShape(spacing)\n renderBody()",
    ),
    ConversationItem(
        "edit-theme", "tool", kind = "tool", toolName = "edit", outcome = "succeeded",
        input = """{"path":"/project/src/Theme.kt","oldString":"14.dp","newString":"12.dp"}""",
        detail = "@@ -12,1 +12,1 @@\n-val radius = 14.dp\n+val radius = 12.dp",
    ),
)

internal fun contentBlockReadFixture(id: String, file: String, start: Int = 1) = ConversationItem(
    id, "tool", kind = "tool", toolName = "read", outcome = "succeeded",
    input = """{"path":"/project/src/$file","offset":$start,"limit":4}""",
    detail = "${start}: data class ${file.substringBefore('.')}(\n${start + 1}:     val title: String,\n${start + 2}:     val body: String,\n${start + 3}: )",
)

internal val contentBlockDocumentFixture = """
    # 消息内容块

    保留信息层级，让内容先于容器。

    | 内容 | 保留 |
    |---|---|
    | diff | 增删与行号 |
    | 文件 | 路径与范围 |

    > 展开后仍是同一个对象。
""".trimIndent()

@Composable
private fun ContentBlocksFixture(dark: Boolean) = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = dark) {
    SessionTypography { ConversationTypography {
        CompositionLocalProvider(LocalProcessDotsPhase provides 0f) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    CodeBlock("val spacing = 12.dp", "kotlin")
                    Column(Modifier.padding(start = 26.dp)) {
                        ProcessEditCardGroup(contentBlockEditFixture, cli = "OpenCode", entryState = rememberSaveableStateHolder(),
                            initiallyOpenItem = "edit-title", previewDetailClosing = false, previewDetailFraction = 1f)
                    }
                    ProcessGroup(listOf(contentBlockReadFixture("single", "Message.kt", 28)), cli = "OpenCode",
                        initiallyExpanded = true, initiallyOpenItem = "single", previewDetailFraction = 1f, previewGroupFraction = 1f)
                    ProcessGroup(listOf(contentBlockReadFixture("batch-a", "Message.kt"), contentBlockReadFixture("batch-b", "Theme.kt")),
                        cli = "OpenCode", initiallyExpanded = true, initiallyOpenItem = "batch-a", previewDetailFraction = 1f, previewGroupFraction = 1f)
                    MarkdownDocumentCard(contentBlockDocumentFixture)
                }
            }
        }
    } }
}

@Preview(name = "Content blocks · light", widthDp = 393, heightDp = 1100)
@Composable internal fun ContentBlocksLightPreview() = ContentBlocksFixture(false)

@Preview(name = "Codex command reads", widthDp = 393, heightDp = 900)
@Composable internal fun CodexCommandReadsPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) {
    SessionTypography { ConversationTypography {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                fun read(id: String, file: String, start: Int): ConversationItem {
                    val command = "sed -n '$start,${start + 3}p' src/$file"
                    return contentBlockReadFixture(id, file, start).copy(kind = "commandExecution", toolName = "", text = command, input = command,
                        detail = "data class ${file.substringBefore('.')}(\n    val title: String,\n    val body: String,\n)", commandCwd = "/project",
                        commandActions = listOf(CommandAction("read", command, "src/$file", file)))
                }
                ProcessGroup(listOf(read("codex-single", "Message.kt", 28)), cli = "Codex", initiallyExpanded = true,
                    initiallyOpenItem = "codex-single", previewDetailFraction = 1f, previewGroupFraction = 1f)
                ProcessGroup(listOf(read("codex-a", "Message.kt", 1), read("codex-b", "Theme.kt", 1)), cli = "Codex", initiallyExpanded = true,
                    initiallyOpenItem = "codex-a", previewDetailFraction = 1f, previewGroupFraction = 1f)
                val edit = ConversationItem("codex-shell-edit", "tool", kind = "commandExecution", outcome = "succeeded",
                    text = "sed -i 's/old/new/g' src/Message.kt", input = "sed -i 's/old/new/g' src/Message.kt", detail = "updated")
                ProcessGroup(listOf(edit), cli = "Codex", initiallyExpanded = true, initiallyOpenItem = edit.id,
                    previewDetailFraction = 1f, previewGroupFraction = 1f)
            }
        }
    } }
}

@Preview(name = "Content blocks · dark", widthDp = 393, heightDp = 1100)
@Composable internal fun ContentBlocksDarkPreview() = ContentBlocksFixture(true)

@Composable
private fun SupportingContentBlocksFixture(dark: Boolean) = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = dark) {
    SessionTypography { ConversationTypography {
        val bitmap = remember { mediaFixtureBitmap() }
        CompositionLocalProvider(LocalMediaFixtureFrame provides MediaFrame(bitmap)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    MarkdownDocumentCard("# 发布记录\n\n统一内容块的层次。\n\n```kotlin\nval spacing = 12.dp\n```", initiallyExpanded = true)
                    NativeMarkdown("| 内容块 | 保留 |\n|---|---|\n| 表格 | 表头与斑马纹 |\n| 引用 | 左侧标记 |\n\n> 引用中的 `inline code` 也沿用同一组底色。\n\n<plugin-card catalog-id=\"github\"></plugin-card>")
                    MediaFileCard(MediaResource("/project/release/app.apk", "app.apk", "application/vnd.android.package-archive", sizeBytes = 41757839))
                    MediaImageCard(MediaResource("palette-fixture.png", "预览图", "image/png"))
                }
            }
        }
    } }
}

@Preview(name = "Supporting content blocks · light", widthDp = 393, heightDp = 1250)
@Composable internal fun SupportingContentBlocksLightPreview() = SupportingContentBlocksFixture(false)

@Preview(name = "Supporting content blocks · dark", widthDp = 393, heightDp = 1250)
@Composable internal fun SupportingContentBlocksDarkPreview() = SupportingContentBlocksFixture(true)

@Preview(name = "Codex wrapped composite reads", widthDp = 393, heightDp = 700)
@Composable internal fun CodexCompositeReadsPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) {
    SessionTypography { ConversationTypography {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                val file = "CodexContractTest.kt"
                val headCommand = "/bin/bash -lc \"cat src/$file | head -n 135\""
                val head = ConversationItem("head-read", "tool", kind = "commandExecution", outcome = "succeeded", text = headCommand,
                    input = headCommand, detail = "package sample\n\nclass CodexContractTest {\n    val input = \"hello\"\n}", commandCwd = "/project",
                    commandActions = listOf(CommandAction("read", "cat src/$file | head -n 135", "src/$file", file)))
                ProcessGroup(listOf(head), cli = "Codex", initiallyExpanded = true, initiallyOpenItem = head.id,
                    previewDetailFraction = 1f, previewGroupFraction = 1f)
                val second = "ContentBlocksInteractionTest.kt"
                val composite = head.copy(id = "composite-read", input = "/bin/bash -lc \"cat src/$file | head -n 135; cat src/$second | head -n190\"",
                    text = "cat src/$file | head -n 135; cat src/$second | head -n190", detail = head.detail + "\n\npackage sample\n\nclass ContentBlocksInteractionTest {\n    val width = 350.dp\n}",
                    commandActions = head.commandActions + CommandAction("read", "cat src/$second | head -n190", "src/$second", second))
                ProcessGroup(listOf(composite), cli = "Codex", initiallyExpanded = true, initiallyOpenItem = composite.id,
                    previewDetailFraction = 1f, previewGroupFraction = 1f)
            }
        }
    } }
}

@Preview(name = "Content blocks · narrow large text", widthDp = 320, heightDp = 1400, fontScale = 1.3f)
@Composable internal fun ContentBlocksNarrowPreview() = ContentBlocksFixture(false)

@Preview(name = "Document controls · narrow large text", widthDp = 320, heightDp = 820, fontScale = 1.3f)
@Composable internal fun DocumentControlsNarrowPreview() = LumaTheme(seedArgb = 0xFF6750A4.toInt(), darkTheme = false) {
    SessionTypography { ConversationTypography {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
                MarkdownDocumentCard(contentBlockDocumentFixture, initiallyExpanded = true)
            }
        }
    } }
}
