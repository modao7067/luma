package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.i18n.tr

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
    HARD_BREAK, CODE, STRONG, EMPHASIS, STRIKE, LINK, IMAGE, HTML_INLINE, MATH,
    FOOTNOTE_REF, FOOTNOTES, FOOTNOTE_ITEM, OTHER,
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
            title = tr("Markdown 文档", "Markdown document"),
            subtitle = tr("Markdown 文档 · 0 行", "Markdown document · 0 lines"),
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
    val rawTitle = headingTitle ?: firstContentTitle ?: tr("Markdown 文档", "Markdown document")
    val title = if (rawTitle.length > 44) safeUtf16Prefix(rawTitle, 44).trimEnd() + "…" else rawTitle
    val excerpt = contentLines
        .asSequence()
        .filter { it.first != titleIndex }
        .map { it.second }
        .take(2)
        .joinToString("\n")
    val subtitle = buildString {
        append(tr("Markdown 文档 · $lineCount 行", "Markdown document · $lineCount lines"))
        if (codeBlockCount > 0) {
            append(tr(" · 含 $codeBlockCount 个代码块", " · $codeBlockCount code blocks"))
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
    val n = lines.size
    if (n < 2) return lines
    val parsed = arrayOfNulls<MarkdownFenceLine>(n)
    for (idx in 0 until n) {
        parsed[idx] = parseMarkdownFence(lines[idx])
    }
    val maxBareBacktickAfter = IntArray(n)
    val maxBareTildeAfter = IntArray(n)
    var maxBacktick = 0
    var maxTilde = 0
    for (idx in n - 1 downTo 0) {
        maxBareBacktickAfter[idx] = maxBacktick
        maxBareTildeAfter[idx] = maxTilde
        val f = parsed[idx]
        if (f != null && f.info.isEmpty()) {
            if (f.char == '`') maxBacktick = maxOf(maxBacktick, f.length)
            else if (f.char == '~') maxTilde = maxOf(maxTilde, f.length)
        }
    }
    var result: MutableList<String>? = null
    var i = 0
    while (i < n) {
        val outer = parsed[i]
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
        while (j < n) {
            val fence = parsed[j]
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
                    (if (fence.char == '`') maxBareBacktickAfter[j] else maxBareTildeAfter[j]) >= fence.length
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
            val endExclusive = if (closeIndex != -1) closeIndex else n
            if (closeIndex != -1) mutable[closeIndex] = ""
            var nestedFence: MarkdownFenceLine? = null
            for (k in (i + 1) until endExclusive) {
                val f = parsed[k]
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
            i = if (closeIndex != -1) closeIndex + 1 else n
        } else if (closeIndex != -1 || isMarkdownContainer) {
            if (maxInnerSameChar >= outer.length) {
                val targetLength = maxInnerSameChar + 1
                val mutable = result ?: lines.toMutableList().also { result = it }
                mutable[i] = widenMarkdownFenceLine(mutable[i], outer, targetLength)
                if (closeIndex != -1) {
                    val closeFence = parsed[closeIndex]!!
                    mutable[closeIndex] = widenMarkdownFenceLine(mutable[closeIndex], closeFence, targetLength)
                }
            }
            i = if (closeIndex != -1) closeIndex + 1 else n
        } else {
            i = if (firstBareCloseIndex != -1) firstBareCloseIndex + 1 else n
        }
    }
    return result ?: lines
}

private data class FootnoteDefinitionDraft(
    val key: String,
    val label: String,
    val body: String,
    val offset: Int,
)

private data class MarkdownSplitSource(
    val parts: List<MarkdownSourcePart>,
    val footnotes: LinkedHashMap<String, FootnoteDefinitionDraft>,
)

private val footnoteDefStartRegex = Regex("""^ {0,3}\[\^([^\s\[\]\n]+)\]:(?:[ \t]+(.*)|[ \t]*)$""")
private val footnoteRefRegex = Regex("""\[\^([^\s\[\]\n]+)\]""")
private val footnoteInterruptHeadingRegex = Regex("""^ {0,3}#{1,6}(?:[ \t]|$)""")
private val footnoteInterruptRuleRegex = Regex("""^ {0,3}(?:(?:\*[ \t]*){3,}|(?:-[ \t]*){3,}|(?:_[ \t]*){3,})$""")
private val footnoteInterruptListRegex = Regex("""^ {0,3}(?:[-*+][ \t]+|\d{1,9}[.)][ \t]+)""")

private fun leadingIndentSpaces(line: String): Int {
    if (line.isEmpty()) return 0
    if (line[0] == '\t') return 4
    var spaces = 0
    while (spaces < line.length && line[spaces] == ' ') spaces++
    return if (spaces < line.length) spaces else 0
}

private fun isFootnoteIndentedContinuation(line: String): Boolean = leadingIndentSpaces(line) >= 2

private fun stripFootnoteIndent(line: String, maxSpaces: Int): String {
    if (line.isEmpty()) return line
    if (line[0] == '\t') return line.substring(1)
    var strip = 0
    val limit = maxSpaces.coerceIn(1, 4)
    while (strip < limit && strip < line.length && line[strip] == ' ') strip++
    return line.substring(strip)
}

private fun interruptsFootnoteParagraph(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return true
    if (parseMarkdownFence(line) != null) return true
    if (trimmed.startsWith("$$") || trimmed.startsWith("\\[")) return true
    if (trimmed.startsWith(">") || trimmed.startsWith("|")) return true
    if (footnoteDefStartRegex.matches(line)) return true
    if (footnoteInterruptHeadingRegex.containsMatchIn(line)) return true
    if (footnoteInterruptRuleRegex.matches(line)) return true
    if (footnoteInterruptListRegex.containsMatchIn(line)) return true
    return false
}

internal fun markdownSourceParts(source: String): List<MarkdownSourcePart> =
    splitMarkdownSource(source, extractFootnotes = false).parts

private fun splitMarkdownSource(source: String, extractFootnotes: Boolean): MarkdownSplitSource {
    val rawLines = source.lines()
    val lines = normalizeMarkdownFenceLines(rawLines)
    val result = mutableListOf<MarkdownSourcePart>()
    val footnotes = LinkedHashMap<String, FootnoteDefinitionDraft>()
    val text = StringBuilder(); val math = StringBuilder()
    var openFence: MarkdownFenceLine? = null; var mathClose: String? = null
    var offset = 0; var textOffset = 0; var mathOffset = 0
    val mayContainFootnotes = extractFootnotes && source.contains("[^")
    fun flush() { if (text.isNotEmpty()) { result += MarkdownSourcePart(text.toString(), false, textOffset); text.clear() } }
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        val rawLength = rawLines[index].length
        val t = line.trim()
        if (mathClose != null) {
            if (t.endsWith(mathClose)) {
                math.append(line.substringBeforeLast(mathClose))
                result += MarkdownSourcePart(math.toString().trim(), true, mathOffset)
                math.clear(); mathClose = null; textOffset = offset + rawLength + 1
            } else math.append(line).append('\n')
            offset += rawLength + 1
            index++
            continue
        }
        val fence = parseMarkdownFence(line)
        if (fence != null) {
            if (openFence == null) {
                openFence = fence
            } else if (fence.info.isEmpty() && fence.char == openFence.char && fence.length >= openFence.length) {
                openFence = null
            }
        }
        if (openFence == null && mayContainFootnotes) {
            val defMatch = footnoteDefStartRegex.matchEntire(line)
            if (defMatch != null) {
                val label = defMatch.groupValues[1]
                val key = label.lowercase()
                val firstRest = defMatch.groupValues[2]
                val defOffset = offset
                val bodyLines = mutableListOf<String>()
                if (firstRest.isNotEmpty()) bodyLines.add(firstRest)
                var defFence = parseMarkdownFence(firstRest)
                var baseIndent = 0
                var nextIdx = index + 1
                while (nextIdx < lines.size) {
                    val candidate = lines[nextIdx]
                    if (defFence != null) {
                        val stripped = stripFootnoteIndent(candidate, if (baseIndent > 0) baseIndent else 4)
                        val closing = parseMarkdownFence(stripped)
                        if (closing != null && closing.info.isEmpty() && closing.char == defFence.char && closing.length >= defFence.length) {
                            defFence = null
                        }
                        bodyLines.add(stripped)
                        nextIdx++
                        continue
                    }
                    if (candidate.isBlank()) {
                        var look = nextIdx + 1
                        while (look < lines.size && lines[look].isBlank()) look++
                        if (look < lines.size && isFootnoteIndentedContinuation(lines[look]) && !footnoteDefStartRegex.matches(lines[look])) {
                            if (bodyLines.isNotEmpty()) bodyLines.add("")
                            nextIdx = look
                            continue
                        }
                        break
                    }
                    if (footnoteDefStartRegex.matches(candidate)) break
                    if (isFootnoteIndentedContinuation(candidate)) {
                        if (baseIndent == 0) {
                            baseIndent = leadingIndentSpaces(candidate).coerceIn(2, 4)
                        }
                        val stripped = stripFootnoteIndent(candidate, baseIndent)
                        val innerFence = parseMarkdownFence(stripped)
                        if (innerFence != null) defFence = innerFence
                        bodyLines.add(stripped)
                        nextIdx++
                        continue
                    }
                    if (bodyLines.isEmpty() || interruptsFootnoteParagraph(candidate)) break
                    bodyLines.add(candidate.trimStart())
                    nextIdx++
                }
                val defBody = bodyLines.joinToString("\n").trimEnd()
                if (defBody.isNotBlank()) {
                    footnotes.putIfAbsent(key, FootnoteDefinitionDraft(key, label, defBody, defOffset))
                    if (text.isEmpty()) textOffset = offset
                    for (consumed in index until nextIdx) {
                        val len = rawLines[consumed].length
                        if (len > 0) text.append(" ".repeat(len))
                        text.append('\n')
                        offset += len + 1
                    }
                    index = nextIdx
                    continue
                }
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
        offset += rawLength + 1
        index++
    }
    if (mathClose != null) { if (text.isEmpty()) textOffset = mathOffset; text.append(if (mathClose == "$$") "$$" else "\\[").append(math) }
    flush()
    return MarkdownSplitSource(result.toList(), footnotes)
}

private fun Node.childNodes(): List<Node> = buildList { var node = firstChild; while (node != null) { add(node); node = node.next } }

private fun rawSourceForNode(node: Node, partSource: String?): String? {
    if (partSource == null) return null
    val spans = node.sourceSpans
    if (spans.isEmpty()) return null
    if (spans.size == 1) {
        val s = spans[0]
        val end = s.inputIndex + s.length
        return if (s.inputIndex in 0..partSource.length && end in s.inputIndex..partSource.length) {
            partSource.substring(s.inputIndex, end)
        } else null
    }
    val sb = StringBuilder()
    for (s in spans) {
        val end = s.inputIndex + s.length
        if (s.inputIndex !in 0..partSource.length || end !in s.inputIndex..partSource.length) return null
        sb.append(partSource, s.inputIndex, end)
    }
    return sb.toString()
}

private fun escapedLiteralPositions(literal: String, rawSlice: String?): BooleanArray? {
    if (rawSlice == null || rawSlice == literal || '\\' !in rawSlice) return null
    val escaped = BooleanArray(literal.length)
    var r = 0
    var l = 0
    while (l < literal.length && r < rawSlice.length) {
        if (rawSlice[r] == '\\' && r + 1 < rawSlice.length && rawSlice[r + 1] == literal[l]) {
            escaped[l] = true
            r += 2
            l++
        } else if (rawSlice[r] == literal[l]) {
            r++
            l++
        } else if (rawSlice[r] == '&') {
            val semi = rawSlice.indexOf(';', r + 1)
            if (semi > r && semi - r <= 32) {
                r = semi + 1
                l++
            } else {
                r++
            }
        } else {
            r++
        }
    }
    return escaped
}

private fun splitFootnoteRefTextNodes(
    literal: String,
    rawSlice: String?,
    footnoteKeys: Set<String>,
): List<MarkdownAst>? {
    if (footnoteKeys.isEmpty() || "[^" !in literal) return null
    val escaped = escapedLiteralPositions(literal, rawSlice)
    var cursor = 0
    var parts: MutableList<MarkdownAst>? = null
    for (match in footnoteRefRegex.findAll(literal)) {
        val start = match.range.first
        if (escaped != null && start < escaped.size && escaped[start]) continue
        val key = match.groupValues[1].lowercase()
        if (key !in footnoteKeys) continue
        val list = parts ?: mutableListOf<MarkdownAst>().also { parts = it }
        if (start > cursor) {
            val prefix = literal.substring(cursor, start)
            list.add(MarkdownAst(MarkdownKind.TEXT, literal = prefix, inlineUnits = prefix.length, revealUnits = prefix.length))
        }
        list.add(
            MarkdownAst(
                kind = MarkdownKind.FOOTNOTE_REF,
                literal = "[$key]",
                destination = key,
                inlineUnits = key.length + 2,
                revealUnits = key.length + 2,
            ),
        )
        cursor = match.range.last + 1
    }
    val out = parts ?: return null
    if (cursor < literal.length) {
        val suffix = literal.substring(cursor)
        out.add(MarkdownAst(MarkdownKind.TEXT, literal = suffix, inlineUnits = suffix.length, revealUnits = suffix.length))
    }
    return out
}

internal fun freezeMarkdownNode(node: Node): MarkdownAst = freezeMarkdownNode(node, null, emptySet())

private fun freezeMarkdownNode(
    node: Node,
    partSource: String?,
    footnoteKeys: Set<String>,
): MarkdownAst {
    val children = buildList {
        for (rawChild in node.childNodes()) {
            if (rawChild is Text && footnoteKeys.isNotEmpty()) {
                val split = splitFootnoteRefTextNodes(rawChild.literal, rawSourceForNode(rawChild, partSource), footnoteKeys)
                if (split != null) {
                    addAll(split)
                    continue
                }
            }
            val frozen = freezeMarkdownNode(rawChild, partSource, footnoteKeys)
            if (frozen.kind == MarkdownKind.CODE_BLOCK && frozen.literal.isBlank()) continue
            add(frozen)
        }
    }
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
        MarkdownKind.TEXT, MarkdownKind.HTML_INLINE, MarkdownKind.FOOTNOTE_REF -> literal.length
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

private fun collectFootnoteRefOrder(
    node: MarkdownAst,
    footnoteKeys: Set<String>,
    numberByKey: LinkedHashMap<String, Int>,
    queue: ArrayDeque<String>,
) {
    if (node.kind == MarkdownKind.FOOTNOTE_REF) {
        val key = node.destination
        if (key in footnoteKeys && key !in numberByKey) {
            numberByKey[key] = numberByKey.size + 1
            queue.addLast(key)
        }
        return
    }
    for (child in node.children) {
        collectFootnoteRefOrder(child, footnoteKeys, numberByKey, queue)
    }
}

private fun stampFootnoteNumbers(node: MarkdownAst, numberByKey: Map<String, Int>): MarkdownAst {
    if (node.kind == MarkdownKind.FOOTNOTE_REF) {
        val assigned = numberByKey[node.destination] ?: return node
        val text = "[$assigned]"
        if (node.number == assigned && node.literal == text && node.inlineUnits == text.length && node.revealUnits == text.length) {
            return node
        }
        return node.copy(literal = text, number = assigned, inlineUnits = text.length, revealUnits = text.length)
    }
    if (node.children.isEmpty()) return node
    var changed = false
    val updatedChildren = node.children.map { child ->
        val stamped = stampFootnoteNumbers(child, numberByKey)
        if (stamped !== child) changed = true
        stamped
    }
    if (!changed) return node
    val inline = when (node.kind) {
        MarkdownKind.TEXT, MarkdownKind.HTML_INLINE, MarkdownKind.FOOTNOTE_REF -> node.literal.length
        MarkdownKind.SOFT_BREAK, MarkdownKind.HARD_BREAK -> 1
        MarkdownKind.CODE -> node.literal.length + 2
        MarkdownKind.IMAGE -> 0
        else -> updatedChildren.sumOf { it.inlineUnits }
    }
    val units = when (node.kind) {
        MarkdownKind.PARAGRAPH, MarkdownKind.HEADING, MarkdownKind.TABLE_CELL -> inline + node.mediaCount
        MarkdownKind.CODE_BLOCK -> node.literal.length.coerceAtLeast(1)
        MarkdownKind.HTML_BLOCK -> node.literal.length
        MarkdownKind.RULE -> 1
        MarkdownKind.BULLET_LIST, MarkdownKind.ORDERED_LIST -> updatedChildren.mapIndexed { index, child ->
            val marker = if (node.kind == MarkdownKind.ORDERED_LIST) "${node.number + index}." else "•"
            marker.length + child.revealUnits
        }.sum()
        else -> updatedChildren.sumOf { it.revealUnits }.takeIf { updatedChildren.isNotEmpty() } ?: inline
    }
    return node.copy(
        children = updatedChildren,
        inlineUnits = inline,
        revealUnits = units,
        nodeCount = 1 + updatedChildren.sumOf { it.nodeCount },
    )
}

internal class MarkdownDocumentParser {
    private val parser = Parser.builder().extensions(
        listOf(TablesExtension.create(), StrikethroughExtension.builder().requireTwoTildes(true).build()),
    )
        .includeSourceSpans(org.commonmark.parser.IncludeSourceSpans.BLOCKS_AND_INLINES).build()
    private val parseCount = java.util.concurrent.atomic.AtomicLong()
    private val parseNanosTotal = java.util.concurrent.atomic.AtomicLong()
    private val reusedBlockCount = java.util.concurrent.atomic.AtomicLong()
    val parses: Long get() = parseCount.get()
    val parseNanos: Long get() = parseNanosTotal.get()
    val reusedBlocks: Long get() = reusedBlockCount.get()

    private fun parseFragmentBlocks(
        parts: List<MarkdownSourcePart>,
        footnoteKeys: Set<String>,
        onParse: () -> Unit,
    ): List<MarkdownDocumentBlock> = buildList {
        parts.forEach { part ->
            if (part.math) {
                val node = MarkdownAst(MarkdownKind.MATH, literal = part.source, revealUnits = part.source.length.coerceAtLeast(1))
                add(MarkdownDocumentBlock(part.offset, node))
            } else {
                onParse()
                val document = parser.parse(part.source)
                document.childNodes().forEachIndexed { index, raw ->
                    val node = freezeMarkdownNode(raw, part.source, footnoteKeys)
                    if (node.kind == MarkdownKind.CODE_BLOCK && node.literal.isBlank()) return@forEachIndexed
                    val at = part.offset + (raw.sourceSpans.firstOrNull()?.inputIndex ?: index)
                    add(MarkdownDocumentBlock(at, node))
                }
            }
        }
    }

    fun parse(source: String, complete: Boolean, previous: MarkdownDocument? = null): MarkdownDocument {
        val begin = System.nanoTime()
        val old = previous?.takeIf { it.configuration == CONFIGURATION }?.blocks?.associateBy { it.offset }.orEmpty()
        var localParses = 0L
        var localReused = 0L
        val split = splitMarkdownSource(source, extractFootnotes = true)
        val footnoteDefs = split.footnotes
        val footnoteKeys = footnoteDefs.keys
        val rawBodyBlocks = parseFragmentBlocks(split.parts, footnoteKeys) { localParses++ }

        val finalRawBlocks = if (footnoteDefs.isEmpty()) {
            rawBodyBlocks
        } else {
            val parsedDefBodies = LinkedHashMap<String, List<MarkdownAst>>(footnoteDefs.size)
            for ((key, draft) in footnoteDefs) {
                val defParts = splitMarkdownSource(draft.body, extractFootnotes = false).parts
                parsedDefBodies[key] = parseFragmentBlocks(defParts, footnoteKeys) { localParses++ }.map { it.node }
            }
            val numberByKey = LinkedHashMap<String, Int>(footnoteDefs.size)
            val queue = ArrayDeque<String>()
            rawBodyBlocks.forEach { block ->
                collectFootnoteRefOrder(block.node, footnoteKeys, numberByKey, queue)
            }
            while (queue.isNotEmpty()) {
                val key = queue.removeFirst()
                parsedDefBodies[key]?.forEach { defNode ->
                    collectFootnoteRefOrder(defNode, footnoteKeys, numberByKey, queue)
                }
            }
            for (key in footnoteDefs.keys) {
                if (key !in numberByKey) {
                    numberByKey[key] = numberByKey.size + 1
                }
            }
            val stampedBody = rawBodyBlocks.map { block ->
                MarkdownDocumentBlock(block.offset, stampFootnoteNumbers(block.node, numberByKey))
            }
            val items = numberByKey.entries.map { (key, num) ->
                val draft = footnoteDefs[key]!!
                val itemChildren = parsedDefBodies[key].orEmpty().map { stampFootnoteNumbers(it, numberByKey) }
                val markerLen = "$num.".length
                val itemReveal = markerLen + itemChildren.sumOf { it.revealUnits }
                val itemNodeCount = 1 + itemChildren.sumOf { it.nodeCount }
                MarkdownAst(
                    kind = MarkdownKind.FOOTNOTE_ITEM,
                    literal = draft.label,
                    destination = key,
                    number = num,
                    children = itemChildren,
                    revealUnits = itemReveal,
                    nodeCount = itemNodeCount,
                )
            }
            val footnotesNode = MarkdownAst(
                kind = MarkdownKind.FOOTNOTES,
                children = items,
                revealUnits = 1 + items.sumOf { it.revealUnits },
                nodeCount = 1 + items.sumOf { it.nodeCount },
            )
            val footnotesOffset = footnoteDefs.values.first().offset
            stampedBody + MarkdownDocumentBlock(footnotesOffset, footnotesNode)
        }

        val blocks = finalRawBlocks.map { block ->
            val retained = old[block.offset]?.takeIf { it.node == block.node }
            if (retained != null) {
                localReused++
                retained
            } else {
                block
            }
        }
        if (localParses > 0L) parseCount.addAndGet(localParses)
        if (localReused > 0L) reusedBlockCount.addAndGet(localReused)
        parseNanosTotal.addAndGet(System.nanoTime() - begin)
        return MarkdownDocument(source, blocks.toList(), complete = complete)
    }
    companion object { const val CONFIGURATION = "commonmark-0.32.0:gfm-tables+strike2+footnotes:math-fences:v5" }
}

internal data class MarkdownCacheUsage(val entries: Int, val sourceUnits: Int, val nodes: Int, val hits: Long, val misses: Long, val rejected: Long)
internal class MarkdownDocumentCache(
    private val sourceBudget: Int = 512 * 1024, private val maxEntries: Int = 48,
    private val maxSource: Int = 128 * 1024, private val nodeBudget: Int = 32768, private val maxNodes: Int = 8192,
) {
    private data class Key(val source: String, val configuration: String)
    private val entries = LinkedHashMap<Key, MarkdownDocument>(16, .75f, true)
    private var recentLargeKey: Key? = null
    private var recentLargeDoc: MarkdownDocument? = null
    private var sources = 0; private var nodes = 0; private var hits = 0L; private var misses = 0L; private var rejected = 0L
    init { require(sourceBudget > 0 && maxEntries > 0 && maxSource in 1..sourceBudget && nodeBudget > 0 && maxNodes in 1..nodeBudget) }
    @Synchronized fun peek(source: String): MarkdownDocument? {
        val key = Key(source, MarkdownDocumentParser.CONFIGURATION)
        if (source.length > maxSource) {
            return if (recentLargeKey == key) recentLargeDoc else null
        }
        return entries[key] ?: if (recentLargeKey == key) recentLargeDoc else null
    }
    @Synchronized fun get(source: String): MarkdownDocument? {
        val value = peek(source); if (value == null) misses++ else hits++; return value
    }
    @Synchronized fun put(document: MarkdownDocument) {
        if (!document.complete) return
        val key = Key(document.source, document.configuration)
        if (document.source.length > maxSource || document.nodeCount > maxNodes) {
            rejected++
            recentLargeKey = key
            recentLargeDoc = document
            return
        }
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

internal fun safeUtf16Prefix(text: String, maxChars: Int): String {
    val end = markdownVisibleEnd(text, maxChars)
    return if (end == text.length) text else text.substring(0, end)
}

internal fun safeUtf16SliceStart(text: String, requestedStart: Int): Int {
    var start = requestedStart.coerceIn(0, text.length)
    if (start in 1 until text.length && Character.isHighSurrogate(text[start - 1]) && Character.isLowSurrogate(text[start])) start++
    return start
}
