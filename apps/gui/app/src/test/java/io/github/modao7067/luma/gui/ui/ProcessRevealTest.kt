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
        assertEquals(0x8A / 255f, PrismGlassDefaults.regularLightAlpha, 0f)
        assertEquals(2.5f, PrismGlassDefaults.regularBlurDp, 0f)
        assertEquals(.82f, ComposerGlassDefaults.containerAlpha, 0f)
        assertEquals(12f, ComposerGlassDefaults.blurRadiusDp, 0f)
        assertTrue(ComposerGlassDefaults.containerAlpha > PrismGlassDefaults.regularLightAlpha)
        assertTrue(ComposerGlassDefaults.blurRadiusDp > PrismGlassDefaults.regularBlurDp)
    }

    @Test fun manualExpandedReadStaysOpenAcrossReadBatchGrowthAndCompletion() {
        val state = ProcessGroupExpansionState()
        state.toggleItem("r1")
        assertTrue(state.isItemExpanded("r1"))
        assertTrue(state.isItemPinned("r1"))

        val batchTwo = listOf("r1", "r2")
        assertTrue(state.isAnyExpanded(batchTwo))
        assertTrue(state.isAnyPinned(batchTwo))
        assertEquals("r1", state.selectedBatchItem(batchTwo))

        state.selectBatchItem(batchTwo, "r2")
        val batchThree = listOf("r1", "r2", "r3")
        assertTrue(state.isAnyExpanded(batchThree))
        assertTrue(state.isAnyPinned(batchThree))
        assertEquals("r2", state.selectedBatchItem(batchThree))

        state.toggleItems(batchThree, anchorId = "r2")
        assertFalse(state.isAnyExpanded(batchThree))
        assertFalse(state.isAnyPinned(batchThree))
    }

    @Test fun collapsingGroupClearsPinsWhileRemapPreservesManualExpansion() {
        val state = ProcessGroupExpansionState(initialGroupExpanded = false)
        state.toggleItem("stream-r1")
        state.selectBatchItem(listOf("stream-r1", "stream-r2"), "stream-r2")
        state.remapEntryId("stream-r1", "hist-r1")
        state.remapEntryId("stream-r2", "hist-r2")

        assertTrue(state.isItemExpanded("hist-r1"))
        assertTrue(state.isItemPinned("hist-r1"))
        assertEquals("hist-r2", state.selectedBatchItem(listOf("hist-r1", "hist-r2")))

        state.toggleGroup()
        assertTrue(state.groupExpanded)
        state.toggleItem("done-item")
        assertTrue(state.isItemExpanded("done-item"))
        assertFalse(state.isItemPinned("done-item"))

        state.toggleGroup()
        assertFalse(state.groupExpanded)
        assertFalse(state.isItemPinned("hist-r1"))
        assertTrue(state.isItemExpanded("hist-r1"))
    }

    @Test fun groupTitleShimmerRunsSlowerThanToolRowShimmer() {
        assertTrue(PROCESS_GROUP_TITLE_SHIMMER_DURATION_MS > PROCESS_ROW_SHIMMER_DURATION_MS)
        assertEquals(PROCESS_GROUP_BREATH_DURATION_MS, PROCESS_GROUP_TITLE_SHIMMER_DURATION_MS)
    }
}
