package io.github.modao7067.luma.gui.ui

import android.graphics.Color
import android.view.WindowManager
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun GuiPageDialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    GuiDialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val view = LocalView.current
        val window = (view.parent as? DialogWindowProvider)?.window
        val light = MaterialTheme.colorScheme.background.luminance() > 0.5f
        val scope = rememberCoroutineScope()
        val backProgress = remember { Animatable(0f) }
        var swipeEdge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
        val latestDismiss by rememberUpdatedState(onDismissRequest)
        PredictiveBackHandler { progress ->
            try {
                progress.collect { event ->
                    swipeEdge = event.swipeEdge
                    backProgress.snapTo(event.progress.coerceIn(0f, 1f))
                }
                latestDismiss()
            } catch (cancelled: CancellationException) {
                scope.launch { backProgress.animateTo(0f, spatialSpring()) }
                throw cancelled
            }
        }
        SideEffect {
            window?.let {
                @Suppress("DEPRECATION")
                it.statusBarColor = Color.TRANSPARENT
                @Suppress("DEPRECATION")
                it.navigationBarColor = Color.TRANSPARENT
                it.isStatusBarContrastEnforced = false
                it.isNavigationBarContrastEnforced = false
                it.attributes = it.attributes.apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
                WindowCompat.getInsetsController(it, view).apply {
                    isAppearanceLightStatusBars = light
                    isAppearanceLightNavigationBars = light
                }
            }
        }
        val currentProgress = backProgress.value
        val corner = (currentProgress * 28f).dp
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = currentProgress
                    if (p > 0f) {
                        val scale = 1f - p * 0.08f
                        scaleX = scale
                        scaleY = scale
                        val dir = if (swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
                        translationX = dir * p * 18.dp.toPx()
                        transformOrigin = TransformOrigin(if (swipeEdge == BackEventCompat.EDGE_RIGHT) 0.15f else 0.85f, 0.5f)
                    }
                }
                .then(if (currentProgress > 0f) Modifier.clip(LumaCardShape(corner)) else Modifier),
        ) {
            content()
        }
    }
}
