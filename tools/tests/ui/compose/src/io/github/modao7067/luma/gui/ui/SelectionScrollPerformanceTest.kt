package io.github.modao7067.luma.gui.ui

import android.app.Application
import android.content.Context
import android.content.pm.ResolveInfo
import android.view.*
import android.widget.PopupMenu
import androidx.compose.ui.geometry.Rect
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SelectionScrollPerformanceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val anchor = Rect(0f, 20f, 300f, 650f)
    private class Counts { var queries = 0; var labels = 0; var icons = 0 }
    private fun service(counts: Counts) = object : ResolveInfo() {
        override fun loadLabel(pm: android.content.pm.PackageManager): CharSequence { counts.labels++; return "Translate" }
        override fun loadIcon(pm: android.content.pm.PackageManager): android.graphics.drawable.Drawable { counts.icons++; return android.graphics.drawable.ColorDrawable() }
    }.apply {
        activityInfo = android.content.pm.ActivityInfo().apply { packageName = "fixture.translate"; name = "fixture.translate.Translator" }
    }
    @Test fun repeatedSelectionPositionUpdatesDoNotRequeryOrRebuildTheMenu() {
        val counts = Counts(); val service = service(counts)
        lateinit var mode: RecordingMode
        var copied = -1
        val toolbar = NativeSelectionToolbar(View(context), true, {}, { _, _ -> },
            queryProcessText = { counts.queries++; listOf(service) },
            start = { _, callback -> RecordingMode(context, callback).also { mode = it; callback.onCreateActionMode(it, it.menu) } })
        toolbar.showMenu(anchor, {}, null, null, {})
        val originalCopy = mode.menu.findItem(android.R.id.copy)
        repeat(120) { index ->
            toolbar.showMenu(anchor.copy(left = .1f), { copied = index }, null, null, {})
        }
        println("SELECTION_IDENTICAL updates=120 queries=${counts.queries} prepare=${mode.prepares} labels=${counts.labels} icons=${counts.icons} contentRect=${mode.contentRects}")
        assertEquals(1, counts.queries); assertEquals(0, mode.prepares)
        assertEquals(1, counts.labels); assertEquals(1, counts.icons); assertEquals(0, mode.contentRects)
        assertSame(originalCopy, mode.menu.findItem(android.R.id.copy))
        mode.callback.onPrepareActionMode(mode, mode.menu)
        assertSame(originalCopy, mode.menu.findItem(android.R.id.copy))
        mode.choose(android.R.id.copy); assertEquals(119, copied)
    }
    @Test fun movingSelectionUpdatesOnlyTheContentRectAndKeepsCurrentCallbacks() {
        val counts = Counts(); val service = service(counts)
        lateinit var mode: RecordingMode
        val toolbar = NativeSelectionToolbar(View(context), true, {}, { _, _ -> },
            queryProcessText = { counts.queries++; listOf(service) },
            start = { _, callback -> RecordingMode(context, callback).also { mode = it; callback.onCreateActionMode(it, it.menu) } })
        toolbar.showMenu(anchor, {}, null, null, {})
        repeat(120) { index -> toolbar.showMenu(anchor.translate(0f, -index - 1f), {}, null, null, {}) }
        val actual = android.graphics.Rect(); mode.callback.onGetContentRect(mode, View(context), actual)
        println("SELECTION_MOVING updates=120 queries=${counts.queries} prepare=${mode.prepares} labels=${counts.labels} icons=${counts.icons} contentRect=${mode.contentRects} final=$actual")
        assertEquals(1, counts.queries); assertEquals(0, mode.prepares)
        assertEquals(120, mode.contentRects); assertEquals(-100, actual.top)
    }
    @Test fun changedActionAvailabilityRebuildsButNewSelectionRefreshesServices() {
        val counts = Counts(); val service = service(counts)
        lateinit var mode: RecordingMode
        var all = 0; var cut = 0; var autofill = 0
        val toolbar = NativeSelectionToolbar(View(context), false, {}, { _, _ -> },
            queryProcessText = { counts.queries++; listOf(service) },
            start = { _, callback -> RecordingMode(context, callback).also { mode = it; callback.onCreateActionMode(it, it.menu) } })
        toolbar.showMenu(anchor, {}, null, null, {})
        toolbar.showMenu(anchor, {}, null, null, null)
        assertNull(mode.menu.findItem(android.R.id.selectAll)); assertEquals(1, mode.prepares)
        toolbar.showMenu(anchor, {}, {}, { cut++ }, { all++ }, { autofill++ })
        assertNull(mode.menu.findItem(android.R.id.autofill))
        mode.choose(android.R.id.cut); assertEquals(1, cut); assertEquals(0, autofill)
        toolbar.showMenu(anchor, {}, null, null, { all++ })
        assertEquals(2, counts.queries)
        mode.choose(android.R.id.selectAll); assertEquals(1, all)
    }
    @Test fun clipboardChangeRefreshesImagePasteWithoutRescanningServices() {
        val counts = Counts(); lateinit var mode: RecordingMode
        var imageAvailable = false; var probes = 0; var pasted = 0
        val toolbar = NativeSelectionToolbar(View(context), false, {}, { _, _ -> },
            imagePasteAvailable = { probes++; imageAvailable }, pasteImage = { pasted++; true },
            queryProcessText = { counts.queries++; emptyList() },
            start = { _, callback -> RecordingMode(context, callback).also { mode = it; callback.onCreateActionMode(it, it.menu) } })
        toolbar.showMenu(anchor, {}, null, null, {})
        repeat(120) { toolbar.showMenu(anchor, {}, null, null, {}) }
        assertEquals(1, probes); assertNull(mode.menu.findItem(android.R.id.paste))
        imageAvailable = true
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("fixture", "changed"))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNotNull(mode.menu.findItem(android.R.id.paste)); assertEquals(1, counts.queries)
        mode.choose(android.R.id.paste); assertEquals(1, pasted)
        val previousProbes = probes
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("fixture", "after close"))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(previousProbes, probes)
    }
    @Test fun missingSelectedContentAnchorFinishesTheMenuAndReentryReopensIt() {
        lateinit var mode: RecordingMode
        val toolbar = NativeSelectionToolbar(View(context), true, {}, { _, _ -> },
            queryProcessText = { emptyList() },
            start = { _, callback -> RecordingMode(context, callback).also { mode = it; callback.onCreateActionMode(it, it.menu) } })
        toolbar.showMenu(anchor, {}, null, null, {})
        val original = mode
        toolbar.refreshSelectionAnchor { toolbar.showMenu(anchor, {}, null, null, {}) }
        assertFalse(original.finished)
        toolbar.refreshSelectionAnchor {  }
        assertTrue(original.finished)
        assertEquals(androidx.compose.ui.platform.TextToolbarStatus.Hidden, toolbar.status)
        toolbar.refreshSelectionAnchor { toolbar.showMenu(anchor, {}, null, null, {}) }
        assertNotSame(original, mode)
        assertFalse(mode.finished)
    }
    @Test fun attachedMovingRectDefersInvalidationUntilSettledAndCloseCancelsRecovery() {
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup()
        try {
            val view = View(activity.get())
            activity.get().setContentView(view)
            assertTrue(view.isAttachedToWindow)
            lateinit var mode: RecordingMode
            val toolbar = NativeSelectionToolbar(view, true, {}, { _, _ -> },
                queryProcessText = { emptyList() },
                start = { _, callback -> RecordingMode(context, callback).also { mode = it; callback.onCreateActionMode(it, it.menu) } })
            toolbar.showMenu(anchor, {}, null, null, {})
            repeat(120) { index -> toolbar.showMenu(anchor.translate(0f, -index - 1f), {}, null, null, {}) }
            assertEquals(0, mode.contentRects)
            assertEquals(listOf(ActionMode.DEFAULT_HIDE_DURATION.toLong()), mode.hideDurations)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(120))
            assertEquals(1, mode.contentRects)
            assertEquals(0L, mode.hideDurations.last())
            toolbar.showMenu(anchor, {}, null, null, {})
            val previous = mode
            toolbar.hide()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
            assertTrue(previous.finished)
            assertEquals(1, previous.contentRects)
        } finally { activity.pause().stop().destroy() }
    }
    private class RecordingMode(context: Context, val callback: ActionMode.Callback2) : ActionMode() {
        private val contents = PopupMenu(context, View(context)).menu
        private val context = context
        var prepares = 0; var contentRects = 0
        var finished = false
        val hideDurations = mutableListOf<Long>()
        override fun getMenu() = contents
        fun choose(id: Int) { check(callback.onActionItemClicked(this, contents.findItem(id))) }
        override fun finish() { finished = true; callback.onDestroyActionMode(this) }
        override fun hide(duration: Long) { hideDurations += duration }
        override fun invalidate() { prepares++; callback.onPrepareActionMode(this, contents) }
        override fun invalidateContentRect() { contentRects++ }
        override fun getMenuInflater() = MenuInflater(context)
        override fun setTitle(title: CharSequence?) {}
        override fun setTitle(resId: Int) {}
        override fun setSubtitle(subtitle: CharSequence?) {}
        override fun setSubtitle(resId: Int) {}
        override fun setCustomView(view: View?) {}
        override fun getTitle(): CharSequence = ""
        override fun getSubtitle(): CharSequence = ""
        override fun getCustomView(): View? = null
    }
}
