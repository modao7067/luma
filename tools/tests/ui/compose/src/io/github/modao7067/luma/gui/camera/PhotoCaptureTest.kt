package io.github.modao7067.luma.gui.camera

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.runBlocking
import io.github.modao7067.luma.gui.runtime.AgentRuntime
import io.github.modao7067.luma.gui.ui.FullPhotoContract
import io.github.modao7067.luma.gui.ui.PhotoCaptureHandle
import io.github.modao7067.luma.gui.ui.rememberPhotoCapture
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowContentResolver
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import io.github.modao7067.luma.gui.runtime.MediaDownloadsTest
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PhotoCaptureTest {
    @get:Rule val rule = createComposeRule()
    private val app get() = RuntimeEnvironment.getApplication() as Application
    private val store get() = PhotoCaptureStore(app)
    private val cache get() = File(app.cacheDir, "photo-capture")
    private val added = mutableListOf<String>()
    private val errors = mutableListOf<String>()
    private var owner = "root:session:old"
    private var enabled by mutableStateOf(true)
    private lateinit var handle: PhotoCaptureHandle
    private val registry = object : ActivityResultRegistry() {
        var request = 0
        var launches = 0
        var intent: Intent? = null
        var fail = false
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            launches++; request = requestCode
            intent = contract.createIntent(app, input)
            if (fail) throw android.content.ActivityNotFoundException("fixture missing camera")
        }
    }
    @Before fun prepare() {
        cache.deleteRecursively()
        File(app.filesDir, "runtime/agent/root/attachments").deleteRecursively()
        File(app.filesDir, "runtime/agent/attachments").deleteRecursively()
        shadowOf(android.webkit.MimeTypeMap.getSingleton()).addExtensionMimeTypeMapping("jpg", "image/jpeg")
        val authority = app.packageName + ".capture-files"
        val info = app.packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA)!!
        PhotoCaptureProvider().also {
            it.attachInfo(app, info)
            ShadowContentResolver.registerProviderInternal(authority, it)
        }
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(MediaStore.ACTION_IMAGE_CAPTURE), ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = "test.camera"; name = "test.camera.Main" }
        })
    }
    private fun content(onImage: (String) -> Unit = { added += it }): StateRestorationTester {
        val restorer = StateRestorationTester(rule)
        restorer.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
                override val activityResultRegistry = registry
            }) {
                handle = rememberPhotoCapture({ owner }, enabled, onImage) { errors += it }
            }
        }
        return restorer
    }
    private fun launch(): Uri {
        rule.runOnIdle { handle.launch() }
        return requireNotNull(registry.intent).getParcelableExtra(MediaStore.EXTRA_OUTPUT, Uri::class.java)!!
    }
    private fun result(success: Boolean) = rule.runOnIdle {
        registry.dispatchResult(registry.request, if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED, Intent())
    }
    private fun write(uri: Uri, bytes: ByteArray) {
        app.contentResolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }
    }
    @Test fun fullOriginalGoesOnlyThroughExistingImageImportAndCleansAfterCopy() {
        var imported: File? = null
        content { source ->
            added += source
            val path = try { runBlocking { AgentRuntime(app).importImage("root", Uri.parse(source)) } }
                catch (e: Exception) { throw AssertionError("Existing image import failed", e) }
            imported = File(app.filesDir, "runtime/agent/root/attachments/${path.substringAfterLast('/')}")
        }
        val uri = launch()
        val bytes = ByteArray(1024 * 1024 + 37) { (it % 251).toByte() }
        write(uri, bytes)
        rule.runOnIdle {
            handle.launch(); assertEquals(1, registry.launches); assertTrue(handle.busy)
        }
        result(true)
        rule.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(uri.toString()), added)
        assertNotNull("No import; errors=$errors", imported)
        assertArrayEquals(bytes, imported!!.readBytes())
        assertFalse(store.fileForUri(uri)!!.exists())
        assertTrue(cache.listFiles().orEmpty().isEmpty())
        assertTrue(errors.isEmpty())
        result(true)
        assertEquals(1, added.size)
    }
    @Test fun cancelEmptyOutputAndLaunchFailureLeaveNoAttachmentAndCanRetry() {
        content()
        val cancelled = launch(); write(cancelled, byteArrayOf(1, 2, 3)); result(false)
        assertFalse(store.fileForUri(cancelled)!!.exists()); assertTrue(added.isEmpty()); assertTrue(errors.isEmpty())
        val empty = launch(); result(true)
        assertFalse(store.fileForUri(empty)!!.exists()); assertTrue(added.isEmpty()); assertEquals(1, errors.size)
        registry.fail = true; rule.runOnIdle { handle.launch() }; rule.waitForIdle()
        assertFalse(handle.busy); assertTrue(cache.listFiles().orEmpty().isEmpty()); assertTrue(added.isEmpty())
        registry.fail = false; launch(); assertEquals(4, registry.launches); result(false)
    }
    @Test fun latestOwnerAndFirstSendGateRejectLatePhotoEvenWithoutRecomposition() {
        content()
        val old = launch(); write(old, byteArrayOf(1))
        owner = "root:session:new"
        result(true)
        assertTrue(added.isEmpty()); assertFalse(store.fileForUri(old)!!.exists())
        owner = "root:blank:first"; val blank = launch(); write(blank, byteArrayOf(2))
        owner = "root:blank:second"; result(true)
        assertTrue(added.isEmpty()); assertFalse(store.fileForUri(blank)!!.exists())
        val blocked = launch(); write(blocked, byteArrayOf(3))
        rule.runOnIdle { enabled = false }; rule.waitForIdle(); result(true)
        assertTrue(added.isEmpty()); assertFalse(store.fileForUri(blocked)!!.exists())
        assertEquals(3, errors.size)
    }
    @Test fun pendingCaptureAndOwnerSurviveSavedStateRecreation() {
        val restore = content()
        val uri = launch(); write(uri, byteArrayOf(5, 6, 7))
        restore.emulateSavedInstanceStateRestore()
        rule.waitForIdle(); assertTrue(handle.busy)
        rule.runOnIdle { handle.launch(); assertEquals(1, registry.launches) }
        result(true)
        assertEquals(listOf(uri.toString()), added)
        assertTrue(store.isReady(uri.lastPathSegment!!))
        assertTrue(store.fileForUri(uri)!!.exists())
        app.contentResolver.openInputStream(uri)!!.use { assertArrayEquals(byteArrayOf(5, 6, 7), it.readBytes()) }
        assertTrue(store.fileForUri(uri)!!.exists())
        runBlocking { AgentRuntime(app).importImage("noroot", uri) }
        assertTrue(cache.listFiles().orEmpty().isEmpty())
    }
    @Test fun existingImportBudgetFailureCleansBothSourceAndPartialImportedCopy() {
        var failed = false
        content { source ->
            try { runBlocking { AgentRuntime(app).importImage("noroot", Uri.parse(source)) } }
            catch (e: IOException) { failed = true; assertTrue(e.message!!.contains("20 MiB")) }
        }
        val uri = launch()
        RandomAccessFile(store.fileForUri(uri)!!, "rw").use { it.setLength(20L * 1024 * 1024 + 1) }
        result(true); rule.waitForIdle(); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(failed); assertTrue(cache.listFiles().orEmpty().isEmpty())
        assertTrue(File(app.filesDir, "runtime/agent/attachments").listFiles().orEmpty().isEmpty())
    }
    @Test fun providerIsRestrictedToCaptureCacheAndIntentHasOnlyExactUriGrants() {
        val name = store.create(); val uri = store.uri(name)
        val intent = FullPhotoContract().createIntent(app, uri)
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, intent.action)
        assertEquals(uri, intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT, Uri::class.java))
        assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION, intent.flags)
        assertEquals("image/jpeg", app.contentResolver.getType(uri))
        val info = app.packageManager.resolveContentProvider(app.packageName + ".capture-files", 0)!!
        assertFalse(info.exported); assertTrue(info.grantUriPermissions)
        val unrelated = File(app.cacheDir, "unrelated-secret").apply { writeText("private") }
        try { FileProvider.getUriForFile(app, app.packageName + ".capture-files", unrelated); fail("cache root exposed") }
        catch (_: IllegalArgumentException) {}
        for (invalid in listOf("content://${uri.authority}/photo/../unrelated-secret", "$uri.ready", "content://${uri.authority}/other/$name", "file://${store.file(name)!!.path}")) {
            assertNull(store.fileForUri(Uri.parse(invalid)))
        }
        assertNull(store.file("../unrelated-secret"))
        store.importFinished(Uri.fromFile(unrelated)); assertTrue(unrelated.exists())
        store.importFinished(uri); assertTrue(store.file(name)!!.exists())
        store.discard(name); assertEquals("private", unrelated.readText())
    }
    @Test fun newCaptureSweepsOnlyOldAbandonedOwnFilesNotCurrentOrOtherCache() {
        val old = store.create(); store.file(old)!!.writeText("abandoned"); store.ready(old)
        store.file(old)!!.setLastModified(System.currentTimeMillis() - 25 * 60 * 60 * 1000L)
        val live = store.create(); store.file(live)!!.writeText("in front of user")
        assertFalse(store.file(old)!!.exists()); assertFalse(store.isReady(old))
        val other = File(cache, "unrelated").apply { writeText("keep"); setLastModified(0) }
        store.create()
        assertTrue(store.file(live)!!.exists()); assertTrue(other.exists())
    }
    @Test fun ordinaryGalleryImportRemainsByteExactAndNeverDeletesItsSource() {
        val bytes = byteArrayOf(-1, -40, -1, 0, 1, 2, 3)
        val original = File(app.cacheDir, "gallery-original.jpg").apply { writeBytes(bytes) }
        MediaDownloadsTest.Source(original, "gallery.jpg", "image/jpeg").also {
            it.attachInfo(app, ProviderInfo().apply { authority = "gallery-source" })
            ShadowContentResolver.registerProviderInternal("gallery-source", it)
        }
        val path = runBlocking { AgentRuntime(app).importImage("noroot", Uri.parse("content://gallery-source/photo")) }
        val imported = File(app.filesDir, "runtime/agent/attachments/${path.substringAfterLast('/')}")
        assertArrayEquals(bytes, imported.readBytes()); assertArrayEquals(bytes, original.readBytes())
    }
}
