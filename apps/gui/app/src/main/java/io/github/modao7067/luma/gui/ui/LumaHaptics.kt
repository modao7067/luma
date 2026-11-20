package io.github.modao7067.luma.gui.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView

internal class LumaHapticFeedback(private val view: View) : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        view.performHapticFeedback(resolveConstant(hapticFeedbackType))
    }

    internal companion object {
        internal fun resolveConstant(hapticFeedbackType: HapticFeedbackType): Int = when (hapticFeedbackType) {
            HapticFeedbackType.LongPress -> HapticFeedbackConstants.LONG_PRESS
            HapticFeedbackType.VirtualKey,
            HapticFeedbackType.KeyboardTap,
            HapticFeedbackType.ToggleOn,
            HapticFeedbackType.ToggleOff -> HapticFeedbackConstants.VIRTUAL_KEY
            HapticFeedbackType.ContextClick -> if (Build.VERSION.SDK_INT >= 23) HapticFeedbackConstants.CONTEXT_CLICK else HapticFeedbackConstants.CLOCK_TICK
            HapticFeedbackType.Confirm -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
            HapticFeedbackType.Reject -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
            HapticFeedbackType.TextHandleMove,
            HapticFeedbackType.SegmentTick,
            HapticFeedbackType.SegmentFrequentTick,
            HapticFeedbackType.GestureThresholdActivate,
            HapticFeedbackType.GestureEnd -> HapticFeedbackConstants.CLOCK_TICK
            else -> HapticFeedbackConstants.CLOCK_TICK
        }
    }
}

internal fun HapticFeedback.tick() = performHapticFeedback(HapticFeedbackType.TextHandleMove)

internal fun HapticFeedback.tap() = performHapticFeedback(HapticFeedbackType.VirtualKey)

internal fun HapticFeedback.longPress() = performHapticFeedback(HapticFeedbackType.LongPress)

@Composable
internal fun ProvideLumaHaptics(content: @Composable () -> Unit) {
    val current = LocalHapticFeedback.current
    if (current is LumaHapticFeedback) {
        content()
        return
    }
    val view = LocalView.current
    val luma = remember(view) { LumaHapticFeedback(view) }
    CompositionLocalProvider(LocalHapticFeedback provides luma, content = content)
}
