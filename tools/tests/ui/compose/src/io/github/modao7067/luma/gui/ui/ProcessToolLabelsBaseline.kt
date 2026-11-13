package io.github.modao7067.luma.gui.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.modao7067.luma.gui.session.ConversationItem
import org.json.JSONObject

internal enum class BaselineToolLabelsProcessCategory(val caption: String) {
    Terminal("终端"), Edit("编辑"), Read("读取"), Search("搜索"), WebSearch("网页搜索"), Browse("浏览"), Image("图片"), Tool("工具"),
}

private val piToolCategories = mapOf(
    "bash" to BaselineToolLabelsProcessCategory.Terminal, "powershell" to BaselineToolLabelsProcessCategory.Terminal,
    "edit" to BaselineToolLabelsProcessCategory.Edit, "write" to BaselineToolLabelsProcessCategory.Edit,
    "read" to BaselineToolLabelsProcessCategory.Read, "ls" to BaselineToolLabelsProcessCategory.Read,
    "grep" to BaselineToolLabelsProcessCategory.Search, "find" to BaselineToolLabelsProcessCategory.Search,
)
private val openCodeToolCategories = mapOf(
    "shell" to BaselineToolLabelsProcessCategory.Terminal,
    "edit" to BaselineToolLabelsProcessCategory.Edit, "write" to BaselineToolLabelsProcessCategory.Edit, "patch" to BaselineToolLabelsProcessCategory.Edit,
    "read" to BaselineToolLabelsProcessCategory.Read,
    "grep" to BaselineToolLabelsProcessCategory.Search, "glob" to BaselineToolLabelsProcessCategory.Search, "websearch" to BaselineToolLabelsProcessCategory.WebSearch,
    "webfetch" to BaselineToolLabelsProcessCategory.Browse,
    "experimental_image_generate" to BaselineToolLabelsProcessCategory.Image,
)
private val claudeToolCategories = mapOf(
    "Bash" to BaselineToolLabelsProcessCategory.Terminal,
    "Read" to BaselineToolLabelsProcessCategory.Read,
    "Write" to BaselineToolLabelsProcessCategory.Edit, "Edit" to BaselineToolLabelsProcessCategory.Edit, "MultiEdit" to BaselineToolLabelsProcessCategory.Edit,
    "NotebookEdit" to BaselineToolLabelsProcessCategory.Edit,
    "Glob" to BaselineToolLabelsProcessCategory.Search, "Grep" to BaselineToolLabelsProcessCategory.Search, "WebSearch" to BaselineToolLabelsProcessCategory.WebSearch,
    "WebFetch" to BaselineToolLabelsProcessCategory.Browse,
)

internal fun baselineToolLabelsProcessCategory(item: ConversationItem, cli: String = "Codex"): BaselineToolLabelsProcessCategory = when (item.kind) {
    "commandExecution" -> BaselineToolLabelsProcessCategory.Terminal
    "fileChange" -> BaselineToolLabelsProcessCategory.Edit
    "webSearch" -> BaselineToolLabelsProcessCategory.WebSearch
    "imageView", "imageGeneration" -> BaselineToolLabelsProcessCategory.Image
    "mcpToolCall" -> BaselineToolLabelsProcessCategory.Tool
    "tool" -> when {
        item.toolKind.isNotEmpty() -> when (item.toolKind) {
            "execute" -> BaselineToolLabelsProcessCategory.Terminal
            "edit", "delete", "move" -> BaselineToolLabelsProcessCategory.Edit
            "read" -> BaselineToolLabelsProcessCategory.Read
            "search" -> nativeToolCategory(item, cli)?.takeIf { it == BaselineToolLabelsProcessCategory.WebSearch } ?: BaselineToolLabelsProcessCategory.Search
            "fetch" -> BaselineToolLabelsProcessCategory.Browse
            else -> BaselineToolLabelsProcessCategory.Tool
        }
        else -> nativeToolCategory(item, cli) ?: BaselineToolLabelsProcessCategory.Tool
    }
    else -> BaselineToolLabelsProcessCategory.Tool
}

