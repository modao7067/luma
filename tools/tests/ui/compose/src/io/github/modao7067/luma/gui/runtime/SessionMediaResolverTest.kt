package io.github.modao7067.luma.gui.runtime

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import io.github.modao7067.luma.gui.session.MediaResource
import io.github.modao7067.luma.gui.session.SessionController
import io.github.modao7067.luma.gui.ui.BoundedMediaFrameCache
import io.github.modao7067.luma.gui.ui.MediaFrame
import io.github.modao7067.luma.gui.ui.MediaFrameKey
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileNotFoundException
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SessionMediaResolverTest {
    private val app get()=RuntimeEnvironment.getApplication() as Application
    private val png="iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+kH8sAAAAASUVORK5CYII="
    private fun provider(mime:String="image/png",bytes:ByteArray=Base64.getDecoder().decode(png)):FixtureProvider {
        val file=File(app.cacheDir,"selected-image").apply { writeBytes(bytes) }
        return FixtureProvider(file,mime).also { it.attachInfo(app,ProviderInfo().apply { authority="media-fixture" });ShadowContentResolver.registerProviderInternal("media-fixture",it) }
    }
    class FixtureProvider(private val file:File,private val mime:String,private val onOpen:((Uri)->Unit)?=null):ContentProvider() {
        var opened=0
        override fun onCreate()=true
        override fun getType(uri:Uri)=mime
        override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor =
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>("中文 资源.png",file.length())) }
        override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
            if(uri.lastPathSegment=="missing")throw FileNotFoundException("附件已移除")
            onOpen?.invoke(uri)
            opened++;return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun insert(uri:Uri,values:ContentValues?):Uri?=null
        override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?)=0
        override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?)=0
    }
    @Test fun importResolveAndOpenCodeFileUriUseThePrivateCopyWithoutGuestIo()=runBlocking {
        provider();val runtime=AgentRuntime(app)
        val native=runtime.importImage("root",Uri.parse("content://media-fixture/image"))
        assertTrue(native.startsWith("/proc/1/root"))
        for(source in listOf(native,"file://$native")) {
            val resolved=runtime.resolveMedia("root",MediaResource(source,sessionId="original",cwd="/child"))
            assertEquals("image/png",resolved.mimeType)
            assertArrayEquals(Base64.getDecoder().decode(png),File(Uri.parse(resolved.uri).path!!).readBytes())
            assertEquals(Base64.getDecoder().decode(png).size.toLong(),resolved.sizeBytes)
        }
    }
    @Test fun inspectUsesOnlyMetadataAndDoesNotOpenTheContent()=runBlocking {
        val provider=provider();val runtime=AgentRuntime(app)
        val result=runtime.inspectMedia("root",MediaResource("content://media-fixture/image"))
        assertEquals(0,provider.opened);assertEquals("中文 资源.png",result.name)
        assertEquals("image/png",result.mimeType);assertEquals(Base64.getDecoder().decode(png).size.toLong(),result.sizeBytes)
    }
    @Test fun inlineSniffingAndSessionCacheIdentityAreReal()=runBlocking {
        val runtime=AgentRuntime(app);val source="data:application/octet-stream;base64,$png"
        val a=runtime.resolveMedia("root",MediaResource(source,sessionId="a"))
        val again=runtime.resolveMedia("root",MediaResource(source,sessionId="a"))
        val b=runtime.resolveMedia("root",MediaResource(source,sessionId="b"))
        assertEquals("image/png",a.mimeType);assertEquals(a.uri,again.uri);assertNotEquals(a.uri,b.uri)
        val svg=runtime.resolveMedia("root",MediaResource("data:image/*,%3Csvg%20xmlns='http://www.w3.org/2000/svg'/%3E"))
        assertEquals("image/svg+xml",svg.mimeType)
        val pdf=runtime.resolveMedia("root",MediaResource("data:image/*,%25PDF-1.7"))
        assertEquals("application/pdf",pdf.mimeType)
    }
    @Test fun corruptMissingAndOpaqueFileIdDoNotLeavePartialCacheFiles()=runBlocking {
        provider();val runtime=AgentRuntime(app)
        for(source in listOf("data:image/png;base64,YmFk", "content://media-fixture/missing","codex-file:file-native")) {
            val before=File(app.cacheDir,"session-media").listFiles().orEmpty().size
            try {runtime.resolveMedia("root",MediaResource(source));fail(source)} catch (_:Exception) {}
            assertEquals(before,File(app.cacheDir,"session-media").listFiles().orEmpty().size)
        }
    }
    @Test fun externalApkIsAnExplicitReadOnlyViewIntentNotInstallation()=runBlocking {
        provider("application/vnd.android.package-archive","fixture zip".toByteArray())
        AgentRuntime(app).openMediaExternally("root",MediaResource("content://media-fixture/apk",name="artifact"))
        val intent=shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW,intent.action)
        assertEquals("application/vnd.android.package-archive",intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0,intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(app.packageName+".terminal-files",intent.data!!.authority)
        assertTrue(intent.data!!.pathSegments.first().startsWith("open-media-"))
        assertEquals("artifact.apk",intent.data!!.lastPathSegment)
    }
    @Test fun controllerNeverFallsBackFromAClosedOwnerToSelectedOrBlank()=runBlocking {
        val controller=SessionController(app)
        try {
            try {controller.resolveMedia(MediaResource("data:text/plain,fixture",sessionId="closed-owner"));fail("closed owner")}
            catch (error:IllegalStateException) {assertTrue(error.message!!.contains("来源会话已关闭"))}
            val resolved=controller.resolveMedia(MediaResource("data:text/plain,fixture"))
            assertEquals("fixture",File(Uri.parse(resolved.uri).path!!).readText())
        } finally {controller.disconnectAll()}
    }

    @Test fun parallelResolveDifferentResourcesAndSingleFlightCancellationSafety()=runBlocking {
        val file = File(app.cacheDir, "concurrent-img").apply { writeBytes(Base64.getDecoder().decode(png)) }
        val twoParallel = CountDownLatch(2)
        val sameEntered = CountDownLatch(1)
        val releaseSame = CountDownLatch(1)
        val provider = FixtureProvider(file, "image/png") { uri ->
            when (uri.lastPathSegment) {
                "p1", "p2" -> {
                    twoParallel.countDown()
                    assertTrue("Different resources must resolve concurrently without global lock", twoParallel.await(5, TimeUnit.SECONDS))
                }
                "sf" -> {
                    sameEntered.countDown()
                    assertTrue(releaseSame.await(5, TimeUnit.SECONDS))
                }
            }
        }
        provider.attachInfo(app, ProviderInfo().apply { authority = "media-concurrent" })
        ShadowContentResolver.registerProviderInternal("media-concurrent", provider)

        val resolver = SessionMediaResolver(app, AgentRuntime(app))
        coroutineScope {
            val d1 = async { resolver.resolve("root", MediaResource("content://media-concurrent/p1", sessionId = "s1")) }
            val d2 = async { resolver.resolve("root", MediaResource("content://media-concurrent/p2", sessionId = "s1")) }
            val (r1, r2) = awaitAll(d1, d2)
            assertNotEquals(r1.uri, r2.uri)
        }

        val beforeSf = provider.opened
        coroutineScope {
            val first = async(kotlinx.coroutines.Dispatchers.Default) { resolver.resolve("root", MediaResource("content://media-concurrent/sf", sessionId = "s1")) }
            assertTrue(sameEntered.await(5, TimeUnit.SECONDS))
            val second = async(kotlinx.coroutines.Dispatchers.Default) { resolver.resolve("root", MediaResource("content://media-concurrent/sf", sessionId = "s1")) }
            first.cancelAndJoin()
            releaseSame.countDown()
            val resolved = second.await()
            assertEquals("image/png", resolved.mimeType)
        }
        assertEquals("Single-flight must invoke underlying read exactly once even when one waiter cancels", beforeSf + 1, provider.opened)
    }

    @Test fun softTtlForceRefreshAndActivePinTrimProtection()=runBlocking {
        val provider = provider()
        var nowMs = 1_000_000_000L
        val resolver = SessionMediaResolver(app, AgentRuntime(app)) { nowMs }
        val res = MediaResource("content://media-fixture/ttl-img", sessionId = "s1")

        val first = resolver.resolve("root", res)
        assertEquals(1, provider.opened)
        nowMs += 5_000L
        val hit = resolver.resolve("root", res)
        assertEquals(first.uri, hit.uri)
        assertEquals(1, provider.opened)

        val forced = resolver.resolve("root", res, forceRefresh = true)
        assertNotEquals(first.uri, forced.uri)
        assertEquals(2, provider.opened)

        nowMs += SessionMediaResolver.SOFT_TTL_MS + 1_000L
        val expired = resolver.resolve("root", res)
        assertNotEquals(forced.uri, expired.uri)
        assertEquals(3, provider.opened)

        val mediaDir = File(app.cacheDir, "session-media").apply { mkdirs() }
        val pinnedFile = File(mediaDir, "pinned-old").apply { writeBytes(byteArrayOf(1, 2, 3)); setLastModified(nowMs - 48 * 3600_000L) }
        val unpinnedFile = File(mediaDir, "unpinned-old").apply { writeBytes(byteArrayOf(1, 2, 3)); setLastModified(nowMs - 48 * 3600_000L) }
        val pin = SessionMediaResolver.pinFile(pinnedFile)
        try {
            resolver.trim()
            assertTrue("Pinned active file must not be deleted by trim()", pinnedFile.isFile)
            assertFalse("Unpinned expired file should be trimmed", unpinnedFile.isFile)
        } finally {
            SessionMediaResolver.unpinPath(pin)
            pinnedFile.delete()
        }
    }

    @Test fun boundedMediaFrameCacheEvictsByBytesWithoutRecyclingBitmaps() {
        val b1 = android.graphics.Bitmap.createBitmap(100, 100, android.graphics.Bitmap.Config.ARGB_8888)
        val b2 = android.graphics.Bitmap.createBitmap(100, 100, android.graphics.Bitmap.Config.ARGB_8888)
        val b3 = android.graphics.Bitmap.createBitmap(100, 100, android.graphics.Bitmap.Config.ARGB_8888)
        val singleBytes = b1.allocationByteCount
        val cache = BoundedMediaFrameCache(thumbnailBudgetBytes = singleBytes * 2, fullscreenBudgetBytes = singleBytes * 2)
        val k1 = MediaFrameKey("o", "s", "/w", "1.png", "v1", 256, 0)
        val k2 = MediaFrameKey("o", "s", "/w", "2.png", "v1", 256, 0)
        val k3 = MediaFrameKey("o", "s", "/w", "3.png", "v1", 256, 0)
        cache.put(k1, MediaFrame(b1))
        cache.put(k2, MediaFrame(b2))
        cache.put(k3, MediaFrame(b3))
        assertNull(cache.get(k1))
        assertNotNull(cache.get(k2))
        assertNotNull(cache.get(k3))
        assertFalse("Evicted bitmap must never be recycled", b1.isRecycled)
    }
}
