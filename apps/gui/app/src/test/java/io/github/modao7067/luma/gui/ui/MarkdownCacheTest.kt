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
    @Test fun largeDocumentRetainsSingleSlotRecentCacheEntry() {
        val cache = MarkdownDocumentCache(sourceBudget = 2048, maxEntries = 8, maxSource = 128, nodeBudget = 256, maxNodes = 64)
        val docParser = MarkdownDocumentParser()
        val largeA = "paragraph ".repeat(40)
        val largeB = "another ".repeat(40)
        val parsedA = docParser.parse(largeA, complete = true)
        val parsedB = docParser.parse(largeB, complete = true)
        cache.put(parsedA)
        assertSame(parsedA, cache.get(largeA))
        cache.put(parsedB)
        assertSame(parsedB, cache.get(largeB))
        assertNull(cache.get(largeA))
        assertEquals(2L, cache.usage().rejected)
    }
    @Test fun utf16SafeSlicingNeverSplitsSurrogatePairs() {
        val text = "ab😀cd"
        assertEquals("ab", safeUtf16Prefix(text, 3))
        assertEquals("ab😀", safeUtf16Prefix(text, 4))
        assertEquals(4, safeUtf16SliceStart(text, 3))
    }

    @Test fun chineseNoSpaceFootnotesParseBodyAndRefsWithoutBecomingLinks() {
        val source = buildString {
            appendLine("正文引用了第一条脚注[^1]，以及带名称的第二条[^note]。")
            appendLine()
            appendLine("普通引用链接[官方站点][site]继续正常生效。")
            appendLine()
            appendLine("[site]: https://example.com")
            appendLine("[^note]: 这是第二条无空格中文脚注正文。")
            append("[^1]: 这是第一条无空格中文脚注正文。")
        }
        val doc = MarkdownDocumentParser().parse(source, complete = true)
        fun collect(node: MarkdownAst, kind: MarkdownKind): List<MarkdownAst> =
            (if (node.kind == kind) listOf(node) else emptyList()) + node.children.flatMap { collect(it, kind) }

        val firstPara = doc.blocks[0].node
        assertEquals(MarkdownKind.PARAGRAPH, firstPara.kind)
        val refs = collect(firstPara, MarkdownKind.FOOTNOTE_REF)
        assertEquals(listOf("[1]", "[2]"), refs.map { it.literal })
        assertEquals(listOf(1, 2), refs.map { it.number })
        assertTrue(collect(firstPara, MarkdownKind.LINK).isEmpty())

        val secondPara = doc.blocks[1].node
        val links = collect(secondPara, MarkdownKind.LINK)
        assertEquals(1, links.size)
        assertEquals("https://example.com", links.single().destination)

        val footnotes = doc.blocks.last().node
        assertEquals(MarkdownKind.FOOTNOTES, footnotes.kind)
        assertEquals(2, footnotes.children.size)
        assertEquals(1, footnotes.children[0].number)
        assertEquals(2, footnotes.children[1].number)
        assertEquals("这是第一条无空格中文脚注正文。", collect(footnotes.children[0], MarkdownKind.TEXT).joinToString("") { it.literal })
        assertEquals("这是第二条无空格中文脚注正文。", collect(footnotes.children[1], MarkdownKind.TEXT).joinToString("") { it.literal })
    }

    @Test fun multiParagraphNestedAndProtectedFootnotesPreserveStructureAndEscapes() {
        val source = buildString {
            appendLine("转义\\[^a]、行内代码`[^a]`与真实引用[^b]。")
            appendLine()
            appendLine("```markdown")
            appendLine("[^inside]: 代码块里的脚注定义不应被提取。")
            appendLine("```")
            appendLine()
            appendLine("[^a]: 嵌套脚注最终内容。")
            appendLine("[^b]: 首段见嵌套[^a]。")
            appendLine()
            appendLine("    第二段续行含 `inline`。")
            appendLine()
            appendLine("    ```kotlin")
            appendLine("    val n = 42")
            appendLine("    ```")
            append("[^extra]: 未在正文直接引用但仍保留展示的脚注。")
        }
        val docParser = MarkdownDocumentParser()
        val doc = docParser.parse(source, complete = true)
        fun collect(node: MarkdownAst, kind: MarkdownKind): List<MarkdownAst> =
            (if (node.kind == kind) listOf(node) else emptyList()) + node.children.flatMap { collect(it, kind) }

        val firstPara = doc.blocks[0].node
        val topRefs = collect(firstPara, MarkdownKind.FOOTNOTE_REF)
        assertEquals(listOf("[1]"), topRefs.map { it.literal })
        assertTrue(collect(firstPara, MarkdownKind.TEXT).joinToString("") { it.literal }.contains("转义[^a]"))
        assertEquals(listOf("[^a]"), collect(firstPara, MarkdownKind.CODE).map { it.literal })

        val codeBlock = doc.blocks[1].node
        assertEquals(MarkdownKind.CODE_BLOCK, codeBlock.kind)
        assertTrue(codeBlock.literal.contains("[^inside]: 代码块里的脚注定义不应被提取。"))

        val footnotes = doc.blocks.last().node
        assertEquals(MarkdownKind.FOOTNOTES, footnotes.kind)
        assertEquals(listOf("b", "a", "extra"), footnotes.children.map { it.destination })
        assertEquals(listOf(1, 2, 3), footnotes.children.map { it.number })
        val itemB = footnotes.children[0]
        assertEquals(listOf(MarkdownKind.PARAGRAPH, MarkdownKind.PARAGRAPH, MarkdownKind.CODE_BLOCK), itemB.children.map { it.kind })
        assertEquals(listOf("[2]"), collect(itemB.children[0], MarkdownKind.FOOTNOTE_REF).map { it.literal })
        assertEquals("val n = 42", itemB.children[2].literal)

        val midStream = docParser.parse("流式正文引用[^1]。\n\n[^1]:", complete = false)
        assertTrue(collect(midStream.blocks[0].node, MarkdownKind.FOOTNOTE_REF).isEmpty())
        val resolvedStream = docParser.parse("流式正文引用[^1]。\n\n[^1]: 脚注已到达。", complete = false, previous = midStream)
        assertEquals(listOf("[1]"), collect(resolvedStream.blocks[0].node, MarkdownKind.FOOTNOTE_REF).map { it.literal })
        assertEquals(MarkdownKind.FOOTNOTES, resolvedStream.blocks.last().node.kind)
    }
}
