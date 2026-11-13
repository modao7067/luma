package io.github.modao7067.luma.gui.runtime

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference

class MediaByteReaderTest {
    private fun temp() = Files.createTempDirectory("luma-media-test").toFile()
    private fun read(source: String, cwd: String = "/child 中文", destination: File, guest: (String, File, Long) -> Unit = { _, _, _ -> error("Unexpected guest IO") }): String =
        MediaByteReader.read(source, cwd, destination, "application/octet-stream", { null }, guest)
    @Test fun localPathsNeverBecomeAndroidPathsAndRelativePathsUseTheirOwner() {
        val dir = temp()
        try {
            val paths = listOf("/tmp/图 像.png", "file:///tmp/%E5%9B%BE%20%E5%83%8F.png", "sandbox:/tmp/图 像.png", "../out/图 像.png")
            val expected = listOf("/tmp/图 像.png", "/tmp/图 像.png", "/tmp/图 像.png", "/out/图 像.png")
            paths.forEachIndexed { i, source ->
                val file = File(dir, "$i")
                read(source, destination = file) { path, target, limit ->
                    assertEquals(expected[i], path); assertEquals(MediaSourcePolicy.MAX_BYTES, limit); target.writeText("guest bytes")
                }
                assertEquals("guest bytes", file.readText())
            }
        } finally { dir.deleteRecursively() }
    }
    @Test fun fileAuthorityAndUnknownSchemesAreNotReadAsLocalFiles() {
        listOf("file://remote/tmp/a", "mcp-resource:private", "codex-file:file-anything", "luma-error:附件不可读取").forEach { source ->
            val dir=temp()
            try { try { read(source, destination=File(dir,"out")); fail(source) } catch (_: Exception) {} }
            finally { dir.deleteRecursively() }
        }
    }
    @Test fun inlineBase64AndPercentBytesPreserveBinaryAndChinese() {
        val dir = temp()
        try {
            val first = File(dir,"base64")
            assertEquals("image/png", read("data:image/png;base64,AAECA/8=", destination=first))
            assertArrayEquals(byteArrayOf(0,1,2,3,-1),first.readBytes())
            val second=File(dir,"text")
            read("data:text/plain,%E4%B8%AD%E6%96%87+%20text", destination=second)
            assertEquals("中文+ text",second.readText())
            listOf("data:image/png;base64,bad!@", "data:text/plain,%GG", "data:image/png").forEach { bad ->
                try { read(bad,destination=File(dir,"bad")); fail(bad) } catch (_: Exception) {}
            }
        } finally { dir.deleteRecursively() }
    }
    @Test fun localHttpHasNoCliAuthorizationAndFollowsOnlyBoundedHttpRedirects() {
        val seen=AtomicReference<String?>();val pathSeen=AtomicReference<String?>()
        val server=ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val serving=Thread {
            while (!server.isClosed) {
                try { server.accept().use { socket ->
                    socket.soTimeout=5000
                    val input=socket.getInputStream().bufferedReader()
                    val request=input.readLine().split(' ')[1]
                    val headers=mutableListOf<String>()
                    while (true) { val header=input.readLine() ?: break; if (header.isEmpty()) break; headers+=header }
                    seen.set(headers.firstOrNull { it.startsWith("Authorization:",true) })
                    val path=URI(request).path;pathSeen.set(path)
                    val response=when(path) {
                        "/redirect" -> "HTTP/1.1 302 Found\r\nLocation: /%E5%9B%BE%20%E5%83%8F.pdf\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        "/short" -> "HTTP/1.1 200 OK\r\nContent-Length: 40\r\nConnection: close\r\n\r\nshort"
                        "/loop" -> "HTTP/1.1 302 Found\r\nLocation: /loop\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        else -> "HTTP/1.1 200 OK\r\nContent-Type: application/pdf\r\nContent-Length: 12\r\nConnection: close\r\n\r\n%PDF-fixture"
                    }
                    socket.getOutputStream().write(response.toByteArray())
                } } catch (_: java.io.IOException) { if (server.isClosed) break }
            }
        }.apply { isDaemon=true;start() }
        val dir=temp()
        try {
            val address="http://127.0.0.1:${server.localPort}"
            assertEquals("application/pdf",read("$address/redirect",destination=File(dir,"pdf")))
            assertNull(seen.get());assertEquals("/图 像.pdf",pathSeen.get())
            try { read("$address/loop",destination=File(dir,"loop"));fail("redirect loop") } catch (_: java.io.IOException) {}
            try { read("$address/short",destination=File(dir,"short"));fail("truncated response accepted") } catch (_: java.io.IOException) {}
        } finally { server.close();serving.join(1000);dir.deleteRecursively() }
    }
    @Test fun documentContextAndHttpPolicyArePreservedWithoutNetworkFallback() {
        assertEquals("/child/docs",MediaSourcePolicy.documentDirectory("docs/readme.md","/child"))
        assertEquals("https://example.test/docs/",MediaSourcePolicy.documentDirectory("https://example.test/docs/readme.md","/child"))
        assertEquals("https://example.test/docs/",MediaSourcePolicy.documentDirectory("docs/readme.md","https://example.test/"))
        assertEquals("中文 file.pdf",MediaSourcePolicy.name("file:/tmp/中文%20file.pdf"))
        assertEquals("https://example.test/docs/image%20one.png",MediaSourcePolicy.relativeHttp("image one.png","https://example.test/docs/"))
        val dir=temp()
        try {
            try {
                MediaByteReader.read("http://example.invalid/file.pdf",null,File(dir,"blocked"),"application/pdf",{ null },{_,_,_->fail("guest fallback")},allowHttp={ false })
                fail("cleartext policy bypassed")
            } catch (error:java.io.IOException) { assertTrue(error.message!!.contains("HTTP 明文地址被应用安全策略阻止")) }
            assertFalse(File(dir,"blocked").exists())
        } finally {dir.deleteRecursively()}
    }
    @Test fun boundedCopyRejectsGrowthAndInterruption() {
        val dir=temp()
        val stream=object:InputStream(){ var remaining=MediaSourcePolicy.MAX_BYTES+1
            override fun read()=if(remaining-->0)0 else -1
            override fun read(b:ByteArray,off:Int,len:Int):Int {if(remaining==0L)return -1;val n=minOf(remaining,len.toLong()).toInt();remaining-=n;return n}
        }
        try {
            try { MediaByteReader.copy(stream,File(dir,"big"));fail("oversized") } catch (_: java.io.IOException) {}
            Thread.currentThread().interrupt()
            try { MediaByteReader.copy("bytes".byteInputStream(),File(dir,"cancel"));fail("interruption") } catch (_: InterruptedException) {}
            finally { Thread.interrupted() }
        } finally { dir.deleteRecursively() }
    }
    @Test fun mimeIsDetectedFromBytesAndUnknownDoesNotBecomeText() {
        assertEquals("image/png",MediaSourcePolicy.sniff(byteArrayOf(-119,80,78,71,13,10,26,10),"image/*"))
        assertEquals("image/svg+xml",MediaSourcePolicy.sniff("<?xml version='1.0'?><svg xmlns='http://www.w3.org/2000/svg'/>".toByteArray(),"image/*"))
        assertEquals("application/pdf",MediaSourcePolicy.sniff("%PDF-1.7".toByteArray(),"image/*"))
        assertEquals("application/octet-stream",MediaSourcePolicy.mime("unknown.binary"))
        assertFalse(MediaSourcePolicy.isText(MediaSourcePolicy.mime("unknown.binary")))
        assertTrue(MediaSourcePolicy.isText(MediaSourcePolicy.mime("source.kt")))
    }
    @Test fun streamingInlineDataPreservesUnicodeAndRejectsTrailingBase64Garbage() {
        val dir=temp()
        try {
            val output=File(dir,"unicode")
            read("data:text/plain,中文😀+%20end",destination=output)
            assertEquals("中文😀+ end",output.readText())
            try{read("data:application/octet-stream;base64,QQ==garbage",destination=File(dir,"invalid"));fail("trailing bytes accepted")}catch(_:java.io.IOException){}
        }finally{dir.deleteRecursively()}
    }
    @Test fun exportLimitIsForwardedToGuestWithoutChangingTheDefaultPreviewLimit() {
        val dir=temp()
        try {
            MediaByteReader.read("asset.apk","/child",File(dir,"export"),"application/vnd.android.package-archive",{null},{path,file,limit->
                assertEquals("/child/asset.apk",path);assertEquals(MediaDownloads.MAX_BYTES,limit);file.writeText("opaque bytes")
            },limit=MediaDownloads.MAX_BYTES)
        }finally{dir.deleteRecursively()}
    }

}
