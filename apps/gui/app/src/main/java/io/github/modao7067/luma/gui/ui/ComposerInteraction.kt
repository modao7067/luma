package io.github.modao7067.luma.gui.ui

import kotlin.math.roundToInt

internal enum class ComposerMenuAnchor { Plus, Permission, Model }
internal data class ComposerMenuState(val active: ComposerMenuAnchor? = null) {
    fun toggle(anchor: ComposerMenuAnchor) = copy(active = if (active == anchor) null else anchor)
    fun dismiss(anchor: ComposerMenuAnchor) = if (active == anchor) copy(active = null) else this
}

internal data class ComposerEditorHorizontal(val left: Int, val width: Int, val leadingInset: Int)
internal fun composerEditorHorizontal(width: Int, button: Int, gutter: Int, textInset: Int, fraction: Float): ComposerEditorHorizontal {
    val textFraction = composerTextWidthFraction(fraction)
    fun mix(a: Int, b: Int) = (a + (b - a) * textFraction).roundToInt()
    val leading = mix(gutter, 0)
    return ComposerEditorHorizontal(
        left = mix(button + gutter, textInset) - leading,
        width = (mix(width - 2 * button - 2 * gutter, width - 2 * textInset) + leading).coerceAtLeast(1),
        leadingInset = leading,
    )
}
