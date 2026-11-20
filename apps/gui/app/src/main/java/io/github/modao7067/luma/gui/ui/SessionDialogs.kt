package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.i18n.tr

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.modao7067.luma.gui.session.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@Composable
internal fun LiquidModalDialog(
    title: String,
    subtitle: String = "",
    onDismiss: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!LocalSessionInteractive.current) {
        SideEffect(onDismiss)
        return
    }
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val baseTint = if (dark) scheme.surfaceContainerHigh.copy(alpha = 0.96f) else scheme.surfaceContainerLowest.copy(alpha = 0.97f)
    GuiDialog(onDismissRequest = onDismiss) {
        val inspecting = LocalInspectionMode.current
        val entry = remember { Animatable(if (inspecting) 1f else 0f) }
        LaunchedEffect(inspecting) {
            if (!inspecting) entry.animateTo(1f, PrismSprings.alert())
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 420.dp)
                .graphicsLayer {
                    val p = entry.value.coerceIn(0f, 1f)
                    val s = PrismSprings.ALERT_ENTRY_SCALE + (1f - PrismSprings.ALERT_ENTRY_SCALE) * p
                    scaleX = s
                    scaleY = s
                    alpha = p
                },
            shape = LumaCardShape(28.dp),
            color = Color.Transparent,
            contentColor = scheme.onSurface,
            tonalElevation = 0.dp,
            shadowElevation = 10.dp,
        ) {
            LiquidSurface(
                cornerRadius = 28.dp,
                tonalTint = baseTint,
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (subtitle.isNotBlank()) {
                            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        content = content,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (dismissButton != null) {
                            dismissButton()
                            Spacer(Modifier.width(8.dp))
                        }
                        confirmButton()
                    }
                }
            }
        }
    }
}

internal fun requestConnectionReady(view: SessionView, requestId: String): Boolean =
    view.requests.any { it.id == requestId } &&
        (view.connected || (view.summary.cli in CliCatalog.bridged && view.loading))

internal fun requestDialogPresentation(title: String, body: String): Pair<String, String> {
    val cleanTitle = title.trim()
    val firstLine = cleanTitle.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    val isMultilineOrLong = cleanTitle.contains('\n') || cleanTitle.length > 72
    if (!isMultilineOrLong) return cleanTitle.ifEmpty { tr("工具授权", "Tool authorization") } to body
    val header = when {
        firstLine.isEmpty() -> tr("工具授权", "Tool authorization")
        firstLine.length > 72 -> firstLine.take(72) + "…"
        cleanTitle.contains('\n') -> "$firstLine…"
        else -> firstLine
    }
    val redundantSingleCommandJson = body.trim().startsWith("{") && runCatching {
        val obj = org.json.JSONObject(body)
        obj.length() == 1 && (obj.optString("CommandLine") == cleanTitle || obj.optString("command") == cleanTitle)
    }.getOrDefault(false)
    val detailBody = when {
        body.isBlank() || redundantSingleCommandJson -> "```\n$cleanTitle\n```"
        body.contains(cleanTitle) -> body
        else -> "```\n$cleanTitle\n```\n\n$body"
    }
    return header to detailBody
}

@Composable
internal fun RequestDialog(
    request: UserRequest,
    actions: CockpitActions,
    canRespond: Boolean = true,
    reconnecting: Boolean = false,
    closing: Boolean = false,
    onDismiss: () -> Unit,
) {
    RequestSheet(
        request = request,
        actions = actions,
        canRespond = canRespond,
        reconnecting = reconnecting,
        closing = closing,
        onDismiss = onDismiss,
    )
}

@Composable
internal fun FilePreviewDialog(preview: FilePreview, actions: CockpitActions) {
    GuiPageDialog(onDismissRequest = actions::closePreview) { FilePreviewScreen(preview, actions) }
}

