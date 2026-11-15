package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.i18n.LumaI18n
import io.github.modao7067.luma.gui.i18n.tr

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.modao7067.luma.gui.session.CockpitActions
import io.github.modao7067.luma.gui.session.FileBrowser
import io.github.modao7067.luma.gui.session.FileEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun fileBreadcrumbs(path: String): List<Pair<String, String>> {
    val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
    return listOf("/" to "/") + parts.indices.map { index -> parts[index] to "/" + parts.subList(0, index + 1).joinToString("/") }
}

internal fun fileParent(path: String): String = path.trimEnd('/').substringBeforeLast('/', "").ifBlank { "/" }

internal fun fileReferenceText(paths: Collection<String>): String = paths.joinToString(" ") { "@\"$it\"" }

@Composable
internal fun FileBrowserDialog(browser: FileBrowser, actions: CockpitActions) {
    GuiPageDialog(onDismissRequest = actions::closeFiles) { FileBrowserScreen(browser, actions) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FileBrowserScreen(browser: FileBrowser, actions: CockpitActions) {
    val colors = MaterialTheme.colorScheme
    val history = remember { mutableStateListOf<String>() }
    val selected = remember { mutableStateListOf<String>() }
    var selecting by remember { mutableStateOf(browser.picking) }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var editingPath by remember { mutableStateOf(false) }
    var typedPath by remember(browser.path) { mutableStateOf(browser.path) }

    fun navigate(to: String) {
        if (to == browser.path) return
        history.add(browser.path)
        editingPath = false
        actions.browseFiles(to)
    }
    fun toggle(path: String) { if (!selected.remove(path)) selected.add(path) }
    fun back() {
        when {
            editingPath -> editingPath = false
            !browser.picking && selecting -> { selecting = false; selected.clear() }
            history.isNotEmpty() -> actions.browseFiles(history.removeAt(history.lastIndex))
            else -> actions.closeFiles()
        }
    }
    val scope = rememberCoroutineScope()
    val internalBackProgress = remember { Animatable(0f) }
    val hasInternalBack = editingPath || (!browser.picking && selecting) || history.isNotEmpty()
    PredictiveBackHandler(enabled = hasInternalBack) { progress ->
        try {
            progress.collect { event ->
                internalBackProgress.snapTo(event.progress.coerceIn(0f, 1f))
            }
            back()
            internalBackProgress.snapTo(0f)
        } catch (cancelled: CancellationException) {
            scope.launch { internalBackProgress.animateTo(0f, spatialSpring()) }
            throw cancelled
        }
    }

    val shown = remember(browser.entries, showHidden) {
        if (showHidden) browser.entries else browser.entries.filterNot { it.name.startsWith('.') }
    }
    val hiddenCount = browser.entries.size - shown.size

    Surface(Modifier.fillMaxSize(), color = colors.background) {
        LiquidScaffold(
            wrapTop = false,
            wrapBottom = false,
            topBar = {
                DetailPageHeader(
                    title = when {
                        browser.picking -> tr("引用文件", "Reference files")
                        selecting -> tr("选择要引用的项目", "Select items to reference")
                        else -> tr("文件", "Files")
                    },
                    onBack = ::back,
                    subtitle = browser.path,
                    backLabel = if (history.isEmpty() && !selecting) tr("关闭文件", "Close files") else tr("返回上一个位置", "Back"),
                ) {
                    LiquidGlass(
                        Modifier.size(FloatingChromeSize)
                            .liquidClickable(cornerRadius = FloatingChromeRadius, onClick = actions::closeFiles)
                            .semantics { contentDescription = tr("关闭", "Close") },
                        cornerRadius = FloatingChromeRadius,
                    ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ComposerGlyph("close") } }
                }
            },
            bottomBar = {},
        ) { padding ->
            val backShift = internalBackProgress.value
            Column(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        if (backShift > 0f) {
                            translationX = backShift * 42.dp.toPx()
                            alpha = 1f - backShift * 0.28f
                        }
                    }
                    .padding(top = padding.calculateTopPadding()),
            ) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState(Int.MAX_VALUE))
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    val crumbs = fileBreadcrumbs(browser.path)
                    crumbs.forEachIndexed { index, (label, target) ->
                        if (index > 1) Text("›", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                        val current = index == crumbs.lastIndex
                        Surface(
                            onClick = { navigate(target) },
                            enabled = !current && !browser.loading,
                            shape = LumaCardShape(10.dp),
                            color = if (current) colors.secondaryContainer else colors.surfaceContainer.copy(alpha = 0.6f),
                            contentColor = if (current) colors.onSecondaryContainer else colors.onSurfaceVariant,
                        ) {
                            Text(label, Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal),
                                maxLines = 1)
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = { editingPath = !editingPath }, contentPadding = PaddingValues(horizontal = 10.dp)) {
                        Text(if (editingPath) tr("取消", "Cancel") else tr("输入路径", "Enter path"), style = MaterialTheme.typography.labelLarge)
                    }
                }
                if (editingPath) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = typedPath,
                            onValueChange = { typedPath = it },
                            label = { Text(tr("环境内路径", "Path inside environment")) },
                            shape = LumaCardShape(16.dp),
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        FilledTonalButton(onClick = { navigate(typedPath.trim()) }, enabled = typedPath.trim().startsWith('/')) { Text(tr("前往", "Go")) }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (browser.loading) tr("正在读取…", "Loading…") else tr("${shown.size} 项", "${shown.size} items") + if (hiddenCount > 0 && !showHidden) tr("（另有 $hiddenCount 个隐藏项）", " ($hiddenCount hidden)") else "",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                    )
                    TextButton(onClick = { showHidden = !showHidden }, contentPadding = PaddingValues(horizontal = 10.dp)) {
                        Text(if (showHidden) tr("隐藏 . 开头的项", "Hide dotfiles") else tr("显示隐藏项", "Show hidden"), style = MaterialTheme.typography.labelMedium)
                    }
                    if (!browser.picking && !selecting) {
                        TextButton(onClick = { selecting = true }, contentPadding = PaddingValues(horizontal = 10.dp)) {
                            Text(tr("选择", "Select"), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                if (browser.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                browser.error?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = colors.error, style = MaterialTheme.typography.bodySmall) }
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().testTag("file-browser-list"),
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = 4.dp,
                        bottom = 16.dp + if (selecting) 0.dp else padding.calculateBottomPadding(),
                    ),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (browser.path != "/") item(key = "..") {
                        FileRow(
                            name = tr("上一级", "Parent folder"),
                            detail = fileParent(browser.path),
                            directory = true,
                            selecting = false,
                            checked = false,
                            enabled = !browser.loading,
                            onClick = { navigate(fileParent(browser.path)) },
                            onLongClick = null,
                            onCheck = null,
                        )
                    }
                    items(shown, key = { it.path }) { file ->
                        FileRow(
                            name = file.name,
                            detail = fileEntryDetail(file),
                            directory = file.directory,
                            selecting = selecting,
                            checked = file.path in selected,
                            enabled = !browser.loading,
                            onClick = {
                                when {
                                    file.directory -> navigate(file.path)
                                    selecting -> toggle(file.path)
                                    else -> actions.previewFile(file.path)
                                }
                            },
                            onLongClick = if (browser.picking) null else ({ selecting = true; if (file.path !in selected) selected.add(file.path) }),
                            onCheck = { toggle(file.path) },
                        )
                    }
                    if (!browser.loading && browser.error == null && shown.isEmpty()) item(key = "empty") {
                        Text(if (hiddenCount > 0) tr("只有隐藏项", "Only hidden items") else tr("目录为空", "Empty folder"), Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
                if (selecting) {
                    Surface(color = colors.surfaceContainer, tonalElevation = 2.dp) {
                        Row(
                            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(if (selected.isEmpty()) tr("点选文件，或勾选文件夹", "Tap files or check folders") else tr("已选 ${selected.size} 项", "${selected.size} selected"),
                                Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (selected.isNotEmpty()) TextButton(onClick = { selected.clear() }) { Text(tr("清空", "Clear")) }
                            Button(
                                onClick = { actions.referenceFiles(selected.toList()) },
                                enabled = selected.isNotEmpty(),
                                modifier = Modifier.testTag("file-browser-reference"),
                            ) { Text(if (selected.isEmpty()) tr("引用", "Reference") else tr("引用 ${selected.size} 项", "Reference ${selected.size}")) }
                        }
                    }
                }
            }
        }
    }
}

private val fileTimeZhFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.SIMPLIFIED_CHINESE)
private val fileTimeEnFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

