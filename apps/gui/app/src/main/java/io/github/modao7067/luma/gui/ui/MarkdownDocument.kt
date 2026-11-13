package io.github.modao7067.luma.gui.ui

import androidx.compose.runtime.Immutable
import org.commonmark.node.*
import org.commonmark.ext.gfm.tables.*
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.parser.Parser
import java.util.LinkedHashMap

internal enum class MarkdownKind {
    DOCUMENT, PARAGRAPH, HEADING, CODE_BLOCK, QUOTE, BULLET_LIST, ORDERED_LIST, TABLE,
    TABLE_HEAD, TABLE_BODY, TABLE_ROW, TABLE_CELL, RULE, HTML_BLOCK, TEXT, SOFT_BREAK,
    HARD_BREAK, CODE, STRONG, EMPHASIS, STRIKE, LINK, IMAGE, HTML_INLINE, MATH, OTHER,
}
@Immutable
internal data class MarkdownAst(
    val kind: MarkdownKind,
    val literal: String = "",
    val destination: String = "",
    val title: String = "",
    val number: Int = 1,
    val info: String = "",
    val header: Boolean = false,
    val alignment: String = "",
    val children: List<MarkdownAst> = emptyList(),
    val inlineUnits: Int = 0,
    val mediaCount: Int = 0,
    val revealUnits: Int = 0,
    val nodeCount: Int = 1,
)
@Immutable
internal data class MarkdownDocumentBlock(val offset: Int, val node: MarkdownAst)
@Immutable
internal data class MarkdownDocument(
    val source: String,
    val blocks: List<MarkdownDocumentBlock>,
    val configuration: String = MarkdownDocumentParser.CONFIGURATION,
    val complete: Boolean = true,
) {
    val revealUnits: Int = blocks.sumOf { it.node.revealUnits }
    val nodeCount: Int = blocks.sumOf { it.node.nodeCount }
}
internal data class MarkdownSourcePart(val source: String, val math: Boolean, val offset: Int)
internal data class MarkdownFenceLine(val indent: Int, val char: Char, val length: Int, val info: String)

private val markdownContainerLanguages = setOf("markdown", "md", "gfm", "commonmark", "mdx", "rmd", "quarto")

internal fun isMarkdownContainerLanguage(info: String): Boolean =
    info.trim().substringBefore(' ').lowercase() in markdownContainerLanguages

internal data class MarkdownCardSummary(
    val title: String,
    val subtitle: String,
    val excerpt: String,
    val lineCount: Int,
    val codeBlockCount: Int,
)

internal fun summarizeMarkdownCard(code: String): MarkdownCardSummary {
    val trimmed = code.trimEnd('\n', '\r')
    if (trimmed.isBlank()) {
        return MarkdownCardSummary(
            title = "Markdown 文档",
            subtitle = "Markdown 文档 · 0 行",
            excerpt = "",
            lineCount = 0,
            codeBlockCount = 0,
        )
    }
    val lines = trimmed.lines()
    val lineCount = lines.size
    var openFence: MarkdownFenceLine? = null
    var codeBlockCount = 0
    var headingTitle: String? = null
    var headingLineIndex = -1
    var firstContentTitle: String? = null
    var firstContentLineIndex = -1
    val contentLines = ArrayList<Pair<Int, String>>(8)
    for (index in lines.indices) {
        val raw = lines[index]
        val fence = parseMarkdownFence(raw)
        if (fence != null) {
            if (openFence == null) {
                openFence = fence
                codeBlockCount++
            } else if (fence.info.isEmpty() && fence.char == openFence.char && fence.length >= openFence.length) {
                openFence = null
            }
            continue
        }
        if (openFence != null) continue
        val clean = raw.trim()
        if (clean.isEmpty()) continue
        if (headingTitle == null && clean.startsWith("#")) {
            val stripped = clean.trimStart('#').trim().replace("`", "").replace("**", "")
            if (stripped.isNotEmpty()) {
                headingTitle = stripped
                headingLineIndex = index
            }
        }
        val strippedLine = clean
            .removePrefix("- ")
            .removePrefix("* ")
            .removePrefix("> ")
            .trimStart('#')
            .trim()
            .replace("`", "")
            .replace("**", "")
        if (strippedLine.isNotEmpty()) {
            if (firstContentTitle == null) {
                firstContentTitle = strippedLine
                firstContentLineIndex = index
            }
            if (contentLines.size < 6) {
                contentLines.add(index to strippedLine)
            }
        }
    }
    val titleIndex = if (headingTitle != null) headingLineIndex else firstContentLineIndex
    val rawTitle = headingTitle ?: firstContentTitle ?: "Markdown 文档"
    val title = if (rawTitle.length > 44) rawTitle.take(44).trimEnd() + "…" else rawTitle
    val excerpt = contentLines
        .asSequence()
        .filter { it.first != titleIndex }
        .map { it.second }
        .take(2)
        .joinToString("\n")
    val subtitle = buildString {
        append("Markdown 文档 · ")
        append(lineCount)
        append(" 行")
        if (codeBlockCount > 0) {
            append(" · 含 ")
            append(codeBlockCount)
            append(" 个代码块")
        }
    }
    return MarkdownCardSummary(
        title = title,
        subtitle = subtitle,
        excerpt = excerpt,
        lineCount = lineCount,
        codeBlockCount = codeBlockCount,
    )
}

