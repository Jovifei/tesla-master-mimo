package com.matelink.data.local
import org.junit.Assert.*
import org.junit.Test
class CompletionStateTest {
 private val cutoff=java.time.Instant.parse("2026-10-07T05:00:00Z").toEpochMilli()
 private val fresh="2026-10-07T05:01:00Z"
 @Test fun emptyFirstSyncCreatesBaselineAndNextNaturalCompletionCreatesEvent(){
  val first=advanceCompletionState(CompletionState(null,cutoff,emptyList()),-7,1,"charge",emptyList())
  assertEquals(0,first.baseline);assertTrue(first.events.isEmpty())
  val next=advanceCompletionState(first,-7,1,"charge",listOf(1 to fresh))
  assertEquals(1,next.events.single().chargeId)
 }
 @Test fun firstArchiveNeverReplaysAndLaterOlderArchiveWithLargerIdIsRejected(){
  val first=advanceCompletionState(CompletionState(null,cutoff,emptyList()),-7,1,"charge",listOf(3 to fresh))
  assertTrue(first.events.isEmpty())
  val old=advanceCompletionState(first,-7,1,"charge",listOf(9 to "2026-10-01T05:00:00Z"))
  assertTrue(old.events.isEmpty())
 }
 @Test fun retriesDoNotDuplicateAndConsumersAreIndependent(){
  val state=advanceCompletionState(CompletionState(0,cutoff,emptyList()),-7,1,"charge",listOf(1 to fresh,1 to fresh))
  assertEquals(1,state.events.size)
  assertEquals(state.events,advanceCompletionState(state,-7,1,"charge",listOf(1 to fresh)).events)
  // A denied system delivery performs no consume; foreground can still acknowledge.
  val app=consumeCompletionEvent(state.events,1,false)
  assertTrue(app.single().appConsumed);assertFalse(app.single().systemConsumed)
  assertTrue(consumeCompletionEvent(app,1,true).isEmpty())
  val system=consumeCompletionEvent(state.events,1,true)
  assertTrue(system.single().systemConsumed);assertFalse(system.single().appConsumed)
 }
 @Test fun laterLowerIdCompletionIsNotLostAndPrunedEventsNeverReplay(){
  val start=advanceCompletionState(CompletionState(null,cutoff,emptyList()),-7,1,"charge",listOf(10 to "2026-10-01T05:00:00Z"))
  val twelve=advanceCompletionState(start,-7,1,"charge",listOf(12 to fresh))
  val consumed=twelve.copy(events=consumeCompletionEvent(consumeCompletionEvent(twelve.events,12,false),12,true))
  assertTrue(consumed.events.isEmpty())
  val late=advanceCompletionState(consumed,-7,1,"charge",listOf(12 to fresh,11 to fresh))
  assertEquals(listOf(11),late.events.map { it.chargeId })
  assertTrue(12 in late.seen);assertTrue(11 in late.seen)
 }
 @Test fun differentVehicleAndKindEventsHaveDistinctIdentity(){
  val initial=CompletionState(0,cutoff,emptyList())
  val a=advanceCompletionState(initial,-7,1,"charge",listOf(1 to fresh)).events.single()
  val b=advanceCompletionState(initial,-8,1,"charge",listOf(1 to fresh)).events.single()
  val c=advanceCompletionState(initial,-7,1,"drive",listOf(1 to fresh)).events.single()
  assertNotEquals(a,b);assertNotEquals(a,c)
 }
}
