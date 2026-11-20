package io.github.modao7067.luma.gui.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.modao7067.luma.gui.session.ConversationItem
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ProcessToolLabelsOffscreenBenchmark {
    @get:Rule val rule = createComposeRule()
    private val directory = File(System.getProperty("java.io.tmpdir"), "luma-verification/tool-labels-title-revision-20261105").apply { mkdirs() }

    @Test fun sameInputSummaryTimingAndRealTimelineGeometry() {
        ProcessToolLabelsTest().apply {
            bothLevelsShareLiteralFirstSeenNamesAndCountEachNativeCall()
            updatesAreOneCallAndNonCallsAreNotToolInvocations()
            structuredCommandLabelsPreserveNativeToolSummaryNames()
            summaryCountsStepsAndOnlyEmphasizesEdits()
            onlyKnownWebIdentityOverridesGenericSearch()
            duplicateLabelsDisappearButDistinctContextSurvives()
            codexDynamicIdentitySurvivesOnlyItsOwnUpdates()
        }
        val regular = toolLabelsFixture()
        assertEquals("调用 8 次 · commandExecution、read、glob、websearch · 2 次思考 · 1 次失败", baselineToolLabelsProcessGroupCaption(regular, "OpenCode"))
        assertEquals("10 步", processGroupCaption(regular, "OpenCode"))
        repeat(200) { baselineToolLabelsProcessGroupCaption(regular, "OpenCode"); processGroupCaption(regular, "OpenCode") }
        val timings = mutableListOf<String>()
        var checksum = 0
        repeat(6) { sample ->
            for (old in if (sample % 2 == 0) listOf(true, false) else listOf(false, true)) {
                val start = System.nanoTime()
                repeat(1000) { checksum += (if (old) baselineToolLabelsProcessGroupCaption(regular, "OpenCode") else processGroupCaption(regular, "OpenCode")).length }
                timings += "$sample,$old,${(System.nanoTime() - start) / 1_000_000.0}"
            }
        }
        var baseline by mutableStateOf(true)
        var width by mutableStateOf(320.dp)
        var scale by mutableFloatStateOf(1f)
        var entries by mutableStateOf(regular)
        var expectedEmphasisColor = androidx.compose.ui.graphics.Color.Unspecified
        rule.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, scale), LocalProcessDotsPhase provides 0f) { MaterialTheme {
            expectedEmphasisColor = processRowPalette(MaterialTheme.colorScheme).emphasis
            Column(Modifier.width(width).testTag("process-fixture")) {
                if (baseline) BaselineToolLabelsProcessGroup(entries, cli = "OpenCode")
                else ProcessGroup(entries, cli = "OpenCode")
            }
        } } }
        val evidence = mutableListOf<String>()
        fun snapshot(name: String) {
            rule.waitForIdle()
            File(directory, "$name-tree.txt").writeText(rule.onRoot().printToString())
        }
        snapshot("baseline-collapsed")
        rule.onNodeWithText("调用 8 次 · commandExecution、read、glob、websearch · 2 次思考 · 1 次失败").performClick()
        snapshot("baseline-expanded")
        rule.runOnIdle { baseline = false }
        val summary = "10 步"
        rule.onNodeWithText(summary).assertExists()
        val renderedSummary = rule.onNodeWithText(summary, useUnmergedTree = true).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text].single()
        assertEquals(processSummaryText(processGroupSummary(regular, "OpenCode"), expectedEmphasisColor), renderedSummary)
        evidence += "summarySpans=" + renderedSummary.spanStyles.joinToString { "${it.start}..${it.end}:${renderedSummary.text.substring(it.start, it.end)}" }
        rule.onNodeWithText(summary).performClick()
        rule.onAllNodesWithText("read").onFirst().assertExists()
        snapshot("expanded")
        rule.onAllNodesWithText("read").onFirst().performClick()
        rule.onNodeWithText("synthetic result 1").assertExists()
        rule.onAllNodesWithText("read").onFirst().performClick()
        val longName = "mcp__SyntheticServer__" + "ExtremelyLongMixedCaseToolName_".repeat(5)
        val long = regular.first().copy(id = "long", kind = "mcpToolCall", toolName = longName, text = longName, detail = "long-name result")
        rule.runOnIdle { entries = regular + long }
        for ((w, s) in listOf(320.dp to 1f, 240.dp to 1.3f)) {
            rule.runOnIdle { width = w; scale = s }
            val node = rule.onNodeWithText(longName, useUnmergedTree = true)
            node.assertExists()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            val root = rule.onNodeWithTag("process-fixture").fetchSemanticsNode().boundsInRoot
            assertTrue("long name must keep positive width", bounds.width > 0f)
            assertTrue("name must stay within right edge without reserving trailing icon width", bounds.right <= root.right + 0.5f && bounds.right >= root.right - 16f)
            evidence += "width=$w fontScale=$s name=$bounds root=$root"
            node.performTouchInput { click() }
            rule.onNodeWithText("long-name result").assertExists()
            node.performTouchInput { click() }
            snapshot("long-name-${w.value.toInt()}-$s")
            rule.runOnIdle { baseline = true }
            rule.onNodeWithText(baselineToolLabelsProcessGroupCaption(entries, "OpenCode")).performClick()
            snapshot("baseline-long-name-${w.value.toInt()}-$s")
            rule.runOnIdle { baseline = false }
            rule.onNodeWithText(processGroupCaption(entries, "OpenCode")).performClick()
        }
        val finalSummary = processGroupCaption(regular + long, "OpenCode")
        rule.onNodeWithText(finalSummary).performClick()
        rule.onNodeWithText(longName).assertDoesNotExist()
        snapshot("collapsed-long-name")
        File(directory, "benchmark.txt").writeText("runtime=Ubuntu ARM64 JVM, Robolectric API35 LEGACY; summary CPU only, not ART/GPU frame timing\niterations=1000 per sample; checksum=$checksum\nsample,deliveredBaseline,elapsedMs\n" + timings.joinToString("\n") + "\n" + evidence.joinToString("\n"))
        println("TOOL_LABELS " + evidence.joinToString("; ") + "; timings=$timings")
    }
}
