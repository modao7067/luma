package io.github.modao7067.luma.gui.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.modao7067.luma.gui.session.CockpitActions
import io.github.modao7067.luma.gui.session.CockpitState
import io.github.modao7067.luma.gui.session.ModelOption
import io.github.modao7067.luma.gui.session.RequestChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ModelSpeedMenuTest {
    @get:Rule val rule = createComposeRule()
    private fun SemanticsNodeInteraction.clickAction() = performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }

    private fun idleView(speed: String) = sampleView().copy(activeTurnId = null, queue = emptyList(), draft = "", speed = speed)

    @Test fun speedRowStaysHiddenWhenTheModelDeclaresNone() {
        val model = ModelOption("gpt-6-astra", "GPT-6-Astra", listOf("medium", "high"))
        rule.setContent { MaterialTheme { Box(Modifier.size(360.dp, 700.dp)) {
            ModelMenu(true, {}, CockpitState(models = listOf(model)), idleView(""), previewActions)
        } } }
        rule.onNodeWithText("模型").assertIsDisplayed()
        rule.onNodeWithText("思考强度").assertIsDisplayed()
        rule.onNodeWithText("速度").assertDoesNotExist()
        rule.onNodeWithText("CLI 未提供可选项").assertDoesNotExist()
    }

    @Test fun speedRowShowsTheNativeLabelAndSelectsBlankAsDefault() {
        val speeds = listOf(RequestChoice("default", "Default"), RequestChoice("priority", "Fast"))
        val model = ModelOption("gpt-6-astra", "GPT-6-Astra", listOf("medium", "high"), speeds = speeds)
        var view by mutableStateOf(idleView(""))
        val calls = mutableListOf<String>()
        val actions = object : CockpitActions by previewActions {
            override fun configureSpeed(id: String) { calls += id }
        }
        rule.setContent { MaterialTheme { Box(Modifier.size(360.dp, 700.dp)) {
            ModelMenu(true, {}, CockpitState(models = listOf(model)), view, actions)
        } } }
        rule.onNode(hasText("速度") and hasText("Default")).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("priority").assertDoesNotExist()
        rule.onNodeWithText("速度").clickAction()
        rule.onNode(hasText("Default") and !hasText("速度")).performScrollTo().assertIsSelected()
        rule.onNode(hasText("Fast") and !hasText("速度")).performScrollTo().assertIsDisplayed().clickAction()
        rule.runOnIdle { assertEquals(listOf("priority"), calls); view = view.copy(speed = "priority") }
        rule.onNode(hasText("速度") and hasText("Fast")).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("priority").assertDoesNotExist()
    }
}
