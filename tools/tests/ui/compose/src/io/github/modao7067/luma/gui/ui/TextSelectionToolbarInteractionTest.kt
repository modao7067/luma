package io.github.modao7067.luma.gui.ui

import android.app.Application
import android.content.*
import android.content.pm.*
import android.database.Cursor
import android.net.Uri
import android.view.*
import android.widget.PopupMenu
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.modao7067.luma.gui.session.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp", shadows = [SelectionMagnifierShadow::class])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class TextSelectionToolbarInteractionTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var mode: RecordingMode
    private var launched: Intent? = null
    private var returnResult: ((Intent?) -> Unit)? = null
    private val factory: (View, ActionMode.Callback2) -> ActionMode? = { view, callback -> RecordingMode(view.context, callback).also { mode = it; callback.onCreateActionMode(it, it.menu) } }
    private fun service(exported: Boolean = true, permission: String? = null, suffix: String = "Translator"): ResolveInfo = ResolveInfo().apply {
        nonLocalizedLabel = suffix
        activityInfo = ActivityInfo().apply {
            packageName = "fixture.translate"; name = "fixture.translate.$suffix"; this.exported = exported; enabled = true; this.permission = permission
            applicationInfo = ApplicationInfo().apply { packageName = "fixture.translate"; enabled = true }
        }
    }
    private fun installServices() {
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
        shadowOf(context.packageManager).addResolveInfoForIntent(intent, service())
        shadowOf(context.packageManager).addResolveInfoForIntent(intent, service(false, suffix = "Private"))
        shadowOf(context.packageManager).addResolveInfoForIntent(intent, service(permission = "fixture.denied", suffix = "Protected"))
    }
    @Composable private fun Host(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalNativeTextActionMode provides factory,
            LocalProcessTextLauncher provides { intent, callback -> launched = intent; returnResult = callback }) {
            MaterialTheme { TextSelectionHost { content() } }
        }
    }
    private fun longPressText() {
        longPressWord(rule.onNodeWithText("alpha beta"))
        rule.waitForIdle()
    }
    private fun longPressWord(node: SemanticsNodeInteraction) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val position = layouts.single().let { it.getBoundingBox(it.layoutInput.text.length - 2).center }
        node.performTouchInput { longClick(position) }
    }
    @Test fun realLongPressSurvivesAndBlankTapClosesWithoutConsumingAnotherButton() {
        var clicks = 0
        rule.setContent { Host { Column(Modifier.fillMaxSize()) {
            NativeMarkdown("alpha beta")
            androidx.compose.material3.Button(onClick = { clicks++ }) { androidx.compose.material3.Text("普通按钮") }
            Box(Modifier.fillMaxWidth().weight(1f).testTag("blank"))
        } } }
        longPressText()
        assertFalse(mode.finished)
        rule.onNodeWithTag("blank").performTouchInput { click(center) }
        rule.runOnIdle { assertTrue(mode.finished) }
        rule.onNodeWithText("普通按钮").performTouchInput { click(center) }
        rule.runOnIdle { assertEquals(1, clicks) }
        longPressText(); assertFalse(mode.finished)
    }
    @Test fun readonlyProcessSendsOnlySelectedTextAndNeverTouchesSystemClipboard() {
        installServices()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("existing", "leave clipboard alone"))
        rule.setContent { Host { Column(Modifier.fillMaxSize()) { NativeMarkdown("alpha beta"); Spacer(Modifier.weight(1f)) } } }
        longPressText()
        rule.runOnIdle {
            assertEquals(1, processTextActivities(context).size)
            mode.choose(NativeSelectionToolbar.PROCESS_BASE)
            assertEquals("beta", launched!!.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT).toString())
            assertTrue(launched!!.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false))
            assertEquals("leave clipboard alone", clipboard.primaryClip!!.getItemAt(0).text.toString())
            returnResult!!(Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, "must not rewrite document"))
        }
        rule.onNodeWithText("alpha beta").assertExists()
    }
    @Test fun editorProcessReplacesExactSelectionAndRejectsChangedDraftOrSelection() {
        installServices()
        var value by mutableStateOf(TextFieldValue("alpha beta"))
        val target = EditorProcessTarget({ value }, { value = it }, { true })
        rule.setContent { Host { Column(Modifier.fillMaxSize()) {
            SelectionToolbarProvider(editor = target) { BasicTextField(value, { value = it }, Modifier.fillMaxWidth().testTag("editor")) }
            Box(Modifier.weight(1f).fillMaxWidth().testTag("blank"))
        } } }
        longPressText()
        rule.runOnIdle { assertEquals(TextRange(6,10), value.selection); mode.choose(NativeSelectionToolbar.PROCESS_BASE); assertEquals("beta", launched!!.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT).toString()); assertFalse(launched!!.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)); returnResult!!(Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, "译文")); assertEquals("alpha 译文", value.text) }
        val snapshot = TextFieldValue("alpha beta", TextRange(0,5))
        rule.runOnIdle {
            value = snapshot.copy(selection = TextRange(6,10)); target.apply(snapshot, "wrong"); assertEquals("alpha beta", value.text)
            value = snapshot.copy(text = "new draft"); target.apply(snapshot, "wrong"); assertEquals("new draft", value.text)
            value = snapshot; target.alive = false; target.apply(snapshot, "wrong"); assertEquals("alpha beta", value.text)
        }
    }
    @Test fun ordinaryNativeCopyPasteAndOutsideDismissKeepTheEditorAndItsSelection() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("text", "NEW"))
        var value by mutableStateOf(TextFieldValue("alpha beta"))
        rule.setContent { Host { Column(Modifier.fillMaxSize()) {
            BasicTextField(value, { value = it }, Modifier.fillMaxWidth().testTag("editor"))
            Box(Modifier.weight(1f).fillMaxWidth().testTag("blank"))
        } } }
        longPressText()
        rule.runOnIdle { mode.choose(android.R.id.paste); assertEquals("alpha NEW", value.text) }
        longPressWord(rule.onNodeWithTag("editor"))
        rule.runOnIdle { mode.choose(android.R.id.copy); assertEquals("NEW", clipboard.primaryClip!!.getItemAt(0).text.toString()) }
        longPressWord(rule.onNodeWithTag("editor"))
        rule.onNodeWithTag("blank").performTouchInput { click(center) }
        rule.runOnIdle { assertTrue(mode.finished) }
        rule.onNodeWithTag("editor").assertIsFocused().performTextInput("!")
        rule.runOnIdle { assertEquals("alpha !", value.text) }
    }
    @Test fun nativePasteOffersImageAttachmentWithoutSendingOrChangingDraft() {
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "image/png"
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, where: String?, args: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, where: String?, args: Array<out String>?) = 0
        }
        ShadowContentResolver.registerProviderInternal("fixture.image", provider)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(ClipData("image", arrayOf("image/png"), ClipData.Item(Uri.parse("content://fixture.image/original"))))
        val added = mutableListOf<String>(); var sends = 0; var draft = "alpha beta"
        val actions = object : CockpitActions by previewActions {
            override fun addImage(uri: String) { added += uri }
            override fun draft(text: String) { draft = text }
            override fun send() { sends++ }
        }
        rule.setContent { Host { Column(Modifier.fillMaxSize()) {
            CompactComposer(CockpitState(), sampleView().copy(draft = draft), actions, {}, {})
            Box(Modifier.fillMaxWidth().weight(1f).testTag("blank"))
        } } }
        rule.onNodeWithContentDescription("消息草稿").performTouchInput { longClick(centerLeft + androidx.compose.ui.geometry.Offset(20f,0f)) }
        rule.waitForIdle()
        rule.runOnIdle { assertNotNull(mode.menu.findItem(android.R.id.paste)); mode.choose(android.R.id.paste); assertEquals(listOf("content://fixture.image/original"), added); assertEquals(0,sends); assertEquals("alpha beta",draft) }
    }
    @Test fun importingAttachmentCannotOpenUntilItsPrivateOriginalIsReady() {
        var busy by mutableStateOf(true)
        rule.setContent { MaterialTheme {
            val bitmap = remember { mediaFixtureBitmap() }
            CompositionLocalProvider(LocalMediaFixtureFrame provides MediaFrame(bitmap)) {
                MediaViewerHost("owner", previewActions, MediaContext("owner", "/work")) { ImageThumbnail("original.png", "图片", busy) }
            }
        } }
        rule.onNodeWithTag("media-card:original.png").assertIsNotEnabled()
        rule.runOnIdle { busy = false }
        rule.onNodeWithTag("media-card:original.png").assertIsEnabled()
    }
    @Test fun longSelectedMarkdownScrollsWithoutMenuPreparationOrLosingCopy() {
        val paragraphs = (0 until 32).map { "section$it alpha beta gamma delta epsilon zeta." }
        lateinit var scroll: androidx.compose.foundation.ScrollState
        var shows = 0
        rule.setContent { Host {
            val delegate = LocalTextToolbar.current
            val observer = remember(delegate) { object : TextToolbar by delegate {
                override fun showMenu(rect: androidx.compose.ui.geometry.Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?, onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?, onAutofillRequested: (() -> Unit)?) {
                    shows++
                    delegate.showMenu(rect, onCopyRequested, onPasteRequested, onCutRequested, onSelectAllRequested, onAutofillRequested)
                }
            } }
            CompositionLocalProvider(LocalTextToolbar provides observer) {
                scroll = rememberScrollState()
                Column(Modifier.fillMaxSize().verticalScroll(scroll).testTag("selected-document")) { NativeMarkdown(paragraphs.joinToString("\n\n")) }
            }
        } }
        longPressWord(rule.onNodeWithText(paragraphs.first()))
        rule.runOnIdle { mode.choose(android.R.id.selectAll) }
        rule.runOnIdle { kotlinx.coroutines.runBlocking { scroll.scrollTo(20) } }
        rule.waitForIdle()
        val active = mode; val beforeShows = shows; val beforePrepares = active.prepares
        rule.onNodeWithTag("selected-document").performTouchInput { swipeUp(durationMillis = 400) }
        rule.runOnIdle {
            println("SELECTION_COMPOSE paragraphs=32 scroll=${scroll.value} showMenu=${shows - beforeShows} prepare=${active.prepares - beforePrepares} contentRect=${active.contentRects}")
            assertTrue(scroll.value > 20); assertTrue(shows > beforeShows)
            assertSame(active, mode); assertFalse(mode.finished); assertEquals(beforePrepares, mode.prepares)
            mode.choose(android.R.id.copy)
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val copied = clipboard.primaryClip!!.getItemAt(0).text.toString()
            paragraphs.forEach { assertTrue("copy retained $it", copied.contains(it)) }
        }
    }
    @Test fun inlineCodeKeepsItsExactTextAndPlacesBackingOutsideGlyphSpans() {
        rule.setContent { Host { NativeMarkdown("path `alpha beta`") } }
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("path  alpha beta ").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val text = layouts.single().layoutInput.text
        val code = text.getStringAnnotations(INLINE_CODE_BACKGROUND, 0, text.length).single()
        assertEquals(" alpha beta ", text.text.substring(code.start, code.end))
        assertTrue(text.spanStyles.none { it.item.background != androidx.compose.ui.graphics.Color.Unspecified })
    }
    @Test fun ordinaryWordSelectionHidesOffscreenAndReopensOnReentryWithoutLosingCopy() {
        lateinit var scroll: androidx.compose.foundation.ScrollState
        val source = "alpha beta\n\n" + (0 until 32).joinToString("\n\n") { "section$it gamma delta epsilon zeta." }
        rule.setContent { Host {
            scroll = rememberScrollState()
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) { NativeMarkdown(source) }
        } }
        longPressText()
        val original = mode
        rule.runOnIdle { kotlinx.coroutines.runBlocking { scroll.scrollTo(500) } }
        rule.runOnIdle { assertTrue("local word is outside a still-visible long document", original.finished) }
        rule.runOnIdle { kotlinx.coroutines.runBlocking { scroll.scrollTo(0) } }
        rule.runOnIdle {
            assertNotSame(original, mode)
            assertFalse(mode.finished)
            mode.choose(android.R.id.copy)
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            assertEquals("beta", clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
    }
    @Test fun renderedOrderedListCopyRetainsItsVisibleNumbersAndText() {
        lateinit var scroll: androidx.compose.foundation.ScrollState
        val source = "3. alpha beta\n4. gamma delta\n\n" + "tail paragraph\n\n".repeat(32)
        rule.setContent { Host {
            scroll = rememberScrollState()
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) { NativeMarkdown(source) }
        } }
        longPressText()
        rule.runOnIdle { mode.choose(android.R.id.selectAll) }
        rule.runOnIdle { kotlinx.coroutines.runBlocking { scroll.scrollTo(1) } }
        rule.runOnIdle {
            mode.choose(android.R.id.copy)
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val text = clipboard.primaryClip!!.getItemAt(0).text.toString()
            assertTrue(text.contains("3.")); assertTrue(text.contains("4."))
            assertTrue(text.contains("alpha beta")); assertTrue(text.contains("gamma delta"))
        }
    }
    @Test fun nativeSelectionHandleStillDragsAndCopiesTheExpandedRange() {
        var value by mutableStateOf(TextFieldValue("alpha beta"))
        rule.setContent { Host { BasicTextField(value, { value = it }, Modifier.fillMaxWidth().testTag("editor")) } }
        longPressText()
        rule.runOnIdle { assertEquals(TextRange(6,10), value.selection) }
        val startHandle = SemanticsMatcher("start selection handle") { node ->
            node.config.any { it.key.name == "SelectionHandleInfo" && it.value.toString().contains("SelectionStart") }
        }
        rule.onNode(startHandle, useUnmergedTree = true).performTouchInput {
            swipe(center, center + androidx.compose.ui.geometry.Offset(-60f, 0f), 300)
        }
        rule.runOnIdle {
            assertTrue(value.selection.min < 6); assertEquals(10, value.selection.max)
            val expected = value.text.substring(value.selection.min, value.selection.max)
            mode.choose(android.R.id.copy)
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            assertEquals(expected, clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
    }
    @Test fun readonlySelectionHandleDragsThroughTheCustomContainerAndCopiesExactText() {
        rule.setContent { Host { NativeMarkdown("alpha beta") } }
        longPressText()
        val startHandle = SemanticsMatcher("start selection handle") { node ->
            node.config.any { it.key.name == "SelectionHandleInfo" && it.value.toString().contains("SelectionStart") }
        }
        rule.onNode(startHandle, useUnmergedTree = true).performTouchInput {
            swipe(center, center + androidx.compose.ui.geometry.Offset(-100f, 0f), 300)
        }
        rule.runOnIdle {
            mode.choose(android.R.id.copy)
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            assertEquals("alpha beta", clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
    }
    @Test fun typographyOnlyThemesKeepTheWindowSelectionPalette() {
        var actual: androidx.compose.foundation.text.selection.TextSelectionColors? = null
        var expected: androidx.compose.foundation.text.selection.TextSelectionColors? = null
        rule.setContent { Host { SessionTypography { ConversationTypography {
            actual = androidx.compose.foundation.text.selection.LocalTextSelectionColors.current
            expected = nativeTextSelectionColors(MaterialTheme.colorScheme)
        } } } }
        rule.runOnIdle { assertEquals(expected, actual) }
    }
    private class RecordingMode(context: Context, val callback: ActionMode.Callback2) : ActionMode() {
        private val contents = PopupMenu(context, View(context)).menu
        var finished = false
        var prepares = 0; var contentRects = 0
        override fun getMenu() = contents
        fun choose(id: Int) { check(callback.onActionItemClicked(this, contents.findItem(id))) }
        override fun finish() { finished = true; callback.onDestroyActionMode(this) }
        override fun invalidate() { prepares++; callback.onPrepareActionMode(this, contents) }
        override fun invalidateContentRect() { contentRects++ }
        override fun getMenuInflater() = MenuInflater(context)
        private val context = context
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