internal fun parseMarkdownFence(line: String): MarkdownFenceLine? {
    var indent = 0
    while (indent < line.length && (line[indent] == ' ' || line[indent] == '\t')) indent++
    if (indent >= line.length) return null
    val char = line[indent]
    if (char != '`' && char != '~') return null
    var length = 0
    while (indent + length < line.length && line[indent + length] == char) length++
    if (length < 3) return null
    val info = line.substring(indent + length).trim()
    if (char == '`' && info.contains('`')) return null
    return MarkdownFenceLine(indent, char, length, info)
}

private fun widenMarkdownFenceLine(line: String, fence: MarkdownFenceLine, targetLength: Int): String {
    if (targetLength <= fence.length) return line
    return buildString(line.length + targetLength - fence.length) {
        append(line, 0, fence.indent)
        repeat(targetLength) { append(fence.char) }
        append(line, fence.indent + fence.length, line.length)
    }
}

private fun normalizeMarkdownFenceLines(lines: List<String>): List<String> {
    if (lines.size < 2) return lines
    var result: MutableList<String>? = null
    var i = 0
    while (i < lines.size) {
        val outer = parseMarkdownFence(lines[i])
        if (outer == null) {
            i++
            continue
        }
        val infoTag = outer.info.substringBefore(' ').lowercase()
        val isCarousel = infoTag == "carousel"
        val isMarkdownContainer = isCarousel || infoTag in markdownContainerLanguages
        val innerStack = mutableListOf<MarkdownFenceLine>()
        var maxInnerSameChar = 0
        var closeIndex = -1
        var firstBareCloseIndex = -1
        var j = i + 1
        while (j < lines.size) {
            val fence = parseMarkdownFence(lines[j])
            if (fence != null) {
                if (firstBareCloseIndex == -1 && fence.info.isEmpty() && fence.char == outer.char && fence.length >= outer.length) {
                    firstBareCloseIndex = j
                }
                if (fence.info.isNotEmpty()) {
                    innerStack.add(fence)
                    if (fence.char == outer.char) maxInnerSameChar = maxOf(maxInnerSameChar, fence.length)
                } else if (innerStack.isNotEmpty()) {
                    val top = innerStack.last()
                    if (fence.char == top.char && fence.length >= top.length) {
                        innerStack.removeAt(innerStack.lastIndex)
                    }
                    if (fence.char == outer.char) maxInnerSameChar = maxOf(maxInnerSameChar, fence.length)
                } else if (
                    isMarkdownContainer &&
                    fence.indent > outer.indent &&
                    (j + 1 until lines.size).any { k ->
                        val later = parseMarkdownFence(lines[k])
                        later != null && later.info.isEmpty() && later.char == fence.char && later.length >= fence.length
                    }
                ) {
                    innerStack.add(fence)
                    if (fence.char == outer.char) maxInnerSameChar = maxOf(maxInnerSameChar, fence.length)
                } else if (fence.char == outer.char && fence.length >= outer.length) {
                    closeIndex = j
                    break
                }
            }
            j++
        }
        if (isCarousel) {
            val mutable = result ?: lines.toMutableList().also { result = it }
            mutable[i] = ""
            val endExclusive = if (closeIndex != -1) closeIndex else lines.size
            if (closeIndex != -1) mutable[closeIndex] = ""
            var nestedFence: MarkdownFenceLine? = null
            for (k in (i + 1) until endExclusive) {
                val f = parseMarkdownFence(mutable[k])
                if (f != null) {
                    if (nestedFence == null) {
                        nestedFence = f
                    } else if (f.info.isEmpty() && f.char == nestedFence.char && f.length >= nestedFence.length) {
                        nestedFence = null
                    }
                } else if (nestedFence == null && mutable[k].contains("<!--")) {
                    val cleaned = mutable[k].replace(Regex("<!--\\s*slide\\s*-->"), "\n\n")
                    mutable[k] = if (cleaned.isBlank()) "" else cleaned
                }
            }
            i = if (closeIndex != -1) closeIndex + 1 else lines.size
        } else if (closeIndex != -1 || isMarkdownContainer) {
            if (maxInnerSameChar >= outer.length) {
                val targetLength = maxInnerSameChar + 1
                val mutable = result ?: lines.toMutableList().also { result = it }
                mutable[i] = widenMarkdownFenceLine(mutable[i], outer, targetLength)
                if (closeIndex != -1) {
                    val closeFence = parseMarkdownFence(mutable[closeIndex])!!
                    mutable[closeIndex] = widenMarkdownFenceLine(mutable[closeIndex], closeFence, targetLength)
                }
            }
            i = if (closeIndex != -1) closeIndex + 1 else lines.size
        } else {
            i = if (firstBareCloseIndex != -1) firstBareCloseIndex + 1 else lines.size
        }
    }
    return result ?: lines
}

