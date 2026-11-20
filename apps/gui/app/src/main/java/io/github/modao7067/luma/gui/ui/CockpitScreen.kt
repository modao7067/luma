package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.i18n.LumaI18n
import io.github.modao7067.luma.gui.i18n.tr

import android.content.Context
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import io.github.modao7067.luma.gui.session.*

@Composable
fun CockpitScreen(state: CockpitState, actions: CockpitActions, previewDrawerOpen: Boolean = false,
                  currentSessionId: () -> String? = { state.selected?.summary?.id },
                  currentBlankKey: () -> String = { state.blank.key }) =
    SessionTypography {
        ProvideLumaHaptics {
            val owner = state.selected?.summary?.id ?: "blank:${state.blank.key}"
            MediaViewerHost(owner, actions, MediaContext(state.selected?.summary?.id, state.selected?.summary?.cwd ?: state.blank.cwd)) {
                CockpitContent(state, actions, previewDrawerOpen, currentSessionId, currentBlankKey)
            }
        }
    }

private sealed interface CockpitSecondaryPage {
    data object Usage : CockpitSecondaryPage
    data object Settings : CockpitSecondaryPage
    data class Files(val browser: FileBrowser) : CockpitSecondaryPage
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CockpitContent(state: CockpitState, actions: CockpitActions, previewDrawerOpen: Boolean, currentSessionId: () -> String?, currentBlankKey: () -> String) {
    val context = LocalContext.current
    val inspection = LocalInspectionMode.current
    val prefs = remember(context, inspection) {
        if (inspection) null else runCatching { context.getSharedPreferences("cockpit-navigation", Context.MODE_PRIVATE) }.getOrNull()
    }
    var enabledClis by remember {
        val defaultSet = CliCatalog.visibleIds.toSet()
        val saved = prefs?.getStringSet("enabled_clis", null)?.intersect(defaultSet)?.takeIf { it.isNotEmpty() }
        mutableStateOf(saved ?: defaultSet)
    }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var usageOpen by rememberSaveable { mutableStateOf(false) }
    val drawer = rememberDrawerState(if (previewDrawerOpen) DrawerValue.Open else DrawerValue.Closed)
    suspend fun openDrawer() = drawer.animateTo(DrawerValue.Open, PrismCurves.sideSheetEnter())
    suspend fun closeDrawer() = drawer.animateTo(DrawerValue.Closed, PrismCurves.sideSheetExit())
    val scope = rememberCoroutineScope()
    var sidebarRenaming by remember { mutableStateOf<SessionSummary?>(null) }
    val activityWindow = remember(context) { context.actionActivityWindow() }
    val density = LocalDensity.current
    val viewportWidth = LocalWindowInfo.current.containerSize.width
    val sidebarWidth = minOf(with(density) { 316.dp.toPx() }, viewportWidth.toFloat())
    val drawerVisible by remember(drawer, sidebarWidth) {
        derivedStateOf {
            drawer.currentValue == DrawerValue.Open || drawer.targetValue == DrawerValue.Open ||
                (drawer.currentOffset.isFinite() && drawer.currentOffset > -sidebarWidth + 1f)
        }
    }
    ActionWindowPolicy(activityWindow, enabled = drawerVisible && sidebarRenaming == null)

    val savedScreens = rememberSaveableStateHolder()
    val langCode = LumaI18n.langCode()
    var previousLangCode by rememberSaveable { mutableStateOf(langCode) }
    var pickerSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var pickerError by rememberSaveable { mutableStateOf<String?>(null) }
    var pickerSessionMismatch by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(langCode) {
        if (previousLangCode != langCode) {
            previousLangCode = langCode
            pickerError = null
        }
    }
    val selected = state.selected
    val capture = rememberPhotoCapture(
        ownerKey = { actions.environment + ":" + (currentSessionId()?.let { "session:$it" } ?: "blank:${currentBlankKey()}") },
        enabled = !state.creating && selected?.preparingFirstSend != true && selected?.capabilities?.images != false &&
            state.models.find { it.id == selected?.summary?.model }?.supportsImages != false,
        onImage = actions::addImage,
        onError = {
            pickerSessionMismatch = false
            pickerError = it
        },
    )
    val lastViews = remember { HashMap<String, SessionView>() }
    val seenSaveableIds = remember { HashSet<String>() }
    selected?.let {
        lastViews[it.summary.id] = it
        seenSaveableIds.add(it.summary.id)
    }
    LaunchedEffect(selected?.summary?.id, state.sessions) {
        val activeId = selected?.summary?.id
        if (activeId != null) kotlinx.coroutines.delay(250)
        lastViews.keys.retainAll(setOfNotNull(activeId))
        val aliveIds = state.sessions.map { it.id }.toSet()
        val removed = seenSaveableIds - aliveIds
        removed.forEach { id ->
            savedScreens.removeState(id)
            seenSaveableIds.remove(id)
        }
    }
    val picker = if (!LocalInspectionMode.current) {
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            val target = pickerSessionId
            pickerSessionId = null
            if (uris.isNotEmpty()) {
                val current = currentSessionId()?.let { "session:$it" } ?: "blank:${currentBlankKey()}"
                if (target == null || target != current) {
                    pickerError = null
                    pickerSessionMismatch = true
                } else {
                    uris.forEach { actions.addImage(it.toString()) }
                }
            }
        }
    } else null
    val keyboard = LocalSoftwareKeyboardController.current
    val browserActive = state.browserOpen && selected != null
    var retainedBrowserSessionId by remember { mutableStateOf(selected?.summary?.id?.takeIf { state.browserOpen }) }
    if (selected != null && state.browserOpen) retainedBrowserSessionId = selected.summary.id
    val browserOffset = remember { Animatable(if (browserActive) 1f else 0f) }
    var browserBackDragging by remember { mutableStateOf(false) }
    var measuredWidthPx by remember { mutableIntStateOf(0) }
    val slideWidthPx = (measuredWidthPx.takeIf { it > 0 } ?: viewportWidth.takeIf { it > 0 } ?: with(density) { 412.dp.roundToPx() }).toFloat()
    LaunchedEffect(browserActive, browserBackDragging) {
        if (!browserBackDragging) {
            keyboard?.hide()
            if (browserActive && drawer.isOpen) closeDrawer()
            browserOffset.animatePagePush(if (browserActive) 1f else 0f)
            if (!browserActive) retainedBrowserSessionId = null
        }
    }

