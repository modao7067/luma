@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "ExperimentalFoundationApi")

package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.i18n.tr

import android.app.Activity
import android.content.Intent
import android.view.ActionMode
import android.view.View
import android.view.autofill.AutofillManager as SystemAutofillManager
import android.view.autofill.AutofillValue
import android.widget.Toast
import androidx.compose.ui.autofill.AndroidAutofillManager
import androidx.compose.ui.autofill.PlatformAutofillManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.collection.MutableLongLongMap
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.ContextMenuArea
import androidx.compose.foundation.text.Handle
import androidx.compose.foundation.text.detectDownAndDragGesturesWithObserver
import androidx.compose.foundation.text.rememberClipboardEventsHandler
import androidx.compose.foundation.text.selection.LocalSelectionRegistrar
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.Selectable
import androidx.compose.foundation.text.selection.Selection
import androidx.compose.foundation.text.selection.SelectionHandle
import androidx.compose.foundation.text.selection.SelectionManager
import androidx.compose.foundation.text.selection.SelectionRegistrar
import androidx.compose.foundation.text.selection.SelectionRegistrarImpl
import androidx.compose.foundation.text.selection.SimpleLayout
import androidx.compose.foundation.text.selection.rememberPlatformSelectionBehaviors
import androidx.compose.foundation.text.selection.SelectedTextType
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.packInts
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

internal val LocalNativeTextActionMode = staticCompositionLocalOf<(View, ActionMode.Callback2) -> ActionMode?> {
    { view, callback -> view.startActionMode(callback, ActionMode.TYPE_FLOATING) }
}
internal val LocalProcessTextCatalog = staticCompositionLocalOf<ProcessTextCatalog?> { null }
internal val LocalProcessTextLauncher = staticCompositionLocalOf<((Intent, (Intent?) -> Unit) -> Unit)?> { null }
internal val LocalConversationScrolling = compositionLocalOf { false }

@OptIn(ExperimentalFoundationApi::class)
internal object LumaSelectionConfiguration {
    init { ComposeFoundationFlags.isNewContextMenuEnabled = false }
    fun initialize() = Unit
}

internal class SelectionClipboard(private val delegate: Clipboard) : Clipboard by delegate {
    private var selected: ((String) -> Unit)? = null
    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        val capture = selected
        if (capture != null) clipEntry?.clipData?.getItemAt(0)?.text?.toString()?.let(capture)
        else delegate.setClipEntry(clipEntry)
    }
    fun selectedText(copy: (() -> Unit)?): String? {
        var text: String? = null
        selected = { text = it }
        try { copy?.invoke() } finally { selected = null }
        return text
    }
}
private val LocalReadOnlySelectionClipboard = staticCompositionLocalOf<SelectionClipboard?> { null }
internal class EditorProcessTarget(val read: () -> TextFieldValue, val write: (TextFieldValue) -> Unit, val enabled: () -> Boolean) {
    var alive = true
    fun apply(snapshot: TextFieldValue, result: CharSequence?) {
        val current = read()
        if (!alive || !enabled() || result == null || current.text != snapshot.text || current.selection != snapshot.selection) return
        val start = snapshot.selection.min; val end = snapshot.selection.max
        val replacement = result.toString()
        write(TextFieldValue(snapshot.text.replaceRange(start, end, replacement), TextRange(start + replacement.length)))
    }
}
internal object DisabledPlatformAutofillManager : PlatformAutofillManager {
    override fun notifyViewEntered(view: View, semanticsId: Int, bounds: android.graphics.Rect) = Unit
    override fun notifyViewExited(view: View, semanticsId: Int) = Unit
    override fun notifyValueChanged(view: View, semanticsId: Int, autofillValue: AutofillValue) = Unit
    override fun notifyViewVisibilityChanged(view: View, semanticsId: Int, isVisible: Boolean) = Unit
    override fun commit() = Unit
    override fun cancel() = Unit
    override fun requestAutofill(view: View, semanticsId: Int, bounds: android.graphics.Rect) = Unit
}

