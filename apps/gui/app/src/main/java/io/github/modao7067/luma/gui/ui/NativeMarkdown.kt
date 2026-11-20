package io.github.modao7067.luma.gui.ui

import androidx.compose.ui.platform.LocalHapticFeedback

import io.github.modao7067.luma.gui.i18n.tr

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import ru.noties.jlatexmath.JLatexMathAndroid
import ru.noties.jlatexmath.JLatexMathDrawable

internal val LocalMarkdownNestingDepth = compositionLocalOf { 0 }
internal val LocalMarkdownCardInitiallyExpanded = compositionLocalOf { false }
private val LocalMarkdownPluginAuthUrl = compositionLocalOf<String?> { null }
private val LocalEditorialMarkdown = compositionLocalOf { true }
private val LocalMarkdownTextColor = compositionLocalOf { Color.Unspecified }
private val LocalListNestingDepth = compositionLocalOf { 0 }
private val LocalQuoteNestingDepth = compositionLocalOf { 0 }
private val LocalMarkdownIndentDp = compositionLocalOf { 0.dp }
private val LocalMarkdownStreamingTail = compositionLocalOf { false }

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
internal data class RenderedFormula(
    val bitmap: ImageBitmap,
    val widthPx: Int,
    val heightPx: Int,
    val verticalAlign: PlaceholderVerticalAlign = PlaceholderVerticalAlign.Center,
    val baselineFraction: Float = 0.78f,
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
            val baseline = runCatching { drawable.icon().baseLine }.getOrDefault(0.78f)
                .let { if (it.isFinite()) it.coerceIn(0.25f, 0.95f) else 0.78f }
            RenderedFormula(bmp.asImageBitmap(), w, h, PlaceholderVerticalAlign.Center, baseline)
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
        authUrl != null && idLower in setOf("gdrive", "google-drive") -> tr("云盘插件授权 · $catalogId", "Cloud Drive auth · $catalogId")
        authUrl != null -> tr("插件账号授权 · $catalogId", "Plugin account auth · $catalogId")
        else -> tr("插件连接 · 请点击下方授权链接登录", "Plugin connection · Tap the link below to sign in")
    }
    DisableSelection {
        Surface(
            shape = LumaCardShape(12.dp),
            color = contentBlockColors(colors).surface,
            modifier = Modifier.fillMaxWidth().testTag("plugin-card-$idLower"),
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
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
                        color = contentBlockColors(colors).tab,
                        contentColor = colors.onSurfaceVariant,
                    ) {
                        Text(
                            tr("待授权", "Auth required"),
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
internal fun NativeMarkdown(
    source: String,
    onFileLink: ((String) -> Unit)? = null,
    presentation: MarkdownPresentation? = null,
    editorial: Boolean = true,
) {
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
    CompositionLocalProvider(
        LocalMarkdownPluginAuthUrl provides pluginAuthUrl,
        LocalEditorialMarkdown provides editorial,
        LocalListNestingDepth provides 0,
        LocalQuoteNestingDepth provides 0,
        LocalMarkdownIndentDp provides 0.dp,
        LocalMarkdownStreamingTail provides !shown.document.complete,
    ) {
        LumaSelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(11.dp), modifier = Modifier.fillMaxWidth()) {
                MarkdownChildren(
                    nodes = nodes,
                    budget = shown.visibleUnits,
                    onFileLink = fileLink,
                    keys = offsets,
                )
            }
        }
    }
}

@Composable
private fun MarkdownChildren(
    nodes: List<MarkdownAst>,
    budget: Int,
    onFileLink: ((String) -> Unit)?,
    keys: List<Int>? = null,
) {
    val parentStreamingTail = LocalMarkdownStreamingTail.current
    var remaining = budget
    nodes.forEachIndexed { index, child ->
        if (remaining > 0) {
            val full = remaining >= child.revealUnits
            val childStreamingTail = parentStreamingTail && index == nodes.lastIndex
            key(keys?.get(index) ?: index) {
                CompositionLocalProvider(LocalMarkdownStreamingTail provides childStreamingTail) {
                    MarkdownBlock(
                        node = child,
                        onFileLink = onFileLink,
                        budget = if (full) Int.MAX_VALUE else remaining,
                        isFirstBlock = index == 0,
                    )
                }
            }
            remaining = (remaining - child.revealUnits).coerceAtLeast(0)
        }
    }
}

private data class ListIndentSpec(val markerStart: Dp, val slotWidth: Dp, val childIndent: Dp)

private fun listIndentSpec(
    ordered: Boolean,
    listDepth: Int,
    parentIndent: Dp,
    maxNumber: Int,
): ListIndentSpec {
    val slot = if (ordered) {
        when {
            maxNumber >= 100 -> 30.dp
            maxNumber >= 10 -> 24.dp
            else -> 18.dp
        }
    } else {
        when (listDepth) {
            0 -> 14.dp
            1 -> 12.dp
            else -> 10.dp
        }
    }
    val maxMarkerStart = if (ordered) {
        when (listDepth) {
            0 -> 0.dp
            1 -> 14.dp
            else -> 18.dp
        }
    } else {
        when (listDepth) {
            0 -> 0.dp
            1 -> 14.dp
            else -> 22.dp
        }
    }
    val markerStart = minOf(parentIndent, maxMarkerStart)
    val childIndent = markerStart + slot
    return ListIndentSpec(markerStart, slot, childIndent)
}

private fun bulletMarkerForDepth(depth: Int): String = when (depth) {
    0 -> "•"
    1 -> "◦"
    else -> "▪"
}

private fun markerAlphaForDepth(depth: Int): Float = when (depth) {
    0 -> 0.78f
    1 -> 0.66f
    else -> 0.56f
}

@Composable
private fun MarkdownBlock(
    node: MarkdownAst,
    onFileLink: ((String) -> Unit)?,
    budget: Int,
    isFirstBlock: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val standaloneCatalogId = remember(node) { extractStandalonePluginCatalogId(node) }
    if (standaloneCatalogId != null) {
        MarkdownPluginCard(standaloneCatalogId, LocalMarkdownPluginAuthUrl.current)
        return
    }
    val indent = LocalMarkdownIndentDp.current
    val indentModifier = if (indent > 0.dp) Modifier.padding(start = indent) else Modifier
    val useEditorial = LocalEditorialMarkdown.current
    when (node.kind) {
        MarkdownKind.PARAGRAPH -> {
            val bodyStyle = if (useEditorial) {
                editorialBodyStyle()
            } else {
                MaterialTheme.typography.bodyLarge.copy(lineBreak = LineBreak.Simple)
            }
            val inlineCatalogIds = remember(node) { extractInlinePluginCatalogIds(node) }
            val media = remember(node) { markdownImages(node, resourceLinks = true) }
            val mediaBudget = if (budget == Int.MAX_VALUE) media.size else (budget - node.inlineUnits).coerceIn(0, media.size)
            if (indent > 0.dp && (mediaBudget > 0 || inlineCatalogIds.isNotEmpty())) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Box(indentModifier) {
                        RichText(node, bodyStyle, onFileLink, budget, emitMedia = false)
                    }
                    if (mediaBudget > 0) {
                        DisableSelection { MessageMedia(media.take(mediaBudget)) }
                    }
                    inlineCatalogIds.forEach { id ->
                        MarkdownPluginCard(id, LocalMarkdownPluginAuthUrl.current)
                    }
                }
            } else if (inlineCatalogIds.isEmpty()) {
                Box(indentModifier) {
                    RichText(node, bodyStyle, onFileLink, budget)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Box(indentModifier) {
                        RichText(node, bodyStyle, onFileLink, budget)
                    }
                    inlineCatalogIds.forEach { id ->
                        MarkdownPluginCard(id, LocalMarkdownPluginAuthUrl.current)
                    }
                }
            }
        }
        MarkdownKind.HEADING -> {
            val topPad = if (isFirstBlock) 0.dp else when (node.number) {
                1 -> 12.dp
                2 -> 10.dp
                3 -> 6.dp
                else -> 4.dp
            }
            val headingStyle = if (useEditorial) {
                editorialHeadingStyle(node.number)
            } else {
                when (node.number) {
                    1 -> MaterialTheme.typography.headlineMedium
                    2 -> MaterialTheme.typography.titleLarge
                    3 -> MaterialTheme.typography.titleMedium
                    else -> MaterialTheme.typography.titleSmall
                }.copy(lineBreak = LineBreak.Simple)
            }
            val headingMode = if (budget < node.inlineUnits || LocalMarkdownStreamingTail.current) {
                TypesetMode.STREAMING
            } else {
                TypesetMode.BALANCED_LEFT
            }
            Column(
                modifier = indentModifier.padding(top = topPad),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RichText(node, headingStyle, onFileLink, budget, typesetMode = headingMode)
            }
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
                )
            }
        }
        MarkdownKind.MATH -> if (budget >= node.revealUnits) FormulaBlock(node.literal)
        MarkdownKind.QUOTE -> {
            val quoteBudget = if (budget >= node.revealUnits) Int.MAX_VALUE else budget
            val quoteDepth = LocalQuoteNestingDepth.current
            val outerStart = minOf(indent, 14.dp)
            val innerStart = if (quoteDepth >= 1) 8.dp else 10.dp
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = outerStart)
                    .drawBehind {
                        drawRoundRect(
                            colors.outline.copy(alpha = 0.48f),
                            Offset.Zero,
                            Size(2.dp.toPx(), size.height),
                            CornerRadius(1.dp.toPx()),
                        )
                    }
                    .padding(start = innerStart, top = 2.dp, bottom = 2.dp),
            ) {
                CompositionLocalProvider(
                    LocalMarkdownTextColor provides colors.onSurface.copy(alpha = 0.82f),
                    LocalQuoteNestingDepth provides quoteDepth + 1,
                    LocalMarkdownIndentDp provides 0.dp,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        MarkdownChildren(node.children, quoteBudget, onFileLink)
                    }
                }
            }
        }
        MarkdownKind.BULLET_LIST, MarkdownKind.ORDERED_LIST -> {
            val listDepth = LocalListNestingDepth.current
            val ordered = node.kind == MarkdownKind.ORDERED_LIST
            val maxNum = if (ordered) node.number + (node.children.size - 1).coerceAtLeast(0) else 0
            val indentSpec = listIndentSpec(ordered, listDepth, indent, maxNum)
            val listStreamingTail = LocalMarkdownStreamingTail.current
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                var remaining = budget
                node.children.forEachIndexed { index, child ->
                    val marker = if (ordered) "${node.number + index}." else bulletMarkerForDepth(listDepth)
                    if (remaining > 0) {
                        val itemFull = remaining >= marker.length + child.revealUnits
                        val shownMarker = if (itemFull) marker else marker.substring(0, markdownVisibleEnd(marker, remaining))
                        val bodyBudget = if (itemFull) Int.MAX_VALUE else (remaining - marker.length).coerceAtLeast(0)
                        val itemStreamingTail = listStreamingTail && index == node.children.lastIndex
                        key(index) {
                            CompositionLocalProvider(LocalMarkdownStreamingTail provides itemStreamingTail) {
                                MarkdownListItem(shownMarker, listDepth, indentSpec, child.children, bodyBudget, onFileLink)
                            }
                        }
                    }
                    remaining = if (remaining == Int.MAX_VALUE) Int.MAX_VALUE else (remaining - marker.length - child.revealUnits).coerceAtLeast(0)
                }
            }
        }
        MarkdownKind.TABLE -> MarkdownTable(node, onFileLink, budget)
        MarkdownKind.RULE -> HorizontalDivider(
            Modifier.padding(vertical = 4.dp),
            thickness = 0.5.dp,
            color = colors.outlineVariant.copy(alpha = 0.45f),
        )
        MarkdownKind.HTML_BLOCK -> {
            val bodyStyle = if (useEditorial) {
                editorialBodyStyle()
            } else {
                MaterialTheme.typography.bodyLarge.copy(lineBreak = LineBreak.Simple)
            }
            Text(node.literal.substring(0, markdownVisibleEnd(node.literal, budget)), modifier = indentModifier, style = bodyStyle)
        }
        else -> MarkdownChildren(node.children, budget, onFileLink)
    }
}