internal fun markdownSourceParts(source: String): List<MarkdownSourcePart> {
    val rawLines = source.lines()
    val lines = normalizeMarkdownFenceLines(rawLines)
    val result = mutableListOf<MarkdownSourcePart>()
    val text = StringBuilder(); val math = StringBuilder()
    var openFence: MarkdownFenceLine? = null; var mathClose: String? = null
    var offset = 0; var textOffset = 0; var mathOffset = 0
    fun flush() { if (text.isNotEmpty()) { result += MarkdownSourcePart(text.toString(), false, textOffset); text.clear() } }
    for (index in lines.indices) {
        val line = lines[index]
        val rawLength = rawLines[index].length
        val t = line.trim()
        if (mathClose != null) {
            if (t.endsWith(mathClose)) {
                math.append(line.substringBeforeLast(mathClose))
                result += MarkdownSourcePart(math.toString().trim(), true, mathOffset)
                math.clear(); mathClose = null; textOffset = offset + rawLength + 1
            } else math.append(line).append('\n')
        } else {
            val fence = parseMarkdownFence(line)
            if (fence != null) {
                if (openFence == null) {
                    openFence = fence
                } else if (fence.info.isEmpty() && fence.char == openFence.char && fence.length >= openFence.length) {
                    openFence = null
                }
            }
            if (openFence == null && (t.startsWith("$$") || t.startsWith("\\["))) {
                flush(); mathOffset = offset
                val open = t.take(2); val close = if (open == "$$") "$$" else "\\]"
                val rest = t.drop(2)
                if (rest.endsWith(close)) {
                    result += MarkdownSourcePart(rest.dropLast(2).trim(), true, offset)
                    textOffset = offset + rawLength + 1
                } else { mathClose = close; math.append(rest).append('\n') }
            } else { if (text.isEmpty()) textOffset = offset; text.append(line).append('\n') }
        }
        offset += rawLength + 1
    }
    if (mathClose != null) { if (text.isEmpty()) textOffset = mathOffset; text.append(if (mathClose == "$$") "$$" else "\\[").append(math) }
    flush(); return result.toList()
}

