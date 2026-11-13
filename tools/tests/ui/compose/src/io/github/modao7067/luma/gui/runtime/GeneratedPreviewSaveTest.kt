package io.github.modao7067.luma.gui.runtime

import android.app.Application
import android.content.pm.ProviderInfo
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.*
import io.github.modao7067.luma.gui.agent.*
import io.github.modao7067.luma.gui.session.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.util.Base64
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class GeneratedPreviewSaveTest {
    private class Paused:CoroutineDispatcher(){override fun dispatch(context:CoroutineContext,block:Runnable){}}
    private class Harness:AutoCloseable {
        val app:Application=RuntimeEnvironment.getApplication()
        val controller=SessionController(app)
        val paused=CoroutineScope(SupervisorJob()+Paused());val running=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val id:String;val live:Any
        val calls=mutableListOf<JSONObject>();lateinit var receive:(String)->Unit
        val rpc:CodexRpc
        init{
            (field(controller,"scope") as CoroutineScope).cancel();set(controller,"scope",paused)
            id=runBlocking{controller.manage(obj("action" to "create","cli" to "Codex","cwd" to "/original/cwd","requestId" to "preview-create"))}.getString("id")
            live=(field(controller,"sessions") as Map<*,*>)[id]!!
            val record=field(live,"record") as SessionRecord;record.threadId="native"
            controller.openSession(id);(field(live,"connectJob") as? Job)?.cancel();set(live,"connectJob",null)
            rpc=CodexRpc("fixture",null,{}, {},object:CodexTransport{
                override fun connect(opened:()->Unit,message:(String)->Unit,failed:(Throwable)->Unit){receive=message;opened()}
                override fun send(message:String):Boolean{
                    val frame=JSONObject(message)
                    if(frame.optString("method")=="command/exec")calls+=frame
                    else if(frame.has("id"))receive(obj("id" to frame.get("id"),"result" to JSONObject()).toString())
                    return true
                }
                override fun close(){}
            });runBlocking{rpc.connect()};set(live,"rpc",rpc)
            set(live,"view",SessionView(SessionSummary(id,"fixture",record.environment,record.cwd),connected=true))
            set(controller,"scope",running)
        }
        fun reply(exit:Int,stdout:String="",stderr:String=""){
            receive(obj("id" to calls.single().get("id"),"result" to obj("exitCode" to exit,"stdout" to stdout,"stderr" to stderr)).toString());pump()
        }
        fun pump(){Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))}
        fun await(done:()->Boolean){val end=System.nanoTime()+3_000_000_000L;while(!done()&&System.nanoTime()<end){Thread.sleep(10);pump()};assertTrue(done())}
        fun sink()=MediaDownloadsTest.Downloads(app.cacheDir).also{it.attachInfo(app,ProviderInfo().apply{authority=MediaStore.AUTHORITY});ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY,it)}
        override fun close(){controller.disconnectAll();paused.cancel();running.cancel();rpc.close()}
    }
    @Test fun generatedDiffIsOnlySaveableAfterSuccessAndSavesTheCapturedUtf8SnapshotWithoutRunningGitAgain()=Harness().use{h->
        val sink=h.sink();h.controller.previewDiff();h.pump()
        assertTrue(h.controller.state.value.filePreview!!.loading);assertNull(h.controller.state.value.filePreview!!.sourceResource)
        val diff="diff --git a/中文 b/中文\n+original snapshot\n"
        h.reply(0,diff,"warning is not patch content")
        val preview=h.controller.state.value.filePreview!!;val resource=preview.sourceResource!!
        assertEquals("changes.patch",resource.name);assertEquals("text/x-diff",resource.mimeType)
        assertEquals(h.id,resource.sessionId);assertEquals("/original/cwd",resource.cwd)
        assertArrayEquals(diff.toByteArray(Charsets.UTF_8),Base64.getDecoder().decode(resource.source.substringAfter(',')))
        assertTrue(preview.text.contains("预览最多 512 KiB"));assertTrue(preview.text.contains("warning is not patch content"))
        val saved=runBlocking{h.controller.saveMedia(resource)}
        assertEquals("/sdcard/Download/Luma/changes.patch",saved)
        assertArrayEquals(diff.toByteArray(Charsets.UTF_8),sink.rows.values.single().file.readBytes())
        assertEquals(1,h.calls.size)
    }
    @Test fun failedGeneratedPreviewNeverPublishesAnErrorMessageAsAnOriginalFile()=Harness().use{h->
        val sink=h.sink();h.controller.previewDiff();h.pump();h.reply(128,"partial output","fixture git failure")
        val preview=h.controller.state.value.filePreview!!
        assertFalse(preview.loading);assertTrue(preview.text.contains("git diff 退出码 128"));assertTrue(preview.text.contains("partial output"));assertNull(preview.sourceResource)
        assertTrue(sink.rows.isEmpty())
    }
    @Test fun largeMarkdownPreviewErrorRetainsOriginalResourceAndSaveDoesNotWriteTheErrorOrTruncatedText()=Harness().use{h->
        val sink=h.sink();val file=File(h.app.cacheDir,"large-markdown").apply{outputStream().use{out->repeat(22000){out.write("# 原始 Markdown 正文\n".toByteArray())}}}
        assertTrue(file.length()>512*1024)
        MediaDownloadsTest.Source(file,"large.md","text/markdown").also{it.attachInfo(h.app,ProviderInfo().apply{authority="save-source"});ShadowContentResolver.registerProviderInternal("save-source",it)}
        val original=MediaResource("content://save-source/file","large.md","text/markdown",h.id,"/original/cwd")
        h.controller.previewResource(original);h.await{h.controller.state.value.filePreview?.loading==false}
        val preview=h.controller.state.value.filePreview!!;assertTrue(preview.text.contains("512 KiB"));assertEquals(original,preview.sourceResource)
        runBlocking{h.controller.saveMedia(preview.sourceResource!!)}
        assertEquals(file.length(),sink.rows.values.single().file.length());assertArrayEquals(file.readBytes(),sink.rows.values.single().file.readBytes())
        assertTrue(h.calls.isEmpty())
    }
    @Test fun inventorySaveUsesOnlyTheDisplayedRedactedTextSnapshotAndNeverAnOriginalPath()=Harness().use{h->
        val sink=h.sink()
        val secret="api_key = \"synthetic-never-a-real-key\"\npublic = true\nAuthorization: Bearer synthetic-fake-only\n"
        h.controller.previewInventory(ReadOnlyEntry("config.toml",secret))
        h.await{h.controller.state.value.filePreview?.loading==false}
        val preview=h.controller.state.value.filePreview!!;val resource=preview.sourceResource!!
        assertEquals("config.toml",resource.name);assertEquals("text/plain",resource.mimeType)
        assertTrue(resource.source.startsWith("data:text/plain;base64,"))
        assertEquals(h.id,resource.sessionId);assertEquals("/original/cwd",resource.cwd)
        val savedText=Base64.getDecoder().decode(resource.source.substringAfter(',')).toString(Charsets.UTF_8)
        assertEquals(preview.text,savedText);assertTrue(savedText.contains("[已隐藏]"));assertFalse(savedText.contains("synthetic-never"));assertFalse(savedText.contains("synthetic-fake"))
        runBlocking{h.controller.saveMedia(resource)}
        assertArrayEquals(preview.text.toByteArray(Charsets.UTF_8),sink.rows.values.single().file.readBytes())
        assertTrue(h.calls.isEmpty())
        h.controller.previewInventory(ReadOnlyEntry("MCP server","{\"name\":\"safe metadata\"}"))
        h.await{h.controller.state.value.filePreview?.loading==false}
        val detail=h.controller.state.value.filePreview!!.sourceResource!!
        assertEquals("MCP server.txt",detail.name);assertEquals("text/plain",detail.mimeType)
        runBlocking{h.controller.saveMedia(detail)}
        assertEquals("{\"name\":\"safe metadata\"}",sink.rows.values.last().file.readText())
    }
    @Test fun inventoryReadFailureDoesNotExposeItsPathOrSaveAnErrorAsAnOriginal()=Harness().use{h->
        h.controller.previewInventory(ReadOnlyEntry("config.toml","not a substitute","relative-path-is-not-an-authorized-file"))
        h.await{h.controller.state.value.filePreview?.loading==false}
        val preview=h.controller.state.value.filePreview!!
        assertTrue(preview.text.startsWith("无法读取："));assertNull(preview.sourceResource);assertTrue(h.calls.isEmpty())
    }
    companion object{
        private fun field(o:Any,n:String):Any?=o.javaClass.getDeclaredField(n).apply{isAccessible=true}.get(o)
        private fun set(o:Any,n:String,v:Any?){o.javaClass.getDeclaredField(n).apply{isAccessible=true}.set(o,v)}
    }
}
