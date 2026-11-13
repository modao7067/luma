package io.github.modao7067.luma.gui.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import io.github.modao7067.luma.gui.session.*
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
class ModelProviderAlignmentTest {
    @get:Rule val rule = createComposeRule()
    private fun textBounds(text: String) = rule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun SemanticsNodeInteraction.clickAction() = performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
    private fun modelOption(label: String) = rule.onNode(hasText(label) and !hasText("模型"))
    private fun textLayout(text: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

    @Test fun providerEndsAtContentEdgeAndDoesNotMoveWhenSelectionChanges() {
        var chosen by mutableStateOf(true)
        var density = 1f
        rule.setContent { GuiScale { MaterialTheme {
            density = LocalDensity.current.density
            Column(Modifier.width(280.dp).testTag("options")) {
                ActionMenuItem("GPT-6-Astra", MenuSymbol.MODEL, badge = "openai", selected = chosen, selectionAtStart = true) { chosen = !chosen }
                ActionMenuItem("Claude", MenuSymbol.MODEL, badge = "anthropic", selectionAtStart = true) {}
            }
        } } }
        val shell = rule.onNodeWithTag("options").fetchSemanticsNode().boundsInRoot
        val before = textBounds("openai")
        assertEquals(shell.right - 25 * density, before.right, 1f)
        assertEquals(before.right, textBounds("anthropic").right, 1f)
        rule.onNodeWithText("GPT-6-Astra").assertIsSelected().clickAction().assertIsNotSelected()
        assertEquals(before, textBounds("openai"))
        rule.onNodeWithText("GPT-6-Astra").clickAction().assertIsSelected()
        assertEquals(before, textBounds("openai"))
        println("PASS provider edge=$before shell=$shell density=$density; selection toggle has no shift")
    }

    @Test fun nativeTrailingSlotAndLeadingModelSelectionReserveTheirExpectedWidths() {
        val label = "A deliberately long model name that fills the available track ".repeat(8)
        rule.setContent { MaterialTheme { Column(Modifier.width(280.dp)) {
            ActionMenuItem(label, MenuSymbol.MODEL, badge = "opencode", selected = true) {}
            ActionMenuItem(label, MenuSymbol.MODEL, badge = "opencode", selected = true, selectionAtStart = true) {}
            ActionMenuItem(label, MenuSymbol.MODEL) {}
            ActionMenuItem(label, MenuSymbol.MODEL, selectionAtStart = true) {}
        } } }
        val labels = rule.onAllNodesWithText(label, useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
        assertEquals(29 * rule.density.density, labels[1].width - labels[0].width, 1f)
        assertEquals(labels[2].width, labels[3].width, 1f)
        println("PASS model width legacy=${labels[0].width} edge=${labels[1].width}; +29dp; no-provider width=${labels[3].width} unchanged")
    }

    @Test fun longProviderAndLongModelShareNarrowTrackWithoutShrinkingType() {
        val model = "Long Reasoning Model Name With A Stable Suffix ".repeat(6)
        val provider = "organization-" + "very-long-".repeat(12) + "provider-id"
        var width by mutableStateOf(280.dp)
        var fontScale by mutableFloatStateOf(1f)
        var density = 1f
        rule.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
            GuiScale { MaterialTheme {
                density = LocalDensity.current.density
                Column(Modifier.width(width).testTag("options")) {
                    ActionMenuItem(model, MenuSymbol.MODEL, badge = provider, selected = true, selectionAtStart = true) {}
                }
            } }
        } }
        for (size in listOf(280.dp, 184.dp)) for (scale in listOf(1f, 1.3f)) {
            rule.runOnIdle { width = size; fontScale = scale }
            val name = textBounds(model)
            val id = textBounds(provider)
            val shell = rule.onNodeWithTag("options").fetchSemanticsNode().boundsInRoot
            assertTrue("both texts retain width", name.width > 0 && id.width > 0)
            assertTrue("6dp gap must remain: name=$name provider=$id", id.left - name.right >= 6 * density - 1)
            assertTrue("provider may not consume the model half: name=$name provider=$id", name.width >= id.width - 1)
            assertEquals(shell.right - 25 * density, id.right, 1f)
            assertEquals(14f, textLayout(model).layoutInput.style.fontSize.value)
            assertEquals(11f, textLayout(provider).layoutInput.style.fontSize.value)
            assertEquals(name.width, id.width, 1f)
            assertEquals(1, textLayout(model).layoutInput.maxLines)
            assertEquals(1, textLayout(provider).layoutInput.maxLines)
            assertEquals(TextOverflow.Ellipsis, textLayout(model).layoutInput.overflow)
            assertEquals(TextOverflow.MiddleEllipsis, textLayout(provider).layoutInput.overflow)
            assertEquals(TextAlign.End, textLayout(provider).layoutInput.style.textAlign)
            println("PASS width=$size fontScale=$scale model=$name provider=$id density=$density")
        }
    }

