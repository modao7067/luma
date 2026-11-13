package io.github.modao7067.luma.gui.ui

import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus

internal fun processTextActivities(context: Context): List<ResolveInfo> =
    context.packageManager.queryIntentActivities(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain"), 0)
        .filter { result -> result.activityInfo?.let { activity ->
            activity.exported && activity.enabled && activity.applicationInfo.enabled &&
                (activity.permission.isNullOrBlank() || context.checkSelfPermission(activity.permission) == PackageManager.PERMISSION_GRANTED)
        } == true }.distinctBy { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }

internal fun processTextIntent(activity: ResolveInfo, text: String, readOnly: Boolean): Intent =
    Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
        .setComponent(ComponentName(activity.activityInfo.packageName, activity.activityInfo.name))
        .putExtra(Intent.EXTRA_PROCESS_TEXT, text).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, readOnly)

internal class NativeSelectionToolbar(
    private val view: View,
    val readOnly: Boolean,
    private val activate: (NativeSelectionToolbar) -> Unit,
    private val process: (ResolveInfo, (() -> Unit)?) -> Unit,
    private val imagePasteAvailable: () -> Boolean = { false },
    private val pasteImage: () -> Boolean = { false },
    private val queryProcessText: () -> List<ResolveInfo> = { processTextActivities(view.context) },
    private val start: (View, ActionMode.Callback2) -> ActionMode? = { v, callback -> v.startActionMode(callback, ActionMode.TYPE_FLOATING) },
) : TextToolbar {
    private var mode: ActionMode? = null
    private var rect = Rect.Zero
    private var copy: (() -> Unit)? = null
    private var paste: (() -> Unit)? = null
    private var cut: (() -> Unit)? = null
    private var selectAll: (() -> Unit)? = null
    private var autofill: (() -> Unit)? = null
    private var imagePaste = false
    private var services = emptyList<ResolveInfo>()
    private var servicesQueried = false
    private var populatedActions: Int? = null
    private var clipboard: ClipboardManager? = null
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (mode != null) {
            imagePaste = imagePasteAvailable()
            if (populatedActions != availableActions()) mode?.invalidate()
        }
    }
    private var suppressed = false
    private var movingRect = false
    private var anchorVersion = 0L
    private val settleContentRect = Runnable {
        movingRect = false
        val current = mode ?: return@Runnable
        current.invalidateContentRect()
        current.hide(0)
    }
    val keepsEditorFocus get() = !readOnly || cut != null || paste != null || autofill != null
    override val status get() = if (mode == null) TextToolbarStatus.Hidden else TextToolbarStatus.Shown
    fun allowNextGesture() { suppressed = false }
    fun dismissOutside() { suppressed = true; hide() }
    fun onScrollMovement() {
        val current = mode ?: return
        if (!view.isAttachedToWindow) return
        if (!movingRect) {
            movingRect = true
            current.hide(ActionMode.DEFAULT_HIDE_DURATION.toLong())
        }
        view.removeCallbacks(settleContentRect)
        view.postDelayed(settleContentRect, CONTENT_RECT_SETTLE_MS)
    }
    fun refreshSelectionAnchor(update: () -> Unit) {
        val before = anchorVersion
        update()
        if (anchorVersion == before) hide()
    }
    override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?, onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) =
        showMenu(rect, onCopyRequested, onPasteRequested, onCutRequested, onSelectAllRequested, null)
    override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?, onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?, onAutofillRequested: (() -> Unit)?) {
        if (suppressed) return
        anchorVersion++
        val previousRect = this.rect
        this.rect = rect; copy = onCopyRequested; paste = onPasteRequested; cut = onCutRequested
        selectAll = onSelectAllRequested; autofill = onAutofillRequested
        val opening = mode == null
        if (opening) imagePaste = imagePasteAvailable()
        if (copy != null && !servicesQueried) {
            services = queryProcessText()
            servicesQueried = true
        }
        activate(this)
        if (opening) {
            movingRect = false
            view.removeCallbacks(settleContentRect)
            mode = start(view, callback)
            if (mode == null) releaseMenu()
            if (mode != null && !readOnly) {
                clipboard = view.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard?.addPrimaryClipChangedListener(clipboardListener)
            }
        } else {
            if (populatedActions != availableActions()) mode?.invalidate()
            if (!sameContentRect(previousRect, rect)) {
                if (view.isAttachedToWindow) onScrollMovement()
                else mode?.invalidateContentRect()
            }
        }
    }
    override fun hide() {
        val previous = mode
        mode = null
        releaseMenu()
        previous?.finish()
    }
    private fun releaseMenu() {
        view.removeCallbacks(settleContentRect)
        movingRect = false
        clipboard?.removePrimaryClipChangedListener(clipboardListener)
        clipboard = null
        services = emptyList()
        servicesQueried = false
        populatedActions = null
    }
    private fun availableActions(): Int =
        (if (copy != null) 1 else 0) or (if (paste != null || imagePaste) 2 else 0) or
            (if (cut != null) 4 else 0) or (if (selectAll != null) 8 else 0) or (if (autofill != null) 16 else 0)
    internal val callback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean { populate(menu); return true }
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            imagePaste = imagePasteAvailable()
            if (populatedActions == availableActions()) return false
            populate(menu)
            return true
        }
        override fun onGetContentRect(mode: ActionMode, view: View, outRect: android.graphics.Rect) {
            outRect.set(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
        }
        override fun onDestroyActionMode(mode: ActionMode) {
            if (this@NativeSelectionToolbar.mode === mode) {
                this@NativeSelectionToolbar.mode = null
                releaseMenu()
            }
        }
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            when (item.itemId) {
                android.R.id.copy -> copy?.invoke()
                android.R.id.paste -> if (!pasteImage()) paste?.invoke()
                android.R.id.cut -> cut?.invoke()
                android.R.id.selectAll -> selectAll?.invoke()
                android.R.id.autofill -> autofill?.invoke()
                else -> {
                    val service = services.getOrNull(item.itemId - PROCESS_BASE) ?: return false
                    process(service, copy)
                }
            }
            hide(); return true
        }
    }
    private fun populate(menu: Menu) {
        populatedActions = availableActions()
        menu.removeGroup(CORE_GROUP); menu.removeGroup(PROCESS_GROUP)
        fun action(id: Int, title: Int, enabled: Boolean, order: Int) {
            if (enabled) menu.add(CORE_GROUP, id, order, title).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
        action(android.R.id.copy, android.R.string.copy, copy != null, 0)
        action(android.R.id.paste, android.R.string.paste, paste != null || imagePaste, 1)
        action(android.R.id.cut, android.R.string.cut, cut != null, 2)
        action(android.R.id.selectAll, android.R.string.selectAll, selectAll != null, 3)
        action(android.R.id.autofill, android.R.string.autofill, autofill != null, 4)
        (if (copy != null) services else emptyList()).forEachIndexed { index, service ->
            val component = ComponentName(service.activityInfo.packageName, service.activityInfo.name)
            if ((0 until menu.size()).any { menu.getItem(it).intent?.let { intent -> intent.action == Intent.ACTION_PROCESS_TEXT && intent.component == component } == true }) return@forEachIndexed
            menu.add(PROCESS_GROUP, PROCESS_BASE + index, 100 + index, service.loadLabel(view.context.packageManager))
                .setIcon(service.loadIcon(view.context.packageManager))
                .setIntent(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").setComponent(component))
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
    }
    private fun sameContentRect(first: Rect, second: Rect): Boolean =
        first.left.toInt() == second.left.toInt() && first.top.toInt() == second.top.toInt() &&
            first.right.toInt() == second.right.toInt() && first.bottom.toInt() == second.bottom.toInt()
    companion object {
        internal const val PROCESS_BASE = 0x4c550000
        private const val CORE_GROUP = 0x4c551000
        private const val PROCESS_GROUP = 0x4c552000
        private const val CONTENT_RECT_SETTLE_MS = 120L
    }
}