@Composable
private fun MarkdownListItem(
    marker: String,
    listDepth: Int,
    indentSpec: ListIndentSpec,
    children: List<MarkdownAst>,
    bodyBudget: Int,
    onFileLink: ((String) -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    val useEditorial = LocalEditorialMarkdown.current
    val bodyStyle = if (useEditorial) {
        editorialBodyStyle()
    } else {
        MaterialTheme.typography.bodyLarge.copy(lineBreak = LineBreak.Simple)
    }
    val markerStyle = bodyStyle.copy(
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = TABULAR_NUMS_FEATURE,
    )
    val markerColor = colors.onSurfaceVariant.copy(alpha = markerAlphaForDepth(listDepth))
    if (children.isEmpty()) {
        Row(Modifier.fillMaxWidth().padding(start = indentSpec.markerStart)) {
            Text(marker, Modifier.widthIn(min = indentSpec.slotWidth), color = markerColor, style = markerStyle)
        }
        return
    }
    val first = children.first()
    val firstFull = bodyBudget >= first.revealUnits
    val firstBudget = if (firstFull) Int.MAX_VALUE else bodyBudget
    val restBudget = if (bodyBudget == Int.MAX_VALUE) Int.MAX_VALUE else (bodyBudget - first.revealUnits).coerceAtLeast(0)
    val rest = if (children.size > 1) children.subList(1, children.size) else emptyList()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (first.kind == MarkdownKind.PARAGRAPH && extractStandalonePluginCatalogId(first) == null) {
            val inlineCatalogIds = remember(first) { extractInlinePluginCatalogIds(first) }
            val media = remember(first) { markdownImages(first, resourceLinks = true) }
            val mediaBudget = if (firstBudget == Int.MAX_VALUE) media.size else (firstBudget - first.inlineUnits).coerceIn(0, media.size)
            val firstStreamingTail = LocalMarkdownStreamingTail.current && rest.isEmpty()
            Row(Modifier.fillMaxWidth().padding(start = indentSpec.markerStart)) {
                Text(
                    marker,
                    Modifier.widthIn(min = indentSpec.slotWidth).alignByBaseline(),
                    color = markerColor,
                    style = markerStyle,
                )
                Box(Modifier.weight(1f).alignByBaseline()) {
                    CompositionLocalProvider(LocalMarkdownStreamingTail provides firstStreamingTail) {
                        RichText(first, bodyStyle, onFileLink, firstBudget, emitMedia = false)
                    }
                }
            }
            if (mediaBudget > 0) {
                DisableSelection { MessageMedia(media.take(mediaBudget)) }
            }
            inlineCatalogIds.forEach { id ->
                MarkdownPluginCard(id, LocalMarkdownPluginAuthUrl.current)
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(start = indentSpec.markerStart)) {
                Text(
                    marker,
                    Modifier.widthIn(min = indentSpec.slotWidth),
                    color = markerColor,
                    style = markerStyle,
                )
                Box(Modifier.weight(1f)) {
                    CompositionLocalProvider(
                        LocalListNestingDepth provides listDepth + 1,
                        LocalMarkdownIndentDp provides 0.dp,
                    ) {
                        MarkdownBlock(first, onFileLink, firstBudget, isFirstBlock = true)
                    }
                }
            }
        }
        if (rest.isNotEmpty() && restBudget > 0) {
            CompositionLocalProvider(
                LocalListNestingDepth provides listDepth + 1,
                LocalMarkdownIndentDp provides indentSpec.childIndent,
            ) {
                MarkdownChildren(rest, restBudget, onFileLink)
            }
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
    emitMedia: Boolean = true,
    typesetMode: TypesetMode? = null,
) {
    val colors = MaterialTheme.colorScheme
    val isDark = codePaletteDark(colors)
    val color = LocalMarkdownTextColor.current.let { if (it == Color.Unspecified) colors.onSurface else it }
    val linkColor = if (isDark) Color(0xFF6DA7EC) else Color(0xFF184F95)
    val inlineCodeColor = if (isDark) Color(0xFFE29E74) else Color(0xFFA64622)
    val mono = terminalFont()
    val context = LocalContext.current
    val density = LocalDensity.current
    val useEditorial = LocalEditorialMarkdown.current
    val streamingTail = LocalMarkdownStreamingTail.current
    val sizePx = with(density) { (if (style.fontSize.isUnspecified) 16.sp else style.fontSize).toPx() }
    val signature = node
    val rich = remember(signature, color, linkColor, inlineCodeColor, mono, sizePx, onFileLink, context) {
        buildRichText(context, node, color, linkColor, inlineCodeColor, mono, sizePx, onFileLink)
    }
    val visible = remember(rich.first, budget) { visibleMarkdownText(rich.first, budget) }
    val media = remember(signature, resourceCards, emitMedia) {
        if (emitMedia) markdownImages(node, resourceCards) else emptyList()
    }
    val mediaBudget = if (!emitMedia) 0 else if (budget == Int.MAX_VALUE) media.size else (budget - node.inlineUnits).coerceIn(0, media.size)
    val resolvedMode = typesetMode ?: when {
        budget < node.inlineUnits || streamingTail -> TypesetMode.STREAMING
        useEditorial -> TypesetMode.JUSTIFY
        else -> TypesetMode.BALANCED_LEFT
    }
    if (visible.isNotBlank() && mediaBudget > 0) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TypesetText(visible, style, color, resolvedMode, mono, rich.second)
            DisableSelection { MessageMedia(media.take(mediaBudget)) }
        }
    } else {
        if (visible.isNotBlank()) TypesetText(visible, style, color, resolvedMode, mono, rich.second)
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

private val inlineMath = Regex("\\\\\\((.+?)\\\\\\)|(?<!\\\\)\\$([^$\\n]+?)\\$")
private fun buildRichText(
    context: android.content.Context,
    node: MarkdownAst,
    color: Color,
    linkColor: Color,
    accent: Color,
    mono: FontFamily,
    sizePx: Float,
    onFileLink: ((String) -> Unit)?,
): Pair<AnnotatedString, Map<String, RenderedFormula>> {
    val images = linkedMapOf<String, RenderedFormula>()
    val argb = color.toArgb()
    val codeStyle = SpanStyle(
        fontFamily = mono,
        fontFeatureSettings = com.termux.view.BundledTerminalFonts.FEATURE_SETTINGS,
        fontSize = 0.88.em,
        letterSpacing = 0.sp,
        fontWeight = FontWeight.Medium,
        color = accent,
    )
    val result = buildAnnotatedString {
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
                    withStyle(codeStyle) { append(expression) }
                } else {
                    val rendered = renderAndCacheFormula(context, expression, sizePx, argb, padding = 0)
                    if (rendered == null) {
                        append(tr("[公式未能排版：${match.value}]", "[Formula render failed: ${match.value}]"))
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
                MarkdownKind.CODE -> withStyle(codeStyle) { append(current.literal) }
                MarkdownKind.STRONG -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { current.children.forEach(::visit) }
                MarkdownKind.EMPHASIS -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { current.children.forEach(::visit) }
                MarkdownKind.STRIKE -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { current.children.forEach(::visit) }
                MarkdownKind.LINK -> {
                    val destination = current.destination
                    val linkStyle = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
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
    val headerBg = contentBlockColors(colors).header
    val stripeBg = contentBlockColors(colors).header.copy(alpha = 0.45f)
    val density = LocalDensity.current
    val hPadPx = with(density) { 10.dp.roundToPx() }
    val vPadPx = with(density) { 7.dp.roundToPx() }
    val minColPx = with(density) { 44.dp.roundToPx() }
    val maxColPx = with(density) { 248.dp.roundToPx() }
    val strokePx = with(density) { 0.5.dp.toPx() }
    val gridOffsets = remember { mutableStateOf(intArrayOf() to intArrayOf()) }
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        LiquidSurface(
            cornerRadius = 12.dp,
            tonalTint = contentBlockColors(colors).surface,
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
                                strokeWidth = if (r == 1) strokePx * 1.6f else strokePx,
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
                                        if (LocalEditorialMarkdown.current) {
                                            editorialTableStyle(cell.header)
                                        } else {
                                            MaterialTheme.typography.bodyMedium.copy(
                                                fontFeatureSettings = TABULAR_NUMS_FEATURE,
                                                fontWeight = if (cell.header) FontWeight.SemiBold else FontWeight.Normal,
                                            )
                                        },
                                        onFileLink,
                                        if (remaining >= cell.revealUnits) Int.MAX_VALUE else remaining,
                                        resourceCards = false,
                                        typesetMode = TypesetMode.BALANCED_LEFT,
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
    val outerBodyBg = contentBlockColors(colors).surface
    val trackBg = contentBlockColors(colors).tab
    val thumbBg = contentBlockColors(colors).selected
    val copiedTint = if (dark) Color(0xFF34D399) else Color(0xFF10B981)
    val headerCaption = language.trim().substringBefore(' ').lowercase().ifBlank { "markdown" }
    val haptic = LocalHapticFeedback.current
    val copyMarkdown: () -> Unit = {
        haptic.tick()
        runCatching { clipboard.setText(AnnotatedString(code.substring(0, minOf(visibleEnd, 262_144)))) }.onSuccess {
            copied = true
        }
    }
    val effectiveShowSource = showSource || exceedsPreviewCap
    val retainedPresentationState = remember { mutableStateOf<MarkdownPresentation?>(null) }
    Surface(
        shape = LumaCardShape(12.dp),
        color = outerBodyBg,
        border = BorderStroke(0.5.dp, if (dark) Color.White.copy(alpha = 0.10f) else Color(0xFF0B0B0B).copy(alpha = 0.09f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("markdown-document-card")
            .semantics {
                stateDescription = if (expanded) {
                    if (effectiveShowSource) tr("已展开 Markdown 源码", "Expanded Markdown source") else tr("已展开 Markdown 预览", "Expanded Markdown preview")
                } else {
                    tr("已折叠 Markdown 文档卡片", "Collapsed Markdown card")
                }
            },
    ) {
        TopReveal(
            expanded = expanded,
            collapsedContent = {
                DisableSelection {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                Modifier
                                    .weight(1f)
                                    .clip(LumaCardShape(8.dp))
                                    .clickable {
                                        onManualLayoutToggle?.invoke()
                                        expanded = true
                                    }
                                    .semantics { contentDescription = tr("展开", "Expand") }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        summary.title,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = colors.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        summary.subtitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant) {
                                    MenuIcon(MenuSymbol.DOWN, size = 16.dp)
                                }
                            }
                            Box(
                                Modifier
                                    .size(28.dp)
                                    .clip(LumaCardShape(8.dp))
                                    .blockHorizontalPageSwipe(allowVerticalScroll = true)
                                    .clickable(onClick = copyMarkdown)
                                    .semantics { contentDescription = tr("复制", "Copy") },
                                contentAlignment = Alignment.Center,
                            ) {
                                CompositionLocalProvider(LocalContentColor provides if (copied) copiedTint else colors.onSurfaceVariant) {
                                    ComposerGlyph(if (copied) "check" else "copy")
                                }
                            }
                        }
                        if (summary.excerpt.isNotBlank()) {
                            Text(
                                summary.excerpt,
                                Modifier
                                    .fillMaxWidth()
                                    .clip(LumaCardShape(6.dp))
                                    .clickable {
                                        onManualLayoutToggle?.invoke()
                                        expanded = true
                                    }
                                    .padding(vertical = 2.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
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
                            .heightIn(min = 34.dp)
                            .background(contentBlockColors(colors).header)
                            .padding(start = 6.dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            if (exceedsPreviewCap) {
                                Text(
                                    headerCaption,
                                    Modifier.padding(horizontal = 6.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            } else {
                                listOf(false, true).forEach { sourceTab ->
                                    val selected = showSource == sourceTab
                                    Box(
                                        Modifier
                                            .clip(LumaCardShape(7.dp))
                                            .background(if (selected) thumbBg else trackBg)
                                            .selectable(selected = selected, role = Role.Tab) {
                                                if (showSource != sourceTab) {
                                                    onManualLayoutToggle?.invoke()
                                                    showSource = sourceTab
                                                }
                                            }
                                            .padding(horizontal = 9.dp, vertical = 5.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            if (sourceTab) tr("源码", "Source") else tr("预览", "Preview"),
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                            ),
                                            color = if (selected) colors.onSurface else colors.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                        Text(
                            tr("${summary.lineCount} 行", "${summary.lineCount} lines"),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                        )
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(LumaCardShape(8.dp))
                                .clickable {
                                    onManualLayoutToggle?.invoke()
                                    expanded = false
                                }
                                .semantics { contentDescription = tr("收起", "Collapse") },
                            contentAlignment = Alignment.Center,
                        ) {
                            CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant) {
                                MenuIcon(MenuSymbol.DOWN, size = 16.dp, rotationProvider = { 180f })
                            }
                        }
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(LumaCardShape(8.dp))
                                .blockHorizontalPageSwipe(allowVerticalScroll = true)
                                .clickable(onClick = copyMarkdown)
                                .semantics { contentDescription = tr("复制", "Copy") },
                            contentAlignment = Alignment.Center,
                        ) {
                            CompositionLocalProvider(LocalContentColor provides if (copied) copiedTint else colors.onSurfaceVariant) {
                                ComposerGlyph(if (copied) "check" else "copy")
                            }
                        }
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 9.dp),
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
                                        editorial = LocalEditorialMarkdown.current,
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
                                        tr("… 文档超过 128 KiB，已切换为截断源码视图（共 ${visibleCode.length / 1024} KiB）", "… Document exceeds 128 KiB; showing truncated source (${visibleCode.length / 1024} KiB total)"),
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
    caption: String = language.ifBlank { tr("代码", "Code") },
    softWrap: Boolean = true,
) {
    val visibleEnd = markdownVisibleEnd(code, visibleCharacters)
    val clipboard = LocalClipboardManager.current
    val colors = MaterialTheme.colorScheme
    val dark = codePaletteDark(colors)
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2200)
            copied = false
        }
    }
    val blockColors = contentBlockColors(colors)
    val copiedTint = if (dark) Color(0xFF34D399) else Color(0xFF10B981)
    val headerCaption = if (caption == language && language.isNotBlank()) caption.lowercase() else caption
    val wrapIndicatorColor = colors.onSurfaceVariant.copy(alpha = if (dark) 0.46f else 0.40f)
    val wrapPath = remember { Path() }
    var codeLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Surface(
        shape = LumaCardShape(12.dp),
        color = blockColors.surface,
        border = BorderStroke(0.5.dp, if (dark) Color.White.copy(alpha = 0.10f) else Color(0xFF0B0B0B).copy(alpha = 0.09f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    headerCaption,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                    color = colors.onSurfaceVariant,
                    fontFamily = terminalFont(),
                )
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(LumaCardShape(8.dp))
                        .clickable {
                            runCatching { clipboard.setText(AnnotatedString(code.substring(0, minOf(visibleEnd, 262_144)))) }.onSuccess {
                                copied = true
                            }
                        }
                        .semantics { contentDescription = tr("复制", "Copy") },
                    contentAlignment = Alignment.Center,
                ) {
                    CompositionLocalProvider(LocalContentColor provides if (copied) copiedTint else colors.onSurfaceVariant) {
                        ComposerGlyph(if (copied) "check" else "copy")
                    }
                }
            }
            HorizontalDivider(
                thickness = 0.5.dp,
                color = if (dark) Color.White.copy(alpha = 0.08f) else Color(0xFF0B0B0B).copy(alpha = 0.07f),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .then(if (softWrap) Modifier.padding(vertical = 9.dp) else Modifier.padding(horizontal = 12.dp, vertical = 9.dp))
                    .heightIn(max = 242.dp)
                    .clipToBounds()
                    .codeBlockScrollBoundary(bodyVerticalScrollState, if (softWrap) null else bodyHorizontalScrollState)
                    .verticalScroll(bodyVerticalScrollState),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .then(
                            if (softWrap) {
                                Modifier
                                    .drawBehind {
                                        val margin = 260.dp.toPx()
                                        val visTop = (bodyVerticalScrollState.value.toFloat() - margin).coerceAtLeast(0f)
                                        val visBottom = bodyVerticalScrollState.value.toFloat() + margin * 2f
                                        drawSoftWrapIndicators(codeLayout, wrapIndicatorColor, wrapPath, visTop, visBottom)
                                    }
                                    .padding(start = SOFT_WRAP_GUTTER_START, end = SOFT_WRAP_GUTTER_END)
                            } else {
                                Modifier
                            },
                        ),
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
                                fontSize = 12.5.sp,
                                letterSpacing = 0.sp,
                                lineHeight = 19.5.sp,
                                lineBreak = LineBreak.Simple,
                                hyphens = Hyphens.None,
                            ),
                            softWrap = softWrap,
                            onTextLayout = { codeLayout = it },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun rememberHighlightedCode(
    code: String,
    language: String,
    colors: ColorScheme,
    streamingHint: Boolean? = null,
): AnnotatedString {
    val dark = codePaletteDark(colors)
    var mountedCode by remember { mutableStateOf(code) }
    val streaming = streamingHint ?: (code != mountedCode)
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

@Composable
internal fun rememberHighlightedDiffHunk(
    lines: List<DiffLine>,
    language: String,
    colors: ColorScheme,
): AnnotatedString {
    val dark = codePaletteDark(colors)
    val totalChars = remember(lines) { lines.sumOf { it.text.length + 3 } }
    val immediate = remember(lines, language, dark) {
        cachedDiffHighlight(lines, language, dark)
            ?: if (totalChars <= IMMEDIATE_HIGHLIGHT_LIMIT) highlightedDiffHunk(lines, language, dark) else null
    }
    val result = remember(lines, language, dark) { mutableStateOf(immediate) }
    LaunchedEffect(result) {
        if (result.value != null) return@LaunchedEffect
        result.value = withContext(Dispatchers.Default) {
            val context = coroutineContext
            highlightedDiffHunk(lines, language, dark) { context.ensureActive() }
        }
    }
    return result.value ?: remember(lines) {
        AnnotatedString(lines.joinToString("\n") { line ->
            val prefix = when (line.kind) {
                DiffLineKind.ADDITION -> "+ "
                DiffLineKind.DELETION -> "- "
                DiffLineKind.CONTEXT -> "  "
            }
            prefix + line.text
        })
    }
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
            Text(tr("公式未能排版", "Formula render failed"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
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
                    contentDescription = tr("公式：$expression", "Formula: $expression"),
                    modifier = Modifier.size(
                        with(density) { formula.widthPx.toDp() },
                        with(density) { formula.heightPx.toDp() },
                    ),
                )
            }
        }
    }
}
