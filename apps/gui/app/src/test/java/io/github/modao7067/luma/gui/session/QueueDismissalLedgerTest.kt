package io.github.modao7067.luma.gui.session

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QueueDismissalLedgerTest {
    private fun view(r:SessionRecord)=SessionView(SessionSummary(r.id,r.title,r.environment,r.cwd,cli=r.cli),connected=true)
    @Test fun allTwelveClisDismissEveryUnsettledStateWithoutChangingDeliveryTruthOrLosingMedia() {
        for(cli in CliCatalog.ids) for(state in listOf("pending","unknown","accepted","queued")) {
            val r=SessionRecord("local","Title","root","/work",cli=cli,draft="same",images= mutableListOf(StoredImage("i","file:///i","/i","i")))
            SubmissionLedger.begin(r,"request",busy=true); r.submissions.single().state=state
            val card=SubmissionLedger.project(r,view(r)).queue.single(); assertTrue(card.canDismiss)
            SubmissionLedger.dismiss(r,card){}
            assertEquals(state,r.submissions.single().state); assertEquals("i",r.submissions.single().images.single().id)
            var projected=SubmissionLedger.project(r,view(r)); repeat(4){projected=SubmissionLedger.project(r,projected)}
            assertTrue("$cli/$state",projected.queue.isEmpty()); assertTrue(projected.messages.isEmpty())
            val restored=decodeSessionIndex(JSONObject(encodeSessionIndex(listOf(r)).toString())).single()
            assertTrue(restored.submissions.single().dismissed); assertEquals(state,restored.submissions.single().state)
            assertTrue(SubmissionLedger.project(restored,view(restored)).queue.isEmpty())
            SubmissionLedger.state(restored,"request","local"); assertEquals(state,restored.submissions.single().state)
            SubmissionLedger.consumed(restored,"request"); SubmissionLedger.accepted(restored,id="request")
            val consumed=SubmissionLedger.project(restored,view(restored).copy(messages=listOf(ConversationItem("request","user","same"))))
            assertTrue(consumed.queue.isEmpty()); assertEquals(1,consumed.messages.size)
        }
    }
    @Test fun dismissalSaveFailureRollsBackBothIntentAndLocalClaimAndNeverRestoresCrossedInputToComposer() {
        val r=SessionRecord("local","Title","root","/work",draft="old")
        SubmissionLedger.begin(r,"one",busy=true); SubmissionLedger.state(r,"one","local")
        val q=SubmissionLedger.project(r,view(r)).queue.single()
        assertThrows(SubmissionPersistenceFailure::class.java){SubmissionLedger.dismiss(r,q){throw java.io.IOException("fixture")}}
        assertFalse(r.submissions.single().dismissed); assertEquals("local",r.submissions.single().state); assertTrue(r.queueDismissals.isEmpty())
        SubmissionLedger.state(r,"one","unknown"); SubmissionLedger.dismiss(r,q){}
        SubmissionLedger.rejected(r,"one"); assertEquals("",r.draft)
        val placeholder=ConversationItem("one","user","old",nativeInputEvidence=false)
        assertTrue(SubmissionLedger.project(r,view(r).copy(messages=listOf(placeholder))).messages.isEmpty())
    }
    @Test fun anonymousNativeEntryNeedsExactAckBindingNotMatchingTextAndTombstoneSurvivesLateSnapshots() {
        val r=SessionRecord("local","Title","root","/work",draft="same")
        SubmissionLedger.begin(r,"one",busy=true)
        val native=QueueEntry("native-q","same","待发",clientUserMessageId=null)
        assertEquals(2,SubmissionLedger.project(r,view(r).copy(queue=listOf(native))).queue.size)
        SubmissionLedger.bindQueue(r,"one","native-q")
        val linked=SubmissionLedger.project(r,view(r).copy(queue=listOf(native)))
        assertEquals(1,linked.queue.size); assertEquals("one",linked.queue.single().clientUserMessageId)
        SubmissionLedger.dismiss(r,linked.queue.single()){}
        val restored=decodeSessionIndex(JSONObject(encodeSessionIndex(listOf(r)).toString())).single()
        val fresh=SubmissionLedger.project(restored,view(restored).copy(queue=listOf(native,QueueEntry("other","same","待发"))))
        assertEquals(listOf("other"),fresh.queue.map{it.id}); assertEquals("native-q",restored.submissions.single().nativeQueueId)
    }
    @Test fun nativeEntryWithoutRetractionCapabilityIsStillDismissibleButIsNotClaimedAsCancelled() {
        val r=SessionRecord("local","Title","root","/work")
        val q=SubmissionLedger.project(r,view(r).copy(queue=listOf(QueueEntry("opaque","native","waiting",canEdit=false,canSteer=false,canRemove=false)))).queue.single()
        assertTrue(q.canDismiss); assertFalse(q.canRemove)
        SubmissionLedger.dismiss(r,q){}
        assertEquals("waiting",r.queueDismissals.single().state); assertTrue(r.submissions.isEmpty())
        assertTrue(SubmissionLedger.project(r,view(r).copy(queue=listOf(q))).queue.isEmpty())
    }
    @Test fun anInvisibleUnresolvedNativeDismissalIsNotAnEmptySessionThatMayBePruned() {
        val r=SessionRecord("local","Title","root","/work",threadId="native")
        val empty=view(r)
        r.queueDismissals += StoredQueueDismissal("native-q",state="waiting")
        assertFalse(EmptySessionPolicy.canDiscard(r,empty,true))
        r.queueDismissals.single().state="unknown";assertFalse(EmptySessionPolicy.canDiscard(r,empty,true))
        r.queueDismissals.single().state="rejected";assertFalse(EmptySessionPolicy.canDiscard(r,empty,true))
    }

}