internal class TextSelectionWindow(
    val view: View,
    val clipboard: SelectionClipboard,
    val start: (View, ActionMode.Callback2) -> ActionMode?,
    val processTextCatalog: ProcessTextCatalog,
    val launch: (Intent, (Intent?) -> Unit) -> Unit,
) {
    var active: NativeSelectionToolbar? = null
    private val toolbars = mutableSetOf<NativeSelectionToolbar>()
    fun register(toolbar: NativeSelectionToolbar) { toolbars += toolbar }
    fun unregister(toolbar: NativeSelectionToolbar) { toolbars -= toolbar; if (active === toolbar) active = null; toolbar.hide() }
    fun beginRootGesture() {
        toolbars.forEach { toolbar ->
            if (!toolbar.scopedToEditor && toolbar.status == TextToolbarStatus.Hidden) toolbar.allowNextGesture()
        }
    }
    fun notifyScrollMovement() { active?.onScrollMovement() }
    fun activate(toolbar: NativeSelectionToolbar) { if (active !== toolbar) active?.hide(); active = toolbar }
}
private val LocalSelectionWindow = staticCompositionLocalOf<TextSelectionWindow?> { null }

private class RelativePositionSelectionRegistrar(
    val delegate: SelectionRegistrarImpl,
) : SelectionRegistrar by delegate {
    var containerCoordinates: LayoutCoordinates? = null
    private val lastRelativePositions = MutableLongLongMap()
    private val lastSizes = MutableLongLongMap()

    override fun unsubscribe(selectable: Selectable) {
        val id = selectable.selectableId
        lastRelativePositions.remove(id)
        lastSizes.remove(id)
        delegate.unsubscribe(selectable)
    }

    override fun notifySelectableChange(selectableId: Long) {
        lastRelativePositions.remove(selectableId)
        lastSizes.remove(selectableId)
        delegate.notifySelectableChange(selectableId)
    }

    override fun notifyPositionChange(selectableId: Long) {
        val container = containerCoordinates
        val selectable = delegate.selectableMap[selectableId]
        val coords = selectable?.getLayoutCoordinates()
        if (container != null && container.isAttached && coords != null && coords.isAttached) {
            val rel = container.localPositionOf(coords, Offset.Zero)
            val packedPos = rel.packedValue
            val packedSize = packInts(coords.size.width, coords.size.height)
            if (lastRelativePositions.containsKey(selectableId) &&
                lastRelativePositions[selectableId] == packedPos &&
                lastSizes[selectableId] == packedSize
            ) {
                return
            }
            lastRelativePositions.put(selectableId, packedPos)
            lastSizes.put(selectableId, packedSize)
        }
        if (delegate.subselections.containsKey(selectableId)) {
            delegate.notifyPositionChange(selectableId)
        } else {
            delegate.sorted = false
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LumaSelectionContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    LumaSelectionConfiguration.initialize()
    var selection by remember { mutableStateOf<Selection?>(null) }
    val registrarImpl = rememberSaveable(saver = SelectionRegistrarImpl.Saver) { SelectionRegistrarImpl() }
    val manager = remember(registrarImpl) { SelectionManager(registrarImpl) }
    val filteredRegistrar = remember(registrarImpl) { RelativePositionSelectionRegistrar(registrarImpl) }
    val clipboard = LocalReadOnlySelectionClipboard.current ?: LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    val textToolbar = LocalTextToolbar.current
    manager.hapticFeedBack = LocalHapticFeedback.current
    manager.onCopyHandler = remember(coroutineScope, clipboard) {
        { textToCopy: AnnotatedString ->
            coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                clipboard.setClipEntry(
                    ClipEntry(android.content.ClipData.newPlainText("plain text", textToCopy.text)),
                )
            }
        }
    }
    manager.textToolbar = textToolbar
    manager.onSelectionChange = { selection = it }
    manager.selection = selection
    if (ComposeFoundationFlags.isSmartSelectionEnabled) {
        manager.platformSelectionBehaviors =
            rememberPlatformSelectionBehaviors(SelectedTextType.StaticText, null)
    }
    manager.coroutineScope = coroutineScope
    manager.shouldIgnoreCopyKeyEvent = rememberClipboardEventsHandler(
        onCopy = { manager.getSelectedText() },
        isEnabled = manager.isNonEmptySelection(),
    )

    val listScrolling = LocalConversationScrolling.current
    val nativeToolbar = textToolbar as? NativeSelectionToolbar
    SideEffect {
        if (listScrolling && selection != null && manager.draggingHandle == null) {
            nativeToolbar?.onScrollMovement()
        }
    }

    SimpleLayout(
        modifier = modifier
            .then(manager.modifier)
            .onGloballyPositioned { coords ->
                filteredRegistrar.containerCoordinates = coords
                if (nativeToolbar != null && manager.hasFocus && selection != null && manager.showToolbar && manager.isInTouchMode) {
                    nativeToolbar.refreshSelectionAnchor {
                        manager.showToolbar = true
                    }
                }
            },
    ) {
        ContextMenuArea(manager) {
            CompositionLocalProvider(LocalSelectionRegistrar provides filteredRegistrar) {
                content()
                val activeSelection = selection
                val showHandles = manager.isInTouchMode &&
                    manager.hasFocus &&
                    !manager.isTriviallyCollapsedSelection() &&
                    activeSelection != null &&
                    (!listScrolling || manager.draggingHandle != null)
                if (showHandles && activeSelection != null) {
                    val startVisible = manager.startHandlePosition != null || manager.draggingHandle == Handle.SelectionStart
                    if (startVisible) {
                        val startObserver = remember(manager) { manager.handleDragObserver(true) }
                        val startPositionProvider = remember(manager) {
                            { manager.startHandlePosition ?: Offset.Unspecified }
                        }
                        SelectionHandle(
                            offsetProvider = startPositionProvider,
                            isStartHandle = true,
                            direction = activeSelection.start.direction,
                            handlesCrossed = activeSelection.handlesCrossed,
                            lineHeight = manager.startHandleLineHeight,
                            modifier = Modifier.pointerInput(startObserver) {
                                detectDownAndDragGesturesWithObserver(startObserver)
                            },
                        )
                    }
                    val endVisible = manager.endHandlePosition != null || manager.draggingHandle == Handle.SelectionEnd
                    if (endVisible) {
                        val endObserver = remember(manager) { manager.handleDragObserver(false) }
                        val endPositionProvider = remember(manager) {
                            { manager.endHandlePosition ?: Offset.Unspecified }
                        }
                        SelectionHandle(
                            offsetProvider = endPositionProvider,
                            isStartHandle = false,
                            direction = activeSelection.end.direction,
                            handlesCrossed = activeSelection.handlesCrossed,
                            lineHeight = manager.endHandleLineHeight,
                            modifier = Modifier.pointerInput(endObserver) {
                                detectDownAndDragGesturesWithObserver(endObserver)
                            },
                        )
                    }
                }
            }
        }
    }
    DisposableEffect(manager) {
        onDispose {
            manager.onRelease()
            manager.hasFocus = false
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TextSelectionHost(content: @Composable () -> Unit) {
    LumaSelectionConfiguration.initialize()
    val colors = MaterialTheme.colorScheme
    val selectionColors = remember(colors) { nativeTextSelectionColors(colors) }
    CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) { NativeTextSelectionWindow(content) }
}

@Composable
private fun NativeTextSelectionWindow(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (LocalInspectionMode.current || LocalSelectionWindow.current?.view === view) { content(); return }
    val composeAutofill = LocalAutofillManager.current
    SideEffect {
        (composeAutofill as? AndroidAutofillManager)?.platformAutofillManager = DisabledPlatformAutofillManager
    }
    DisposableEffect(view) {
        runCatching { view.context.getSystemService(SystemAutofillManager::class.java)?.cancel() }
        onDispose {}
    }
    val nativeClipboard = LocalClipboard.current
    val clipboard = remember(nativeClipboard) { SelectionClipboard(nativeClipboard) }
    val focus = LocalFocusManager.current
    val overrideLaunch = LocalProcessTextLauncher.current
    var resultHandler by remember { mutableStateOf<((Intent?) -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = resultHandler; resultHandler = null
        callback?.invoke(result.data.takeIf { result.resultCode == Activity.RESULT_OK })
    }
    val launch: (Intent, (Intent?) -> Unit) -> Unit = { intent, result ->
        if (overrideLaunch != null) overrideLaunch(intent, result) else {
            resultHandler = result
            try { launcher.launch(intent) }
            catch (_: Exception) { resultHandler = null; Toast.makeText(view.context, tr("无法打开", "Unable to open"), Toast.LENGTH_SHORT).show() }
        }
    }
    val latestLaunch by rememberUpdatedState(launch)
    val start = LocalNativeTextActionMode.current
    val providedCatalog = LocalProcessTextCatalog.current
    val processTextCatalog = remember(providedCatalog, view.context) {
        providedCatalog ?: ProcessTextCatalog.forContext(view.context)
    }
    LaunchedEffect(processTextCatalog) { processTextCatalog.prewarm() }
    val window = remember(view, clipboard, start, processTextCatalog) {
        TextSelectionWindow(view, clipboard, start, processTextCatalog) { intent, callback -> latestLaunch(intent, callback) }
    }
    CompositionLocalProvider(
        LocalSelectionWindow provides window,
        LocalReadOnlySelectionClipboard provides clipboard,
        LocalAutofill provides null,
        LocalAutofillManager provides null,
    ) {
        SelectionToolbarProvider {
            Box(Modifier.pointerInput(window) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                    val shown = window.active
                    val anchorAtDown = shown?.anchorRevision ?: 0L
                    window.beginRootGesture()
                    var tap = true
                    var notifiedScroll = false
                    var finished = false
                    while (!finished) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        if (event.changes.size != 1) tap = false
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val movedBeyondSlop = (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                        if (movedBeyondSlop) {
                            if (!notifiedScroll) {
                                notifiedScroll = true
                                if (shown != null && window.active === shown && !shown.hasSelection) {
                                    shown.dismissOutside()
                                } else {
                                    window.notifyScrollMovement()
                                }
                            }
                            tap = false
                        }
                        if (change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) tap = false
                        if (change.changedToUpIgnoreConsumed()) {
                            if (tap && shown != null && window.active === shown && shown.status == TextToolbarStatus.Shown && shown.anchorRevision == anchorAtDown) {
                                val clearSelection = !shown.keepsEditorFocus && !change.isConsumed
                                shown.dismissOutside()
                                if (clearSelection) focus.clearFocus()
                            }
                            finished = true
                        }
                    }
                }
            }, propagateMinConstraints = true) { content() }
        }
    }
}

@Composable
internal fun SelectionToolbarProvider(
    editor: EditorProcessTarget? = null,
    imagePasteAvailable: () -> Boolean = { false },
    pasteImage: () -> Boolean = { false },
    content: @Composable () -> Unit,
) {
    val window = LocalSelectionWindow.current
    if (window == null) { content(); return }
    val available by rememberUpdatedState(imagePasteAvailable)
    val paste by rememberUpdatedState(pasteImage)
    val toolbar = remember(window, editor) {
        NativeSelectionToolbar(window.view, readOnly = editor == null, window::activate,
            process = { activity, copy ->
                val snapshot = editor?.read()
                val text = if (snapshot != null) snapshot.text.substring(snapshot.selection.min, snapshot.selection.max) else window.clipboard.selectedText(copy)
                if (!text.isNullOrEmpty()) {
                    val intent = processTextIntent(activity, text, editor == null)
                    window.launch(intent) { result -> if (snapshot != null) editor?.apply(snapshot, runCatching { result?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT) }.getOrNull()) }
                }
            }, imagePasteAvailable = { available() }, pasteImage = { paste() }, start = window.start, catalog = window.processTextCatalog)
    }
    DisposableEffect(window, toolbar) { window.register(toolbar); onDispose { window.unregister(toolbar) } }
    if (editor != null) {
        Box(
            Modifier.pointerInput(toolbar) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (toolbar.status == TextToolbarStatus.Hidden) toolbar.allowNextGesture()
                }
            },
            propagateMinConstraints = true,
        ) {
            CompositionLocalProvider(LocalTextToolbar provides toolbar, content = content)
        }
    } else {
        CompositionLocalProvider(LocalTextToolbar provides toolbar, content = content)
    }
}
