package io.github.modao7067.luma.gui.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.modao7067.luma.gui.agent.NativeCliProjection
import io.github.modao7067.luma.gui.session.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ClaudeEffortInteractionTest {
    @get:Rule val rule = createComposeRule()
    private fun SemanticsNodeInteraction.clickAction() = performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
    @Test fun nativeCatalogEffortsAreSelectableBeforeFirstMessage() {
        val snapshot = JSONObject("""{"connected":true,"running":false,"model":"default","effort":"medium",
            "models":[{"id":"default","label":"Default","efforts":["low","medium","high","xhigh","max"],"supportsImages":false}],
            "permissions":[{"id":"auto","label":"自动审批"}]}""")
        val initial = sampleView().copy(activeTurnId = null, queue = emptyList(), draft = "", messages = emptyList())
        var view by mutableStateOf(NativeCliProjection.view(snapshot, initial).copy(
            summary = initial.summary.copy(cli = "Claude Code", model = snapshot.getString("model")), effort = snapshot.getString("effort")))
        val models = NativeCliProjection.models(snapshot)
        val calls = mutableListOf<Pair<String, String>>()
        val actions = object : CockpitActions by previewActions {
            override fun configure(model: String, effort: String) { calls += model to effort }
        }
        rule.setContent { MaterialTheme { Box(Modifier.size(360.dp, 700.dp)) {
            ModelMenu(true, {}, CockpitState(models = models), view, actions)
        } } }
        rule.onNodeWithText("思考强度").clickAction()
        rule.onNodeWithText("CLI 未提供可选项").assertDoesNotExist()
        rule.onNodeWithText("max").assertIsDisplayed().assertIsEnabled().clickAction()
        rule.runOnIdle { assertEquals(listOf("default" to "max"), calls); view = view.copy(configuring = true) }
        rule.onNodeWithText("max").assertIsNotEnabled()
        rule.runOnIdle { view = view.copy(configuring = false, effort = "max") }
        rule.onNode(hasText("max") and !hasText("思考强度")).assertIsSelected()
    }
}
