package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.i18n.tr

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import io.github.modao7067.luma.gui.session.*

@Composable
internal fun BlankConversation(
    state: CockpitState, actions: CockpitActions,
    currentSessionId: () -> String?, currentBlankKey: () -> String,
    onPickImages: () -> Unit, onDrawer: () -> Unit, onNew: () -> Unit, onSettings: () -> Unit,
    onUsage: () -> Unit = {},
    onTakePhoto: (() -> Unit)? = null, takePhotoEnabled: Boolean = true,
) {
    val blank = state.blank
    val sessionProvider = rememberUpdatedState(currentSessionId)
    val blankProvider = rememberUpdatedState(currentBlankKey)
    fun ownsContext() = sessionProvider.value() == null && blankProvider.value() == blank.key
    val interactive = ownsContext()
    val guarded = remember(blank.key, actions) { CurrentSessionActions(actions) { ownsContext() } }
    val view = SessionView(SessionSummary("blank-composer:${blank.key}", "", actions.environment, blank.cwd, cli = blank.cli),
        draft = blank.draft, attachments = blank.attachments, skills = blank.skills)
    val wide = WindowInsets.ime.getBottom(LocalDensity.current) > 0 || blank.draft.isNotEmpty() || blank.attachments.isNotEmpty() || blank.skills.isNotEmpty()
    val width by animateFloatAsState(if (wide) 1f else 0.88f, spatialSpring(), label = "blank-composer-width")
    var retainedError by remember { mutableStateOf(state.error.orEmpty()) }
    SideEffect { state.error?.let { retainedError = it } }
    CompositionLocalProvider(LocalSessionInteractive provides interactive) {
        Box(Modifier.fillMaxSize().then(if (interactive) Modifier else Modifier.clearAndSetSemantics {}
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } } })) {
            LiquidScaffold(wrapTop = false, wrapBottom = false,
                topBar = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ConversationHeader(null, guarded,
                            onDrawer = { if (ownsContext()) onDrawer() },
                            onNew = { if (ownsContext()) onNew() },
                            onSettings = { if (ownsContext()) onSettings() },
                            onUsage = { if (ownsContext()) onUsage() },
                            blankCli = blank.cli, hasError = state.error != null)
                    }
                },
                bottomBar = {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        ComposerGlass(Modifier.fillMaxWidth(width)) {
                            CompactComposer(state, view, guarded,
                                onPickImages = { if (ownsContext()) onPickImages() }, onModels = {}, canStartSession = true,
                                onTakePhoto = onTakePhoto?.let { action -> { if (ownsContext()) action() } }, takePhotoEnabled = takePhotoEnabled)
                        }
                    }
                }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding).padding(horizontal = 28.dp), contentAlignment = Alignment.CenterStart) {
                    Text(tr("想做点什么？", "What are we building?"), style = editorialGreetingStyle())
                }
            }
        }
    }
}