internal fun fileEntryDetail(file: FileEntry): String = listOfNotNull(
    if (file.directory) tr("文件夹", "Folder") else file.size?.let(::formatMediaSize),
    file.modified?.let {
        val formatter = if (LumaI18n.isZh()) fileTimeZhFormatter else fileTimeEnFormatter
        formatter.withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(it))
    },
).joinToString(" · ")

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    name: String,
    detail: String,
    directory: Boolean,
    selecting: Boolean,
    checked: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onCheck: (() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(LumaCardShape(14.dp))
            .background(if (checked) colors.secondaryContainer.copy(alpha = 0.7f) else colors.surfaceContainer.copy(alpha = 0f))
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(38.dp).background(colors.surfaceContainerHighest.copy(alpha = 0.8f), CircleShape), contentAlignment = Alignment.Center) {
            MenuIcon(if (directory) MenuSymbol.FOLDER else fileNameSymbol(name) ?: MenuSymbol.FILE, size = 19.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (directory) FontWeight.Medium else FontWeight.Normal),
                maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        if (selecting && onCheck != null) {
            Checkbox(checked = checked, onCheckedChange = { onCheck() }, enabled = enabled)
        } else if (directory) {
            Text("›", style = MaterialTheme.typography.titleLarge, color = colors.onSurfaceVariant)
        }
    }
}