private fun Node.childNodes(): List<Node> = buildList { var node = firstChild; while (node != null) { add(node); node = node.next } }
internal fun freezeMarkdownNode(node: Node): MarkdownAst {
    val children = node.childNodes().map(::freezeMarkdownNode).filterNot { it.kind == MarkdownKind.CODE_BLOCK && it.literal.isBlank() }
    val kind = when (node) {
        is Document -> MarkdownKind.DOCUMENT; is Paragraph -> MarkdownKind.PARAGRAPH
        is Heading -> MarkdownKind.HEADING; is FencedCodeBlock, is IndentedCodeBlock -> MarkdownKind.CODE_BLOCK
        is BlockQuote -> MarkdownKind.QUOTE; is BulletList -> MarkdownKind.BULLET_LIST; is OrderedList -> MarkdownKind.ORDERED_LIST
        is TableBlock -> MarkdownKind.TABLE; is TableHead -> MarkdownKind.TABLE_HEAD; is TableBody -> MarkdownKind.TABLE_BODY
        is TableRow -> MarkdownKind.TABLE_ROW; is TableCell -> MarkdownKind.TABLE_CELL; is ThematicBreak -> MarkdownKind.RULE
        is HtmlBlock -> MarkdownKind.HTML_BLOCK; is Text -> MarkdownKind.TEXT; is SoftLineBreak -> MarkdownKind.SOFT_BREAK
        is HardLineBreak -> MarkdownKind.HARD_BREAK; is Code -> MarkdownKind.CODE; is StrongEmphasis -> MarkdownKind.STRONG
        is Emphasis -> MarkdownKind.EMPHASIS; is Strikethrough -> MarkdownKind.STRIKE; is Link -> MarkdownKind.LINK
        is Image -> MarkdownKind.IMAGE; is HtmlInline -> MarkdownKind.HTML_INLINE; else -> MarkdownKind.OTHER
    }
    val literal = when (node) { is Text -> node.literal; is Code -> node.literal; is HtmlInline -> node.literal; is HtmlBlock -> node.literal
        is FencedCodeBlock -> node.literal.trimEnd('\n'); is IndentedCodeBlock -> node.literal.trimEnd('\n'); else -> "" }
    val destination = when (node) { is Link -> node.destination; is Image -> node.destination; else -> "" }
    val media = when { kind == MarkdownKind.IMAGE -> 1; kind == MarkdownKind.LINK && markdownResourceLink(destination) -> 1; else -> children.sumOf { it.mediaCount } }
    val inline = when (kind) {
        MarkdownKind.TEXT, MarkdownKind.HTML_INLINE -> literal.length
        MarkdownKind.SOFT_BREAK, MarkdownKind.HARD_BREAK -> 1
        MarkdownKind.CODE -> literal.length + 2
        MarkdownKind.IMAGE -> 0
        MarkdownKind.LINK -> children.sumOf { it.inlineUnits }
        else -> children.sumOf { it.inlineUnits }
    }
    val number = when (node) { is Heading -> node.level; is OrderedList -> node.markerStartNumber ?: 1; else -> 1 }
    val units = when (kind) {
        MarkdownKind.PARAGRAPH, MarkdownKind.HEADING, MarkdownKind.TABLE_CELL -> inline + media
        MarkdownKind.CODE_BLOCK -> literal.length.coerceAtLeast(1)
        MarkdownKind.HTML_BLOCK -> literal.length
        MarkdownKind.RULE -> 1
        MarkdownKind.BULLET_LIST, MarkdownKind.ORDERED_LIST -> children.mapIndexed { index, child ->
            val marker = if (kind == MarkdownKind.ORDERED_LIST) "${number + index}." else "•"
            marker.length + child.revealUnits
        }.sum()
        else -> children.sumOf { it.revealUnits }.takeIf { children.isNotEmpty() } ?: inline
    }
    return MarkdownAst(kind, literal, destination, (node as? Link)?.title.orEmpty(), number,
        (node as? FencedCodeBlock)?.info?.substringBefore(' ').orEmpty(), (node as? TableCell)?.isHeader ?: false,
        (node as? TableCell)?.alignment?.name.orEmpty(), children.toList(), inline, media, units, 1 + children.sumOf { it.nodeCount })
}