private fun nativeToolCategory(item: ConversationItem, cli: String): BaselineToolLabelsProcessCategory? = when (cli) {
    "pi" -> piToolCategories[item.toolName]
    "OpenCode" -> openCodeToolCategories[item.toolName]
    "Claude Code" -> claudeToolCategories[item.toolName]
    else -> null
}

private val invocationKinds = setOf(
    "tool", "commandExecution", "fileChange", "webSearch", "imageView", "imageGeneration",
    "mcpToolCall", "dynamicToolCall", "collabAgentToolCall",
)

internal fun baselineToolLabelsProcessToolInvocations(entries: List<ConversationItem>): List<ConversationItem> {
    val calls = linkedMapOf<Triple<String, String, Int>, ConversationItem>()
    entries.forEachIndexed { index, item ->
        if (item.role != "tool" || item.kind !in invocationKinds) return@forEachIndexed
        calls[Triple(item.turnId, item.id, if (item.id.isBlank()) index else -1)] = item
    }
    return calls.values.toList()
}

internal fun baselineToolLabelsProcessToolName(item: ConversationItem): String = item.toolName.takeIf { it.isNotBlank() }
    ?: if (item.kind == "tool") item.toolKind.ifBlank { "工具调用" } else item.kind.ifBlank { "工具事件" }

internal fun baselineToolLabelsProcessEntryLabel(item: ConversationItem, cli: String): String =
    if (item.kind == "reasoning") reasoningCaption(item.outcome) else baselineToolLabelsProcessToolName(item)

internal fun baselineToolLabelsProcessEntryContext(item: ConversationItem, cli: String): String {
    val label = baselineToolLabelsProcessEntryLabel(item, cli)
    val raw = if (item.kind == "reasoning") {
        item.detail.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().replace("**", "")
    } else item.text
    if (raw == label || raw in setOf("工具", "工具调用")) return ""
    if (item.kind == "mcpToolCall" && raw.trim() == "·") return ""
    if (raw.startsWith("$label · ")) return raw.removePrefix("$label · ").trim()
    if (raw.startsWith("$label:")) return raw.removePrefix("$label:").trim()
    if (raw.endsWith(" · $label")) return raw.removeSuffix(" · $label").trim()
    return when (item.kind) {
        "webSearch" -> raw.removePrefix("搜索 · ")
        "fileChange" -> raw.takeUnless { it == "文件变更" }.orEmpty()
        "imageView" -> raw.takeUnless { it == "查看图片" }.orEmpty()
        "imageGeneration" -> raw.takeUnless { it == "生成图片" }.orEmpty()
        else -> raw
    }
}

internal fun baselineToolLabelsProcessInputCaption(item: ConversationItem, cli: String): String =
    if (item.kind == "commandExecution" ||
        (baselineToolLabelsProcessCategory(item, cli) == BaselineToolLabelsProcessCategory.Terminal && item.toolName in setOf("bash", "powershell", "shell", "Bash"))) "shell" else "参数"

internal fun baselineToolLabelsProcessOutputCaption(item: ConversationItem): String = when {
    item.outcome == "failed" -> "output · 错误"
    item.kind == "fileChange" -> "diff"
    else -> "output"
}

