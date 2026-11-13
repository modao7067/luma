package io.github.modao7067.luma.gui.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.tables.*
import org.commonmark.node.*
import io.github.modao7067.luma.gui.session.MediaResource
import io.github.modao7067.luma.gui.ui.shapes.addSquircleRect
import ru.noties.jlatexmath.JLatexMathAndroid
import ru.noties.jlatexmath.JLatexMathDrawable

internal val LocalMarkdownNestingDepth = compositionLocalOf { 0 }
internal val LocalMarkdownCardInitiallyExpanded = compositionLocalOf { false }
private val LocalMarkdownPluginAuthUrl = compositionLocalOf<String?> { null }

internal const val MAX_CARD_MARKDOWN_CHARS = 128 * 1024
internal const val MAX_INLINE_MATH_CHARS = 512
internal const val MAX_BLOCK_MATH_CHARS = 2048

private data class FormulaCacheKey(
    val expression: String,
    val sizeTenthsPx: Int,
    val argbColor: Int,
    val padding: Int,
)

@Immutable
private data class RenderedFormula(
    val bitmap: ImageBitmap,
    val widthPx: Int,
    val heightPx: Int,
    val verticalAlign: PlaceholderVerticalAlign = PlaceholderVerticalAlign.Center,
)

private val formulaRenderLock = Any()
@Volatile
private var latexInitialized = false

private fun ensureLatexInitialized(context: android.content.Context) {
    if (!latexInitialized) {
        synchronized(formulaRenderLock) {
            if (!latexInitialized) {
                JLatexMathAndroid.init(context.applicationContext)
                latexInitialized = true
            }
        }
    }
}

private val formulaBitmapCache = object : LruCache<FormulaCacheKey, RenderedFormula>(192) {
    override fun sizeOf(key: FormulaCacheKey, value: RenderedFormula): Int = 1
}

private fun getCachedFormula(expression: String, sizePx: Float, argbColor: Int, padding: Int): RenderedFormula? {
    val key = FormulaCacheKey(expression, (sizePx * 10f).roundToInt(), argbColor, padding)
    return synchronized(formulaBitmapCache) { formulaBitmapCache.get(key) }
}

private fun renderAndCacheFormula(
    context: android.content.Context,
    expression: String,
    sizePx: Float,
    argbColor: Int,
    padding: Int,
): RenderedFormula? {
    val key = FormulaCacheKey(expression, (sizePx * 10f).roundToInt(), argbColor, padding)
    synchronized(formulaBitmapCache) { formulaBitmapCache.get(key) }?.let { return it }
    ensureLatexInitialized(context)
    val rendered = runCatching {
        synchronized(formulaRenderLock) {
            val builder = JLatexMathDrawable.builder(expression).textSize(sizePx).color(argbColor)
            if (padding > 0) builder.padding(padding)
            val drawable = builder.build()
            val w = drawable.intrinsicWidth.coerceIn(1, 8192)
            val h = drawable.intrinsicHeight.coerceIn(1, 4096)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(canvas)
            RenderedFormula(bmp.asImageBitmap(), w, h, PlaceholderVerticalAlign.Center)
        }
    }.getOrNull() ?: return null
    synchronized(formulaBitmapCache) { formulaBitmapCache.put(key, rendered) }
    return rendered
}
private val pluginCardBlockRegex = Regex(
    """^<plugin-card\b[^>]*\bcatalog-id\s*=\s*["']([^"']+)["'][^>]*>\s*(?:</plugin-card>)?\s*$|^<plugin-card\b[^>]*\bcatalog-id\s*=\s*["']([^"']+)["'][^>]*/>\s*$""",
    RegexOption.IGNORE_CASE,
)
private val pluginCardOpenTagRegex = Regex(
    """<plugin-card\b[^>]*\bcatalog-id\s*=\s*["']([^"']+)["'][^>]*/?>""",
    RegexOption.IGNORE_CASE,
)
private val pluginCardAnyTagRegex = Regex(
    """^</?plugin-card\b[^>]*>$""",
    RegexOption.IGNORE_CASE,
)
private val pluginCardStripInlineRegex = Regex(
    """</?plugin-card\b[^>]*>""",
    RegexOption.IGNORE_CASE,
)

private fun extractStandalonePluginCatalogId(node: MarkdownAst): String? = when (node.kind) {
    MarkdownKind.HTML_BLOCK -> {
        val m = pluginCardBlockRegex.matchEntire(node.literal.trim())
        m?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
    }
    MarkdownKind.PARAGRAPH -> {
        if (node.children.isEmpty() || !node.children.all {
                it.kind in setOf(MarkdownKind.HTML_INLINE, MarkdownKind.TEXT, MarkdownKind.SOFT_BREAK, MarkdownKind.HARD_BREAK)
            }
        ) {
            null
        } else {
            val joined = node.children.joinToString("") {
                if (it.kind == MarkdownKind.SOFT_BREAK || it.kind == MarkdownKind.HARD_BREAK) "" else it.literal
            }.trim()
            val m = pluginCardBlockRegex.matchEntire(joined)
            m?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
        }
    }
    else -> null
}

private fun extractInlinePluginCatalogIds(node: MarkdownAst): List<String> = buildList {
    fun walk(n: MarkdownAst) {
        if (n.kind == MarkdownKind.HTML_INLINE) {
            pluginCardOpenTagRegex.find(n.literal)?.groupValues?.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let(::add)
        }
        n.children.forEach(::walk)
    }
    node.children.forEach(::walk)
}

private fun findPluginAuthUrl(nodes: List<MarkdownAst>): String? {
    val links = mutableListOf<String>()
    fun walk(n: MarkdownAst) {
        if (n.kind == MarkdownKind.LINK && (n.destination.startsWith("https://") || n.destination.startsWith("http://"))) {
            links.add(n.destination)
        }
        n.children.forEach(::walk)
    }
    nodes.forEach(::walk)
    return links.firstOrNull { url ->
        val lower = url.lowercase()
        "oauth" in lower || "accounts.google" in lower || "authorize" in lower || "login" in lower || "signin" in lower || "auth" in lower
    } ?: links.firstOrNull()
}

