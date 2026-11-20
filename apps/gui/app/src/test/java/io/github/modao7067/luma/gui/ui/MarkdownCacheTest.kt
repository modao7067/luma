package io.github.modao7067.luma.gui.ui

import org.commonmark.parser.Parser
import org.junit.Assert.*
import org.junit.Test

class MarkdownCacheTest {
    private val parser = Parser.builder().build()
    @Test fun literalsCannotMasqueradeAsStructuralMarkersInCacheKeys() {
        assertNotEquals(nodeSignature(parser.parse("A`B`")), nodeSignature(parser.parse("ACB")))
        assertNotEquals(nodeSignature(parser.parse("**bold**")), nodeSignature(parser.parse("B{Tbold}")))
        assertEquals(nodeSignature(parser.parse("[link](path)")), nodeSignature(parser.parse("[link](path)")))
    }
    @Test fun resourceLinksRetainInlineUnitsAndCollectMediaCard() {
        val node = parser.parse("[terminal-emulator](file:///workspace/shared/terminal-emulator)")
        val ast = freezeMarkdownNode(node)
        val images = markdownImages(ast)
        assertEquals(1, images.size)
        assertEquals("file:///workspace/shared/terminal-emulator", images[0].source)
        assertEquals("terminal-emulator".length, ast.children[0].inlineUnits)
    }
    @Test fun directoryMediaResourceShowsFolderSymbolAndLabel() {
        val dir = io.github.modao7067.luma.gui.session.MediaResource("file:///workspace/dir", mimeType = "inode/directory")
        assertTrue(mediaIsDirectory(dir))
        assertEquals(MenuSymbol.FOLDER, mediaFileSymbol(dir))
        assertEquals("文件夹", mediaFormatLabel(dir))
    }
    @Test fun nestedFencedCodeBlocksInsideMarkdownFenceDoNotTruncateOrLeaveEmptyFence() {
        val source = buildString {
            appendLine("下面是一份包含嵌套代码块的示例文档：")
            appendLine()
            appendLine("---")
            appendLine()
            appendLine("```markdown")
            appendLine("# 示例文档：配置模型菜单中的「速度」选项")
            appendLine()
            appendLine("## 1. 背景与现状")
            appendLine("- UI 入口：`apps/gui/app/src/main/java/io/github/modao7067/luma/gui/ui/ModelMenu.kt`")
            appendLine("  ```kotlin")
            appendLine("  listOf(\"模型\" to (model?.label ?: view.summary.model))")
            appendLine("  ```")
            appendLine("- 数据契约：`apps/gui/app/src/main/java/io/github/modao7067/luma/gui/session/CockpitContract.kt`")
            appendLine("- 构建、校验与覆盖安装流程：")
            appendLine("  ```bash")
            appendLine("  cd /workspace/project")
            appendLine("  ./luma assets check")
            appendLine("  ```")
            appendLine("- 说明：示例保留已有菜单选项。")
            append("```")
        }
        val docParser = MarkdownDocumentParser()
        val doc = docParser.parse(source, complete = true)
        assertEquals(listOf(MarkdownKind.PARAGRAPH, MarkdownKind.RULE, MarkdownKind.CODE_BLOCK), doc.blocks.map { it.node.kind })
        val markdownBlock = doc.blocks.last().node
        assertEquals("markdown", markdownBlock.info)
        assertTrue(markdownBlock.literal.contains("```kotlin"))
        assertTrue(markdownBlock.literal.contains("- 数据契约：`apps/gui/app/src/main/java/io/github/modao7067/luma/gui/session/CockpitContract.kt`"))
        assertTrue(markdownBlock.literal.contains("```bash"))
        assertTrue(markdownBlock.literal.endsWith("- 说明：示例保留已有菜单选项。"))
        val midStream = source.substringBefore("- 构建、校验与覆盖安装流程：")
        val partialDoc = docParser.parse(midStream, complete = false)
        assertEquals(listOf(MarkdownKind.PARAGRAPH, MarkdownKind.RULE, MarkdownKind.CODE_BLOCK), partialDoc.blocks.map { it.node.kind })
        assertTrue(partialDoc.blocks.last().node.literal.contains("- 数据契约："))
    }
    @Test fun trailingBlankCodeFenceDoesNotProduceEmptyCodeBlock() {
        val doc = MarkdownDocumentParser().parse("正文段落\n\n```\n", complete = true)
        assertEquals(listOf(MarkdownKind.PARAGRAPH), doc.blocks.map { it.node.kind })
    }
    @Test fun carouselFenceUnwrapsSlidesIntoInlineMarkdownAndImages() {
        val carouselSource = buildString {
            appendLine("````carousel")
            appendLine("![折叠态](/tmp/slide1.png)")
            appendLine("<!-- slide -->")
            appendLine("![展开态](/tmp/slide2.png)")
            appendLine("<!-- slide -->")
            appendLine("```kotlin")
            appendLine("val x = 1")
            appendLine("```")
            appendLine("````")
        }.trimEnd()
        val doc = MarkdownDocumentParser().parse(carouselSource, complete = true)
        assertFalse(doc.blocks.any { it.node.kind == MarkdownKind.CODE_BLOCK && it.node.info == "carousel" })
        val allImages = doc.blocks.flatMap { markdownImages(it.node) }
        assertEquals(listOf("/tmp/slide1.png", "/tmp/slide2.png"), allImages.map { it.source })
        val codeBlocks = doc.blocks.filter { it.node.kind == MarkdownKind.CODE_BLOCK }
        assertEquals(1, codeBlocks.size)
        assertEquals("kotlin", codeBlocks.single().node.info)
        assertEquals("val x = 1", codeBlocks.single().node.literal)
    }
    @Test fun codeBlockSymbolMatchesLanguageAndFileSemantics() {
        assertEquals(MenuSymbol.TERMINAL, codeBlockSymbol("bash"))
        assertEquals(MenuSymbol.TERMINAL, codeBlockSymbol("sh"))
        assertEquals(MenuSymbol.CODE, codeBlockSymbol("kotlin"))
        assertEquals(MenuSymbol.DATA, codeBlockSymbol("json"))
        assertEquals(MenuSymbol.SETTINGS, codeBlockSymbol("yaml"))
        assertEquals(MenuSymbol.DIFF, codeBlockSymbol("diff"))
        assertEquals(MenuSymbol.ARTICLE, codeBlockSymbol("markdown"))
        assertEquals(MenuSymbol.TABLE, codeBlockSymbol("csv"))
        assertEquals(MenuSymbol.TEXT, codeBlockSymbol("text"))
        assertEquals(MenuSymbol.TERMINAL, codeBlockSymbol("", "build.sh"))
        assertEquals(MenuSymbol.ARTICLE, codeBlockSymbol("", "README.md"))
    }
    @Test fun singleTildesDoNotTriggerStrikethroughWhileDoubleTildesStillDo() {
        val parser = MarkdownDocumentParser()
        val singleTilde = parser.parse("手机每行 20~24 字，进入嵌套列表后只有 17~19 字。", complete = true)
        fun hasKind(node: MarkdownAst, kind: MarkdownKind): Boolean =
            node.kind == kind || node.children.any { hasKind(it, kind) }
        assertFalse(singleTilde.blocks.any { hasKind(it.node, MarkdownKind.STRIKE) })
        val doubleTilde = parser.parse("保留 ~~删除内容~~ 结尾", complete = true)
        assertTrue(doubleTilde.blocks.any { hasKind(it.node, MarkdownKind.STRIKE) })
    }
}