internal fun baselineToolLabelsProcessGroupCaption(entries: List<ConversationItem>, cli: String = "Codex"): String {
    if (entries.isNotEmpty() && entries.all { it.kind == "subAgent" }) return "${entries.size} 个子代理"
    val tools = baselineToolLabelsProcessToolInvocations(entries)
    val failedTools = tools.count { it.outcome == "failed" }
    val reasoning = entries.filter { it.kind == "reasoning" }.distinctBy { it.turnId to it.id }
    val completed = reasoning.count { it.outcome == "succeeded" }
    val running = reasoning.any { it.outcome == "running" }
    return buildList {
        if (tools.isNotEmpty()) {
            add("调用 ${tools.size} 次")
            add(tools.map(::baselineToolLabelsProcessToolName).distinct().joinToString("、"))
        }
        if (completed > 0) add("$completed 次思考")
        if (running) add("思考中")
        if (reasoning.isNotEmpty() && completed == 0 && !running) add("思考")
        if (failedTools > 0) add("$failedTools 次失败")
        val agents = entries.count { it.kind == "subAgent" }
        if (agents > 0) add("$agents 个子代理")
        if (isEmpty() && entries.isNotEmpty()) add("执行过程")
    }.joinToString(" · ")
}

internal fun baselineToolLabelsProcessSummaryText(caption: String) = buildAnnotatedString {
    append(caption)
    if (caption.startsWith("调用 ")) addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), 0, 2)
}

