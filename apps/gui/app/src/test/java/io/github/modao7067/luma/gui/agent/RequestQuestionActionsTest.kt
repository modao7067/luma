package io.github.modao7067.luma.gui.agent

import io.github.modao7067.luma.gui.session.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RequestQuestionActionsTest {
    @Test fun codexNativeSkipIsExactlyEmptyAnswersEvenIfCallerSuppliesASelection() {
        val pending=PendingApproval(7,"item/tool/requestUserInput",obj("questions" to JSONArray().put(obj("id" to "q","header" to "h","question" to "prompt"))))
        assertEquals("skip",pending.view().skipChoice)
        assertEquals("Answer",pending.view().title)
        assertEquals(0,pending.response("skip",mapOf("q" to listOf("must not leak"))).getJSONObject("answers").length())
        assertEquals("original",pending.response("submit",mapOf("q" to listOf("original"))).getJSONObject("answers").getJSONObject("q").getJSONArray("answers").getString(0))
        val approval=PendingApproval(9,"item/commandExecution/requestApproval",obj("availableDecisions" to JSONArray(listOf("accept","cancel"))))
        assertNull(approval.view().skipChoice);assertEquals("cancel",approval.response("1",emptyMap()).getString("decision"))
    }
    @Test fun asyncNativeQuestionsDoNotClaimAnUnsupportedEmptyAnswerProtocol() {
        val item=CodexProjection.item(obj("id" to "native","type" to "agentMessage","delivery" to "async","questions" to JSONArray().put(obj("title" to "prompt","options" to JSONArray(listOf("one","two"))))))
        val request=CodexAsyncQuestions.pending(listOf(item)).single()
        assertEquals("Answer",request.title)
        assertNull(request.skipChoice)
        try{CodexAsyncQuestions.reply(request,emptyMap());fail("fake async skip") }catch(_:IllegalArgumentException){}
    }
    private fun view(cli:String,ids:List<String>,fields:Map<String,Any?> = emptyMap()):UserRequest {
        val r=obj("id" to "r","title" to "question","questions" to JSONArray().put(obj("id" to "q","prompt" to "prompt")),
            "choices" to JSONArray(ids.map{obj("id" to it,"label" to it)}))
        fields.forEach{(k,v)->r.put(k,v ?: JSONObject.NULL)}
        return NativeCliProjection.view(obj("cli" to cli,"requests" to JSONArray().put(r)),SessionView(SessionSummary("l","fixture","noroot","/workspace"))).requests.single()
    }
    @Test fun knownLegacyProducersKeepExactNativeActionsWithoutRequiringAServiceUpgrade() {
        for((cli,submit,skip) in listOf(Triple("Claude Code","allow","deny"),Triple("Grok Build","accept","cancel"),Triple("Kimi Code","accept","decline"),Triple("pi","submit","cancel"))){
            val request=view(cli,listOf(submit,skip));assertEquals(submit,request.submitChoice);assertEquals(skip,request.skipChoice)
        }
        assertEquals("终止提问",view("Kimi Code",listOf("accept","decline","cancel")).choices.last().label)
    }
    @Test fun newProducerActionMetadataWinsAndUnknownActionsAreNotGuessedFromLabels() {
        val known=view("Qoder CLI",listOf("native-allow","native-reject"),mapOf("submitChoice" to "native-allow","skipChoice" to "native-reject"))
        assertEquals("native-allow",known.submitChoice);assertEquals("native-reject",known.skipChoice)
        val unknown=view("other",listOf("Allow","Cancel"));assertNull(unknown.submitChoice);assertNull(unknown.skipChoice)
    }
    @Test fun defaultQuestionTitlesBecomeAnswerWithoutReplacingCustomQuestionsOrApprovalTitles() {
        for((cli,title) in listOf("Claude Code" to "Claude Code 提问","Kimi Code" to "Kimi Code 提问","Grok Build" to "问题","pi" to "CLI 请求","other" to "需要你的回答")) {
            assertEquals("Answer",view(cli,listOf("allow","cancel"),mapOf("title" to title)).title)
            assertEquals(title,view(cli,listOf("allow","cancel"),mapOf("title" to title,"questions" to JSONArray())).title)
            assertEquals("Project-specific question",view(cli,listOf("allow","cancel"),mapOf("title" to "Project-specific question")).title)
        }
        assertEquals("CLI 请求",view("pi",listOf("submit","cancel"),mapOf("title" to "CLI 请求","submitChoice" to "submit","skipChoice" to "cancel")).title)
        val form=obj("id" to "f","fields" to JSONArray().put(obj("key" to "q","type" to "string")))
        assertEquals("Answer",OpenCodeProjection.form(form).title)
        form.put("title","Project-specific form");assertEquals("Project-specific form",OpenCodeProjection.form(form).title)
    }
}