internal class MarkdownDocumentParser {
    private val parser = Parser.builder().extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
        .includeSourceSpans(org.commonmark.parser.IncludeSourceSpans.BLOCKS_AND_INLINES).build()
    var parses: Long = 0; private set
    var parseNanos: Long = 0; private set
    var reusedBlocks: Long = 0; private set
    fun parse(source: String, complete: Boolean, previous: MarkdownDocument? = null): MarkdownDocument {
        val begin = System.nanoTime()
        val old = previous?.takeIf { it.configuration == CONFIGURATION }?.blocks?.associateBy { it.offset }.orEmpty()
        val blocks = buildList {
            markdownSourceParts(source).forEach { part ->
                if (part.math) {
                    val node = MarkdownAst(MarkdownKind.MATH, literal = part.source, revealUnits = part.source.length.coerceAtLeast(1))
                    val retained = old[part.offset]?.takeIf { it.node == node }
                    if (retained != null) { reusedBlocks++; add(retained) } else add(MarkdownDocumentBlock(part.offset, node))
                } else {
                    parses++
                    val document = parser.parse(part.source)
                    document.childNodes().forEachIndexed { index, raw ->
                        val node = freezeMarkdownNode(raw)
                        if (node.kind == MarkdownKind.CODE_BLOCK && node.literal.isBlank()) return@forEachIndexed
                        val at = part.offset + (raw.sourceSpans.firstOrNull()?.inputIndex ?: index)
                        val retained = old[at]?.takeIf { it.node == node }
                        if (retained != null) { reusedBlocks++; add(retained) } else add(MarkdownDocumentBlock(at, node))
                    }
                }
            }
        }
        parseNanos += System.nanoTime() - begin
        return MarkdownDocument(source, blocks.toList(), complete = complete)
    }
    companion object { const val CONFIGURATION = "commonmark-0.32.0:gfm-tables+strike:math-fences:v3" }
}

internal data class MarkdownCacheUsage(val entries: Int, val sourceUnits: Int, val nodes: Int, val hits: Long, val misses: Long, val rejected: Long)
internal class MarkdownDocumentCache(
    private val sourceBudget: Int = 512 * 1024, private val maxEntries: Int = 48,
    private val maxSource: Int = 128 * 1024, private val nodeBudget: Int = 32768, private val maxNodes: Int = 8192,
) {
    private data class Key(val source: String, val configuration: String)
    private val entries = LinkedHashMap<Key, MarkdownDocument>(16, .75f, true)
    private var sources = 0; private var nodes = 0; private var hits = 0L; private var misses = 0L; private var rejected = 0L
    init { require(sourceBudget > 0 && maxEntries > 0 && maxSource in 1..sourceBudget && nodeBudget > 0 && maxNodes in 1..nodeBudget) }
    @Synchronized fun peek(source: String): MarkdownDocument? = if (source.length > maxSource) null else entries[Key(source, MarkdownDocumentParser.CONFIGURATION)]
    @Synchronized fun get(source: String): MarkdownDocument? {
        val value = peek(source); if (value == null) misses++ else hits++; return value
    }
    @Synchronized fun put(document: MarkdownDocument) {
        if (!document.complete) return
        if (document.source.length > maxSource || document.nodeCount > maxNodes) { rejected++; return }
        val key = Key(document.source, document.configuration)
        entries.remove(key)?.let { sources -= it.source.length; nodes -= it.nodeCount }
        entries[key] = document; sources += document.source.length; nodes += document.nodeCount
        while (entries.size > maxEntries || sources > sourceBudget || nodes > nodeBudget) {
            val first = entries.entries.iterator(); val old = first.next().value; first.remove()
            sources -= old.source.length; nodes -= old.nodeCount
        }
    }
    @Synchronized fun usage() = MarkdownCacheUsage(entries.size, sources, nodes, hits, misses, rejected)
}

internal fun markdownVisibleEnd(text: String, requested: Int): Int {
    var end = requested.coerceIn(0, text.length)
    if (end in 1 until text.length && Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
    return end
}