    val liveSecondaryPage: CockpitSecondaryPage? = when {
        state.fileBrowser != null -> CockpitSecondaryPage.Files(state.fileBrowser)
        settingsOpen -> CockpitSecondaryPage.Settings
        usageOpen -> CockpitSecondaryPage.Usage
        else -> null
    }
    var retainedSecondaryPage by remember { mutableStateOf(liveSecondaryPage) }
    if (liveSecondaryPage != null) retainedSecondaryPage = liveSecondaryPage
    val secondaryActive = liveSecondaryPage != null
    val secondaryOffset = remember { Animatable(if (secondaryActive) 1f else 0f) }
    var secondaryBackDragging by remember { mutableStateOf(false) }
    LaunchedEffect(secondaryActive, secondaryBackDragging) {
        if (!secondaryBackDragging) {
            if (secondaryActive) {
                keyboard?.hide()
                if (drawer.isOpen) closeDrawer()
            }
            secondaryOffset.animatePagePush(if (secondaryActive) 1f else 0f)
            if (!secondaryActive) retainedSecondaryPage = null
        }
    }

    val liveInlinePreview = state.filePreview?.takeIf { state.inventory == null }
    var retainedInlinePreview by remember { mutableStateOf(liveInlinePreview) }
    if (liveInlinePreview != null) retainedInlinePreview = liveInlinePreview
    val previewActive = liveInlinePreview != null
    val previewOffset = remember { Animatable(if (previewActive) 1f else 0f) }
    var previewBackDragging by remember { mutableStateOf(false) }
    LaunchedEffect(previewActive, previewBackDragging) {
        if (!previewBackDragging) {
            if (previewActive) keyboard?.hide()
            previewOffset.animatePagePush(if (previewActive) 1f else 0f)
            if (!previewActive) retainedInlinePreview = null
        }
    }

    fun dismissSecondaryPage() {
        when {
            state.fileBrowser != null -> actions.closeFiles()
            settingsOpen -> settingsOpen = false
            usageOpen -> usageOpen = false
        }
    }

    val secProgress = secondaryOffset.value.coerceIn(0f, 1f)
    val prevProgress = previewOffset.value.coerceIn(0f, 1f)
    val tier0Depth = (secProgress + (1f - secProgress) * prevProgress).coerceIn(0f, 1f)
    val tier0Covered = secondaryActive || previewActive || tier0Depth > 0.01f

