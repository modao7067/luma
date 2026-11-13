package io.github.modao7067.luma.gui.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.modao7067.luma.gui.session.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class RequestDraftDialogProbe {
    @get:Rule val rule=createComposeRule()
    @Test fun enteredDraftIsNeverIncludedInSkip() {
        var request by mutableStateOf(UserRequest("q","Answer","",questions=listOf(RequestQuestion("q","选择","请选择方式",listOf("默认","其它"),defaultSelection=listOf("默认"))),skipChoice="native-skip"))
        val sent=mutableListOf<Pair<String,Map<String,List<String>>>>()
        rule.mainClock.autoAdvance=false
        rule.setContent { MaterialTheme { RequestDialog(request,object:CockpitActions by previewActions {
            override fun answer(requestId:String,choice:String,answers:Map<String,List<String>>) { sent+=choice to answers }
        }) {} } }
        rule.mainClock.advanceTimeBy(64);rule.waitForIdle()
        rule.onNodeWithText("Answer").assertExists()
        rule.onNodeWithText("Other").assertExists();rule.onNodeWithText("补充或自行回答").assertDoesNotExist()
        rule.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.SetText){it(AnnotatedString("must not leak"))}
        rule.mainClock.advanceTimeBy(32);rule.waitForIdle()
        rule.onNode(hasSetTextAction()).assertTextContains("must not leak")
        rule.onNodeWithText("跳过").performSemanticsAction(SemanticsActions.OnClick){it()}
        rule.runOnIdle {
            assertEquals("native-skip" to emptyMap<String,List<String>>(),sent.single())
            request=request.copy(questions=request.questions.map{it.copy(options=emptyList(),defaultSelection=emptyList())})
        }
        rule.mainClock.advanceTimeBy(32);rule.waitForIdle()
        rule.onNodeWithText("你的回答").assertExists();rule.onNodeWithText("Other").assertDoesNotExist()
    }
}