@Composable
internal fun FilePreviewScreen(preview: FilePreview, actions: CockpitActions, previewScrollOffset: Int = 0) {
    val inherited = LocalMediaContext.current
    val parentContext = MediaContext(preview.sessionId ?: inherited.sessionId, preview.cwd ?: inherited.cwd)
    val scope = rememberCoroutineScope()
    val saveFeedback = remember { SnackbarHostState() }
    var opening by remember(preview.sourceResource ?: preview.path) { mutableStateOf(false) }
    var openError by remember(preview.sourceResource ?: preview.path) { mutableStateOf<String?>(null) }
    MediaViewerHost("file:${preview.sessionId}:${preview.sourceResource?.source ?: preview.path}:${preview.cwd}", actions, parentContext) {
        val media = preview.media
        if (media != null) {
            MediaViewerScreen(media.inContext(parentContext), onBack = actions::closePreview)
        } else Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            LiquidScaffold(wrapTop = false, wrapBottom = false, edgeToEdgeContent = true,
                topBar = {
                    DetailPageHeader(preview.path.substringAfterLast('/'), actions::closePreview, backLabel = tr("返回来源", "Back")) {
                        MediaSaveButton(preview.sourceResource?.inContext(parentContext), actions) { message ->
                            scope.launch { saveFeedback.showSnackbar(message) }
                        }
                    }
                },
                bottomBar = {},
            ) { padding ->
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState(previewScrollOffset)).testTag("file-preview-scroll").padding(padding).padding(horizontal = 16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            val source = preview.sourceResource
                            if (source != null) {
                                TextButton(enabled = !opening, onClick = {
                                    opening = true; openError = null
                                    scope.launch {
                                        try { actions.openMediaExternally(source.inContext(parentContext)) }
                                        catch (cancelled: CancellationException) { throw cancelled }
                                        catch (failure: Exception) { openError = failure.message ?: tr("无法打开", "Unable to open") }
                                        finally { opening = false }
                                    }
                                }) { MenuIcon(MenuSymbol.OPEN_EXTERNAL, size = 18.dp); Spacer(Modifier.width(8.dp)); Text(tr("用其他应用打开", "Open in another app")) }
                            } else {
                                Spacer(Modifier.width(1.dp))
                            }
                            if (preview.text.isNotBlank() && !filePreviewIsMarkdown(preview)) {
                                val clipboard = LocalClipboardManager.current
                                TextButton(onClick = {
                                    clipboard.setText(AnnotatedString(preview.text))
                                    scope.launch { saveFeedback.showSnackbar(tr("已复制全文", "Copied full text")) }
                                }) { ComposerGlyph("copy"); Spacer(Modifier.width(6.dp)); Text(tr("复制全文", "Copy all")) }
                            }
                        }
                        openError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                        when {
                            preview.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                            preview.text.isEmpty() -> Text(if (preview.path.contains("diff", true)) tr("没有可显示的差异。", "No diff to display.") else tr("文件为空。", "File is empty."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            filePreviewIsMarkdown(preview) -> ConversationTypography { NativeMarkdown(preview.text, actions::previewFile) }
                            else -> CodeFileViewer(preview.text, if (preview.path.contains("diff", true)) "diff" else resolveFileCodeLanguage(preview.path, preview.text))
                        }
                    }
                    SnackbarHost(saveFeedback, Modifier.align(Alignment.BottomCenter).padding(padding).padding(16.dp))
                }
            }
        }
    }
}

