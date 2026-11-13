package io.github.modao7067.luma.gui.ui

import android.graphics.Insets
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.WindowManager.LayoutParams
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

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
        if (filterImeInsets) {
            decor.setOnApplyWindowInsetsListener { _, insets -> withoutMenuIme(insets) }
            decor.setWindowInsetsAnimationCallback(object : WindowInsetsAnimation.Callback(WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onProgress(insets: WindowInsets, runningAnimations: MutableList<WindowInsetsAnimation>): WindowInsets = withoutMenuIme(insets)
            })
            decor.requestApplyInsets()
        }
        onDispose {
            if (filterImeInsets) {
                decor.setOnApplyWindowInsetsListener(null)
                decor.setWindowInsetsAnimationCallback(null)
            } else {
                window.setFlags(previousFlags, mask)
                window.setSoftInputMode(previousSoftInput)
                if (previousSoftInput == LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED) {
                    window.attributes = window.attributes.apply { softInputMode = previousSoftInput }
                }
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