    PredictiveBackHandler(enabled = drawer.isOpen && !browserActive && !secondaryActive && !previewActive) { progress ->
        try {
            progress.collect { backEvent ->
                val clamped = backEvent.progress.coerceIn(0f, 1f)
                val targetOffset = -sidebarWidth * clamped
                val current = drawer.currentOffset
                if (current.isFinite()) {
                    @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
                    drawer.anchoredDraggableState.dispatchRawDelta(targetOffset - current)
                }
            }
            closeDrawer()
        } catch (cancelled: CancellationException) {
            scope.launch { openDrawer() }
            throw cancelled
        }
    }
    PredictiveBackHandler(enabled = browserActive && !secondaryActive && !previewActive) { progress ->
        browserBackDragging = true
        try {
            progress.collect { backEvent ->
                val clamped = backEvent.progress.coerceIn(0f, 1f)
                browserOffset.snapTo(1f - clamped)
            }
            browserBackDragging = false
            actions.closeBrowser()
            scope.launch {
                browserOffset.animatePagePush(0f)
                retainedBrowserSessionId = null
            }
        } catch (cancelled: CancellationException) {
            browserBackDragging = false
            scope.launch { browserOffset.animateTo(1f, PrismSprings.dragBackRelease()) }
            throw cancelled
        }
    }