    @Test fun nativeBadgeChevronPermissionAndStatusSlotsUseMaterialGeometry() {
        val label = "A deliberately long default row that fills the remaining width ".repeat(8)
        rule.setContent { MaterialTheme { Column(Modifier.width(300.dp).testTag("defaults")) {
            ActionMenuItem(label, MenuSymbol.MODEL, badge = "default-provider", selected = true) {}
            ActionMenuItem(label, MenuSymbol.MODEL, trailing = MenuSymbol.DOWN) {}
            ActionMenuItem(label, MenuSymbol.SHIELD, selected = true, iconContainer = true) {}
            ActionMenuItem(label, MenuSymbol.MODEL, selected = true, statusAtEdge = true, statusContent = { Text("status-edge") }) {}
        } } }
        val right = rule.onNodeWithTag("defaults").fetchSemanticsNode().boundsInRoot.right
        val density = rule.density.density
        assertEquals(right - 54 * density, textBounds("default-provider").right, 1f)
        val rows = rule.onAllNodesWithText(label, useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
        assertEquals(right - 54 * density, rows[1].right, 1f)
        assertEquals(right - 54 * density, rows[2].right, 1f)
        val status = textBounds("status-edge")
        assertEquals(right - 18 * density - (24 * density - status.width).coerceAtLeast(0f), status.right, 1f)
        println("PASS native badge/chevron/permission use 24dp trailing slot; status uses its native content geometry")
    }

    @Test fun productionModelMenuUsesLeadingSelectionAndKeepsExactNativeChoice() {
        val first = ModelOption("openai/first", "Current model")
        val second = ModelOption("another-provider/second", "Other model")
        val initial = sampleView().copy(activeTurnId = null, queue = emptyList(), summary = sampleView().summary.copy(model = first.id))
        var view by mutableStateOf(initial)
        val calls = mutableListOf<String>()
        val actions = object : CockpitActions by previewActions {
            override fun configure(model: String, effort: String) { calls += model; view = view.copy(summary = view.summary.copy(model = model)) }
        }
        var density = 1f
        rule.setContent { GuiScale { MaterialTheme {
            density = LocalDensity.current.density
            Box(Modifier.size(360.dp, 700.dp)) { ModelMenu(true, {}, CockpitState(models = listOf(first, second)), view, actions) }
        } } }
        rule.onNodeWithText("模型").clickAction()
        val row = modelOption("Other model").fetchSemanticsNode().boundsInRoot
        val edge = textBounds("another-provider")
        assertEquals(row.right - 19 * density, edge.right, 1f)
        modelOption("Current model").assertIsSelected()
        modelOption("Other model").clickAction().assertIsSelected()
        assertEquals(listOf(second.id), calls)
        modelOption("Current model").assertIsNotSelected()
        val selectedEdge = rule.onAllNodesWithText("another-provider", useUnmergedTree = true).fetchSemanticsNodes().last().boundsInRoot
        assertEquals(edge.right, selectedEdge.right, 1f)
        println("PASS production ModelMenu content edge=$edge row=$row; exact native choice=${calls.single()}")
    }
}
