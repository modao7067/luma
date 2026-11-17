package io.github.modao7067.luma.gui.ui

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.modao7067.luma.gui.session.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class RequestActionsInteractionTest {
    @get:Rule val rule = createComposeRule()
    private data class Answer(val choice:String,val answers:Map<String,List<String>>)
    private val sent=mutableListOf<Answer>();private var cancelled=0
    private var ready by mutableStateOf(true)
    private var request by mutableStateOf(UserRequest("q","Answer","The agent has a question.",
        choices=listOf(RequestChoice("native-submit","提交"),RequestChoice("native-skip","跳过"),RequestChoice("cancel","终止提问")),
        questions=listOf(RequestQuestion("q","选择","请选择方式",listOf("默认","其它"),defaultSelection=listOf("默认"),allowOther=false)),
        submitChoice="native-submit",skipChoice="native-skip"))
    private var backDispatcher:OnBackPressedDispatcher?=null
    private fun show() {
        ShadowDialog.reset()
        rule.setContent {
            backDispatcher=LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            MaterialTheme {
                RequestDialog(request,object:CockpitActions by previewActions {
                    override fun answer(requestId:String,choice:String,answers:Map<String,List<String>>) { sent+=Answer(choice,answers) }
                },canRespond=ready) { cancelled++ }
            }
        }
        rule.runOnIdle { assertNull(ShadowDialog.getLatestDialog()) }
    }
    private fun click(text:String)=rule.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick){it()}
    private fun dismissSheetViaBack() {
        rule.runOnIdle { backDispatcher!!.onBackPressed() }
        rule.waitForIdle()
    }
    @Test fun skipNeverIncludesDefaultSelectionAndSuppressesRedundantCancelAndBoilerplate() {
        show()
        rule.onNodeWithText("取消").assertDoesNotExist()
        rule.onNodeWithText("终止提问").assertDoesNotExist()
        rule.onNodeWithText("The agent has a question.").assertDoesNotExist()
        dismissSheetViaBack()
        rule.runOnIdle{assertEquals(1,cancelled);assertTrue(sent.isEmpty())}
        click("跳过");rule.runOnIdle{assertEquals(Answer("native-skip",emptyMap()),sent.single())}
    }
    @Test fun submitUsesTheNativeActionAndOriginalEnteredAnswer() {
        show();click("其它");click("提交")
        rule.runOnIdle{assertEquals(Answer("native-submit",mapOf("q" to listOf("其它"))),sent.single())}
        rule.onNodeWithText("先查看对话").assertDoesNotExist();rule.onNodeWithText("提交回答").assertDoesNotExist()
    }
    @Test fun skipAndSubmitKeepConnectionAndAnsweringGuardsWhileSheetDismissClosesLocally() {
        show();rule.runOnIdle{ready=false}
        rule.onNodeWithText("跳过").assertIsNotEnabled();rule.onNodeWithText("提交").assertIsNotEnabled()
        dismissSheetViaBack()
        rule.runOnIdle{assertEquals(1,cancelled);ready=true;request=request.copy(answering=true)}
        rule.onNodeWithText("跳过").assertIsNotEnabled();rule.onNodeWithText("提交").assertIsNotEnabled()
        rule.runOnIdle{assertTrue(sent.isEmpty())}
    }
    @Test fun nonQuestionApprovalChoicesKeepTheirOriginalNativeIdsWithoutRedundantViewChatLink() {
        request=UserRequest("permission","原生权限","",choices=listOf(RequestChoice("opaque-once","仅允许一次"),RequestChoice("opaque-reject","拒绝权限")))
        show();rule.onNodeWithText("跳过").assertDoesNotExist();rule.onNodeWithText("提交").assertDoesNotExist()
        rule.onNodeWithText("先查看对话").assertDoesNotExist()
        dismissSheetViaBack();rule.runOnIdle{assertTrue(sent.isEmpty());assertEquals(1,cancelled)}
        click("拒绝权限");rule.runOnIdle{assertEquals(Answer("opaque-reject",emptyMap()),sent.single())}
    }
    @Test fun protocolWithoutSkipHasNoFakeSkipAndUnknownNativeChoicesStillCarryAnswers() {
        request=request.copy(submitChoice=null,skipChoice=null,choices=listOf(RequestChoice("opaque-allow-once","原生允许一次")))
        show();rule.onNodeWithText("跳过").assertDoesNotExist();rule.onNodeWithText("提交").assertIsNotEnabled()
        click("原生允许一次");rule.runOnIdle{assertEquals(Answer("opaque-allow-once",mapOf("q" to listOf("默认"))),sent.single())}
    }
    @Test fun oauthRetainsItsNativeCancelAndDoesNotGainQuestionActions() {
        request=antigravitySignInFixture()
        show()
        rule.onNodeWithText("跳过").assertDoesNotExist();rule.onNodeWithText("提交").assertDoesNotExist();rule.onNodeWithText("取消").assertDoesNotExist()
        rule.onNodeWithText("打开 Google 登录").assertIsEnabled()
        click("取消登录")
        rule.runOnIdle{assertEquals(Answer("cancel",emptyMap()),sent.single());assertEquals(0,cancelled);request=request.copy(answering=true)}
        rule.onNodeWithText("取消登录").assertIsNotEnabled();rule.onNodeWithText("打开 Google 登录").assertIsNotEnabled()
    }
    @Test fun threeChoiceRegularApprovalPlacesThreeCapsulesSideBySideAndPreservesIds() {
        request=UserRequest("opencode-perm","OpenCode 权限请求","{\"command\":\"git status\",\"cwd\":\"/workspace/project\"}",
            choices=listOf(RequestChoice("once","允许一次"),RequestChoice("always","记住并允许"),RequestChoice("reject","拒绝")))
        show()
        val left=rule.onNodeWithText("拒绝").fetchSemanticsNode().boundsInRoot
        val mid=rule.onNodeWithText("记住并允许").fetchSemanticsNode().boundsInRoot
        val right=rule.onNodeWithText("允许一次").fetchSemanticsNode().boundsInRoot
        assertEquals(left.center.y,mid.center.y,2f)
        assertEquals(mid.center.y,right.center.y,2f)
        assertTrue(left.left < mid.left && mid.left < right.left)
        click("记住并允许")
        rule.runOnIdle{assertEquals(Answer("always",emptyMap()),sent.single())}
    }
    @Test fun multiQuestionStepperUsesItemLabelsAndInlineOtherInputMaintainsExclusivity() {
        request=UserRequest("multi-q","Answer","The agent has 2 questions.",questions=listOf(
            RequestQuestion("q1","数据库","选择存储方案",listOf("SQLite","PostgreSQL"),defaultSelection=listOf("SQLite")),
            RequestQuestion("q2","迁移","选择迁移策略",listOf("自动迁移","手动脚本"),allowOther=true)),
            submitChoice="submit-all",skipChoice="skip-all")
        show()
        rule.onNodeWithText("The agent has 2 questions.").assertDoesNotExist()
        rule.onNodeWithText("第 1 / 2 项 · 数据库").assertExists()
        rule.onNodeWithText("上一项").assertDoesNotExist()
        click("下一项")
        rule.waitForIdle()
        rule.onNodeWithText("第 2 / 2 项 · 迁移").assertExists()
        rule.onNodeWithText("上一项").assertExists()
        click("其他（自定义输入）")
        rule.onNodeWithText("提交").assertIsNotEnabled()
        rule.onNode(hasSetTextAction()).performTextInput("蓝绿双写迁移")
        rule.onNodeWithText("提交").assertIsEnabled()
        click("提交")
        rule.runOnIdle{assertEquals(Answer("submit-all",mapOf("q1" to listOf("SQLite"),"q2" to listOf("蓝绿双写迁移"))),sent.single())}
    }
    @Test fun liveArrivedRequestAutoOpensAnimatesCloseWhenResolvedAndRemembersLocalDismissal() {
        ShadowDialog.reset()
        var view by mutableStateOf(sampleView().copy(requests=emptyList(),draft="",messages=emptyList(),subAgents=emptyList(),queue=emptyList()))
        rule.setContent {
            backDispatcher=LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            MaterialTheme { SessionDetail(CockpitState(selected=view),view,previewActions,onPickImages={}) }
        }
        rule.waitForIdle()
        val liveReq=UserRequest("live-1","命令执行审批","{\"command\":\"./luma verify\"}",listOf(RequestChoice("once","允许一次"),RequestChoice("deny","拒绝")))
        rule.runOnIdle { view=view.copy(requests=listOf(liveReq)) }
        rule.waitForIdle()
        rule.runOnIdle { assertNull(ShadowDialog.getLatestDialog()) }
        rule.onNodeWithText("允许一次").assertExists()
        dismissSheetViaBack()
        rule.onNodeWithText("允许一次").assertDoesNotExist()
        rule.onNodeWithText("处理").assertExists()
        click("处理")
        rule.waitForIdle()
        rule.onNodeWithText("允许一次").assertExists()
        rule.onNodeWithTag("request-sheet-scrim").performTouchInput { click(topCenter) }
        rule.waitForIdle()
        rule.onNodeWithText("允许一次").assertDoesNotExist()
        click("处理")
        rule.waitForIdle()
        rule.onNodeWithText("允许一次").assertExists()
        rule.runOnIdle { view=view.copy(requests=emptyList()) }
        rule.waitForIdle()
        rule.onNodeWithText("允许一次").assertDoesNotExist()
        val draftedReq=UserRequest("live-2","文件修改审批","{\"path\":\"README.md\"}",listOf(RequestChoice("once","允许一次"),RequestChoice("deny","拒绝")))
        rule.runOnIdle { view=view.copy(requests=listOf(draftedReq),draft="正在输入草稿") }
        rule.waitForIdle()
        rule.onNodeWithText("允许一次").assertDoesNotExist()
        rule.onNodeWithText("处理").assertExists()
    }
    @Test fun markdownPluginCardRendersSignInAndLinksToAuthUrlWithoutRawHtml() {
        val openedUrls=mutableListOf<String>()
        val uriHandler=object:UriHandler { override fun openUri(uri:String) { openedUrls+=uri } }
        val md="请完成授权：\n\n<plugin-card catalog-id=\"gdrive\"></plugin-card>\n\n[打开 Google Drive OAuth 授权页面](https://accounts.google.com/o/oauth2/v2/auth?client_id=test)"
        rule.setContent {
            CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                MaterialTheme { NativeMarkdown(md) }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("<plugin-card",substring=true).assertDoesNotExist()
        rule.waitUntil(3_000) { rule.onAllNodesWithTag("plugin-card-gdrive").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("<plugin-card",substring=true).assertDoesNotExist()
        rule.onNodeWithText("Google Drive").assertExists()
        rule.onNodeWithText("Sign in").performClick()
        rule.runOnIdle { assertEquals(listOf("https://accounts.google.com/o/oauth2/v2/auth?client_id=test"),openedUrls) }
    }
    @Test fun cockpitInTreeSecondaryPagesPushPopCleanlyAndPlusMenuDropsObsoleteSteer() {
        val activeView=sampleView().copy(activeTurnId="turn-running",requests=emptyList(),messages=emptyList(),subAgents=emptyList(),queue=emptyList())
        var state by mutableStateOf(CockpitState(selected=activeView))
        val actions=object:CockpitActions by previewActions {
            override fun closeFiles() { state=state.copy(fileBrowser=null) }
        }
        rule.setContent { MaterialTheme { CockpitScreen(state,actions) } }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("添加附件").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("追加到当前任务").assertDoesNotExist()
        rule.onNodeWithText("停止任务").assertExists()
        rule.runOnIdle {
            state=state.copy(fileBrowser=FileBrowser("/workspace/project",listOf(FileEntry("README.md","/workspace/project/README.md",false,size=12))))
        }
        rule.waitForIdle()
        rule.onNodeWithText("README.md").assertExists()
        rule.onNodeWithContentDescription("关闭").performSemanticsAction(SemanticsActions.OnClick){it()}
        rule.waitForIdle()
        rule.onNodeWithText("README.md").assertDoesNotExist()
    }
    @Test fun longShellCommandWithJsonBodyAndSyntheticCancelRendersCleanCodeWithoutLatexOrDuplicateCancel() {
        val cmd="f=\$(mktemp /tmp/agy-perm-test.XXXXXX) && rm -f \"\$f\" && echo \"perm-approval-ok\""
        request=UserRequest(
            id="agy-cmd",
            title=cmd,
            body="{\"CommandLine\": \"f=\$(mktemp /tmp/agy-perm-test.XXXXXX) && rm -f \\\"\$f\\\" && echo \\\"perm-approval-ok\\\"\", \"WaitMsBeforeAsync\": 5000, \"toolSummary\": \"Permission approval probe\"}",
            choices=listOf(RequestChoice("allow","Allow"),RequestChoice("reject","Reject"),RequestChoice("cancel","取消")),
        )
        show()
        rule.onNodeWithText("命令审批").assertExists()
        rule.onNodeWithText("Permission approval probe").assertExists()
        rule.onNodeWithText(cmd).assertExists()
        rule.onNodeWithText("公式未能排版",substring=true).assertDoesNotExist()
        rule.onNodeWithText("WaitMsBeforeAsync",substring=true).assertDoesNotExist()
        rule.onNodeWithText("取消").assertDoesNotExist()
        rule.onNodeWithText("拒绝").assertExists()
        rule.onNodeWithText("允许").assertExists()
        click("允许")
        rule.runOnIdle { assertEquals(Answer("allow",emptyMap()),sent.single()) }
    }
}
