package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.i18n.tr

import androidx.activity.compose.PredictiveBackHandler
import kotlinx.coroutines.CancellationException
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import io.github.modao7067.luma.gui.session.CockpitActions
import io.github.modao7067.luma.gui.session.CockpitState
import io.github.modao7067.luma.gui.session.SessionView

@Composable
internal fun CompactComposer(
    state: CockpitState,
    view: SessionView,
    actions: CockpitActions,
    onPickImages: () -> Unit,
    onModels: () -> Unit,
    previewExpanded: Boolean = false,
    previewMenu: Boolean = false,
    previewPlugins: Boolean = false,
    previewMotionFraction: Float? = null,
    previewMenuAnchor: ComposerMenuAnchor? = null,
    canStartSession: Boolean = false,
    onTakePhoto: (() -> Unit)? = null,
    takePhotoEnabled: Boolean = true,
    suppressIme: Boolean = false,
    onComposerSettledChanged: ((Boolean) -> Unit)? = null,
    onCollapseRequested: (() -> Unit)? = null,
) {
    val sessionInteractive = LocalSessionInteractive.current
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = !suppressIme && WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var editing by remember(view.summary.id) { mutableStateOf(ComposerFocusState()) }
    var menus by remember(view.summary.id) { mutableStateOf(ComposerMenuState(previewMenuAnchor ?: ComposerMenuAnchor.Plus.takeIf { previewMenu })) }
    val plusAnchor = rememberMorphAnchor()
    val modelAnchor = rememberMorphAnchor()
    val permissionAnchor = rememberMorphAnchor()
    val plusMenu = sessionInteractive && menus.active == ComposerMenuAnchor.Plus
    val permissions = sessionInteractive && menus.active == ComposerMenuAnchor.Permission
    val models = sessionInteractive && menus.active == ComposerMenuAnchor.Model
    var pluginExpanded by remember(view.summary.id) { mutableStateOf(previewPlugins) }
    var pasteNotice by remember(view.summary.id) { mutableStateOf<String?>(null) }
    SideEffect {
        if (!plusMenu && pluginExpanded) pluginExpanded = false
        if ((!sessionInteractive || view.preparingFirstSend) && menus.active != null) {
            menus = ComposerMenuState()
            pluginExpanded = false
        }
    }
    val liveEditing = remember(editing, imeVisible) { editing.onIme(imeVisible) }
    LaunchedEffect(imeVisible) {
        editing = editing.onIme(imeVisible)
    }
    val presentation = liveEditing.presentation(
        view.draft,
        (view.attachments.isNotEmpty() || view.skills.isNotEmpty()),
    )
    val expanded = previewExpanded || presentation.expanded
    var backProgress by remember(view.summary.id) { mutableFloatStateOf(0f) }
    val animatedFractionState = animateFloatAsState(if (expanded) 1f else 0f, animationSpec = spatialSpring(), label = "composer-capsule-expansion")
    val currentFraction: () -> Float = {
        ((previewMotionFraction ?: animatedFractionState.value) * (1f - backProgress)).coerceIn(0f, 1f)
    }
    val settledNow = remember(expanded, previewMotionFraction) {
        derivedStateOf { !expanded && currentFraction() <= 0.02f }
    }.value
    SideEffect {
        onComposerSettledChanged?.invoke(settledNow)
    }
    fun collapse(notifySend: Boolean = false) {
        if (notifySend && (imeVisible || expanded || currentFraction() > 0.02f)) {
            onCollapseRequested?.invoke()
        }
        focusManager.clearFocus()
        keyboard?.hide()
        editing = liveEditing.collapse().copy(focused = false)
    }
    val toolsInteractive by remember(expanded, previewMotionFraction) {
        derivedStateOf { composerToolsInteractive(expanded, currentFraction()) }
    }
    PredictiveBackHandler(enabled = sessionInteractive && liveEditing.capturesBack && !plusMenu && !permissions && !models) { progress ->
        try {
            progress.collect { event ->
                backProgress = event.progress.coerceIn(0f, 1f)
            }
            backProgress = 0f
            collapse()
        } catch (cancelled: CancellationException) {
            backProgress = 0f
            throw cancelled
        }
    }
    val imagesSupported = view.capabilities.images && state.models.find { it.id == view.summary.model }?.supportsImages != false
    val hasInput = view.draft.isNotBlank() || view.attachments.isNotEmpty() || view.skills.isNotEmpty()
    val handshakeLocked = view.preparingFirstSend || (view.awaitingHandshake && view.activeTurnId == null)
    val inputBusy = handshakeLocked
    val canSend = composerCanSend(
        true,
        view.configuring,
        inputBusy,
        false,
        hasInput,
        view.attachments.any { it.busy },
        imagesSupported,
        view.attachments.isNotEmpty(),
    )
    val primaryWaiting = handshakeLocked || (view.sending && !hasInput && view.activeTurnId == null)
    val primaryAction = composerPrimaryAction(view.activeTurnId != null, hasInput, primaryWaiting)
    val editorLineHeight = bodyLineHeight()
    val editorMaxHeight = editorLineHeight * 7 + 1.dp
    val editor = remember(view.summary.id) { ComposerEditorState(presentation.draft) }
    editor.sync(presentation.draft)
    editor.onTextChange = actions::draft
    val canEdit by rememberUpdatedState(sessionInteractive && !inputBusy)
    val target = remember(editor) { EditorProcessTarget({ editor.value }, editor::edit, { canEdit }) }
    DisposableEffect(target) { onDispose { target.alive = false } }
    Column(Modifier.fillMaxWidth()) {
        AnimatedVisibility(
            visible = view.attachments.isNotEmpty(),
            enter = collapsibleEnter(),
            exit = collapsibleExit(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 14.dp, end = 14.dp, top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                view.attachments.forEach { attachment ->
                    key(attachment.id) {
                        Box(Modifier.padding(top = 4.dp, end = 4.dp)) {
                            ImageThumbnail(attachment.uri, attachment.name, busy = attachment.busy, blockHorizontalSwipe = false)
                            val removeHaptic = LocalHapticFeedback.current
                            Surface(
                                onClick = {
                                    removeHaptic.tick()
                                    actions.removeImage(attachment.id)
                                },
                                enabled = !inputBusy,
                                shape = androidx.compose.foundation.shape.CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f),
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                shadowElevation = 2.dp,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 4.dp, y = (-4).dp)
                                    .size(24.dp)
                                    .elasticPress(enabled = !inputBusy, stretch = false)
                                    .semantics { contentDescription = tr("移除", "Remove") },
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    ComposerGlyph("close")
                                }
                            }
                        }
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = view.skills.isNotEmpty(),
            enter = collapsibleEnter(),
            exit = collapsibleExit(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 14.dp, end = 14.dp, top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                view.skills.forEach { skill ->
                    key(skill.id) {
                        val skillHaptic = LocalHapticFeedback.current
                        val skillEnabled = !inputBusy && !view.uncertainSubmission
                        InputChip(
                            selected = true,
                            onClick = {
                                skillHaptic.tick()
                                actions.toggleSkill(skill)
                            },
                            enabled = skillEnabled,
                            modifier = Modifier
                                .elasticPress(enabled = skillEnabled, stretch = false),
                            label = { Text(skill.name, style = MaterialTheme.typography.labelMedium) },
                            trailingIcon = { ComposerGlyph("close") },
                        )
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = pasteNotice != null,
            enter = collapsibleEnter(),
            exit = collapsibleExit(),
        ) {
            Text(
                pasteNotice.orEmpty(),
                Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Layout(
            modifier = Modifier.fillMaxWidth(),
            content = {
                Box(Modifier.layoutId("input"), contentAlignment = Alignment.CenterStart) {
                    SelectionToolbarProvider(editor = target,
                        imagePasteAvailable = { imagesSupported && !inputBusy && clipboardHasImage(context) },
                        pasteImage = { imagesSupported && !inputBusy && pasteClipboardImages(context, actions::addImage) { pasteNotice = it } },
                    ) {
                    BasicTextField(
                        value = editor.value,
                        enabled = sessionInteractive && !inputBusy,
                        onValueChange = editor::edit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { editing = editing.onFocus(it.isFocused) }
                            .semantics { contentDescription = tr("消息草稿", "Message draft") },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(if (expanded) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent),
                        singleLine = false,
                        minLines = 1,
                        maxLines = 7,
                        decorationBox = { inner ->
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .layout { measurable, constraints ->
                                        val fraction = currentFraction()
                                        val controlFraction = composerControlsFraction(fraction)
                                        val lead = (6.dp.toPx() * (1f - composerTextWidthFraction(fraction))).roundToInt()
                                        val vPad = (12.dp.toPx() * controlFraction).roundToInt()
                                        val w = (constraints.maxWidth - lead).coerceAtLeast(0)
                                        val maxH = if (constraints.maxHeight == Constraints.Infinity) Constraints.Infinity else (constraints.maxHeight - vPad * 2).coerceAtLeast(0)
                                        val placeable = measurable.measure(Constraints(w, w, 0, maxH))
                                        val boxH = maxOf(constraints.minHeight, placeable.height + vPad * 2)
                                        layout(constraints.maxWidth, boxH) {
                                            placeable.placeRelative(lead, (boxH - placeable.height) / 2)
                                        }
                                    },
                                propagateMinConstraints = true,
                            ) {
                                inner()
                            }
                        },
                    )
                    }
                }
                Box(Modifier.layoutId("plus"), contentAlignment = Alignment.Center) {
                    ComposerAnchorButton(
                        anchor = plusAnchor,
                        enabled = true,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        onOpen = { if (!plusMenu) pluginExpanded = false; menus = menus.toggle(ComposerMenuAnchor.Plus) },
                        modifier = Modifier
                            .size(34.dp)
                            .semantics { contentDescription = tr("添加附件", "Add attachment") },
                    ) {
                        Box(Modifier.glyphPulse(plusMenu), contentAlignment = Alignment.Center) {
                            ComposerGlyph("plus")
                        }
                    }
                    MorphMenu(
                        expanded = plusMenu,
                        onDismiss = { menus = menus.dismiss(ComposerMenuAnchor.Plus) },
                        anchor = plusAnchor,
                        width = 248.dp,
                        placement = MorphPlacement.Start,
                        preferAbove = true,
                    ) {
                        Column {
                            onTakePhoto?.let { takePhoto ->
                                ActionMenuItem(tr("拍照", "Camera"), MenuSymbol.CAMERA, iconContainer = true,
                                    enabled = imagesSupported && !inputBusy && takePhotoEnabled,
                                    onClick = { menus = menus.dismiss(ComposerMenuAnchor.Plus); takePhoto() })
                            }
                            ActionMenuItem(
                                tr("照片", "Photos"),
                                MenuSymbol.IMAGE,
                                iconContainer = true,
                                enabled = imagesSupported && !inputBusy,
                                onClick = { menus = menus.dismiss(ComposerMenuAnchor.Plus); onPickImages() },
                            )
                            ActionMenuItem(
                                tr("引用文件", "Reference files"),
                                MenuSymbol.FILE,
                                iconContainer = true,
                                enabled = !canStartSession && !view.preparingFirstSend,
                                onClick = { menus = menus.dismiss(ComposerMenuAnchor.Plus); actions.pickFiles(view.summary.cwd) },
                            )
                            HorizontalDivider(
                                Modifier.padding(horizontal = 16.dp, vertical = 5.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                            )
                            val idle = !view.sending && !view.uncertainSubmission && view.connected && view.activeTurnId == null && view.queue.isEmpty()
                            ActionMenuItem(
                                tr("压缩上下文", "Compact context"),
                                MenuSymbol.COMPRESS,
                                iconContainer = true,
                                enabled = idle && view.capabilities.compact,
                                onClick = { menus = menus.dismiss(ComposerMenuAnchor.Plus); actions.command("compact") },
                            )
                            if (view.activeTurnId != null) {
                                ActionMenuItem(
                                    tr("停止任务", "Stop task"),
                                    MenuSymbol.STOP,
                                    iconContainer = true,
                                    destructive = true,
                                    enabled = view.connected && view.capabilities.stop && !view.stopping,
                                    onClick = { menus = menus.dismiss(ComposerMenuAnchor.Plus); actions.stop() },
                                )
                            }
                        }
                    }
                }
                Box(Modifier.layoutId("primary"), contentAlignment = Alignment.Center) {
                    val stopping = primaryAction == ComposerPrimaryAction.STOP
                    val primaryEnabled = !inputBusy && (if (stopping) view.connected && view.capabilities.stop && !view.stopping else canSend)
                    CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    val sendHaptic = LocalHapticFeedback.current
                    FilledIconButton(
                        enabled = primaryEnabled,
                        onClick = {
                            sendHaptic.tap()
                            if (stopping) {
                                actions.stop()
                            } else {
                                collapse(notifySend = true)
                                actions.send()
                            }
                        },
                        modifier = Modifier
                            .size(34.dp)
                            .elasticPress(enabled = primaryEnabled)
                            .blockHorizontalPageSwipe(enabled = primaryEnabled)
                            .focusProperties { canFocus = false }
                            .semantics {
                                contentDescription = if (view.preparingFirstSend) {
                                    tr("正在创建会话", "Creating session…")
                                } else if (primaryWaiting) {
                                    tr("等待送达确认", "Awaiting delivery receipt")
                                } else if (stopping) {
                                    if (view.stopping) tr("停止请求中", "Stopping…") else tr("停止任务", "Stop task")
                                } else {
                                    tr("发送", "Send")
                                }
                            },
                    ) {
                        Box(Modifier.glyphPulse(Triple(stopping, primaryWaiting, view.stopping)), contentAlignment = Alignment.Center) {
                            if (primaryWaiting || (stopping && view.stopping)) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                ComposerGlyph(if (stopping) "stop" else "send")
                            }
                        }
                    }
                    }
                }
                run {
                    Box(
                        Modifier
                            .layoutId("model")
                            .graphicsLayer { alpha = composerTextWidthFraction(currentFraction()) }
                            .then(if (!toolsInteractive) Modifier.clearAndSetSemantics {} else Modifier),
                    ) {
                        val modelEnabled = toolsInteractive && view.connected && !view.preparingFirstSend
                        ComposerAnchorButton(
                            anchor = modelAnchor,
                            enabled = modelEnabled,
                            containerColor = if (modelEnabled) MaterialTheme.colorScheme.surfaceContainerHighest
                                else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = if (view.connected) 1f else 0.6f),
                            contentColor = if (modelEnabled) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = if (view.connected) 1f else 0.38f),
                            onOpen = { menus = menus.toggle(ComposerMenuAnchor.Model) },
                            modifier = Modifier
                                .height(34.dp)
                                .defaultMinSize(minWidth = ButtonDefaults.MinWidth),
                        ) {
                            Text(
                                view.summary.modelLabel.ifBlank { view.summary.model }.ifBlank { tr("默认模型", "Default model") },
                                Modifier.padding(horizontal = 12.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            )
                        }
                        ModelMenu(models, { menus = menus.dismiss(ComposerMenuAnchor.Model) }, state, view, actions, preferAbove = true, anchor = modelAnchor)
                    }
                    Box(
                        Modifier
                            .layoutId("permission")
                            .graphicsLayer { alpha = composerTextWidthFraction(currentFraction()) }
                            .then(if (!toolsInteractive) Modifier.clearAndSetSemantics {} else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        val permissionButton = composerPermissionButtonState(
                            view.summary.cli,
                            view.permission,
                            view.permissions,
                            view.planModeSupported,
                            view.planMode,
                            view.skipPermissionsActive,
                        )
                        val buttonContainerColor = if (permissionButton.planHighlight) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
                        val buttonContentColor = if (permissionButton.planHighlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        ComposerAnchorButton(
                            anchor = permissionAnchor,
                            enabled = toolsInteractive && !view.preparingFirstSend,
                            containerColor = buttonContainerColor,
                            contentColor = buttonContentColor,
                            onOpen = { menus = menus.toggle(ComposerMenuAnchor.Permission) },
                            modifier = Modifier
                                .size(34.dp)
                                .semantics { contentDescription = permissionButton.description },
                        ) {
                            Box(Modifier.glyphPulse(permissionButton.symbol to permissionButton.planHighlight), contentAlignment = Alignment.Center) {
                                MenuIcon(permissionButton.symbol, warning = permissionButton.warning, containerLuminance = buttonContainerColor.luminance())
                            }
                        }
                        MorphMenu(permissions, { menus = menus.dismiss(ComposerMenuAnchor.Permission) }, permissionAnchor, width = 300.dp, placement = MorphPlacement.Start, preferAbove = true) {
                            val canConfigure = !view.sending && !view.uncertainSubmission && !view.configuring && view.connected && view.activeTurnId == null && view.queue.isEmpty()
                            val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                            if (view.planModeSupported) {
                                ActionMenuItem(
                                    "Plan",
                                    MenuSymbol.PLAN,
                                    selected = view.planMode,
                                    iconContainer = true,
                                    enabled = canConfigure,
                                    onClick = { menus = menus.dismiss(ComposerMenuAnchor.Permission); actions.configurePlanMode(!view.planMode) },
                                )
                                if (view.permissions.isNotEmpty()) {
                                    HorizontalDivider(
                                        Modifier.padding(horizontal = 16.dp, vertical = 5.dp).testTag("permission-plan-divider"),
                                        thickness = 0.5.dp,
                                        color = dividerColor,
                                    )
                                }
                            }
                            if (view.permissions.isEmpty()) {
                                Text(
                                    view.capabilityNotice ?: tr("CLI 未提供可选权限", "No selectable permission modes from CLI"),
                                    Modifier.padding(16.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            val permissionGroups = permissionMenuGroups(view.summary.cli, view.permissions, view.planModeSupported)
                            permissionGroups.forEachIndexed { groupIndex, group ->
                                if (groupIndex > 0) {
                                    HorizontalDivider(
                                        Modifier.padding(horizontal = 16.dp, vertical = 5.dp).testTag("permission-group-divider-$groupIndex"),
                                        thickness = 0.5.dp,
                                        color = dividerColor,
                                    )
                                }
                                if (permissionGroups.size > 1 && group.first().configName.isNotBlank()) {
                                    Text(group.first().configName, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
                                }
                                group.forEach { option ->
                                    val permission = permissionPresentation(option.id, option.label, view.summary.cli, option)
                                    val isSelected = isPermissionSelected(option, view.permission)
                                    ActionMenuItem(
                                        permission.title,
                                        permission.icon,
                                        selected = isSelected,
                                        warning = permission.warning,
                                        iconContainer = true,
                                        enabled = canConfigure,
                                        onClick = {
                                            menus = menus.dismiss(ComposerMenuAnchor.Permission)
                                            if (!(isSelected && !view.planModeSupported && isPlanPermissionOption(view.summary.cli, option))) {
                                                if (option.configId.isBlank()) actions.configurePermission(option.id)
                                                else actions.configurePermissionGroup(option.configId, option.id)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            },
        ) { children, constraints ->
            val fraction = currentFraction()
            val toolsAlpha = composerTextWidthFraction(fraction)
            val width = constraints.maxWidth
            val button = 48.dp.roundToPx()
            val gutter = 6.dp.roundToPx()
            val textInset = 18.dp.roundToPx()
            fun mix(a: Int, b: Int, progress: Float = fraction) = (a + (b - a) * progress).roundToInt()
            val editorHorizontal = composerEditorHorizontal(width, button, gutter, textInset, fraction)
            val inputWidth = editorHorizontal.width
            val controlProgress = composerControlsFraction(fraction)
            val editorPadding = (24.dp.toPx() * controlProgress).roundToInt()
            val lineWithPadding = editorLineHeight.roundToPx() + editorPadding
            val minInputHeight = mix(button, lineWithPadding, controlProgress)
            val maxExpandedInput = minOf(
                editorMaxHeight.roundToPx() + editorPadding,
                (constraints.maxHeight - button).coerceAtLeast(lineWithPadding),
            )
            val maxInputHeight = maxOf(minInputHeight, mix(button, maxExpandedInput, fraction))
            val input = children.first { it.layoutId == "input" }.measure(
                Constraints(
                    minWidth = inputWidth,
                    maxWidth = inputWidth,
                    minHeight = minInputHeight,
                    maxHeight = maxInputHeight,
                ),
            )
            val plus = children.first { it.layoutId == "plus" }.measure(Constraints.fixed(button, button))
            val primary = children.first { it.layoutId == "primary" }.measure(Constraints.fixed(button, button))
            val modelWidth = (width - 3 * button - 3 * gutter).coerceAtLeast(1)
            val model = children.firstOrNull { it.layoutId == "model" }?.measure(Constraints(0, modelWidth, 0, button))
            val permission = children.first { it.layoutId == "permission" }.measure(Constraints.fixed(button, button))
            val controlsY = mix(0, input.height, controlProgress)
            val height = controlsY + button
            layout(width, height) {
                input.placeRelative(editorHorizontal.left, 0)
                plus.placeRelative(0, controlsY)
                primary.placeRelative(width - button, controlsY)
                if (toolsAlpha > 0f) {
                    model?.placeRelative(width - button - gutter * 2 - model.width, controlsY + (button - model.height) / 2)
                    permission.placeRelative(button, controlsY)
                }
            }
        }
    }
}

@Composable
private fun ComposerAnchorButton(
    anchor: MorphAnchor,
    enabled: Boolean,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .focusProperties { canFocus = false }
            .morphAnchor(anchor, enabled, onOpen, elastic = rememberElasticPress(), sourceColor = containerColor, touchTarget = true)
            .clip(CircleShape)
            .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
    }
}

@Composable
internal fun ComposerGlyph(kind: String) {
    val color = LocalContentColor.current
    val sizeDp = when (kind) {
        "close" -> 14.dp
        "copy", "check" -> 16.dp
        else -> 20.dp
    }
    Canvas(Modifier.size(sizeDp)) {
        val stroke = (if (kind == "close" || kind == "copy" || kind == "check") 1.6.dp else 1.9.dp).toPx()
        fun line(a: Offset, b: Offset) = drawLine(color, a, b, stroke, StrokeCap.Round)
        when (kind) {
            "plus" -> {
                val inset = 3.5.dp.toPx()
                line(Offset(size.width / 2f, inset), Offset(size.width / 2f, size.height - inset))
                line(Offset(inset, size.height / 2f), Offset(size.width - inset, size.height / 2f))
            }
            "close" -> {
                val inset = 3.dp.toPx()
                line(Offset(inset, inset), Offset(size.width - inset, size.height - inset))
                line(Offset(size.width - inset, inset), Offset(inset, size.height - inset))
            }
            "back" -> {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(12.5.dp.toPx(), 4.5.dp.toPx())
                    lineTo(6.5.dp.toPx(), size.height / 2f)
                    lineTo(12.5.dp.toPx(), size.height - 4.5.dp.toPx())
                }
                drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            "copy" -> {
                val strokeStyle = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
                val r = 1.6.dp.toPx()
                val d = r * 2f
                val b0 = 1.5.dp.toPx()
                val b1 = 10.5.dp.toPx()
                val backSheet = androidx.compose.ui.graphics.Path().apply {
                    arcTo(Rect(b0, b1 - d, b0 + d, b1), 90f, 90f, true)
                    arcTo(Rect(b0, b0, b0 + d, b0 + d), 180f, 90f, false)
                    arcTo(Rect(b1 - d, b0, b1, b0 + d), 270f, 90f, false)
                }
                drawPath(backSheet, color, style = strokeStyle)
                drawRoundRect(
                    color,
                    Offset(5.5.dp.toPx(), 5.5.dp.toPx()),
                    Size(9.0.dp.toPx(), 9.0.dp.toPx()),
                    CornerRadius(r),
                    style = strokeStyle,
                )
            }
            "check" -> {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(2.8.dp.toPx(), 8.4.dp.toPx())
                    lineTo(6.6.dp.toPx(), 12.2.dp.toPx())
                    lineTo(13.4.dp.toPx(), 4.2.dp.toPx())
                }
                drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            "down" -> {
                val cx = size.width / 2f
                line(Offset(cx, 3.dp.toPx()), Offset(cx, size.height - 3.5.dp.toPx()))
                line(Offset(4.5.dp.toPx(), size.height - 8.5.dp.toPx()), Offset(cx, size.height - 3.5.dp.toPx()))
                line(Offset(size.width - 4.5.dp.toPx(), size.height - 8.5.dp.toPx()), Offset(cx, size.height - 3.5.dp.toPx()))
            }
            "shield" -> {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width / 2f, 1.5.dp.toPx())
                    lineTo(size.width - 3.dp.toPx(), 5.dp.toPx())
                    lineTo(size.width - 4.dp.toPx(), size.height * 0.62f)
                    quadraticTo(size.width * 0.75f, size.height * 0.88f, size.width / 2f, size.height - 1.5.dp.toPx())
                    quadraticTo(size.width * 0.25f, size.height * 0.88f, 4.dp.toPx(), size.height * 0.62f)
                    lineTo(3.dp.toPx(), 5.dp.toPx())
                    close()
                }
                drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, join = StrokeJoin.Round))
            }
            "stop" -> drawRoundRect(
                color,
                Offset(4.5.dp.toPx(), 4.5.dp.toPx()),
                Size(size.width - 9.dp.toPx(), size.height - 9.dp.toPx()),
                CornerRadius(2.5.dp.toPx()),
            )
            else -> {
                val cx = size.width / 2f
                line(Offset(cx, size.height - 3.dp.toPx()), Offset(cx, 3.5.dp.toPx()))
                line(Offset(4.5.dp.toPx(), 9.dp.toPx()), Offset(cx, 3.5.dp.toPx()))
                line(Offset(size.width - 4.5.dp.toPx(), 9.dp.toPx()), Offset(cx, 3.5.dp.toPx()))
            }
        }
    }
}
