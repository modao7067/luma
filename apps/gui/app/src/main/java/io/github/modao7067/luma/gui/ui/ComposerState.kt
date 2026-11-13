package io.github.modao7067.luma.gui.ui

internal data class ComposerFocusState(val focused: Boolean = false, val imeVisible: Boolean = false, val imeDismissed: Boolean = false) {
    val capturesBack: Boolean get() = (focused && !imeDismissed) || imeVisible
    fun onFocus(focused: Boolean) = copy(focused = focused)
    fun onIme(visible: Boolean) = copy(imeVisible = visible,
        imeDismissed = if (visible) false else imeDismissed || imeVisible)
    fun collapse() = copy(imeVisible = false, imeDismissed = true)
    fun presentation(draft: String, hasAttachments: Boolean) = ComposerPresentation(
        expanded = (focused && !imeDismissed) || imeVisible || draft.isNotEmpty() || hasAttachments,
        draft = draft,
    )
}
internal data class ComposerPresentation(val expanded: Boolean, val draft: String)
internal enum class ComposerPrimaryAction { SEND, STOP, SENDING }
internal fun composerPrimaryAction(active: Boolean, hasInput: Boolean, sending: Boolean = false) =
    if (sending) ComposerPrimaryAction.SENDING else if (active && !hasInput) ComposerPrimaryAction.STOP else ComposerPrimaryAction.SEND
internal fun composerCanSend(connected: Boolean, loading: Boolean, sending: Boolean, uncertain: Boolean, hasInput: Boolean, attachmentsBusy: Boolean, imagesSupported: Boolean, hasAttachments: Boolean) =
    connected && !loading && !sending && !uncertain && hasInput && !attachmentsBusy && (imagesSupported || !hasAttachments)
