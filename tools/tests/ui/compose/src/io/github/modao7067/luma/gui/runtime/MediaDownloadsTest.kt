package io.github.modao7067.luma.gui.runtime

import android.app.Application
import android.content.*
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.runBlocking
import io.github.modao7067.luma.gui.session.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class MediaDownloadsTest {
    private val app get()=RuntimeEnvironment.getApplication() as Application
    class Downloads(private val directory:File):ContentProvider() {
        data class Row(val uri:Uri,val file:File,val values:ContentValues)
        val rows=linkedMapOf<Uri,Row>();val occupied=mutableSetOf<String>();val requested=mutableListOf<ContentValues>()
        var sequence=0;var deleted=0;var failInsert=false;var failOpen=false;var failCommit=false;var failQuery=false
        var interruptOpen=false;var allocatedName:String?=null
        override fun onCreate()=true
        override fun getType(uri:Uri)=rows.getValue(uri).values.getAsString(MediaStore.MediaColumns.MIME_TYPE)
        override fun insert(uri:Uri,values:ContentValues?):Uri? {
            check(uri==MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY))
            val copy=ContentValues(values!!);requested+=copy
            check(copy.getAsInteger(MediaStore.MediaColumns.IS_PENDING)==1)
            check(copy.getAsString(MediaStore.MediaColumns.RELATIVE_PATH)=="Download/Luma/")
            check(!copy.containsKey(MediaStore.MediaColumns.DATA))
            if(failInsert)return null
            val name=copy.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)
            val dot=name.lastIndexOf('.');val base=if(dot>0)name.substring(0,dot) else name;val suffix=if(dot>0)name.substring(dot) else ""
            var actual=allocatedName ?: name;var n=0;while(actual in occupied){actual="$base (${++n})$suffix"}
            occupied+=actual;copy.put(MediaStore.MediaColumns.DISPLAY_NAME,actual)
            val result=ContentUris.withAppendedId(uri,(++sequence).toLong())
            rows[result]=Row(result,File(directory,"download-$sequence"),copy);return result
        }
        override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
            val row=rows.getValue(uri);check(row.values.getAsInteger(MediaStore.MediaColumns.IS_PENDING)==1)
            check(mode=="w");if(failOpen)throw IOException("fixture open failure")
            val pfd=ParcelFileDescriptor.open(row.file,ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
            if(interruptOpen)Thread.currentThread().interrupt()
            return pfd
        }
        override fun update(uri:Uri,values:ContentValues?,selection:String?,args:Array<out String>?):Int {
            val row=rows.getValue(uri);check(values!!.getAsInteger(MediaStore.MediaColumns.IS_PENDING)==0)
            if(failCommit)return 0
            row.values.putAll(values);return 1
        }
        override fun query(uri:Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,sort:String?):Cursor {
            check(uri in rows);check(selection==null && args==null)
            if(failQuery)throw IOException("fixture metadata failure")
            val row=rows.getValue(uri)
            return MatrixCursor(arrayOf(MediaStore.MediaColumns.DISPLAY_NAME,MediaStore.MediaColumns.RELATIVE_PATH)).apply {
                addRow(arrayOf(row.values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME),row.values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH)))
            }
        }
        override fun delete(uri:Uri,selection:String?,args:Array<out String>?):Int {
            val row=rows.remove(uri)?:return 0;deleted++;row.file.delete();return 1
        }
    }
    class Source(private val file:File,private val name:String,private val mime:String):ContentProvider() {
        override fun onCreate()=true
        override fun getType(uri:Uri)=mime
        override fun query(uri:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,o:String?):Cursor=MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)).apply{addRow(arrayOf<Any>(name,file.length()))}
        override fun openFile(uri:Uri,mode:String)=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)
        override fun insert(uri:Uri,values:ContentValues?):Uri?=null
        override fun update(uri:Uri,values:ContentValues?,s:String?,a:Array<out String>?)=0
        override fun delete(uri:Uri,s:String?,a:Array<out String>?)=0
    }
    private fun downloads():Downloads = Downloads(app.cacheDir).also{
        it.attachInfo(app,ProviderInfo().apply{authority=MediaStore.AUTHORITY});ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY,it)
    }
    private fun source(file:File,name:String,mime:String):MediaResource {
        Source(file,name,mime).also{it.attachInfo(app,ProviderInfo().apply{authority="save-source"});ShadowContentResolver.registerProviderInternal("save-source",it)}
        return MediaResource("content://save-source/file",cwd="/captured/child")
    }
    private fun noExportTemp()=assertTrue(File(app.cacheDir,"session-export").listFiles().orEmpty().isEmpty())
    private fun sameBytes(a:File,b:File) {
        assertEquals(a.length(),b.length())
        a.inputStream().use{left->b.inputStream().use{right->val x=ByteArray(65536);val y=ByteArray(65536)
            while(true){val n=left.read(x);if(n<0){assertEquals(-1,right.read());break};var at=0;while(at<n){val read=right.read(y,at,n-at);assertTrue(read>0);at+=read};assertArrayEquals(x.copyOf(n),y.copyOf(n))}
        }}
    }
    @Test fun allOriginalTypesAreByteExactAndDuplicateNamesAllocateNewRowsWithoutOverwriting()=runBlocking {
        val sink=downloads();val runtime=AgentRuntime(app)
        val samples=listOf("image/svg+xml" to "图.svg", "application/pdf" to "document.pdf", "application/vnd.android.package-archive" to "Luma.apk", "text/markdown" to "说明.md", "application/octet-stream" to "raw.bin")
        for((type,name) in samples){
            val bytes=when(type){"image/svg+xml"->"<svg xmlns='http://www.w3.org/2000/svg'><text>中文</text></svg>".toByteArray();"application/pdf"->"%PDF-1.7\nfixture original".toByteArray();else->byteArrayOf(0,1,2,13,10,-1)}
            val original=File(app.cacheDir,"source").apply{writeBytes(bytes)}
            val ref=source(original,name,type)
            val path=runtime.saveMedia("root",ref)
            assertEquals("/sdcard/Download/Luma/$name",path)
            val row=sink.rows.values.last();assertEquals(type,row.values.getAsString(MediaStore.MediaColumns.MIME_TYPE));assertEquals(0,row.values.getAsInteger(MediaStore.MediaColumns.IS_PENDING).toInt())
            assertArrayEquals(bytes,row.file.readBytes());assertArrayEquals(bytes,original.readBytes());noExportTemp()
        }
        val file=File(app.cacheDir,"duplicate").apply{writeText("first")};val ref=source(file,"report.txt","text/plain")
        sink.occupied+="report.txt"
        assertEquals("/sdcard/Download/Luma/report (1).txt",runtime.saveMedia("root",ref))
        file.writeText("second");assertEquals("/sdcard/Download/Luma/report (2).txt",runtime.saveMedia("root",ref))
        assertEquals("first",sink.rows.values.elementAt(5).file.readText());assertEquals("second",sink.rows.values.last().file.readText());noExportTemp()
    }
    @Test fun aReal105412213ByteFileUsesApkMetadataAndExportsAboveThePreviewBudget()=runBlocking {
        val sink=downloads();val original=File(app.cacheDir,"large-apk")
        RandomAccessFile(original,"rw").use{it.setLength(105412213);it.write(byteArrayOf(80,75,3,4));it.seek(original.length()-4);it.write("TAIL".toByteArray())}
        val ref=source(original,"large.apk","application/vnd.android.package-archive");val runtime=AgentRuntime(app)
        try{runtime.resolveMedia("root",ref);fail("preview budget bypass")}catch(e:IOException){assertTrue(e.message!!.contains("64 MiB"))}
        assertEquals("/sdcard/Download/Luma/large.apk",runtime.saveMedia("root",ref))
        sameBytes(original,sink.rows.values.single().file);assertEquals(105412213L,original.length());noExportTemp()
    }
    @Test fun damagedRasterAndTextOver512KiBAreSavedAsOriginalBytesNotPreviewOutput()=runBlocking {
        val sink=downloads();val runtime=AgentRuntime(app)
        val broken=File(app.cacheDir,"broken").apply{writeText("not a decodable PNG")};val ref=source(broken,"broken.png","image/png")
        try{runtime.resolveMedia("root",ref);fail("raster unexpectedly decoded")}catch(_:IOException){}
        runtime.saveMedia("root",ref);sameBytes(broken,sink.rows.values.last().file)
        val text=File(app.cacheDir,"long-text").apply{outputStream().use{out->repeat(20000){out.write("正文不是预览错误提示\n".toByteArray())}}}
        assertTrue(text.length()>512*1024);runtime.saveMedia("noroot",source(text,"large.md","text/markdown"))
        sameBytes(text,sink.rows.values.last().file);noExportTemp()
    }
    @Test fun insertOpenCommitAndMetadataFailuresDeleteOnlyTheirNewPendingRowAndCleanTemporaryOriginal()=runBlocking {
        val sink=downloads();val file=File(app.cacheDir,"failure-source").apply{writeText("original preserved")};val runtime=AgentRuntime(app)
        val ref=source(file,"safe.txt","text/plain")
        for(stage in listOf("insert","open","commit","query")){
            sink.failInsert=stage=="insert";sink.failOpen=stage=="open";sink.failCommit=stage=="commit";sink.failQuery=stage=="query"
            try{runtime.saveMedia("root",ref);fail(stage)}catch(_:IOException){}
            assertTrue(stage,sink.rows.isEmpty());assertEquals("original preserved",file.readText());noExportTemp()
        }
        assertEquals(3,sink.deleted)
    }
    @Test fun interruptedWriteRemovesThePendingRowAndDoesNotPublishPartialContent() {
        val sink=downloads();val file=File(app.cacheDir,"interrupt").apply{writeText("source")};sink.interruptOpen=true
        try{MediaDownloads.save(app.contentResolver,file,"file.txt","text/plain");fail("interruption ignored")}
        catch(_:InterruptedException){}finally{Thread.interrupted()}
        assertTrue(sink.rows.isEmpty());assertEquals(1,sink.deleted)
    }
    @Test fun oversizedExportAndOpaqueNativeIdFailBeforePublishingAndDoNotFallBackToAnotherSession()=runBlocking {
        val sink=downloads();val runtime=AgentRuntime(app)
        val big=File(app.cacheDir,"over-budget");RandomAccessFile(big,"rw").use{it.setLength(MediaDownloads.MAX_BYTES+1)}
        try{runtime.saveMedia("root",source(big,"too-big.bin","application/octet-stream"));fail("export budget bypass")}catch(e:IOException){assertTrue(e.message!!.contains("256 MiB"))}
        try{runtime.saveMedia("root",MediaResource("codex-file:opaque"));fail("guessed fileId")}catch(e:IOException){assertTrue(e.message!!.contains("文件 ID"))}
        val controller=SessionController(app)
        try{controller.saveMedia(MediaResource("data:text/plain,never",sessionId="closed"));fail("owner fallback")}catch(e:IllegalStateException){assertTrue(e.message!!.contains("来源会话已关闭"))}
        finally{controller.disconnectAll()}
        assertTrue(sink.requested.isEmpty());noExportTemp()
    }
    @Test fun returnValueUsesTheProvidersFinalNameAndTextFileMimeIsNotForcedToPlain() {
        val sink=downloads();val file=File(app.cacheDir,"code").apply{writeText("original source")}
        sink.allocatedName="actual allocated (7).java"
        val location=MediaDownloads.save(app.contentResolver,file,"Source.java","text/plain")
        assertEquals("/sdcard/Download/Luma/actual allocated (7).java",location)
        val expected=android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension("java")
        assertEquals(expected ?: "text/plain",sink.rows.values.single().values.getAsString(MediaStore.MediaColumns.MIME_TYPE))
    }
    @Test fun unsafeNamesNeverBecomePathsAndMimeAddsOnlyMissingExtension() {
        assertEquals("artifact.apk",MediaDownloads.name("artifact","application/vnd.android.package-archive"))
        assertEquals("changes.patch",MediaDownloads.name("changes.patch","text/x-diff"))
        assertEquals("evil.pdf",MediaDownloads.name("../escape\\evil.pdf","application/pdf"))
        assertEquals("a_b.txt",MediaDownloads.name("a\u0000b.txt","text/plain"))
        val long=MediaDownloads.name("中😀".repeat(150)+".pdf","application/pdf")
        assertTrue(long.endsWith(".pdf"));assertTrue(long.toByteArray().size<=220);assertFalse(long.contains('\uFFFD'))
    }
}