@Composable
private fun MarkdownPluginCard(catalogId: String, authUrl: String?) {
    val colors = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current
    val idLower = catalogId.trim().lowercase()
    val title = when (idLower) {
        "gdrive", "google-drive" -> "Google Drive"
        "github" -> "GitHub"
        "slack" -> "Slack"
        else -> catalogId.trim().replace('-', ' ').replaceFirstChar { it.uppercase() }
    }
    val subtitle = when {
        authUrl != null && idLower in setOf("gdrive", "google-drive") -> "云盘插件授权 · $catalogId"
        authUrl != null -> "插件账号授权 · $catalogId"
        else -> "插件连接 · 请点击下方授权链接登录"
    }
    DisableSelection {
        Surface(
            shape = LumaCardShape(18.dp),
            color = colors.surfaceContainerLow,
            border = BorderStroke(0.5.dp, colors.outlineVariant.copy(alpha = 0.55f)),
            modifier = Modifier.fillMaxWidth().testTag("plugin-card-$idLower"),
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier
                        .size(38.dp)
                        .background(colors.primary.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    MenuIcon(MenuSymbol.MCP, size = 18.dp)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (authUrl != null) {
                    Surface(
                        onClick = { runCatching { uriHandler.openUri(authUrl) } },
                        shape = CircleShape,
                        color = colors.primary,
                        contentColor = colors.onPrimary,
                    ) {
                        Text(
                            "Sign in",
                            Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        )
                    }
                } else {
                    Surface(
                        shape = CircleShape,
                        color = colors.surfaceContainerHighest,
                        contentColor = colors.onSurfaceVariant,
                    ) {
                        Text(
                            "待授权",
                            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}

private fun Node.children(): List<Node> = buildList {
    var child = firstChild
    while (child != null) {
        add(child)
        child = child.next
    }
}

@Composable
internal fun NativeMarkdown(source: String, onFileLink: ((String) -> Unit)? = null, presentation: MarkdownPresentation? = null) {
    val context = LocalContext.current
    val mediaContext = LocalMediaContext.current
    val mediaActions = LocalMediaActions.current
    val openMedia = LocalOpenMedia.current
    val latestOnFileLink by rememberUpdatedState(onFileLink)
    val hasFileLink = onFileLink != null
    val fileLink: ((String) -> Unit)? = remember(mediaContext, mediaActions, openMedia, hasFileLink) {
        if (!hasFileLink && mediaActions == null) null else { destination: String ->
            val resource = MediaResource(markdownDestination(destination)).inContext(mediaContext)
            if ((mediaIsImage(resource) || mediaIsPdf(resource)) && openMedia != null) openMedia(resource, null)
            else if (mediaActions != null) mediaActions.previewResource(resource)
            else latestOnFileLink?.invoke(destination)
        }
    }
    remember(context) { ensureLatexInitialized(context); true }
    val shown = presentation ?: rememberMarkdownPresentation(remember { Any() }, source)
    val nodes = remember(shown.document) { shown.document.blocks.map { it.node } }
    val offsets = remember(shown.document) { shown.document.blocks.map { it.offset } }
    val pluginAuthUrl = remember(nodes) { findPluginAuthUrl(nodes) }
    CompositionLocalProvider(LocalMarkdownPluginAuthUrl provides pluginAuthUrl) {
        LumaSelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                MarkdownChildren(nodes, shown.visibleUnits, fileLink, keys = offsets)
            }
        }
    }
}

@Composable
private fun MarkdownChildren(nodes: List<MarkdownAst>, budget: Int, onFileLink: ((String) -> Unit)?, keys: List<Int>? = null) {
    var remaining = budget
    nodes.forEachIndexed { index, child ->
        if (remaining > 0) {
            val full = remaining >= child.revealUnits
            key(keys?.get(index) ?: index) {
                MarkdownBlock(
                    node = child,
                    onFileLink = onFileLink,
                    budget = if (full) Int.MAX_VALUE else remaining,
                    isFirstBlock = index == 0,
                )
            }
            remaining = (remaining - child.revealUnits).coerceAtLeast(0)
        }
    }
}

@Composable
private fun MarkdownBlock(node: MarkdownAst, onFileLink: ((String) -> Unit)?, budget: Int, isFirstBlock: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val standaloneCatalogId = remember(node) { extractStandalonePluginCatalogId(node) }
    if (standaloneCatalogId != null) {
        MarkdownPluginCard(standaloneCatalogId, LocalMarkdownPluginAuthUrl.current)
        return
    }
    when (node.kind) {
        MarkdownKind.PARAGRAPH -> {
            val inlineCatalogIds = remember(node) { extractInlinePluginCatalogIds(node) }
            if (inlineCatalogIds.isEmpty()) {
                RichText(node, MaterialTheme.typography.bodyLarge, onFileLink, budget)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    RichText(node, MaterialTheme.typography.bodyLarge, onFileLink, budget)
                    inlineCatalogIds.forEach { id ->
                        MarkdownPluginCard(id, LocalMarkdownPluginAuthUrl.current)
                    }
                }
            }
        }
        MarkdownKind.HEADING -> Column(
            modifier = if (isFirstBlock) Modifier else Modifier.padding(top = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RichText(node, when (node.number) {
                1 -> MaterialTheme.typography.headlineMedium
                2 -> MaterialTheme.typography.titleLarge
                3 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            }, onFileLink, budget)
        }
        MarkdownKind.CODE_BLOCK -> {
            if (LocalMarkdownNestingDepth.current == 0 && isMarkdownContainerLanguage(node.info)) {
                MarkdownDocumentCard(
                    code = node.literal,
                    language = node.info,
                    visibleCharacters = budget,
                    onFileLink = onFileLink,
                )
            } else {
                CodeBlock(
                    code = node.literal,
                    language = node.info,
                    visibleCharacters = budget,
                    softWrap = isMarkdownContainerLanguage(node.info),
                )
            }
        }
        MarkdownKind.MATH -> if (budget >= node.revealUnits) FormulaBlock(node.literal)
        MarkdownKind.QUOTE -> {
            val quoteBudget = if (budget >= node.revealUnits) Int.MAX_VALUE else budget
            Box(
                Modifier.fillMaxWidth().clip(LumaCardShape(12.dp))
                    .background(colors.surfaceContainerLow.copy(alpha = 0.65f))
                    .drawBehind { drawRoundRect(colors.primary.copy(alpha = 0.55f), Offset.Zero, Size(2.5.dp.toPx(), size.height), CornerRadius(2.dp.toPx())) }
                    .padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            ) { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { MarkdownChildren(node.children, quoteBudget, onFileLink) } }
        }
        MarkdownKind.BULLET_LIST, MarkdownKind.ORDERED_LIST -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            var remaining = budget
            node.children.forEachIndexed { index, child ->
                val marker = if (node.kind == MarkdownKind.ORDERED_LIST) "${node.number + index}." else "•"
                if (remaining > 0) {
                    val itemFull = remaining >= marker.length + child.revealUnits
                    val shownMarker = if (itemFull) marker else marker.substring(0, markdownVisibleEnd(marker, remaining))
                    val bodyBudget = if (itemFull) Int.MAX_VALUE else (remaining - marker.length).coerceAtLeast(0)
                    key(index) {
                        MarkdownListItem(shownMarker, child.children, bodyBudget, onFileLink)
                    }
                }
                remaining = if (remaining == Int.MAX_VALUE) Int.MAX_VALUE else (remaining - marker.length - child.revealUnits).coerceAtLeast(0)
            }
        }
        MarkdownKind.TABLE -> MarkdownTable(node, onFileLink, budget)
        MarkdownKind.RULE -> HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.45f))
        MarkdownKind.HTML_BLOCK -> Text(node.literal.substring(0, markdownVisibleEnd(node.literal, budget)), style = MaterialTheme.typography.bodyLarge)
        else -> MarkdownChildren(node.children, budget, onFileLink)
    }
}

@Composable
private fun MarkdownListItem(
    marker: String,
    children: List<MarkdownAst>,
    bodyBudget: Int,
    onFileLink: ((String) -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth()) {
        Text(
            marker,
            Modifier.widthIn(min = 24.dp),
            color = colors.primary,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MarkdownChildren(children, bodyBudget, onFileLink)
        }
    }
}

internal fun nodeSignature(node: Node): String = buildString {
    fun literal(kind: Char, value: String) { append(kind); append(value.length); append(':'); append(value) }
    fun walk(n: Node) {
        when (n) {
            is org.commonmark.node.Text -> { literal('T', n.literal) }
            is SoftLineBreak -> append('\n')
            is HardLineBreak -> append("\n\n")
            is Code -> { literal('C', n.literal) }
            is StrongEmphasis -> { append("B{"); n.children().forEach(::walk); append('}') }
            is Emphasis -> { append("I{"); n.children().forEach(::walk); append('}') }
            is Strikethrough -> { append("S{"); n.children().forEach(::walk); append('}') }
            is Link -> { literal('L', n.destination); append('{'); n.children().forEach(::walk); append('}') }
            is org.commonmark.node.Image -> { literal('M', n.destination); append('{'); n.children().forEach(::walk); append('}') }
            is HtmlInline -> { literal('H', n.literal) }
            else -> { append(n.javaClass.simpleName); append('{'); n.children().forEach(::walk); append('}') }
        }
    }
    walk(node)
}

@Composable
internal fun RichText(node: Node, style: TextStyle, onFileLink: ((String) -> Unit)?) =
    RichText(remember(node) { freezeMarkdownNode(node) }, style, onFileLink)

@Composable
private fun RichText(
    node: MarkdownAst,
    style: TextStyle,
    onFileLink: ((String) -> Unit)?,
    budget: Int = Int.MAX_VALUE,
    resourceCards: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val isDark = codePaletteDark(colors)
    val nested = LocalMarkdownNestingDepth.current > 0
    val color = colors.onSurface
    val inlineCodeColor = if (isDark) Color(0xFFE8A874) else Color(0xFF924418)
    val codeBackground = when {
        isDark && nested -> Color(0xFF242428)
        isDark -> colors.surfaceContainerHigh.copy(alpha = 0.82f)
        nested -> colors.surfaceContainerHighest.copy(alpha = 0.78f)
        else -> colors.surfaceContainerHigh.copy(alpha = 0.78f)
    }
    val mono = terminalFont()
    val context = LocalContext.current
    val density = LocalDensity.current
    val sizePx = with(density) { (if (style.fontSize.isUnspecified) 16.sp else style.fontSize).toPx() }
    val signature = node
    val rich = remember(signature, color, inlineCodeColor, mono, sizePx, onFileLink, context) {
        buildRichText(context, node, color, inlineCodeColor, mono, sizePx, onFileLink)
    }
    val inline = remember(rich, density) {
        if (rich.second.isEmpty()) emptyMap()
        else rich.second.mapValues { (_, formula) ->
            InlineTextContent(
                Placeholder(
                    with(density) { formula.widthPx.toSp() },
                    with(density) { formula.heightPx.toSp() },
                    formula.verticalAlign,
                ),
            ) {
                Image(
                    bitmap = formula.bitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
    val visible = remember(rich.first, budget) { visibleMarkdownText(rich.first, budget) }
    val codeRanges = remember(visible) { visible.getStringAnnotations(INLINE_CODE_BACKGROUND, 0, visible.length) }
    val layout = remember { mutableStateOf<TextLayoutResult?>(null) }
    val backing = if (codeRanges.isEmpty()) Modifier else Modifier.drawWithCache {
        val corner = CornerRadius(5.dp.toPx(), 5.dp.toPx())
        val vInset = 1.5.dp.toPx()
        val hPad = 3.dp.toPx()
        val rects = buildList {
            val result = layout.value ?: return@buildList
            val raw = result.layoutInput.text.text
            for (range in codeRanges) {
                val start = range.start.coerceIn(0, raw.length)
                val end = range.end.coerceIn(start, raw.length)
                if (start >= end) continue
                val firstLine = result.getLineForOffset(start)
                val lastLine = result.getLineForOffset((end - 1).coerceAtLeast(start))
                for (line in firstLine..lastLine) {
                    var s = maxOf(start, result.getLineStart(line))
                    var e = minOf(end, result.getLineEnd(line, visibleEnd = true))
                    while (s < e && raw[s].isWhitespace()) s++
                    while (e > s && raw[e - 1].isWhitespace()) e--
                    if (s >= e) continue
                    val bounds = result.getPathForRange(s, e).getBounds()
                    if (!bounds.isEmpty) {
                        val top = result.getLineTop(line) + vInset
                        val bottom = (result.getLineBottom(line) - vInset).coerceAtLeast(top + 1f)
                        val left = (bounds.left - hPad).coerceAtLeast(0f)
                        val right = (bounds.right + hPad).coerceAtMost(result.size.width.toFloat())
                        add(androidx.compose.ui.geometry.Rect(left, top, right, bottom))
                    }
                }
            }
        }
        onDrawBehind {
            rects.forEach { rect ->
                drawRoundRect(
                    color = codeBackground,
                    topLeft = rect.topLeft,
                    size = rect.size,
                    cornerRadius = corner,
                )
            }
        }
    }
    val media = remember(signature, resourceCards) { markdownImages(node, resourceCards) }
    val mediaBudget = if (budget == Int.MAX_VALUE) media.size else (budget - node.inlineUnits).coerceIn(0, media.size)
    if (visible.isNotBlank() && mediaBudget > 0) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(visible, modifier = backing, style = style, color = color, inlineContent = inline,
                onTextLayout = { if (codeRanges.isNotEmpty()) layout.value = it })
            DisableSelection { MessageMedia(media.take(mediaBudget)) }
        }
    } else {
        if (visible.isNotBlank()) Text(visible, modifier = backing, style = style, color = color, inlineContent = inline,
            onTextLayout = { if (codeRanges.isNotEmpty()) layout.value = it })
        if (mediaBudget > 0) DisableSelection { MessageMedia(media.take(mediaBudget)) }
    }
}

internal fun visibleMarkdownText(text: AnnotatedString, budget: Int): AnnotatedString {
    var end = markdownVisibleEnd(text.text, budget)
    text.getStringAnnotations("androidx.compose.foundation.text.inlineContent", 0, text.length).forEach {
        if (end > it.start && end < it.end) end = it.start
    }
    return if (end == text.length) text else text.subSequence(0, end)
}

internal fun markdownDestination(destination: String): String =
    if (!destination.contains(":") && destination.contains('%')) {
        runCatching { android.net.Uri.decode(destination) }.getOrElse {
            runCatching { java.net.URLDecoder.decode(destination, Charsets.UTF_8.name()) }.getOrDefault(destination)
        }
    } else destination

internal fun markdownResourceLink(rawDestination: String): Boolean {
    val destination = markdownDestination(rawDestination)
    if (destination.startsWith("#")) return false
    if (destination.startsWith("file:") || destination.startsWith("sandbox:") || destination.startsWith('/')) return true
    if (!destination.contains(":")) return destination.substringAfterLast('/').contains('.')
    return (destination.startsWith("https://") || destination.startsWith("http://")) &&
        destination.substringBefore('?').substringAfterLast('.').lowercase() in setOf("pdf", "png", "jpg", "jpeg", "webp", "svg", "gif", "mp4", "webm", "wav", "mp3", "zip", "apk", "csv", "json", "txt", "md")
}

internal fun markdownImages(node: Node): List<MediaResource> = buildList {
    fun visit(current: Node) {
        if (current is org.commonmark.node.Image) {
            val alt = buildString {
                fun text(n: Node) { if (n is org.commonmark.node.Text) append(n.literal) else n.children().forEach(::text) }
                current.children().forEach(::text)
            }
            add(MediaResource(markdownDestination(current.destination), alt, "image/*"))
        } else if (current is Link && markdownResourceLink(current.destination)) {
            add(MediaResource(markdownDestination(current.destination)))
        } else current.children().forEach(::visit)
    }
    visit(node)
}

internal fun markdownImages(node: MarkdownAst, resourceLinks: Boolean = true): List<MediaResource> = buildList {
    fun visit(current: MarkdownAst) {
        when {
            current.kind == MarkdownKind.IMAGE -> {
                fun alt(n: MarkdownAst): String = if (n.kind == MarkdownKind.TEXT) n.literal else n.children.joinToString("") { alt(it) }
                add(MediaResource(markdownDestination(current.destination), alt(current), "image/*"))
            }
            current.kind == MarkdownKind.LINK && markdownResourceLink(current.destination) -> {
                if (resourceLinks) add(MediaResource(markdownDestination(current.destination)))
            }
            else -> current.children.forEach(::visit)
        }
    }
    visit(node)
}

internal const val INLINE_CODE_BACKGROUND = "luma.inline-code-background"
private val inlineMath = Regex("\\\\\\((.+?)\\\\\\)|(?<!\\\\)\\$([^$\\n]+?)\\$")
private fun buildRichText(
    context: android.content.Context,
    node: MarkdownAst,
    color: Color,
    accent: Color,
    mono: FontFamily,
    sizePx: Float,
    onFileLink: ((String) -> Unit)?,
): Pair<AnnotatedString, Map<String, RenderedFormula>> {
    val images = linkedMapOf<String, RenderedFormula>()
    val argb = color.toArgb()
    val result = buildAnnotatedString {
        fun appendCodeFallback(literal: String) {
            pushStringAnnotation(INLINE_CODE_BACKGROUND, "")
            withStyle(
                SpanStyle(
                    fontFamily = mono,
                    fontFeatureSettings = com.termux.view.BundledTerminalFonts.FEATURE_SETTINGS,
                    fontSize = 0.88.em,
                    letterSpacing = 0.sp,
                    fontWeight = FontWeight.Medium,
                    color = accent,
                ),
            ) { append(" $literal ") }
            pop()
        }
        fun renderText(rawText: String) {
            val text = if ("plugin-card" in rawText) pluginCardStripInlineRegex.replace(rawText, "") else rawText
            if (text.indexOf('$') < 0 && text.indexOf("\\(") < 0) {
                append(text)
                return
            }
            var cursor = 0
            inlineMath.findAll(text).forEach { match ->
                append(text.substring(cursor, match.range.first))
                val expression = match.groups[1]?.value ?: match.groups[2]!!.value
                if (expression.length > MAX_INLINE_MATH_CHARS) {
                    appendCodeFallback(expression)
                } else {
                    val rendered = renderAndCacheFormula(context, expression, sizePx, argb, padding = 0)
                    if (rendered == null) {
                        append("[公式未能排版：${match.value}]")
                    } else {
                        val id = "formula-${images.size}"
                        images[id] = rendered
                        appendInlineContent(id, match.value)
                    }
                }
                cursor = match.range.last + 1
            }
            append(text.substring(cursor))
        }
        fun visit(current: MarkdownAst) {
            when (current.kind) {
                MarkdownKind.TEXT -> renderText(current.literal)
                MarkdownKind.SOFT_BREAK -> append(" ")
                MarkdownKind.HARD_BREAK -> append("\n")
                MarkdownKind.CODE -> {
                    pushStringAnnotation(INLINE_CODE_BACKGROUND, "")
                    withStyle(
                        SpanStyle(
                            fontFamily = mono,
                            fontFeatureSettings = com.termux.view.BundledTerminalFonts.FEATURE_SETTINGS,
                            fontSize = 0.88.em,
                            letterSpacing = 0.sp,
                            fontWeight = FontWeight.Medium,
                            color = accent,
                        ),
                    ) { append(" ${current.literal} ") }
                    pop()
                }
                MarkdownKind.STRONG -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { current.children.forEach(::visit) }
                MarkdownKind.EMPHASIS -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { current.children.forEach(::visit) }
                MarkdownKind.STRIKE -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { current.children.forEach(::visit) }
                MarkdownKind.LINK -> {
                    val destination = current.destination
                    val linkStyle = TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline))
                    when {
                        destination.startsWith("https://") || destination.startsWith("http://") -> withLink(LinkAnnotation.Url(destination, linkStyle)) {
                            current.children.forEach(::visit)
                        }
                        (!destination.contains(":") || destination.startsWith("file:") || destination.startsWith("sandbox:")) && onFileLink != null -> withLink(LinkAnnotation.Clickable(destination, linkStyle) { onFileLink(destination) }) {
                            current.children.forEach(::visit)
                        }
                        else -> current.children.forEach(::visit)
                    }
                }
                MarkdownKind.IMAGE -> Unit
                MarkdownKind.HTML_INLINE -> {
                    if (!pluginCardAnyTagRegex.matches(current.literal.trim())) {
                        append(current.literal)
                    }
                }
                else -> current.children.forEach(::visit)
            }
        }
        node.children.forEach(::visit)
    }
    return result to images
}

@Composable
private fun MarkdownTable(node: MarkdownAst, onFileLink: ((String) -> Unit)?, budget: Int) {
    val rows = remember(node) {
        node.children.flatMap { it.children }.filter { it.kind == MarkdownKind.TABLE_ROW }.map { row ->
            row.children.filter { it.kind == MarkdownKind.TABLE_CELL }
        }
    }
    var available = budget
    val visibleRows = rows.takeWhile { cells ->
        val show = available > 0
        available = (available - cells.sumOf { it.revealUnits }).coerceAtLeast(0)
        show
    }
    val columnCount = remember(rows) { rows.maxOfOrNull { it.size } ?: 0 }
    if (rows.isEmpty() || columnCount == 0) return
    val colors = MaterialTheme.colorScheme
    val borderColor = colors.outlineVariant.copy(alpha = 0.50f)
    val headerBg = colors.surfaceContainerHigh.copy(alpha = 0.55f)
    val stripeBg = colors.surfaceContainerLow.copy(alpha = 0.45f)
    val density = LocalDensity.current
    val hPadPx = with(density) { 14.dp.roundToPx() }
    val vPadPx = with(density) { 10.dp.roundToPx() }
    val minColPx = with(density) { 72.dp.roundToPx() }
    val maxColPx = with(density) { 260.dp.roundToPx() }
    val strokePx = with(density) { 1.dp.toPx() }
    val gridOffsets = remember { mutableStateOf(intArrayOf() to intArrayOf()) }
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        LiquidSurface(
            cornerRadius = 14.dp,
            tonalTint = colors.surfaceContainerLow.copy(alpha = 0.90f),
        ) {
            androidx.compose.ui.layout.Layout(
                modifier = Modifier.drawBehind {
                    val (colOffsets, rowOffsets) = gridOffsets.value
                    if (rowOffsets.isNotEmpty()) {
                        val headerH = (if (rowOffsets.size > 1) rowOffsets[1] else size.height.toInt()).toFloat()
                        drawRect(headerBg, Offset.Zero, Size(size.width, headerH))
                        for (r in 1 until rowOffsets.size) {
                            val top = rowOffsets[r].toFloat()
                            val bottom = (if (r + 1 < rowOffsets.size) rowOffsets[r + 1].toFloat() else size.height)
                            if (r % 2 == 1) {
                                drawRect(stripeBg, Offset(0f, top), Size(size.width, bottom - top))
                            }
                            drawLine(
                                borderColor,
                                Offset(0f, top),
                                Offset(size.width, top),
                                strokeWidth = if (r == 1) strokePx * 1.3f else strokePx,
                            )
                        }
                        for (c in 1 until colOffsets.size) {
                            val x = colOffsets[c].toFloat()
                            drawLine(
                                borderColor,
                                Offset(x, 0f),
                                Offset(x, size.height),
                                strokeWidth = strokePx,
                            )
                        }
                        drawPath(
                            path = androidx.compose.ui.graphics.Path().apply { addSquircleRect(size.width, size.height, 14.dp.toPx()) },
                            color = borderColor,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokePx),
                        )
                    }
                },
                content = {
                    var remaining = budget
                    visibleRows.forEach { cells ->
                        for (c in 0 until columnCount) {
                            val cell = cells.getOrNull(c)
                            Box {
                                if (cell != null && remaining > 0) {
                                    RichText(
                                        cell,
                                        MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = if (cell.header) FontWeight.Bold else FontWeight.Normal,
                                        ),
                                        onFileLink,
                                        if (remaining >= cell.revealUnits) Int.MAX_VALUE else remaining,
                                        resourceCards = false,
                                    )
                                }
                            }
                            remaining = (remaining - (cell?.revealUnits ?: 0)).coerceAtLeast(0)
                        }
                    }
                },
            ) { measurables, _ ->
                val rowCount = visibleRows.size
                val colWidths = IntArray(columnCount) { minColPx }
                val rowHeights = IntArray(rowCount)
                val placeables = Array(rowCount) { r ->
                    Array(columnCount) { c ->
                        val p = measurables[r * columnCount + c].measure(
                            androidx.compose.ui.unit.Constraints(minWidth = 0, maxWidth = maxColPx),
                        )
                        val w = p.width + hPadPx * 2
                        if (w > colWidths[c]) colWidths[c] = w
                        val h = p.height + vPadPx * 2
                        if (h > rowHeights[r]) rowHeights[r] = h
                        p
                    }
                }
                val colOffsets = IntArray(columnCount)
                var totalWidth = 0
                for (c in 0 until columnCount) {
                    colOffsets[c] = totalWidth
                    totalWidth += colWidths[c]
                }
                val rowOffsets = IntArray(rowCount)
                var totalHeight = 0
                for (r in 0 until rowCount) {
                    rowOffsets[r] = totalHeight
                    totalHeight += rowHeights[r]
                }
                gridOffsets.value = colOffsets to rowOffsets
                layout(totalWidth, totalHeight) {
                    for (r in 0 until rowCount) {
                        for (c in 0 until columnCount) {
                            val p = placeables[r][c]
                            val cell = visibleRows[r].getOrNull(c)
                            val colW = colWidths[c]
                            val rowH = rowHeights[r]
                            val x = colOffsets[c] + when (cell?.alignment) {
                                "CENTER" -> (colW - p.width) / 2
                                "RIGHT" -> colW - hPadPx - p.width
                                else -> hPadPx
                            }
                            val y = rowOffsets[r] + (rowH - p.height) / 2
                            p.placeRelative(x, y)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun MarkdownDocumentCard(
    code: String,
    language: String = "markdown",
    visibleCharacters: Int = Int.MAX_VALUE,
    onFileLink: ((String) -> Unit)? = null,
    initiallyExpanded: Boolean = LocalMarkdownCardInitiallyExpanded.current,
    initiallyShowSource: Boolean = false,
) {
    val visibleEnd = markdownVisibleEnd(code, visibleCharacters)
    val visibleCode = remember(code, visibleEnd) {
        if (visibleEnd == code.length) code else code.substring(0, visibleEnd)
    }
    val isComplete = visibleEnd == code.length
    val exceedsPreviewCap = visibleCode.length > MAX_CARD_MARKDOWN_CHARS
    val summary = remember(visibleCode) {
        summarizeMarkdownCard(if (exceedsPreviewCap) visibleCode.substring(0, MAX_CARD_MARKDOWN_CHARS) else visibleCode)
    }
    val cardIdentity = remember { Any() }
    val clipboard = LocalClipboardManager.current
    val colors = MaterialTheme.colorScheme
    val dark = codePaletteDark(colors)
    val onManualLayoutToggle = LocalManualLayoutToggle.current
    var expanded by rememberSaveable(language) { mutableStateOf(initiallyExpanded) }
    var showSource by rememberSaveable(language) { mutableStateOf(initiallyShowSource) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2200)
            copied = false
        }
    }
    val outerHeaderBg = if (dark) Color(0xFF1C1C1E) else colors.surfaceContainerHigh.copy(alpha = 0.48f)
    val outerBodyBg = if (dark) Color(0xFF161618) else colors.surfaceContainerLow.copy(alpha = 0.52f)
    val borderColor = colors.outlineVariant.copy(alpha = if (dark) 0.38f else 0.45f)
    val badgeBg = if (dark) Color(0xFF222225) else colors.surfaceContainerHighest.copy(alpha = 0.65f)
    val excerptWellBg = if (dark) Color(0xFF111113) else colors.surface.copy(alpha = 0.68f)
    val actionBtnBg = if (dark) Color(0xFF222225) else colors.surfaceContainerHigh.copy(alpha = 0.62f)
    val trackBg = if (dark) Color.White.copy(alpha = 0.07f) else Color.Black.copy(alpha = 0.06f)
    val thumbBg = if (dark) Color(0xFF2C2C2A) else colors.surface
    val thumbBorder = if (dark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.08f)
    val copiedTint = if (dark) Color(0xFF34D399) else Color(0xFF10B981)
    val headerCaption = language.trim().substringBefore(' ').lowercase().ifBlank { "markdown" }
    val copyMarkdown = {
        runCatching { clipboard.setText(AnnotatedString(code.substring(0, minOf(visibleEnd, 262_144)))) }
        copied = true
    }
    val effectiveShowSource = showSource || exceedsPreviewCap
    val retainedPresentationState = remember { mutableStateOf<MarkdownPresentation?>(null) }
    Surface(
        shape = LumaCardShape(14.dp),
        color = outerBodyBg,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("markdown-document-card")
            .semantics {
                stateDescription = if (expanded) {
                    if (effectiveShowSource) "已展开 Markdown 源码" else "已展开 Markdown 预览"
                } else {
                    "已折叠 Markdown 文档卡片"
                }
            },
    ) {
        TopReveal(
            expanded = expanded,
            collapsedContent = {
                DisableSelection {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(LumaCardShape(10.dp))
                                .clickable {
                                    onManualLayoutToggle?.invoke()
                                    expanded = true
                                },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(11.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(36.dp)
                                    .clip(LumaCardShape(10.dp))
                                    .background(badgeBg)
                                    .border(0.5.dp, borderColor, LumaCardShape(10.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                CompositionLocalProvider(LocalContentColor provides colors.primary) {
                                    MenuIcon(MenuSymbol.ARTICLE, size = 18.dp)
                                }
                            }
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    summary.title,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = colors.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    summary.subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.onSurfaceVariant,
                                    fontFamily = terminalFont(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (summary.excerpt.isNotBlank()) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(LumaCardShape(10.dp))
                                    .background(excerptWellBg, LumaCardShape(10.dp))
                                    .border(0.5.dp, borderColor, LumaCardShape(10.dp))
                                    .clickable {
                                        onManualLayoutToggle?.invoke()
                                        expanded = true
                                    }
                                    .padding(horizontal = 11.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    summary.excerpt,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                Modifier
                                    .weight(1f)
                                    .height(34.dp)
                                    .clip(LumaCardShape(9.dp))
                                    .background(actionBtnBg, LumaCardShape(9.dp))
                                    .border(0.5.dp, borderColor, LumaCardShape(9.dp))
                                    .clickable {
                                        onManualLayoutToggle?.invoke()
                                        expanded = true
                                    }
                                    .padding(horizontal = 10.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant) {
                                    MenuIcon(MenuSymbol.DOWN, size = 14.dp)
                                }
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    "展开",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                                    color = colors.onSurface,
                                    maxLines = 1,
                                )
                            }
                            Row(
                                Modifier
                                    .weight(1f)
                                    .height(34.dp)
                                    .clip(LumaCardShape(9.dp))
                                    .background(actionBtnBg, LumaCardShape(9.dp))
                                    .border(0.5.dp, borderColor, LumaCardShape(9.dp))
                                    .clickable(onClick = copyMarkdown)
                                    .semantics { contentDescription = "复制" }
                                    .padding(horizontal = 10.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CompositionLocalProvider(LocalContentColor provides if (copied) copiedTint else colors.onSurfaceVariant) {
                                    ComposerGlyph(if (copied) "check" else "copy")
                                }
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    if (copied) "已复制" else "复制",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                                    color = if (copied) copiedTint else colors.onSurface,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            },
        ) {
            val innerPresentation = if (!effectiveShowSource && expanded) {
                rememberMarkdownPresentation(
                    identity = cardIdentity,
                    source = visibleCode,
                    complete = isComplete,
                    animate = false,
                ).also { retainedPresentationState.value = it }
            } else if (!effectiveShowSource) {
                retainedPresentationState.value
            } else {
                null
            }
            Column(Modifier.fillMaxWidth()) {
                DisableSelection {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(38.dp)
                            .background(outerHeaderBg)
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            Modifier.weight(1f, fill = false),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            CompositionLocalProvider(LocalContentColor provides colors.primary) {
                                MenuIcon(MenuSymbol.ARTICLE, size = 15.dp)
                            }
                            Text(
                                headerCaption,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                                color = colors.onSurfaceVariant,
                                fontFamily = terminalFont(),
                                maxLines = 1,
                            )
                            Text(
                                "· ${summary.lineCount} 行",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant.copy(alpha = 0.72f),
                                fontFamily = terminalFont(),
                                maxLines = 1,
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (!exceedsPreviewCap) {
                                Row(
                                    Modifier
                                        .height(26.dp)
                                        .clip(CircleShape)
                                        .background(trackBg)
                                        .padding(1.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        Modifier
                                            .fillMaxHeight()
                                            .clip(CircleShape)
                                            .then(
                                                if (!showSource) {
                                                    Modifier
                                                        .background(thumbBg, CircleShape)
                                                        .border(0.5.dp, thumbBorder, CircleShape)
                                                } else {
                                                    Modifier
                                                },
                                            )
                                            .clickable {
                                                if (showSource) {
                                                    onManualLayoutToggle?.invoke()
                                                    showSource = false
                                                }
                                            }
                                            .padding(horizontal = 9.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            "预览",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = if (!showSource) FontWeight.Medium else FontWeight.Normal,
                                            ),
                                            color = if (!showSource) colors.onSurface else colors.onSurfaceVariant,
                                        )
                                    }
                                    Box(
                                        Modifier
                                            .fillMaxHeight()
                                            .clip(CircleShape)
                                            .then(
                                                if (showSource) {
                                                    Modifier
                                                        .background(thumbBg, CircleShape)
                                                        .border(0.5.dp, thumbBorder, CircleShape)
                                                } else {
                                                    Modifier
                                                },
                                            )
                                            .clickable {
                                                if (!showSource) {
                                                    onManualLayoutToggle?.invoke()
                                                    showSource = true
                                                }
                                            }
                                            .padding(horizontal = 9.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            "源码",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = if (showSource) FontWeight.Medium else FontWeight.Normal,
                                            ),
                                            color = if (showSource) colors.onSurface else colors.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            Row(
                                Modifier
                                    .height(26.dp)
                                    .clip(LumaCardShape(8.dp))
                                    .background(trackBg)
                                    .clickable {
                                        onManualLayoutToggle?.invoke()
                                        expanded = false
                                    }
                                    .padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant) {
                                    MenuIcon(MenuSymbol.DOWN, size = 13.dp, rotationProvider = { 180f })
                                }
                                Text(
                                    "收起",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                    color = colors.onSurfaceVariant,
                                )
                            }
                            Box(
                                Modifier
                                    .size(28.dp)
                                    .clip(LumaCardShape(8.dp))
                                    .clickable(onClick = copyMarkdown)
                                    .semantics { contentDescription = "复制" },
                                contentAlignment = Alignment.Center,
                            ) {
                                CompositionLocalProvider(LocalContentColor provides if (copied) copiedTint else colors.onSurfaceVariant) {
                                    ComposerGlyph(if (copied) "check" else "copy")
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(thickness = 1.dp, color = borderColor)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    MarkdownModeCrossfade(
                        showSource = effectiveShowSource,
                        previewContent = {
                            if (innerPresentation != null) {
                                CompositionLocalProvider(LocalMarkdownNestingDepth provides LocalMarkdownNestingDepth.current + 1) {
                                    NativeMarkdown(
                                        source = visibleCode,
                                        onFileLink = onFileLink,
                                        presentation = innerPresentation,
                                    )
                                }
                            }
                        },
                        sourceContent = {
                            val truncatedSource = if (exceedsPreviewCap) visibleCode.substring(0, MAX_CARD_MARKDOWN_CHARS) else visibleCode
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                LumaSelectionContainer {
                                    Text(
                                        rememberHighlightedCode(truncatedSource, language, colors),
                                        Modifier.fillMaxWidth(),
                                        fontFamily = terminalFont(),
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFeatureSettings = com.termux.view.BundledTerminalFonts.FEATURE_SETTINGS,
                                            letterSpacing = 0.sp,
                                            lineHeight = 19.sp,
                                        ),
                                        softWrap = true,
                                    )
                                }
                                if (exceedsPreviewCap) {
                                    Text(
                                        "… 文档超过 128 KiB，已切换为截断源码视图（共 ${visibleCode.length / 1024} KiB）",
                                        fontFamily = terminalFont(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.onSurfaceVariant.copy(alpha = 0.75f),
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkdownModeCrossfade(
    showSource: Boolean,
    previewContent: @Composable () -> Unit,
    sourceContent: @Composable () -> Unit,
) {
    var sourceEverVisible by remember { mutableStateOf(showSource) }
    if (showSource) sourceEverVisible = true
    val animatedState = animateFloatAsState(
        targetValue = if (showSource) 1f else 0f,
        animationSpec = revealSpring(),
        label = "markdown-mode-reveal",
    )
    val currentFraction: () -> Float = remember(animatedState) {
        { revealFraction(animatedState.value).coerceIn(0f, 1f) }
    }
    val turnReaders = LocalTurnLayoutReaders.current
    if (turnReaders != null && sourceEverVisible) {
        DisposableEffect(turnReaders, currentFraction) {
            val reader: () -> Any? = { currentFraction() }
            turnReaders.add(reader)
            onDispose { turnReaders.remove(reader) }
        }
    }
    val turnAnimReaders = LocalTurnAnimatingReaders.current
    if (turnAnimReaders != null && sourceEverVisible) {
        val latestShowSource by rememberUpdatedState(showSource)
        DisposableEffect(turnAnimReaders, currentFraction) {
            val animReader: () -> Boolean = {
                val targetF = if (latestShowSource) 1f else 0f
                abs(currentFraction() - targetF) > 0.001f
            }
            turnAnimReaders.add(animReader)
            onDispose { turnAnimReaders.remove(animReader) }
        }
    }
    androidx.compose.ui.layout.Layout(
        content = {
            Box(
                Modifier
                    .layoutId("preview")
                    .graphicsLayer {
                        val f = currentFraction()
                        alpha = (1f - f / 0.35f).coerceIn(0f, 1f)
                    }
                    .then(if (!showSource) Modifier else Modifier.clearAndSetSemantics {}),
            ) { previewContent() }
            if (sourceEverVisible) {
                Box(
                    Modifier
                        .layoutId("source")
                        .graphicsLayer {
                            val f = currentFraction()
                            alpha = ((f - 0.25f) / 0.75f).coerceIn(0f, 1f)
                        }
                        .then(if (showSource) Modifier else Modifier.clearAndSetSemantics {}),
                ) { sourceContent() }
            }
        },
        modifier = Modifier.clipToBounds(),
    ) { measurables, constraints ->
        val loose = constraints.copy(minHeight = 0)
        val previewPlaceable = measurables.firstOrNull { it.layoutId == "preview" }?.measure(loose)
        val sourcePlaceable = measurables.firstOrNull { it.layoutId == "source" }?.measure(loose)
        val previewH = previewPlaceable?.height ?: 0
        val sourceH = sourcePlaceable?.height ?: previewH
        val f = currentFraction()
        val height = (previewH + (sourceH - previewH) * f).roundToInt()
        val width = maxOf(previewPlaceable?.width ?: 0, sourcePlaceable?.width ?: 0, constraints.minWidth)
        val previewAlpha = (1f - f / 0.35f).coerceIn(0f, 1f)
        val sourceAlpha = ((f - 0.25f) / 0.75f).coerceIn(0f, 1f)
        layout(width, constraints.constrainHeight(height)) {
            if (previewAlpha > 0f) previewPlaceable?.placeRelative(0, 0)
            if (sourceAlpha > 0f) sourcePlaceable?.placeRelative(0, 0)
        }
    }
}

@Composable
internal fun CodeBlock(
    code: String,
    language: String = "",
    bodyVerticalScrollState: ScrollState = rememberScrollState(),
    bodyHorizontalScrollState: ScrollState = rememberScrollState(),
    visibleCharacters: Int = Int.MAX_VALUE,
    caption: String = language.ifBlank { "代码" },
    softWrap: Boolean = false,
) {
    val visibleEnd = markdownVisibleEnd(code, visibleCharacters)
    val clipboard = LocalClipboardManager.current
    val colors = MaterialTheme.colorScheme
    val dark = codePaletteDark(colors)
    val nested = LocalMarkdownNestingDepth.current > 0
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2200)
            copied = false
        }
    }
    val headerBg = when {
        dark && nested -> Color(0xFF131315)
        dark -> Color(0xFF18181A)
        nested -> colors.surfaceContainerHighest.copy(alpha = 0.72f)
        else -> colors.surfaceContainerHigh.copy(alpha = 0.55f)
    }
    val bodyBg = when {
        dark && nested -> Color(0xFF0C0C0E)
        dark -> Color(0xFF121214)
        nested -> colors.surfaceContainer.copy(alpha = 0.92f)
        else -> colors.surfaceContainerLow.copy(alpha = 0.92f)
    }
    val borderColor = colors.outlineVariant.copy(alpha = if (dark) 0.40f else 0.45f)
    val copiedTint = if (dark) Color(0xFF34D399) else Color(0xFF10B981)
    val headerCaption = if (caption == language && language.isNotBlank()) caption.lowercase() else caption
    Surface(
        shape = LumaCardShape(14.dp),
        color = bodyBg,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .background(headerBg)
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant.copy(alpha = 0.82f)) {
                        MenuIcon(codeBlockSymbol(language, caption), size = 15.dp)
                    }
                    Text(
                        headerCaption,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                        color = colors.onSurfaceVariant,
                        fontFamily = terminalFont(),
                    )
                }
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(LumaCardShape(8.dp))
                        .clickable {
                            runCatching { clipboard.setText(AnnotatedString(code.substring(0, minOf(visibleEnd, 262_144)))) }
                            copied = true
                        }
                        .semantics { contentDescription = "复制" },
                    contentAlignment = Alignment.Center,
                ) {
                    CompositionLocalProvider(LocalContentColor provides if (copied) copiedTint else colors.onSurfaceVariant) {
                        ComposerGlyph(if (copied) "check" else "copy")
                    }
                }
            }
            HorizontalDivider(thickness = 1.dp, color = borderColor)
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 11.dp)
                    .heightIn(max = 242.dp)
                    .clipToBounds()
                    .codeBlockScrollBoundary(bodyVerticalScrollState, bodyHorizontalScrollState)
                    .verticalScroll(bodyVerticalScrollState),
            ) {
                LumaSelectionContainer {
                    Text(
                        rememberHighlightedCode(code, language, colors).let { if (visibleEnd == code.length) it else it.subSequence(0, visibleEnd) },
                        Modifier
                            .fillMaxWidth()
                            .then(if (softWrap) Modifier else Modifier.horizontalScroll(bodyHorizontalScrollState)),
                        fontFamily = terminalFont(),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFeatureSettings = com.termux.view.BundledTerminalFonts.FEATURE_SETTINGS,
                            letterSpacing = 0.sp,
                            lineHeight = 19.sp,
                        ),
                        softWrap = softWrap,
                    )
                }
            }
        }
    }
}

@Composable
internal fun rememberHighlightedCode(code: String, language: String, colors: ColorScheme): AnnotatedString {
    val dark = codePaletteDark(colors)
    var mountedCode by remember { mutableStateOf(code) }
    val streaming = code != mountedCode
    val immediate = remember(code, language, dark) {
        cachedHighlight(code, language, dark)
            ?: if (!streaming && code.length <= IMMEDIATE_HIGHLIGHT_LIMIT) highlightedCode(code, language, dark) else null
    }
    SideEffect { mountedCode = code }
    val result = remember(code, language, dark) { mutableStateOf(immediate) }
    LaunchedEffect(result) {
        if (result.value != null) return@LaunchedEffect
        if (streaming) delay(80)
        result.value = withContext(Dispatchers.Default) {
            val context = coroutineContext
            highlightedCode(code, language, dark) { context.ensureActive() }
        }
    }
    return result.value ?: remember(code) { AnnotatedString(code) }
}

private sealed interface FormulaBlockState {
    data object Loading : FormulaBlockState
    data class Ready(val formula: RenderedFormula) : FormulaBlockState
    data object Failed : FormulaBlockState
}

@Composable
private fun FormulaBlock(expression: String) {
    if (expression.length > MAX_BLOCK_MATH_CHARS) {
        CodeBlock(expression, "LaTeX")
        return
    }
    val context = LocalContext.current
    val inspection = LocalInspectionMode.current
    val density = LocalDensity.current
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val size = with(density) { 18.sp.toPx() }
    val immediate = remember(expression, size, color, inspection, context) {
        getCachedFormula(expression, size, color, padding = 4)?.let { FormulaBlockState.Ready(it) }
            ?: if (inspection) {
                renderAndCacheFormula(context, expression, size, color, padding = 4)
                    ?.let { FormulaBlockState.Ready(it) }
                    ?: FormulaBlockState.Failed
            } else {
                null
            }
    }
    val state by produceState<FormulaBlockState>(
        initialValue = immediate ?: FormulaBlockState.Loading,
        expression,
        size,
        color,
        inspection,
        context,
    ) {
        if (immediate != null) {
            value = immediate
            return@produceState
        }
        val rendered = withContext(Dispatchers.Default) {
            renderAndCacheFormula(context, expression, size, color, padding = 4)
        }
        value = if (rendered != null) FormulaBlockState.Ready(rendered) else FormulaBlockState.Failed
    }
    when (val current = state) {
        FormulaBlockState.Loading -> {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 6.dp),
            ) {
                Text(
                    expression,
                    fontFamily = terminalFont(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                )
            }
        }
        FormulaBlockState.Failed -> {
            Text("公式未能排版", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            CodeBlock(expression, "LaTeX")
        }
        is FormulaBlockState.Ready -> {
            val formula = current.formula
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 6.dp),
            ) {
                Image(
                    bitmap = formula.bitmap,
                    contentDescription = "公式：$expression",
                    modifier = Modifier.size(
                        with(density) { formula.widthPx.toDp() },
                        with(density) { formula.heightPx.toDp() },
                    ),
                )
            }
        }
    }
}
