package io.github.modao7067.luma.gui.agent

import io.github.modao7067.luma.gui.session.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UserImageProjectionTest {
    private val bodies = listOf("看一下这张图。", "", "[图片]\n[image]")
    private fun input(body: String, image: JSONObject) = JSONArray().apply {
        if (body.isNotEmpty()) put(obj("type" to "text", "text" to body))
        put(image)
    }
    private fun raw(body: String, image: JSONObject) = obj("id" to "native", "clientUserMessageId" to "client",
        "type" to "userMessage", "content" to input(body, image))

    @Test fun codexLiveAndHistorySeparateImagesFromRealTextWithoutRewritingLiterals() {
        val images = listOf(obj("type" to "localImage", "path" to "/tmp/p.png"),
            obj("type" to "image", "url" to "https://example.test/p.png"),
            obj("type" to "image", "fileId" to "native-file"))
        for (image in images) for (body in bodies) {
            val history = CodexProjection.item(raw(body, image))
            val live = CodexProjection.item(raw(body, image), history)
            for (row in listOf(history, live)) {
                assertEquals("client", row.id)
                assertEquals(body, row.text)
                assertEquals(body, row.userContent!!.text)
                assertEquals(1, row.withMediaContext("local", "/tmp").media.size)
            }
        }
    }

    @Test fun unavailableImageKeepsFallbackWhileResolvedButUnreadableImageKeepsItsMediaCard() {
        for (type in listOf("image", "localImage")) {
            val row = CodexProjection.item(raw("正文", obj("type" to type)))
            assertEquals("正文\n[图片]", row.text)
            assertTrue(row.media.isEmpty())
        }
        val unreadable = CodexProjection.item(raw("", obj("type" to "localImage", "path" to "/missing.png")))
        assertEquals("", unreadable.text)
        assertEquals("/missing.png", unreadable.media.single().source)
    }

    @Test fun mentionsSkillsAndQueueSummariesKeepTheirExistingContracts() {
        val inputs = input("[图片] @/tmp/a.txt", obj("type" to "localImage", "path" to "/tmp/p.png"))
            .put(obj("type" to "mention", "path" to "/tmp/a.txt"))
            .put(obj("type" to "skill", "name" to "Review", "path" to "/skills/review"))
        val row = CodexProjection.item(obj("id" to "native", "type" to "userMessage", "content" to inputs))
        assertEquals("[图片] @/tmp/a.txt\n@/tmp/a.txt", row.text)
        assertEquals("[图片] @/tmp/a.txt", row.userContent!!.text)
        assertEquals(3, row.userContent!!.attachments.size)
        assertEquals("[图片] @/tmp/a.txt\n[图片]\n@/tmp/a.txt", CodexProjection.inputText(inputs))
    }

    @Test fun nativeAsyncAnswerDisplayStillUsesTheQuestionnaireProjection() {
        val question = CodexProjection.item(obj("id" to "q", "type" to "agentMessage", "delivery" to "async",
            "questions" to JSONArray().put(obj("title" to "Which?", "options" to JSONArray(listOf("A", "B"))))))
        val request = CodexAsyncQuestions.pending(listOf(question)).single()
        val wire = CodexAsyncQuestions.reply(request, mapOf(request.questions.single().id to listOf("A")))
        val row = CodexProjection.item(raw(wire, obj("type" to "localImage", "path" to "/tmp/p.png")))
        assertEquals(CodexAsyncQuestions.displayReply(wire), row.text)
        assertEquals(wire, row.userContent!!.text)
    }

    @Test fun openCodeAndAllBridgeSnapshotConsumersKeepTextAndMediaSeparate() {
        for (body in bodies) {
            val openCode = OpenCodeProjection.message(obj("id" to "native", "type" to "user", "text" to body,
                "files" to JSONArray().put(obj("uri" to "/tmp/p.png", "mime" to "image/png")))).single()
            assertEquals(body, openCode.text); assertEquals(1, openCode.media.size)
            val openCodeV2 = OpenCodeProjection.message(obj("id" to "native-v2", "type" to "user", "text" to body,
                "files" to JSONArray().put(obj("data" to "AAEC", "mime" to "image/jpeg", "name" to "shot.jpg",
                    "source" to obj("type" to "uri", "uri" to "file:///tmp/shot.jpg"))))).single()
            assertEquals(body, openCodeV2.text); assertEquals(1, openCodeV2.media.size)
            assertEquals("file:///tmp/shot.jpg", openCodeV2.media.single().source)
            for (cli in CliCatalog.bridged) {
                val initial = SessionView(SessionSummary("local", "", "root", "/tmp", cli = cli))
                val row = NativeCliProjection.view(obj("cli" to cli, "receipts" to obj("client" to "unknown"),
                    "messages" to JSONArray().put(obj("id" to "client", "role" to "user", "text" to body,
                        "media" to JSONArray().put(obj("source" to "/tmp/p.png", "mimeType" to "image/png"))))), initial).messages.single()
                assertEquals(cli, body, row.text); assertEquals(1, row.media.size)
                assertEquals("client", row.id); assertEquals("pending", row.delivery)
            }
        }
    }

    @Test fun allCliLocalOutboxEchoesKeepOriginalBodyAttachmentAndUnknownIdentity() {
        for (cli in CliCatalog.ids) for (body in bodies) {
            val record = SessionRecord("local", "", "root", "/tmp", cli = cli, draft = body,
                images = mutableListOf(StoredImage("image", "file:///tmp/p.png", "/tmp/p.png", "p.png")))
            SubmissionLedger.begin(record, "client")
            SubmissionLedger.state(record, "client", "unknown")
            val row = SubmissionLedger.messages(record, emptyList()).single()
            assertEquals(cli, body, row.text); assertEquals("client", row.id)
            assertEquals(listOf("file:///tmp/p.png"), row.images); assertEquals("pending", row.delivery)
        }
    }
}