@Composable
internal fun CodeFileViewer(
    code: String,
    language: String = "",
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val highlighted = rememberHighlightedCode(code, language, colors)
    val lineCount = remember(code) { if (code.isEmpty()) 1 else code.count { it == '\n' } + 1 }
    val lineNumbers = remember(lineCount) { (1..lineCount).joinToString("\n") }
    val codeStyle = MaterialTheme.typography.bodyMedium.copy(
        color = colors.onSurface,
        fontFamily = terminalFont(),
        fontFeatureSettings = com.termux.view.BundledTerminalFonts.FEATURE_SETTINGS,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    )

    LumaSelectionContainer {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            DisableSelection {
                Text(
                    text = lineNumbers,
                    style = codeStyle.copy(
                        color = colors.onSurfaceVariant.copy(alpha = 0.55f),
                        textAlign = TextAlign.End,
                    ),
                    modifier = Modifier.padding(end = 16.dp),
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
            ) {
                Text(
                    text = highlighted,
                    style = codeStyle,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
internal fun SettingsDialog(
    enabledClis: Set<String>,
    onToggleCli: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
    environment: String = io.github.modao7067.luma.gui.GuiProduct.environment,
    initialPage: String = "main",
    environmentAccess: io.github.modao7067.luma.gui.runtime.EnvironmentSettingsAccess? = null,
) {
    val hasEnvironmentSettings = environment == "noroot"
    var requestedPage by rememberSaveable { mutableStateOf(initialPage) }
    val subPage = requestedPage.takeUnless { it == "environment" && !hasEnvironmentSettings } ?: "main"
    GuiPageDialog(
        onDismissRequest = { if (subPage != "main") requestedPage = "main" else onDismiss() },
    ) {
        SettingsScreen(
            enabledClis = enabledClis,
            onToggleCli = onToggleCli,
            onDismiss = onDismiss,
            environment = environment,
            initialPage = initialPage,
            environmentAccess = environmentAccess,
            controlledPage = requestedPage,
            onControlledPageChange = { requestedPage = it },
        )
    }
}

@Composable
internal fun SettingsScreen(
    enabledClis: Set<String>,
    onToggleCli: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
    environment: String = io.github.modao7067.luma.gui.GuiProduct.environment,
    initialPage: String = "main",
    environmentAccess: io.github.modao7067.luma.gui.runtime.EnvironmentSettingsAccess? = null,
    controlledPage: String? = null,
    onControlledPageChange: ((String) -> Unit)? = null,
) {
    val hasEnvironmentSettings = environment == "noroot"
    var internalPage by rememberSaveable { mutableStateOf(initialPage) }
    val rawPage = controlledPage ?: internalPage
    val subPage = rawPage.takeUnless { it == "environment" && !hasEnvironmentSettings } ?: "main"
    fun go(page: String) {
        if (onControlledPageChange != null) onControlledPageChange(page) else internalPage = page
    }

    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val subPageBackProgress = remember { Animatable(0f) }
    androidx.activity.compose.PredictiveBackHandler(enabled = subPage != "main") { progress ->
        try {
            progress.collect { event ->
                subPageBackProgress.snapTo(event.progress.coerceIn(0f, 1f))
            }
            go("main")
            subPageBackProgress.snapTo(0f)
        } catch (cancelled: CancellationException) {
            scope.launch { subPageBackProgress.animateTo(0f, PrismSprings.dragBackRelease()) }
            throw cancelled
        }
    }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = colors.background,
    ) {
        LiquidScaffold(
            wrapTop = false,
            wrapBottom = false,
            topBar = {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val backElastic = rememberElasticPress()
                    LiquidGlass(
                        Modifier
                            .size(44.dp)
                            .testTag("settings-back")
                            .semantics { contentDescription = if (subPage == "main") tr("关闭设置", "Close settings") else tr("返回设置", "Back to settings") }
                            .liquidClickable(press = backElastic) {
                                if (subPage != "main") go("main") else onDismiss()
                            },
                        cornerRadius = 22.dp,
                        press = backElastic,
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Box(Modifier.glyphPulse(subPage)) {
                                ComposerGlyph("back")
                            }
                        }
                    }
                    LiquidGlass(
                        Modifier
                            .weight(1f)
                            .height(44.dp),
                        cornerRadius = 22.dp,
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .padding(horizontal = 18.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                when (subPage) { "profile" -> tr("CLI 工具", "CLI tools"); "environment" -> tr("环境设置", "Environment"); else -> tr("设置", "Settings") },
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            )
                        }
                    }
                }
            },
            bottomBar = {},
        ) { padding ->
            androidx.compose.animation.AnimatedContent(
                targetState = subPage,
                transitionSpec = {
                    val pushing = initialState == "main" && targetState != "main"
                    if (pushing) {
                        (slideInHorizontally(PrismSprings.pagePushOffset()) { it / 4 } + fadeIn(tween(220, easing = PrismCurves.EaseOutCubic))) togetherWith
                            (slideOutHorizontally(PrismSprings.pagePushOffset()) { -it / 6 } + fadeOut(tween(180, easing = PrismCurves.EaseInCubic)))
                    } else {
                        (slideInHorizontally(PrismSprings.pagePushOffset()) { -it / 6 } + fadeIn(tween(220, easing = PrismCurves.EaseOutCubic))) togetherWith
                            (slideOutHorizontally(PrismSprings.pagePushOffset()) { it / 4 } + fadeOut(tween(180, easing = PrismCurves.EaseInCubic)))
                    }
                },
                label = "settings-page-transition",
            ) { page ->
                val backShift = if (page != "main") subPageBackProgress.value else 0f
                Column(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            if (backShift > 0f) {
                                translationX = backShift * 42.dp.toPx()
                                alpha = (1f - backShift.coerceAtLeast(0f) * 0.28f).coerceIn(0f, 1f)
                            }
                        }
                        .verticalScroll(rememberScrollState())
                        .padding(
                            start = 16.dp,
                            end = 16.dp,
                            top = padding.calculateTopPadding() + 6.dp,
                            bottom = padding.calculateBottomPadding() + 24.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (page == "main") {
                        SettingsNavigationRow(tr("CLI 工具", "CLI tools"), MenuSymbol.TERMINAL) { go("profile") }
                        if (hasEnvironmentSettings) SettingsNavigationRow(tr("环境设置", "Environment"), MenuSymbol.SETTINGS) { go("environment") }
                    } else if (page == "environment" && hasEnvironmentSettings) {
                        EnvironmentSettingsContent(environmentAccess)
                    } else {
                        CliCatalog.visibleSortedIds.forEach { cli ->
                            ProviderSettingsRow(cli, checked = cli in enabledClis) { onToggleCli(cli, it) }
                        }
                    }
                }
            }
        }
    }
}

internal fun filePreviewIsMarkdown(preview: FilePreview): Boolean =
    preview.sourceResource?.mimeType in setOf("text/markdown", "text/x-markdown") ||
        listOfNotNull(preview.sourceResource?.source, preview.path).any {
            it.substringBefore('?').substringBefore('#').substringAfterLast('.').lowercase() in setOf("md", "markdown")
        }

internal fun requestQuestionCaption(header: String, prompt: String): Pair<String, String> {
    fun normalize(value: String) = value.trim().replace(Regex("^\\d+\\s*[.、:：)）]\\s*"), "").replace(Regex("\\s+"), " ").trim()
    val h = header.trim()
    val p = prompt.trim()
    return when {
        h.isEmpty() -> "" to p
        p.isEmpty() || normalize(h) == normalize(p) -> h to ""
        else -> h to p
    }
}
