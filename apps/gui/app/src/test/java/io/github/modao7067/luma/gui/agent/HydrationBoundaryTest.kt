package io.github.modao7067.luma.gui.agent

import org.junit.Assert.*
import org.junit.Test

class HydrationBoundaryTest {
    @Test fun skipsOnlyEventsAlreadyCoveredByThatItemsSnapshot() {
        val boundary = HydrationBoundary()
        boundary.item("one", 3)
        val delta = obj("method" to "item/agentMessage/delta", "params" to obj("itemId" to "one", "delta" to "new"))
        assertFalse(boundary.shouldReplay(2, delta))
        assertTrue(boundary.shouldReplay(3, delta))
        assertTrue(boundary.shouldReplay(9, delta))
        val other = obj("method" to "item/agentMessage/delta", "params" to obj("itemId" to "new-item"))
        assertTrue(boundary.shouldReplay(0, other))
    }
    @Test fun lifecycleSnapshotsNeverSwallowPendingApprovals() {
        val boundary = HydrationBoundary(); boundary.item("cmd", 5); boundary.turns(6)
        assertFalse(boundary.shouldReplay(4, obj("method" to "turn/started")))
        assertTrue(boundary.shouldReplay(6, obj("method" to "turn/completed")))
        assertTrue(boundary.shouldReplay(0, obj("id" to 0, "method" to "item/commandExecution/requestApproval", "params" to obj("itemId" to "cmd"))))
        boundary.clear()
        assertTrue(boundary.shouldReplay(0, obj("method" to "turn/started")))
    }
    @Test fun parentSnapshotDoesNotSwallowConcurrentChildTurnSettlement() {
        val boundary = HydrationBoundary(); boundary.turns(8, "parent")
        assertFalse(boundary.shouldReplay(2, obj("method" to "turn/completed", "params" to obj("threadId" to "parent"))))
        assertTrue(boundary.shouldReplay(2, obj("method" to "turn/completed", "params" to obj("threadId" to "child"))))
    }
    @Test fun childSnapshotHasItsOwnReplayBoundaryWithoutChangingTheParent() {
        val boundary = HydrationBoundary(); boundary.turns(3, "parent"); boundary.turns(7, "child")
        assertTrue(boundary.shouldReplay(4, obj("method" to "turn/started", "params" to obj("threadId" to "parent"))))
        assertFalse(boundary.shouldReplay(4, obj("method" to "turn/started", "params" to obj("threadId" to "child"))))
        assertFalse(boundary.shouldReplay(4, obj("method" to "thread/status/changed", "params" to obj("threadId" to "child"))))
        assertTrue(boundary.shouldReplay(7, obj("method" to "turn/started", "params" to obj("threadId" to "child"))))
    }
    @Test fun inFlightWindowDeduplicatesSuffixPrefixOverlapWithoutEatingPostWindowTrailingZerosOrNewlines() {
        val boundary = HydrationBoundary()
        boundary.item("msg", bufferedBefore = 1, bufferedAfter = 3)
        val events = listOf(
            obj("method" to "item/agentMessage/delta", "params" to obj("itemId" to "msg", "delta" to "OLD")),
            obj("method" to "item/agentMessage/delta", "params" to obj("itemId" to "msg", "delta" to "ab")),
            obj("method" to "item/agentMessage/delta", "params" to obj("itemId" to "msg", "delta" to "cd0")),
            obj("method" to "item/agentMessage/delta", "params" to obj("itemId" to "msg", "delta" to "0")),
            obj("method" to "item/agentMessage/delta", "params" to obj("itemId" to "msg", "delta" to "\n")),
        )
        val snapshot = "prefix abc"
        val replayed = boundary.replayEvents(events) { id, tool ->
            assertFalse(tool); assertEquals("msg", id); snapshot
        }
        val combined = snapshot + replayed.joinToString("") { it.getJSONObject("params").getString("delta") }
        assertEquals("prefix abcd00\n", combined)
    }
}
