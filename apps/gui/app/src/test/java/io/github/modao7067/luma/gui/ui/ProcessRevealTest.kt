package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.session.ConversationItem
import org.junit.Assert.*
import org.junit.Test

class ProcessRevealTest {
    @Test fun contiguousFinishedRowsRetractTogetherWithoutDroppingOrReorderingEntries() {
        val outcomes = listOf("succeeded", "cancelled", "running", "unknown", "failed", "succeeded")
        val entries = outcomes.mapIndexed { index, outcome -> ConversationItem("$index", "tool", outcome = outcome) }
        val runs = processRevealRuns(entries)
        assertEquals(listOf(false, true, false), runs.map { it.retained })
        assertEquals(listOf(2, 2, 2), runs.map { it.entries.size })
        assertEquals(entries, runs.flatMap { it.entries })
    }
    @Test fun completedAndCancelledRowsCollapseButUnresolvedRowsRemainDiscoverable() {
        listOf("succeeded", "cancelled", "failed").forEach { assertFalse(processEntryRetained(ConversationItem(it, "tool", outcome = it))) }
        listOf("running", "unknown", "").forEach { assertTrue(processEntryRetained(ConversationItem(it, "tool", outcome = it))) }
    }
    @Test fun exitingRowsKeepPixelsButLoseActionsImmediately() {
        val fraction = spatialSample(120, 1f, 0f)
        assertTrue(fraction in 0f..1f)
        assertTrue(spatialContentVisible(false, fraction))
        assertFalse(spatialContentInteractive(false, fraction))
        assertFalse(spatialContentInteractive(false, 1f))
        assertFalse(spatialContentVisible(false, 0f))
    }
    @Test fun retainedRowsKeepTheirActionsWhenTheGroupCloses() {
        val retained = processEntryRetained(ConversationItem("live", "tool", outcome = "running"))
        assertTrue(spatialContentInteractive(false || retained, 1f))
    }
    @Test fun onlyTheEditorStrengthensTheGlass() {
        assertEquals(.4f, SukiGlassDefaults.containerAlpha, 0f)
        assertEquals(4f, SukiGlassDefaults.blurRadiusDp, 0f)
        assertEquals(.82f, ComposerGlassDefaults.containerAlpha, 0f)
        assertEquals(12f, ComposerGlassDefaults.blurRadiusDp, 0f)
        assertTrue(ComposerGlassDefaults.containerAlpha > SukiGlassDefaults.containerAlpha)
        assertTrue(ComposerGlassDefaults.blurRadiusDp > SukiGlassDefaults.blurRadiusDp)
    }
}
