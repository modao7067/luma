package io.github.modao7067.luma.gui.agent

import io.github.modao7067.luma.gui.session.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeCliProjectionTest {
    private val initial = SessionView(SessionSummary("local", "title", "noroot", "/workspace", cli = "pi"))
    @Test fun modeGroupsKeepNativeIdsNamesAndIndependentSelections() {
        val state = JSONObject("""{"permission":"off","permissions":[{"id":"off","label":"First off","configId":"first","configName":"First","selected":true},{"id":"off","label":"Second off","configId":"second","configName":"Second","selected":false},{"id":"on","label":"Second on","configId":"second","configName":"Second","selected":true}]}""")
        val choices = NativeCliProjection.view(state, initial).permissions
        assertEquals(listOf("off", "off", "on"), choices.map { it.id })
        assertEquals(listOf("first", "second", "second"), choices.map { it.configId })
        assertEquals(listOf("First", "Second", "Second"), choices.map { it.configName })
        assertEquals(listOf(true, false, true), choices.map { it.selected })
        val legacy = NativeCliProjection.view(JSONObject("""{"permissions":[{"id":"off","label":"Off"}]}"""), initial).permissions.single()
        assertEquals("", legacy.configId); assertNull(legacy.selected)
    }
    @Test fun nativePermissionsHaveNoSyntheticProfilesOrSpeed() {
        val s = JSONObject("""{"connected":true,"nativeId":"native","permissions":[],"models":[{"id":"provider/model","label":"Native","efforts":["off","high"],"supportsImages":false}],"capabilities":{"steer":true}}""")
        val view = NativeCliProjection.view(s, initial)
        assertTrue(view.permissions.isEmpty()); assertFalse(view.canOpenTui)
        assertFalse(view.capabilities.queue); assertTrue(view.capabilities.steer)
        assertFalse(view.capabilities.images); assertFalse(view.capabilities.compact)
        val model = NativeCliProjection.models(s).single()
        assertTrue(model.speeds.isEmpty()); assertEquals(listOf("off", "high"), model.efforts)
    }
    @Test fun dshPermissionMetadataKeepsActualNativeBundlesAndOldBridgeStaysEmpty() {
        val s = JSONObject("""{"permission":"danger-full-access","permissions":[{"id":"danger-full-access","label":"Native full","description":"Native warning","sandbox":"danger-full-access","approval":"never"}]}""")
        val projected = NativeCliProjection.view(s, initial)
        assertEquals("danger-full-access", projected.permission)
        assertEquals(RequestChoice("danger-full-access", "Native full", "Native warning", "danger-full-access", "never"), projected.permissions.single())
        assertTrue(NativeCliProjection.view(JSONObject(), projected).permissions.isEmpty())
        assertEquals("", NativeCliProjection.view(JSONObject(), projected).permission)
    }
    @Test fun deliveryReceiptIsSeparateFromUserAndToolExecutionStatus() {
        val s = JSONObject("""{"receipts":{"pending":"unknown","confirmed":"accepted"},"messages":[{"id":"pending","role":"user","text":"one","status":"completed"},{"id":"confirmed","role":"user","text":"two","status":"running"},{"id":"tool","role":"tool","status":"running"}]}""")
        val rows = NativeCliProjection.view(s, initial).messages
        assertEquals(listOf("pending", "", ""), rows.map { it.delivery })
        assertEquals("running", rows.last().outcome)
    }
    @Test fun reasoningAndToolsUseExistingTimelineContract() {
        val s = JSONObject("""{"messages":[{"id":"reason","role":"reasoning","kind":"reasoning","text":"Reasoning body","status":"completed"},{"id":"tool","role":"tool","kind":"tool","text":"read","input":"file.txt","outcome":"native output","status":"failed"}]}""")
        val rows = NativeCliProjection.view(s, initial).messages
        assertEquals("tool", rows[0].role); assertEquals("思考", rows[0].text)
        assertEquals("Reasoning body", rows[0].detail); assertEquals("succeeded", rows[0].outcome)
        assertEquals("native output", rows[1].detail); assertEquals("failed", rows[1].outcome)
        assertEquals("file.txt", rows[1].input)
    }
    @Test fun nativeToolMetadataProjectsFromBridgeSnapshotsWithoutChangingKindOrText() {
        val s = JSONObject("""{"messages":[{"id":"pi-call","role":"tool","kind":"tool","text":"read","toolName":"read","input":"file.txt","status":"running"},{"id":"custom-call","role":"tool","kind":"tool","text":"custom","toolName":"native/custom_tool","status":"completed"},{"id":"acp-call","role":"tool","kind":"tool","text":"Arbitrary native title","toolKind":"read","status":"in_progress"}]}""")
        val rows = NativeCliProjection.view(s, initial).messages
        assertEquals(listOf("read", "native/custom_tool", ""), rows.map { it.toolName })
        assertEquals(listOf("", "", "read"), rows.map { it.toolKind })
        assertTrue(rows.all { it.kind == "tool" }); assertEquals("Arbitrary native title", rows[2].text)
        s.getJSONArray("messages").getJSONObject(0).put("status", "completed").put("outcome", "native output")
        val completed = NativeCliProjection.view(s, initial.copy(messages = rows)).messages.first()
        assertEquals("read", completed.toolName); assertEquals("succeeded", completed.outcome)
        assertEquals("native output", completed.detail)
    }
    @Test fun absentOrNullMetadataIsEmptyWithoutGuessingFromLegacyTextOrInput() {
        val s = JSONObject("""{"messages":[{"id":"legacy","role":"tool","kind":"tool","text":"read","input":"cat file.txt","status":"completed"},{"id":"null","role":"tool","kind":"tool","text":"bash","toolName":null,"toolKind":null}]}""")
        val rows = NativeCliProjection.view(s, initial).messages
        assertTrue(rows.all { it.toolName.isEmpty() && it.toolKind.isEmpty() })
        val legacy = ConversationItem("old", "tool", "title", "output", "done", listOf("image"), "tool", "input", "succeeded")
        assertEquals("", legacy.toolName); assertEquals("", legacy.toolKind)
        assertEquals("succeeded", legacy.outcome); assertEquals(listOf("image"), legacy.images)
        val updated = legacy.copy(toolName = "native", toolKind = "read").copy(status = "completed", detail = "more output")
        assertEquals("native", updated.toolName); assertEquals("read", updated.toolKind)
    }
    @Test fun approvalOptionsAndSecretQuestionsStayNative() {
        val s = JSONObject("""{"requests":[{"id":"opaque","title":"Native request","choices":[{"id":"allow_one","label":"允许一次"},{"id":"reject","label":"拒绝"}],"questions":[{"id":"key","header":"Code","prompt":"Enter","secret":true}]}]}""")
        val request = NativeCliProjection.view(s, initial).requests.single()
        assertEquals(listOf("allow_one", "reject"), request.choices.map { it.id })
        assertTrue(request.questions.single().secret)
    }
    @Test fun nativeSignInUrlIsAnEphemeralRequestNotAnApprovalSuccess() {
        val s = JSONObject("""{"connecting":true,"connected":false,"requests":[{"id":"antigravity-auth-browser","title":"Native sign-in","body":"Waiting for official ACP","externalUrl":"https://accounts.google.com/o/oauth2/auth?redirect_uri=http%3A%2F%2F127.0.0.1%3A45678%2F","choices":[{"id":"cancel","label":"Cancel"}]}]}""")
        val view = NativeCliProjection.view(s, initial)
        assertTrue(view.loading); assertFalse(view.connected)
        assertEquals("https://accounts.google.com/o/oauth2/auth?redirect_uri=http%3A%2F%2F127.0.0.1%3A45678%2F", view.requests.single().externalUrl)
        assertEquals(listOf("cancel"), view.requests.single().choices.map { it.id })
        assertTrue(NativeCliProjection.view(JSONObject(), view).requests.isEmpty())
        assertNull(UserRequest("legacy", "title", "body").externalUrl)
    }
    @Test fun queueActionsDefaultToReadOnlyAndMetricsAreNotInvented() {
        val s = JSONObject("""{"queue":[{"id":"a","text":"pending","status":"待执行"}],"running":true,"nativeId":"native"}""")
        val v = NativeCliProjection.view(s, initial)
        assertEquals("native",v.activeTurnId);assertTrue(v.metrics.isEmpty())
        assertFalse(v.queue.single().canEdit);assertFalse(v.queue.single().canRemove)
        assertFalse(v.queue.single().canSteer)
    }
    @Test fun claudeMcpProjectionExposesOnlyNamesAndStatus() {
        val s = JSONObject("""{"mcpAvailable":true,"mcp":[{"name":"native-server","detail":"connected","config":{"env":{"TOKEN":"private"}},"headers":{"Authorization":"private"}}]}""")
        val entry = NativeCliProjection.mcp(s).single()
        assertEquals("native-server", entry.name); assertEquals("connected", entry.detail); assertNull(entry.path)
    }
    @Test fun catalogKeepsNativeBridgesAndTheLegacyDefault() {
        assertEquals("Codex", SessionRecord("x","t","noroot","/workspace").cli)
        assertEquals(CliCatalog.ids.filterNot { it in setOf("Codex", "OpenCode") }.toSet(), CliCatalog.bridged)
        assertTrue(CliCatalog.ids.containsAll(listOf("Codex","OpenCode")))
        assertTrue(CliCatalog.ids.contains("Antigravity"))
        assertTrue(CliCatalog.bridged.containsAll(listOf("Qoder CLI", "Grok Build")))
    }
    @Test fun nativeAgentSnapshotPreservesStableIdentityAndAbsentOldBridgeIsEmpty() {
        val state = JSONObject("""{"subAgents":[{"id":"child","itemId":"tool-launch","role":"explore","status":"failed","nativeStatus":"errored","detail":"error"}]}""")
        val projected = NativeCliProjection.view(state, initial)
        assertEquals("child", projected.subAgents.single().id)
        assertEquals("tool-launch", projected.subAgents.single().itemId)
        assertEquals("failed", projected.subAgents.single().status)
        assertTrue(projected.subAgents.single().messages.isEmpty())
        assertTrue(NativeCliProjection.view(JSONObject(), projected).subAgents.isEmpty())
    }
    @Test fun antigravityChildTranscriptStaysOnThatSubAgent() {
        val state = JSONObject("""{"inputEvidenceProtocol":1,"subAgents":[{"id":"child-native","name":"researcher","role":"helper","status":"running","detail":"finished note","messages":[{"id":"agy:child-native:2:PLANNER_RESPONSE:text","role":"assistant","text":"finished note","status":"completed"}]}]}""")
        val agent = NativeCliProjection.view(state, initial).subAgents.single()
        assertEquals("researcher", agent.name)
        assertEquals("finished note", agent.detail)
        assertEquals("finished note", agent.messages.single().text)
        assertEquals("succeeded", agent.messages.single().outcome)
    }
    @Test fun streamedChildModelFlowsFromSnapshotToDetailWithoutParentModelSubstitution() {
        val snapshot = JSONObject("""{"subAgents":[{"id":"child","model":"native/child","messages":[]}]}""")
        val agent = NativeCliProjection.view(snapshot, initial).subAgents.single()
        val previous = SubAgentDetailView("parent", agent, modelLabel = "Wrong parent model", effort = "high", loading = true)
        val detail = NativeCliProjection.streamedSubAgentDetail(agent, previous,
            listOf(ModelOption("native/child", "Native Child Model", emptyList())))
        assertEquals("native/child", agent.model)
        assertEquals("Native Child Model", detail.modelLabel)
        assertEquals("", detail.effort); assertFalse(detail.loading)
        assertEquals("flash", NativeCliProjection.streamedSubAgentDetail(agent.copy(model = "flash"), previous, emptyList()).modelLabel)
        assertEquals("继承父代理", NativeCliProjection.streamedSubAgentDetail(agent.copy(model = "inherit"), previous, emptyList()).modelLabel)
        val old = NativeCliProjection.view(JSONObject("""{"subAgents":[{"id":"child"}]}"""), initial).subAgents.single()
        assertEquals("", old.model)
        assertEquals("", NativeCliProjection.streamedSubAgentDetail(old, previous, emptyList()).modelLabel)
    }
    @Test fun elicitationQuestionsPreserveAllowOtherAndRequiredFlags() {
        val snapshot = JSONObject("""{"cli":"Antigravity","requests":[{"id":"900","title":"Answer","body":"The agent has 2 questions.","submitChoice":"accept","skipChoice":"decline","choices":[{"id":"accept","label":"提交"},{"id":"decline","label":"不回答"},{"id":"cancel","label":"终止提问"}],"questions":[{"id":"q1","header":"Color?","prompt":"Color?","options":["Red","Blue"],"multiple":false,"allowOther":true,"required":true},{"id":"q2","header":"Strict?","prompt":"Strict?","options":["Yes","No"],"multiple":false,"allowOther":false,"required":false}]}]}""")
        val req = NativeCliProjection.view(snapshot, initial).requests.single()
        assertEquals("accept", req.submitChoice)
        assertEquals("decline", req.skipChoice)
        assertEquals("终止提问", req.choices.last().label)
        assertEquals(listOf("Red", "Blue"), req.questions[0].options)
        assertTrue(req.questions[0].allowOther)
        assertTrue(req.questions[0].required)
        assertFalse(req.questions[1].allowOther)
        assertFalse(req.questions[1].required)
    }
    @Test fun unchangedRowsPreserveReferentialIdentityAcrossSnapshots() {
        val s1 = JSONObject("""{"messages":[{"id":"m1","role":"user","text":"hi","status":"completed"},{"id":"m2","role":"assistant","text":"part1","status":"running"}],"subAgents":[{"id":"sub1","status":"running","messages":[{"id":"c1","role":"assistant","text":"ok","status":"completed"}]}]}""")
        val v1 = NativeCliProjection.view(s1, initial)
        val v1Again = NativeCliProjection.view(s1, v1)
        assertSame(v1.messages, v1Again.messages)
        assertSame(v1.subAgents, v1Again.subAgents)
        val s2 = JSONObject("""{"messages":[{"id":"m1","role":"user","text":"hi","status":"completed"},{"id":"m2","role":"assistant","text":"part1+part2","status":"running"}],"subAgents":[{"id":"sub1","status":"running","messages":[{"id":"c1","role":"assistant","text":"ok","status":"completed"}]}]}""")
        val v2 = NativeCliProjection.view(s2, v1)
        assertSame(v1.messages[0], v2.messages[0])
        assertNotSame(v1.messages[1], v2.messages[1])
        assertEquals("part1+part2", v2.messages[1].text)
        assertSame(v1.subAgents, v2.subAgents)
    }

    @Test fun piAndOmpStripTrailingImageProcessNotesWithoutTouchingNormalText() {
        val note = "[Image: original 2000x1500, displayed at 800x600. Multiply coordinates by 2.50 to map to original image.]"
        val converted = "[Image converted from image/webp to image/png.]"
        val omitted = "[Image omitted: unsupported format.]"
        val snapshot = JSONObject()
            .put("cli", "pi")
            .put("messages", org.json.JSONArray()
                .put(JSONObject().put("id", "u1").put("role", "user").put("text", "看一下这张图\n\n$converted\n\n$note"))
                .put(JSONObject().put("id", "u2").put("role", "user").put("text", "\n\n$note"))
                .put(JSONObject().put("id", "u3").put("role", "user").put("text", "普通括号 [Image: 用户自写] 和\n\n$omitted\n\n结尾正文"))
                .put(JSONObject().put("id", "a1").put("role", "assistant").put("text", "回复保留\n\n$note")))
            .put("queue", org.json.JSONArray()
                .put(JSONObject().put("id", "q1").put("text", "排队消息\n\n$note")))
        val projected = NativeCliProjection.view(snapshot, initial)
        assertEquals("看一下这张图", projected.messages[0].text)
        assertEquals("", projected.messages[1].text)
        assertEquals("普通括号 [Image: 用户自写] 和\n\n$omitted\n\n结尾正文", projected.messages[2].text)
        assertEquals("回复保留\n\n$note", projected.messages[3].text)
        assertEquals("排队消息", projected.queue.single().text)

        val claudeSnapshot = JSONObject(snapshot.toString()).put("cli", "Claude Code")
        assertEquals("看一下这张图\n\n$converted\n\n$note", NativeCliProjection.view(claudeSnapshot, initial).messages[0].text)
    }

    @Test fun planAxisSkipPermissionsAndPermissionNoticeProjectCleanlyWithoutStaticNotice() {
        val defaults = NativeCliProjection.view(JSONObject("""{"notice":"Static bridge intro"}"""), initial)
        assertNull(defaults.capabilityNotice)
        assertFalse(defaults.planModeSupported)
        assertFalse(defaults.planMode)
        assertFalse(defaults.skipPermissionsActive)

        val dual = NativeCliProjection.view(
            JSONObject("""{"planModeSupported":true,"planMode":true,"skipPermissionsActive":true,"notice":"Non-permission notice","permissionNotice":"Permissions unavailable"}"""),
            initial,
        )
        assertEquals("Permissions unavailable", dual.capabilityNotice)
        assertTrue(dual.planModeSupported)
        assertTrue(dual.planMode)
        assertTrue(dual.skipPermissionsActive)

        val questionSnapshot = JSONObject("""{"cli":"Kimi Code","messages":[{"id":"th1","role":"tool","kind":"reasoning","text":"Deep reasoning body","status":"completed"}],"requests":[{"id":"ask","title":"Answer","body":"","choices":[{"id":"submit","label":"提交"},{"id":"cancel","label":"终止提问"}],"submitChoice":"submit","skipChoice":"cancel","questions":[{"id":"q_0","header":"Ask测试","prompt":"你看到这个问题了吗？","options":["看到了","没看到"],"optionValues":["opt_0_0","opt_0_1"],"multiple":false,"allowOther":true,"required":true}]}]}""")
        val projectedView = NativeCliProjection.view(questionSnapshot, initial)
        assertEquals("思考", projectedView.messages.single().text)
        assertEquals("Deep reasoning body", projectedView.messages.single().detail)
        val req = projectedView.requests.single()
        assertEquals("Answer", req.title)
        assertEquals(listOf("看到了", "没看到"), req.questions.single().options)
        assertEquals(listOf("opt_0_0", "opt_0_1"), req.questions.single().optionValues)
    }

}
