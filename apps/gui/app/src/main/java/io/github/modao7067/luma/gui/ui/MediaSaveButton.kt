package io.github.modao7067.luma.gui.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import io.github.modao7067.luma.gui.session.CockpitActions
import io.github.modao7067.luma.gui.session.MediaResource

@Composable
internal fun MediaSaveButton(
    resource: MediaResource?,
    actions: CockpitActions?,
    onFeedback: (String) -> Unit,
) = key(resource) { MediaSaveButtonContent(resource, actions, onFeedback) }

@Composable
private fun MediaSaveButtonContent(resource: MediaResource?, actions: CockpitActions?, onFeedback: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var saving by remember(resource) { mutableStateOf(false) }
    var saved by remember(resource) { mutableStateOf(false) }
    val interactive = LocalSessionInteractive.current
    LiquidGlass(
        Modifier.size(FloatingChromeSize)
            .liquidClickable(enabled = resource != null && actions != null && interactive && !saving && !saved,
                cornerRadius = FloatingChromeRadius, onClick = {
                    if (!saving && !saved && resource != null && actions != null) {
                        saving = true
                        scope.launch {
                            try {
                                actions.saveMedia(resource)
                                saved = true
                                onFeedback("已保存")
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { onFeedback(failure.message?.take(120)?.ifBlank { null } ?: "无法保存") }
                            finally { saving = false }
                        }
                    }
                })
            .semantics {
                contentDescription = "保存原文件"
                stateDescription = when { saving -> "正在保存"; saved -> "已保存"; else -> "" }
            },
        cornerRadius = FloatingChromeRadius,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else CompositionLocalProvider(LocalContentColor provides LocalContentColor.current.copy(alpha = if (resource == null || actions == null) .38f else 1f)) {
                MenuIcon(if (saved) MenuSymbol.CHECK else MenuSymbol.DOWNLOAD, size = 20.dp)
            }
        }
    }
}