@Composable
internal fun BaselineToolLabelsProcessGroup(
    entries: List<ConversationItem>,
    initiallyExpanded: Boolean = false,
    initiallyOpenItem: String? = null,
    previewDetailFraction: Float? = null,
    previewDetailClosing: Boolean = false,
    previewGroupFraction: Float? = null,
    cli: String = "Codex",
    animateInitialArrival: Boolean = false,
) {
    var expanded by rememberSaveable(entries.first().id) { mutableStateOf(initiallyExpanded) }
    val entryState = rememberSaveableStateHolder()
    val caption = remember(entries, cli) { baselineToolLabelsProcessGroupCaption(entries, cli) }
    val hasRunningEntries = entries.any { it.outcome == "running" }
    val colors = MaterialTheme.colorScheme
    val border = colors.outlineVariant.copy(alpha = 0.55f)
    var groupIntroduced by rememberSaveable(entries.first().id) { mutableStateOf(!animateInitialArrival) }
    LaunchedEffect(Unit) { groupIntroduced = true }
    val animatedFractionState = animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = revealSpring(),
        label = "process-group-reveal",
    )
    val groupFractionProvider: () -> Float = {
        (previewGroupFraction ?: revealFraction(animatedFractionState.value)).coerceIn(0f, 1f)
    }
    val initialEntryIds = remember { entries.map { it.id }.toSet() }
    val arrivalStates = entries.associate { entry ->
        key(entry.id) {
            var introduced by rememberSaveable(entry.id) { mutableStateOf(entry.id in initialEntryIds) }
            val arrivalState = animateFloatAsState(
                targetValue = if (introduced) 1f else 0f,
                animationSpec = revealSpring(),
                label = "process-arrival-reveal",
            )
            LaunchedEffect(Unit) { introduced = true }
            entry.id to arrivalState
        }
    }
    val retentionStates = entries.associate { entry ->
        key(entry.id) {
            val retainedState = animateFloatAsState(
                targetValue = if (processEntryRetained(entry)) 1f else 0f,
                animationSpec = revealSpring(),
                label = "process-completion-reveal",
            )
            entry.id to retainedState
        }
    }
    val childrenVisible by remember(expanded, previewGroupFraction, entries) {
        derivedStateOf {
            val gf = groupFractionProvider()
            spatialContentVisible(expanded, gf) || entries.any {
                processEntryRetained(it) || revealFraction(retentionStates[it.id]?.value ?: 0f) > 0f
            }
        }
    }
    val groupBody: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .clip(LumaCardShape(14.dp))
                    .clickable { expanded = !expanded }
                    .semantics { stateDescription = (if (hasRunningEntries) "进行中 · " else "") + if (expanded) "已展开执行过程" else "已折叠执行过程" }
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MenuIcon(
                    MenuSymbol.DOWN,
                    size = 18.dp,
                    rotationProvider = { groupFractionProvider() * 180f },
                )
                Text(
                    baselineToolLabelsProcessSummaryText(caption),
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Normal),
                    color = colors.onSurfaceVariant,
                )
            }
            if (childrenVisible) {
                val transitionSignature by remember(entries, expanded, previewGroupFraction) {
                    derivedStateOf {
                        entries.map { entry ->
                            val retained = processEntryRetained(entry)
                            val retiring = !expanded && !retained && revealFraction(retentionStates[entry.id]?.value ?: 0f) > 0f
                            val arriving = revealFraction(arrivalStates[entry.id]?.value ?: 1f) < 1f
                            Triple(retained, retiring, arriving)
                        }
                    }
                }
                val dynamicRuns = remember(entries, transitionSignature, previewGroupFraction) {
                    buildList<Pair<Boolean, List<ConversationItem>>> {
                        entries.forEachIndexed { index, entry ->
                            val (retained, retiring, arriving) = transitionSignature[index]
                            val previous = lastOrNull()
                            if (!retiring && !arriving && previous != null && previous.first == retained &&
                                previous.second.last().let {
                                    val prevIdx = index - 1
                                    !transitionSignature[prevIdx].second && !transitionSignature[prevIdx].third
                                }
                            ) {
                                this[lastIndex] = previous.copy(second = previous.second + entry)
                            } else {
                                add(retained to listOf(entry))
                            }
                        }
                    }
                }
                Column(
                    Modifier
                        .padding(start = 14.dp)
                        .drawBehind {
                            val inset = 4.dp.toPx()
                            val endY = size.height - inset
                            if (endY > inset) {
                                drawLine(border, Offset(0f, inset), Offset(0f, endY), 1.5.dp.toPx())
                            }
                        }
                        .padding(start = 12.dp),
                ) {
                    dynamicRuns.forEachIndexed { runIndex, (runRetained, runEntries) ->
                        val firstId = runEntries.first().id
                        val isFirstRun = runIndex == 0
                        key(firstId) {
                            val retentionState = retentionStates[firstId]
                            val arrivalState = arrivalStates[firstId]
                            val runFractionProvider = remember(firstId, runRetained, previewGroupFraction, retentionState, arrivalState) {
                                {
                                    val gf = groupFractionProvider()
                                    val rf = if (runRetained) 1f else maxOf(gf, revealFraction(retentionState?.value ?: 0f))
                                    val af = revealFraction(arrivalState?.value ?: 1f)
                                    (rf * af).coerceIn(0f, 1f)
                                }
                            }
                            TopReveal(
                                expanded = expanded || runRetained,
                                previewFraction = previewGroupFraction?.let { runFractionProvider() },
                                fractionProvider = if (previewGroupFraction == null) runFractionProvider else null,
                            ) {
                                Column(Modifier.padding(top = if (isFirstRun) 2.dp else 0.dp)) {
                                    runEntries.forEach { entry ->
                                        key(entry.id) {
                                            val entryRetention = retentionStates[entry.id]
                                            val isRetiring by remember(entry.id, runRetained, entryRetention) {
                                                derivedStateOf {
                                                    !runRetained && revealFraction(entryRetention?.value ?: 0f) > 0f
                                                }
                                            }
                                            Column(Modifier.padding(bottom = 2.dp)) {
                                                entryState.SaveableStateProvider(entry.id) {
                                                    BaselineToolLabelsProcessEntry(
                                                        entry,
                                                        cli = cli,
                                                        retiring = isRetiring,
                                                        initiallyExpanded = initiallyOpenItem == entry.id && !previewDetailClosing,
                                                        previewDetailFraction = previewDetailFraction.takeIf { initiallyOpenItem == entry.id },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    Column(Modifier.fillMaxWidth()) {
        if (animateInitialArrival && previewGroupFraction == null) {
            TopReveal(expanded = groupIntroduced) { groupBody() }
        } else {
            groupBody()
        }
        MessageMedia(entries.flatMap(::messageMedia).distinctBy { it.source })
    }
}

@Composable
private fun BaselineToolLabelsProcessEntry(
    item: ConversationItem,
    cli: String,
    retiring: Boolean = false,
    initiallyExpanded: Boolean = false,
    previewDetailFraction: Float? = null,
) {
    if (item.kind == "subAgent") {
        SubAgentEntry(item, initiallyExpanded, previewDetailFraction)
        return
    }
    var expanded by rememberSaveable(item.id) { mutableStateOf(initiallyExpanded) }
    val reasoning = item.kind == "reasoning"
    val running = item.outcome == "running"
    val failed = item.outcome == "failed"
    val colors = MaterialTheme.colorScheme
    val color = when {
        failed -> colors.error
        running -> colors.primary
        else -> colors.onSurfaceVariant
    }
    val label = baselineToolLabelsProcessEntryLabel(item, cli)
    val symbol = if (reasoning) MenuSymbol.THINKING else when (baselineToolLabelsProcessCategory(item, cli)) {
        BaselineToolLabelsProcessCategory.Terminal -> MenuSymbol.TERMINAL
        BaselineToolLabelsProcessCategory.Edit -> MenuSymbol.DIFF
        BaselineToolLabelsProcessCategory.Read -> MenuSymbol.FOLDER
        BaselineToolLabelsProcessCategory.Search -> MenuSymbol.SEARCH
        BaselineToolLabelsProcessCategory.WebSearch, BaselineToolLabelsProcessCategory.Browse -> MenuSymbol.BROWSER
        BaselineToolLabelsProcessCategory.Image -> MenuSymbol.IMAGE
        BaselineToolLabelsProcessCategory.Tool -> MenuSymbol.EXTENSION
    }
    val cleanTitle = baselineToolLabelsProcessEntryContext(item, cli)
    val chevronFractionState = animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = revealSpring(),
        label = "process-entry-chevron",
    )
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(LumaCardShape(12.dp))
                .clickable { expanded = !expanded }
                .semantics { stateDescription = (if (running) "进行中 · " else "") + if (expanded) "收起$label" else "展开$label" }
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .size(24.dp)
                    .background(color.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                CompositionLocalProvider(LocalContentColor provides color) {
                    MenuIcon(symbol, size = 14.dp)
                }
            }
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = color)) { append(label) }
                    if (cleanTitle.isNotBlank()) {
                        append(" · ")
                        withStyle(SpanStyle(color = colors.onSurface.copy(alpha = 0.82f))) { append(cleanTitle) }
                    }
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CompositionLocalProvider(LocalContentColor provides color.copy(alpha = 0.85f)) {
                    ProcessStatus(item.outcome)
                }
                CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant.copy(alpha = 0.7f)) {
                    MenuIcon(MenuSymbol.DOWN, size = 16.dp, rotationProvider = { revealFraction(chevronFractionState.value) * 180f })
                }
            }
        }
        TopReveal(
            expanded = expanded,
            previewFraction = previewDetailFraction,
            collapsedContent = if ((running || retiring) && item.detail.isNotBlank()) {
                {
                    Text(
                        item.detail.takeLast(360).lineSequence().toList().takeLast(3).joinToString("\n"),
                        Modifier.padding(start = 32.dp, bottom = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = if (reasoning) null else terminalFont(),
                        color = if (failed) color else colors.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else null,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, top = 4.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (reasoning) {
                    if (item.detail.isBlank()) {
                        Text("CLI 尚未提供摘要。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    } else {
                        NativeMarkdown(item.detail)
                    }
                } else {
                    if (item.input.isNotBlank()) {
                        CodeBlock(item.input, baselineToolLabelsProcessInputCaption(item, cli))
                    }
                    if (item.detail.isNotBlank()) {
                        CodeBlock(
                            item.detail,
                            baselineToolLabelsProcessOutputCaption(item),
                        )
                    } else if (messageMedia(item).isEmpty()) {
                        Text(
                            if (running) "等待工具输出…" else "工具未返回文本输出。",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
