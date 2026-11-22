package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.i18n.tr

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

internal class ComposerEditorState(initial: String) {
    var value by mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
        private set
    private var external = initial
    private val pendingLocalTexts = ArrayDeque<String>()
    var onTextChange: (String) -> Unit = {}
    fun sync(text: String) {
        if (external == text) return
        val echoIndex = pendingLocalTexts.indexOf(text)
        if (echoIndex >= 0) {
            repeat(echoIndex + 1) { pendingLocalTexts.removeFirst() }
            external = text
            return
        }
        external = text
        pendingLocalTexts.clear()
        if (value.text != text) value = TextFieldValue(text, TextRange(text.length))
    }
    fun edit(next: TextFieldValue) {
        val changed = value.text != next.text
        value = next
        if (changed) {
            if (pendingLocalTexts.size >= MAX_PENDING_LOCAL_TEXTS) {
                pendingLocalTexts.removeFirst()
            }
            pendingLocalTexts.addLast(next.text)
            onTextChange(next.text)
        }
    }
    companion object {
        private const val MAX_PENDING_LOCAL_TEXTS = 64
    }
}
internal fun clipboardHasImage(context: Context): Boolean = runCatching {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClipDescription?.hasMimeType("image/*") == true
}.getOrDefault(false)

internal fun pasteClipboardImages(context: Context, addImage: (String) -> Unit, error: (String) -> Unit): Boolean {
    return try {
        val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip ?: return false
        val declaredImage = clip.description.hasMimeType("image/*")
        val images = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
            .filter { context.contentResolver.getType(it)?.startsWith("image/") == true }.distinct()
        if (images.isEmpty()) {
            if (declaredImage) error(tr("无法读取剪贴板图片", "Unable to read clipboard image"))
            declaredImage
        } else { images.forEach { addImage(it.toString()) }; true }
    } catch (_: Exception) { error(tr("无法读取剪贴板图片", "Unable to read clipboard image")); true }
}
