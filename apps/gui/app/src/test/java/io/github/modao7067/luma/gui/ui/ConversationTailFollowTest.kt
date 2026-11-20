package io.github.modao7067.luma.gui.ui

import io.github.modao7067.luma.gui.session.ConversationItem
import org.junit.Assert.*
import org.junit.Test

class ConversationTailFollowTest {

    @Test fun parallelToolUpdatesKeepGroupIdentityAndNeverSwallowAssistantText() {
        val user = ConversationItem("user", "user", text = "检查")
        val a = ConversationItem("a", "tool", outcome = "running")
        val b = ConversationItem("b", "tool", outcome = "running")
        val original = conversationRows(listOf(user, a))
        val updated = conversationRows(listOf(user, a.copy(detail = "output", outcome = "succeeded"), b))
        assertEquals(original.map { it.id }, updated.map { it.id })
        val answer = ConversationItem("answer", "assistant", text = "正文")
        val split = conversationRows(listOf(user, a, answer, b))
        assertEquals(listOf("message:user", "process:a", "message:answer", "process:b"), split.map { it.id })
        assertEquals(listOf(user, a, answer, b), split.flatMap { it.entries })
    }

    @Test fun sequentialCompletionsDoNotReopenAlreadyHiddenNeighbours() {
        val entries = listOf("a", "b", "c").map { ConversationItem(it, "tool", outcome = "succeeded") }
        val runs = processPresentationRuns(entries, mapOf("a" to 0f, "b" to .6f, "c" to 1f), 0f)
        assertEquals(listOf(0f, .6f, 1f), runs.map { it.fraction })
        assertEquals(entries, runs.flatMap { it.entries })
        assertTrue(runs.none { it.retained })
    }

    @Test fun manualGroupRevealStillRetractsSettledRowsAsOneRegion() {
        val entries = (1..3).map { ConversationItem("$it", "tool", outcome = "succeeded") }
        val run = processPresentationRuns(entries, emptyMap(), .65f).single()
        assertEquals(entries, run.entries)
        assertEquals(.65f, run.fraction, 0f)
        assertEquals(.8f, processPresentationRuns(entries.take(1), mapOf("1" to .8f), .3f).single().fraction, 0f)
    }

    @Test fun arrivalAndCompletionRemainContinuousEvenWhenGroupIsAlreadyExpanded() {
        val live = ConversationItem("new", "tool", outcome = "running")
        assertEquals(.35f, processPresentationRuns(listOf(live), mapOf("new" to 1f), 1f, mapOf("new" to .35f)).single().fraction, 0f)
        assertEquals(.35f, processPresentationRuns(listOf(live.copy(outcome = "succeeded")), mapOf("new" to 1f), 1f, mapOf("new" to .35f)).single().fraction, 0f)
    }

    @Test fun canonicalRowKeysNeverCollideWhenReplacedRowReappears() {
        val canonicalRowIds = HashMap<String, String>()
        val settledRowIds = HashSet<String>()
        val rowHeights = HashMap<String, Int>()
        val measuredRowHeights = HashMap<String, Int>()
        val u1 = ConversationItem("9d679487-9c7e-4195-8a12-e451b6153bfa", "user", text = "第一条")
        val u2 = ConversationItem("b28f1122-1111-4222-8333-abcdef012345", "user", text = "第二条")
        val step1 = conversationRows(listOf(u1))
        val keys1 = resolveCanonicalRowKeys(step1, emptyList(), canonicalRowIds, settledRowIds, rowHeights, measuredRowHeights)
        assertEquals(listOf("message:${u1.id}"), step1.map { keys1.getValue(it.id) })
        val step2 = conversationRows(listOf(u2))
        val keys2 = resolveCanonicalRowKeys(step2, step1, canonicalRowIds, settledRowIds, rowHeights, measuredRowHeights)
        assertEquals(1, step2.map { keys2.getValue(it.id) }.toSet().size)
        val step3 = conversationRows(listOf(u1, u2))
        val keys3 = resolveCanonicalRowKeys(step3, step2, canonicalRowIds, settledRowIds, rowHeights, measuredRowHeights)
        val resolved3 = step3.map { keys3.getValue(it.id) }
        assertEquals("all LazyColumn slot keys must be strictly unique: $resolved3", step3.size, resolved3.toSet().size)
    }

    @Test fun canonicalRowKeyRemapPreservesUnsettledArrivalStatus() {
        val canonicalRowIds = HashMap<String, String>()
        val settledRowIds = HashSet<String>()
        val rowHeights = HashMap<String, Int>()
        val measuredRowHeights = HashMap<String, Int>()
        val pending = ConversationItem("client-u1", "user", text = "新消息", delivery = "pending")
        val native = ConversationItem("native-u1", "user", text = "新消息")
        val step1 = conversationRows(listOf(pending))
        val keys1 = resolveCanonicalRowKeys(step1, emptyList(), canonicalRowIds, settledRowIds, rowHeights, measuredRowHeights)
        val step2 = conversationRows(listOf(native))
        val keys2 = resolveCanonicalRowKeys(step2, step1, canonicalRowIds, settledRowIds, rowHeights, measuredRowHeights)
        assertEquals(keys1.getValue(step1.single().id), keys2.getValue(step2.single().id))
        assertFalse(step2.single().id in settledRowIds)
        assertFalse(keys2.getValue(step2.single().id) in settledRowIds)
    }
}
