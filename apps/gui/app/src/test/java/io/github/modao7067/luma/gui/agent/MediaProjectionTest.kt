package io.github.modao7067.luma.gui.agent

import io.github.modao7067.luma.gui.session.*
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class MediaProjectionTest {
    @Test fun codexActualNativeShapesAreIdenticalForHistoryAndLiveProjection() {
        val cases=listOf(
            obj("type" to "userMessage","content" to JSONArray().put(obj("type" to "image","fileId" to "file-ref"))),
            obj("type" to "imageView","path" to "/tmp/别处.png"),
            obj("type" to "imageGeneration","savedPath" to "/child/生成.png","result" to "ignored","fileId" to "file-ref"),
            obj("type" to "imageGeneration","result" to "AA=="),
            obj("type" to "imageGeneration","result" to "","fileId" to "file-native"),
            obj("type" to "dynamicToolCall","contentItems" to JSONArray().put(obj("type" to "inputImage","imageUrl" to "data:image/png;base64,AA==")).put(obj("type" to "inputAudio","audioUrl" to "https://test/a.wav"))),
            obj("type" to "mcpToolCall","result" to obj("content" to JSONArray().put(obj("type" to "image","mimeType" to "image/png","data" to "AA==")).put(obj("type" to "resource_link","uri" to "file:///child/a.pdf","mimeType" to "application/pdf"))))
        )
        cases.forEachIndexed { i, raw ->
            raw.put("id","id-$i")
            val history=CodexProjection.item(raw);val live=CodexProjection.item(raw,history)
            assertEquals(history.media,live.media);assertFalse(history.media.isEmpty())
        }
        assertEquals("/child/生成.png",CodexProjection.item(cases[2]).media.single().source)
        assertEquals("codex-file:file-native",CodexProjection.item(cases[4]).media.single().source)
        assertEquals(2,CodexProjection.item(cases[5]).media.size)
        assertEquals(2,CodexProjection.item(cases[6]).media.size)
    }
    @Test fun mcpResourceReadsRetainOriginatingCallThreadAndExactUri() {
        val raw=CodexProjection.item(obj("id" to "call-a","type" to "mcpToolCall","server" to "native-server", "result" to obj("content" to JSONArray().put(obj("type" to "resource_link","uri" to "mcp://scope/file.pdf","mimeType" to "application/pdf")))))
        val resource=raw.withMediaContext("gui-parent","/child","native-child").media.single()
        val params=MediaProjection.mcpParams(resource.source)!!
        assertEquals("call-a",params.getString("originCallId"));assertEquals("native-child",params.getString("threadId"))
        assertEquals("native-server",params.getString("server"));assertEquals("mcp://scope/file.pdf",params.getString("uri"))
        assertEquals("gui-parent",resource.sessionId)
    }
    @Test fun scopeDoesNotDropImagesAndSourcesDoNotDuplicateOrCrossSessions() {
        val source="file:///tmp/图%20像.png"
        val raw=ConversationItem("id","user",images=listOf(source),media=listOf(MediaResource(source)))
        val first=raw.withMediaContext("parent","/workspace")
        assertEquals(listOf(source),first.images);assertEquals(1,first.media.size)
        assertEquals("parent",first.media.single().sessionId)
        val child=raw.copy(media=listOf(MediaResource(source,cwd="/child"))).withMediaContext("parent","/workspace")
        assertEquals("/child",child.media.single().cwd)
        assertEquals("other",raw.withMediaContext("other","/other").media.single().sessionId)
    }
    @Test fun rawCommandOutputIsNotAnAttachmentAndDisplayDoesNotDuplicateLargeBase64() {
        assertTrue(MediaProjection.codex(obj("type" to "commandExecution","aggregatedOutput" to "wrote /tmp/x.png")).isEmpty())
        val data="A".repeat(2048)
        val item=CodexProjection.item(obj("id" to "mcp","type" to "mcpToolCall","result" to obj("content" to JSONArray().put(obj("type" to "image","data" to data,"mimeType" to "image/png")))))
        assertTrue(item.media.single().source.endsWith(data));assertFalse(item.detail.contains(data))
    }
    @Test fun openCodeNativeFilePartsAndToolContentRetainTypesAndSize() {
        val user=OpenCodeProjection.message(obj("id" to "u","type" to "user","text" to "","files" to JSONArray().put(obj("uri" to "file:///tmp/p.pdf","name" to "p.pdf","mime" to "application/pdf"))))
        assertEquals("application/pdf",user.single().media.single().mimeType)
        assertEquals("/child",OpenCodeProjection.cwd(obj("location" to obj("directory" to "/child")),"/parent"))
        val message=obj("id" to "a","type" to "assistant","content" to JSONArray().put(obj("type" to "tool","id" to "t","state" to obj("content" to JSONArray().put(obj("type" to "resource_link","uri" to "/child/file.zip","sizeBytes" to 123))))))
        assertEquals(123L,OpenCodeProjection.message(message).single().media.single().sizeBytes)
    }
}
