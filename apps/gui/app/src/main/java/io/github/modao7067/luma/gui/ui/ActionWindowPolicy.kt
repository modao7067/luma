package io.github.modao7067.luma.gui.ui

import android.graphics.Insets
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.WindowManager.LayoutParams
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import java.util.WeakHashMap

private class DecorImeFilter {
    var activeCount: Int = 0
}

private val decorImeFilters = WeakHashMap<View, DecorImeFilter>()

private fun acquireDecorImeFilter(decor: View): DecorImeFilter {
    val existing = decorImeFilters[decor]
    if (existing != null) {
        existing.activeCount++
        return existing
    }
    val filter = DecorImeFilter().apply { activeCount = 1 }
    decorImeFilters[decor] = filter
    decor.setOnApplyWindowInsetsListener { view, insets ->
        val effective = if (filter.activeCount > 0) withoutMenuIme(insets) else insets
        view.onApplyWindowInsets(effective)
    }
    decor.setWindowInsetsAnimationCallback(
        object : WindowInsetsAnimation.Callback(WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
            override fun onProgress(insets: WindowInsets, runningAnimations: MutableList<WindowInsetsAnimation>): WindowInsets =
                if (filter.activeCount > 0) withoutMenuIme(insets) else insets
        },
    )
    return filter
}

private fun releaseDecorImeFilter(filter: DecorImeFilter) {
    filter.activeCount = (filter.activeCount - 1).coerceAtLeast(0)
}

@Composable
internal fun ActionWindowPolicy(window: Window?, enabled: Boolean, filterImeInsets: Boolean = false) {
    if (window == null || !enabled) return
    DisposableEffect(window, filterImeInsets) {
        val mask = LayoutParams.FLAG_ALT_FOCUSABLE_IM or LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCH_MODAL
        val previousFlags = window.attributes.flags
        val previousSoftInput = window.attributes.softInputMode
        window.setFlags(LayoutParams.FLAG_ALT_FOCUSABLE_IM, mask)
        window.setSoftInputMode(LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        val decor = window.decorView
        val filter = if (filterImeInsets) {
            acquireDecorImeFilter(decor).also { decor.requestApplyInsets() }
        } else {
            null
        }
        onDispose {
            window.setFlags(previousFlags, mask)
            window.setSoftInputMode(previousSoftInput)
            if (previousSoftInput == LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED) {
                window.attributes = window.attributes.apply { softInputMode = previousSoftInput }
            }
            if (filter != null) {
                releaseDecorImeFilter(filter)
                decor.requestApplyInsets()
            }
        }
    }
}

internal fun withoutMenuIme(insets: WindowInsets): WindowInsets = WindowInsets.Builder(insets)
    .setInsets(WindowInsets.Type.ime(), Insets.NONE)
    .setVisible(WindowInsets.Type.ime(), false)
    .build()

internal fun Context.actionActivityWindow(): Window? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current.window
        current = current.baseContext
    }
    return (current as? Activity)?.window
}
