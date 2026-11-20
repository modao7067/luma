package io.github.modao7067.luma.gui.ui

import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.VectorDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class ProcessTextAction(
    val resolveInfo: ResolveInfo,
    val component: ComponentName,
    val label: CharSequence,
    val iconBitmap: Bitmap?,
    val fallbackIcon: Drawable? = null,
) {
    fun createIcon(resources: Resources): Drawable? =
        iconBitmap?.let { BitmapDrawable(resources, it) } ?: fallbackIcon
}

internal fun processTextIconSizePx(resources: Resources): Int =
    (24f * resources.displayMetrics.density).roundToInt().coerceIn(32, 128)

internal fun rasterizeProcessTextIcon(
    drawable: Drawable?,
    resources: Resources,
    sizePx: Int,
): Pair<Bitmap?, Drawable?> {
    if (drawable == null) return null to null
    if (drawable is BitmapDrawable) {
        val bmp = drawable.bitmap
        if (bmp != null && !bmp.isRecycled && bmp.width <= sizePx && bmp.height <= sizePx) {
            return bmp to null
        }
    }
    if (drawable is ColorDrawable ||
        (drawable.intrinsicWidth <= 0 &&
            drawable.intrinsicHeight <= 0 &&
            drawable !is AdaptiveIconDrawable &&
            drawable !is VectorDrawable &&
            drawable !is LayerDrawable)
    ) {
        return null to drawable
    }
    val target = sizePx.coerceIn(32, 128)
    return runCatching {
        val copy = drawable.constantState?.newDrawable(resources)?.mutate() ?: drawable.mutate()
        val iw = copy.intrinsicWidth
        val ih = copy.intrinsicHeight
        val (drawW, drawH) = if (iw > 0 && ih > 0) {
            val scale = min(target.toFloat() / iw.toFloat(), target.toFloat() / ih.toFloat())
            max(1, (iw * scale).roundToInt()).coerceAtMost(target) to
                max(1, (ih * scale).roundToInt()).coerceAtMost(target)
        } else {
            target to target
        }
        val left = (target - drawW) / 2
        val top = (target - drawH) / 2
        val bitmap = Bitmap.createBitmap(target, target, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        copy.setBounds(left, top, left + drawW, top + drawH)
        copy.draw(canvas)
        bitmap to null
    }.getOrElse { null to drawable }
}

internal fun loadProcessTextActions(
    context: Context,
    query: () -> List<ResolveInfo> = { processTextActivities(context) },
): List<ProcessTextAction> {
    val pm = context.packageManager
    val resources = context.resources
    val iconSize = processTextIconSizePx(resources)
    return query().map { service ->
        val component = ComponentName(service.activityInfo.packageName, service.activityInfo.name)
        val label = service.loadLabel(pm)
        val (bitmap, fallback) = rasterizeProcessTextIcon(service.loadIcon(pm), resources, iconSize)
        ProcessTextAction(service, component, label, bitmap, fallback)
    }
}

internal class ProcessTextCatalog(
    context: Context,
    private val executor: Executor = defaultExecutor,
    private val query: (() -> List<ResolveInfo>)? = null,
    registerPackageReceiver: Boolean = true,
) {
    private val appContextRef = WeakReference(context.applicationContext ?: context)
    private val lock = Any()
    private var packageReceiver: BroadcastReceiver? = null
    private var generation = 0L
    private var cachedActions: List<ProcessTextAction>? = null
    private var cachedKey: String? = null
    private var cachedGeneration = -1L
    private var loadingKey: String? = null
    private var loadingGeneration = -1L
    private val listeners = mutableListOf<(List<ProcessTextAction>) -> Unit>()

    init {
        if (registerPackageReceiver) {
            val initialContext = context.applicationContext ?: context
            runCatching {
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_PACKAGE_ADDED)
                    addAction(Intent.ACTION_PACKAGE_REMOVED)
                    addAction(Intent.ACTION_PACKAGE_CHANGED)
                    addAction(Intent.ACTION_PACKAGE_REPLACED)
                    addDataScheme("package")
                }
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        invalidate()
                    }
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    initialContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("DEPRECATION", "UnspecifiedRegisterReceiverFlag")
                    initialContext.registerReceiver(receiver, filter)
                }
                packageReceiver = receiver
            }
        }
    }

    fun close() {
        val receiver = synchronized(lock) {
            val current = packageReceiver
            packageReceiver = null
            current
        } ?: return
        appContextRef.get()?.let { ctx ->
            runCatching { ctx.unregisterReceiver(receiver) }
        }
    }

    private fun configKey(): String {
        val cfg = appContextRef.get()?.resources?.configuration ?: return ""
        return "${cfg.locales.toLanguageTags()}|${cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK}|${cfg.densityDpi}"
    }

    fun invalidate() {
        synchronized(lock) {
            generation++
            cachedActions = null
            cachedKey = null
        }
    }

    fun cached(): List<ProcessTextAction>? {
        val key = configKey()
        synchronized(lock) {
            val actions = cachedActions ?: return null
            return if (cachedGeneration == generation && cachedKey == key) actions else null
        }
    }

    fun prewarm() {
        val start = synchronized(lock) {
            val key = configKey()
            if (cachedActions != null && cachedGeneration == generation && cachedKey == key) null
            else claimLoadLocked(key, generation)
        }
        start?.invoke()
    }

    fun load(onReady: (List<ProcessTextAction>) -> Unit) {
        var hit: List<ProcessTextAction>? = null
        var start: (() -> Unit)? = null
        synchronized(lock) {
            val key = configKey()
            val actions = cachedActions
            if (actions != null && cachedGeneration == generation && cachedKey == key) {
                hit = actions
            } else {
                listeners.add(onReady)
                start = claimLoadLocked(key, generation)
            }
        }
        hit?.let(onReady)
        start?.invoke()
    }

    private fun claimLoadLocked(key: String, gen: Long): (() -> Unit)? {
        if (loadingKey == key && loadingGeneration == gen) return null
        loadingKey = key
        loadingGeneration = gen
        return { executor.execute { runLoad(key, gen) } }
    }

    private fun runLoad(key: String, gen: Long) {
        val ctx = appContextRef.get()
        val loaded = if (ctx != null) {
            runCatching {
                loadProcessTextActions(ctx, query ?: { processTextActivities(ctx) })
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        var deliver: List<(List<ProcessTextAction>) -> Unit>? = null
        var retry: (() -> Unit)? = null
        synchronized(lock) {
            val currentKey = configKey()
            if (gen == generation && key == currentKey) {
                cachedActions = loaded
                cachedKey = key
                cachedGeneration = gen
                loadingKey = null
                loadingGeneration = -1L
                if (listeners.isNotEmpty()) {
                    deliver = listeners.toList()
                    listeners.clear()
                }
            } else {
                loadingKey = null
                loadingGeneration = -1L
                if (listeners.isNotEmpty()) {
                    retry = claimLoadLocked(currentKey, generation)
                }
            }
        }
        deliver?.forEach { it(loaded) }
        retry?.invoke()
    }

    companion object {
        private val defaultExecutor: Executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "luma-process-text").apply { isDaemon = true }
        }
        private val catalogs = WeakHashMap<Context, ProcessTextCatalog>()
        fun forContext(context: Context): ProcessTextCatalog {
            val app = context.applicationContext ?: context
            synchronized(catalogs) {
                return catalogs.getOrPut(app) { ProcessTextCatalog(app) }
            }
        }
    }
}

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
    private val queryProcessText: (() -> List<ResolveInfo>)? = null,
    private val start: (View, ActionMode.Callback2) -> ActionMode? = { v, callback -> v.startActionMode(callback, ActionMode.TYPE_FLOATING) },
    private val catalog: ProcessTextCatalog? = null,
) : TextToolbar {
    private var mode: ActionMode? = null
    private var rect = Rect.Zero
    private var copy: (() -> Unit)? = null
    private var paste: (() -> Unit)? = null
    private var cut: (() -> Unit)? = null
    private var selectAll: (() -> Unit)? = null
    private var imagePaste = false
    private var services = emptyList<ProcessTextAction>()
    private var servicesQueried = false
    private var serviceLoadToken = 0L
    private var populatedActions: Int? = null
    private var populatedServices: List<ProcessTextAction>? = null
    private val mainHandler = Handler(Looper.getMainLooper())
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
    val scopedToEditor get() = !readOnly
    val hasSelection get() = copy != null || cut != null
    val anchorRevision get() = anchorVersion
    val keepsEditorFocus get() = !readOnly || cut != null || paste != null || imagePaste
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
    override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?, onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
        if (suppressed) return
        val previousRect = this.rect
        this.rect = rect; copy = onCopyRequested; paste = onPasteRequested; cut = onCutRequested
        selectAll = onSelectAllRequested
        val opening = mode == null
        if (opening) imagePaste = imagePasteAvailable()
        if (availableActions() == 0) {
            hide()
            return
        }
        anchorVersion++
        if (copy != null && !servicesQueried) {
            servicesQueried = true
            val syncQuery = queryProcessText
            if (syncQuery != null) {
                services = loadProcessTextActions(view.context, syncQuery)
            } else {
                val activeCatalog = catalog ?: ProcessTextCatalog.forContext(view.context)
                val token = ++serviceLoadToken
                val callingThread = Thread.currentThread()
                var inSyncCall = true
                activeCatalog.load { loaded ->
                    if (Thread.currentThread() === callingThread && inSyncCall) {
                        services = loaded
                    } else {
                        mainHandler.post {
                            if (mode != null && copy != null && serviceLoadToken == token) {
                                services = loaded
                                mode?.invalidate()
                            }
                        }
                    }
                }
                inSyncCall = false
            }
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
    override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?, onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?, onAutofillRequested: (() -> Unit)?) =
        showMenu(rect, onCopyRequested, onPasteRequested, onCutRequested, onSelectAllRequested)
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
        serviceLoadToken++
        populatedActions = null
        populatedServices = null
    }
    private fun availableActions(): Int =
        (if (copy != null) 1 else 0) or (if (paste != null || imagePaste) 2 else 0) or
            (if (cut != null) 4 else 0) or (if (selectAll != null) 8 else 0)
    internal val callback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean { populate(menu); return true }
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            imagePaste = imagePasteAvailable()
            if (availableActions() == 0) {
                hide()
                return false
            }
            val currentServices = if (copy != null) services else emptyList()
            if (populatedActions == availableActions() && populatedServices === currentServices) return false
            populate(menu)
            return true
        }
        override fun onGetContentRect(mode: ActionMode, view: View, outRect: android.graphics.Rect) {
            outRect.set(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
        }
        override fun onDestroyActionMode(mode: ActionMode) {
            if (this@NativeSelectionToolbar.mode === mode) {
                this@NativeSelectionToolbar.mode = null
                suppressed = true
                releaseMenu()
            }
        }
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            when (item.itemId) {
                android.R.id.copy -> copy?.invoke()
                android.R.id.paste -> if (!pasteImage()) paste?.invoke()
                android.R.id.cut -> cut?.invoke()
                android.R.id.selectAll -> selectAll?.invoke()
                else -> {
                    val service = services.getOrNull(item.itemId - PROCESS_BASE)?.resolveInfo ?: return false
                    process(service, copy)
                }
            }
            hide(); return true
        }
    }
    private fun populate(menu: Menu) {
        val currentServices = if (copy != null) services else emptyList()
        populatedActions = availableActions()
        populatedServices = currentServices
        menu.removeGroup(CORE_GROUP); menu.removeGroup(PROCESS_GROUP)
        fun action(id: Int, title: Int, enabled: Boolean, order: Int) {
            if (enabled) menu.add(CORE_GROUP, id, order, title).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
        action(android.R.id.copy, android.R.string.copy, copy != null, 0)
        action(android.R.id.paste, android.R.string.paste, paste != null || imagePaste, 1)
        action(android.R.id.cut, android.R.string.cut, cut != null, 2)
        action(android.R.id.selectAll, android.R.string.selectAll, selectAll != null, 3)
        currentServices.forEachIndexed { index, action ->
            if ((0 until menu.size()).any { menu.getItem(it).intent?.let { intent -> intent.action == Intent.ACTION_PROCESS_TEXT && intent.component == action.component } == true }) return@forEachIndexed
            val item = menu.add(PROCESS_GROUP, PROCESS_BASE + index, 100 + index, action.label)
            action.createIcon(view.resources)?.let { item.setIcon(it) }
            item.setIntent(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").setComponent(action.component))
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