    Box(Modifier.fillMaxSize().onSizeChanged { measuredWidthPx = it.width }) {
        CompositionLocalProvider(LocalSessionInteractive provides !tier0Covered) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = -0.26f * slideWidthPx * tier0Depth }
                    .then(
                        if (!tier0Covered) Modifier
                        else Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                            }
                        },
                    ),
            ) {
                CompositionLocalProvider(LocalDrawerOpen provides drawerVisible) {
                    DismissibleNavigationDrawer(
                        drawerState = drawer,
                    gesturesEnabled = !browserActive && browserOffset.value < 0.01f && !tier0Covered,
                    drawerContent = {
                        DismissibleDrawerSheet(
                            Modifier.widthIn(max = 316.dp),
                            drawerShape = RectangleShape,
                            drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            windowInsets = WindowInsets(0, 0, 0, 0),
                        ) {
                            Row(Modifier.fillMaxSize()) {
                                Box(Modifier.weight(1f).fillMaxHeight()) {
                                    SessionSidebar(
                                        state.sessions,
                                        selected?.summary?.id,
                                        onOpen = { actions.openSession(it); scope.launch { closeDrawer() } },
                                        onNew = { cli -> actions.createDefaultCliSession(cli); scope.launch { closeDrawer() } },
                                        enabledClis = enabledClis,
                                        onRename = { id, title -> actions.renameSession(id, title) },
                                        onArchive = { id -> actions.archiveSession(id) },
                                        onRenameRequested = { target ->
                                            sidebarRenaming = target
                                            scope.launch { closeDrawer() }
                                        },
                                        freezeOrder = drawerVisible,
                                    )
                                }
                                VerticalDivider(
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.38f),
                                )
                            }
                        }
                    },
                ) {
                    Box(Modifier.fillMaxSize()) {
                        Surface(
                            color = MaterialTheme.colorScheme.background,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { translationX = -slideWidthPx * browserOffset.value.coerceIn(0f, 1f) }
                                .then(
                                    if (!browserActive && browserOffset.value < 0.01f) Modifier
                                    else Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
                                        awaitPointerEventScope {
                                            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                                        }
                                    },
                                ),
                        ) {
                            AnimatedContent(
                                targetState = selected?.summary?.id,
                                transitionSpec = {
                                    fadeIn(menuMotionSpec(true)) togetherWith fadeOut(menuMotionSpec(false))
                                },
                                label = "cockpit-session-transition",
                            ) { sessionId ->
                                val activeView = if (sessionId == null) null else state.selected?.takeIf { it.summary.id == sessionId } ?: lastViews[sessionId]
                                if (activeView == null) {
                                    BlankConversation(
                                        state, actions, currentSessionId, currentBlankKey,
                                        onPickImages = { pickerSessionId = "blank:${state.blank.key}"; picker?.launch("image/*") },
                                        onTakePhoto = capture.launch, takePhotoEnabled = capture.available && !capture.busy,
                                        onDrawer = { scope.launch { openDrawer() } },
                                        onNew = { actions.createDefaultCliSession(state.blank.cli) },
                                        onSettings = { usageOpen = false; settingsOpen = true },
                                        onUsage = { settingsOpen = false; usageOpen = true },
                                    )
                                } else {
                                    savedScreens.SaveableStateProvider(activeView.summary.id) {
                                        SessionDetail(
                                            state,
                                            activeView,
                                            actions,
                                            onPickImages = { pickerSessionId = "session:${activeView.summary.id}"; picker?.launch("image/*") },
                                            onTakePhoto = capture.launch, takePhotoEnabled = capture.available && !capture.busy,
                                            onDrawer = { scope.launch { openDrawer() } },
                                            onNew = { actions.createDefaultCliSession(activeView.summary.cli) },
                                            onSettings = { usageOpen = false; settingsOpen = true },
                                            onUsage = { settingsOpen = false; usageOpen = true },
                                            currentSessionId = currentSessionId,
                                        )
                                    }
                                }
                            }
                        }
                        val browserOwnerId = selected?.summary?.id?.takeIf { browserActive } ?: retainedBrowserSessionId
                        if (browserOwnerId != null && (browserActive || browserOffset.value > 0.001f)) {
                            Row(
                                Modifier
                                    .fillMaxSize()
                                    .graphicsLayer { translationX = slideWidthPx * (1f - browserOffset.value.coerceIn(0f, 1f)) },
                            ) {
                                VerticalDivider(
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.38f),
                                )
                                AttachedBrowserPane(
                                    sessionId = browserOwnerId,
                                    active = browserActive,
                                    onBack = actions::closeBrowser,
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                    onQuoteSelection = { quote ->
                                        val existing = selected?.draft.orEmpty()
                                        actions.draft(if (existing.isBlank()) quote else existing.trimEnd() + "\n\n" + quote)
                                        actions.closeBrowser()
                                    },
                                )
                            }
                        }
                        if (drawer.isOpen) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .pointerInput(drawer) {
                                        detectTapGestures { scope.launch { closeDrawer() } }
                                    },
                            )
                        }
                    }
                    }
                }
                if (tier0Depth > 0.001f) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.22f * tier0Depth)),
                    )
                }
            }
        }

        PredictiveBackHandler(enabled = secondaryActive && !previewActive) { progress ->
            secondaryBackDragging = true
            try {
                progress.collect { backEvent ->
                    val clamped = backEvent.progress.coerceIn(0f, 1f)
                    secondaryOffset.snapTo(1f - clamped)
                }
                secondaryBackDragging = false
                dismissSecondaryPage()
                scope.launch {
                    secondaryOffset.animatePagePush(0f)
                    retainedSecondaryPage = null
                }
            } catch (cancelled: CancellationException) {
                secondaryBackDragging = false
                scope.launch { secondaryOffset.animateTo(1f, PrismSprings.dragBackRelease()) }
                throw cancelled
            }
        }

        val displayedSecondary = liveSecondaryPage ?: retainedSecondaryPage
        if (displayedSecondary != null && (secondaryActive || secProgress > 0.001f)) {
            val tier1Covered = !secondaryActive || previewActive || prevProgress > 0.01f
            val secCorner = ((1f - secProgress) * 26f).dp
            Row(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = slideWidthPx * (1f - secProgress) - 0.26f * slideWidthPx * prevProgress
                    }
                    .then(
                        if (secProgress < 0.999f) Modifier.clip(RoundedCornerShape(topStart = secCorner, bottomStart = secCorner))
                        else Modifier,
                    )
                    .then(
                        if (!tier1Covered) Modifier
                        else Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                            }
                        },
                    ),
            ) {
                if (secProgress < 0.999f) {
                    VerticalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f),
                    )
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when (displayedSecondary) {
                        CockpitSecondaryPage.Usage -> GlobalUsageScreen(
                            environment = actions.environment,
                            onDismiss = { usageOpen = false },
                        )
                        CockpitSecondaryPage.Settings -> SettingsScreen(
                            enabledClis = enabledClis,
                            onToggleCli = { cli, enabled ->
                                val updated = if (enabled) enabledClis + cli else enabledClis - cli
                                enabledClis = updated
                                prefs?.edit()?.putStringSet("enabled_clis", updated)?.apply()
                            },
                            onDismiss = { settingsOpen = false },
                        )
                        is CockpitSecondaryPage.Files -> FileBrowserScreen(
                            browser = displayedSecondary.browser,
                            actions = actions,
                        )
                    }
                    if (prevProgress > 0.001f) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.22f * prevProgress)),
                        )
                    }
                }
            }
        }

        PredictiveBackHandler(enabled = previewActive) { progress ->
            previewBackDragging = true
            try {
                progress.collect { backEvent ->
                    val clamped = backEvent.progress.coerceIn(0f, 1f)
                    previewOffset.snapTo(1f - clamped)
                }
                previewBackDragging = false
                actions.closePreview()
                scope.launch {
                    previewOffset.animatePagePush(0f)
                    retainedInlinePreview = null
                }
            } catch (cancelled: CancellationException) {
                previewBackDragging = false
                scope.launch { previewOffset.animateTo(1f, PrismSprings.dragBackRelease()) }
                throw cancelled
            }
        }

        val displayedPreview = liveInlinePreview ?: retainedInlinePreview
        if (displayedPreview != null && (previewActive || prevProgress > 0.001f)) {
            val prevCorner = ((1f - prevProgress) * 26f).dp
            Row(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = slideWidthPx * (1f - prevProgress) }
                    .then(
                        if (prevProgress < 0.999f) Modifier.clip(RoundedCornerShape(topStart = prevCorner, bottomStart = prevCorner))
                        else Modifier,
                    )
                    .then(
                        if (previewActive) Modifier
                        else Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                            }
                        },
                    ),
            ) {
                if (prevProgress < 0.999f) {
                    VerticalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f),
                    )
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    FilePreviewScreen(displayedPreview, actions)
                }
            }
        }
    }
    sidebarRenaming?.let { target ->
        SessionRenameDialog(target, actions::renameSession, onDismiss = { sidebarRenaming = null })
    }
    val imageAlertMessage = if (pickerSessionMismatch) {
        tr(
            "选图期间已切换会话，图片未添加。请回到原会话后重新选择。",
            "Switched session while picking image; image was not added. Return to the original session and try again.",
        )
    } else {
        pickerError
    }
    imageAlertMessage?.let { message ->
        val dismissImageAlert = {
            pickerSessionMismatch = false
            pickerError = null
        }
        LiquidModalDialog(
            title = tr("图片未添加", "Image not added"),
            onDismiss = dismissImageAlert,
            confirmButton = {
                TextButton(onClick = dismissImageAlert) { Text(tr("知道了", "Got it")) }
            },
        ) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    state.inventory?.let { inventory ->
        LiquidModalDialog(
            title = inventory.title,
            onDismiss = actions::closeInventory,
            confirmButton = {
                TextButton(onClick = actions::closeInventory) { Text(tr("关闭", "Close")) }
            },
        ) {
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (inventory.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                inventory.error?.let { item { Text(inventoryFetchErrorLabel(inventory.title), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
                if (!inventory.loading && inventory.error == null && inventory.entries.isEmpty()) {
                    item { Text(tr("没有配置项", "No items"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
                }
                items(inventory.entries) { entry ->
                    Surface(
                        shape = LumaCardShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(LumaCardShape(16.dp))
                            .clickable { actions.previewInventory(entry) },
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(34.dp)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                MenuIcon(if (inventory.title == "Skill") MenuSymbol.SKILL else MenuSymbol.MCP, size = 18.dp)
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(entry.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                                Text(
                                    entry.detail,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (state.inventory != null) {
        state.filePreview?.let { FilePreviewDialog(it, actions) }
    }
}

internal fun environmentLabel(value: String) = if (value == "root") "Ubuntu / Root" else "Debian / NoRoot"

@Composable
internal fun ErrorNotice(message: String, dismiss: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    var hadDismiss by remember { mutableStateOf(dismiss != null) }
    SideEffect { if (dismiss != null) hadDismiss = true }
    LiquidSurface(
        tonalTint = colors.errorContainer.copy(alpha = 0.90f),
        cornerRadius = 18.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CompositionLocalProvider(LocalContentColor provides colors.onErrorContainer) {
                MenuIcon(MenuSymbol.WARNING, size = 18.dp)
            }
            Text(
                message,
                color = colors.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (dismiss != null || hadDismiss) {
                TextButton(
                    onClick = { dismiss?.invoke() },
                    enabled = dismiss != null,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.height(32.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.onErrorContainer, disabledContentColor = colors.onErrorContainer),
                ) {
                    Text(tr("知道了", "Got it"), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                }
            }
        }
    }
}
