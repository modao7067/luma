package io.github.modao7067.luma.gui.ui

import android.app.Application
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import io.github.modao7067.luma.gui.session.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
@OptIn(ExperimentalTestApi::class)
class MenuDropdownOffscreenBenchmark {
    private val motion = object : MotionDurationScale { override var scaleFactor by mutableFloatStateOf(1f) }
    @get:Rule val rule = createComposeRule(effectContext = motion)
    private fun output(name: String, body: String) {
        val directory = File(System.getProperty("java.io.tmpdir"), "luma-verification/menu-dropdown-update-20261105")
        directory.mkdirs(); File(directory, name).writeText(body)
    }

    @Test fun productionChoicesAndProviderGeometryAlongsideBenchmark() {
        val provider = "google-vertex"
        val longProvider = "organization-" + "very-long-".repeat(8) + "provider-id"
        val label = "Gemini 4 Argon " + "long-model-name ".repeat(8)
        var width by mutableStateOf(300.dp)
        var scale by mutableFloatStateOf(1f)
        var id by mutableStateOf(provider)
        var enabled by mutableStateOf(true)
        var useProduction by mutableStateOf(false)
        var pending by mutableStateOf(true)
        val models = (0 until 160).map { ModelOption("google-vertex/model-$it", "Choice $it", listOf("low", "high"), speeds = listOf(RequestChoice("fast", "Fast")), defaultEffort = "low") }
        val source = sampleView().copy(activeTurnId = null, queue = emptyList(), summary = sampleView().summary.copy(model = models.first().id))
        val calls = mutableListOf<String>()
        var dismissals = 0
        val actions = object : CockpitActions by previewActions {
            override fun configure(model: String, effort: String) { calls += "$model:$effort" }
            override fun configureSpeed(speed: String) { calls += "speed:$speed" }
        }
        rule.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, scale)) { MaterialTheme {
            if (useProduction) Box(Modifier.size(360.dp, 420.dp)) {
                ModelMenu(true, { dismissals++ }, CockpitState(models = models), source.copy(sending = pending), actions)
            } else Column(Modifier.width(width)) {
                ActionMenuItem("模型", MenuSymbol.MODEL, description = label, badge = id, badgeOnDescription = true, trailing = MenuSymbol.DOWN, enabled = enabled) {}
            }
        } } }
        fun bounds(text: String) = rule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        fun layout(text: String): TextLayoutResult {
            val values = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(values) }
            return values.single()
        }
        val evidence = mutableListOf<String>()
        for (w in listOf(300.dp, 184.dp)) for (s in listOf(1f, 1.3f)) for (p in listOf(provider, longProvider)) {
            rule.runOnIdle { width = w; scale = s; id = p; enabled = s == 1f }
            val name = bounds(label); val badge = bounds(p); val title = bounds("模型")
            assertTrue("provider belongs below the title", badge.top >= title.bottom)
            assertTrue("reserve provider and gap", badge.left >= name.right + 5f)
            assertTrue("model/provider must both keep width", name.width > 0f && badge.width > 0f)
            assertEquals(name.top + layout(label).firstBaseline, badge.top + layout(p).firstBaseline, 1f)
            assertEquals(1, layout(label).layoutInput.maxLines)
            assertEquals(TextOverflow.Ellipsis, layout(label).layoutInput.overflow)
            evidence += "width=$w fontScale=$s enabled=$enabled model=$name provider=$badge baseline=${name.top + layout(label).firstBaseline}"
        }
        rule.runOnIdle { useProduction = true; scale = 1f }
        rule.onNodeWithText("模型").performClick()
        rule.onNode(hasText("Choice 0") and !hasText("模型")).assertIsNotEnabled()
        rule.runOnIdle { pending = false }
        rule.onNode(hasText("Choice 0") and !hasText("模型")).assertIsEnabled()
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(159)
        rule.onNodeWithText("Choice 159").performScrollTo().performClick()
        assertEquals(listOf("google-vertex/model-159:low"), calls)
        assertEquals(1, dismissals)
        rule.onNodeWithText("思考强度").performScrollTo().performClick()
        rule.onNodeWithText("high").performScrollTo().performClick()
        rule.onNodeWithText("速度").performScrollTo().performClick()
        rule.onNodeWithText("Fast").performScrollTo().performClick()
        assertEquals(listOf("google-vertex/model-159:low", "google-vertex/model-0:high", "speed:fast"), calls)
        assertEquals(3, dismissals)
        output("geometry.txt", evidence.joinToString("\n"))
        println(evidence.joinToString("\n"))
    }
}
