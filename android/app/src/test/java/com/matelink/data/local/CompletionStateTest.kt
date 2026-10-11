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
 @Test fun futureOrMalformedCompletionIsNotSeenAndSameIdCanBeCorrectedLater() {
  val t=java.time.Instant.parse("2026-10-07T05:10:00Z").toEpochMilli()
  val initialized=CompletionState(0,cutoff,emptyList())
  val invalid=advanceCompletionState(initialized,-7,1,"charge",
    listOf(21 to "invalid",22 to "2026-10-07T05:20:00Z",23 to "2026-10-07T05:10:01Z"),t)
  assertTrue(invalid.events.isEmpty());assertTrue(invalid.seen.isEmpty());assertEquals(0,invalid.baseline)
  val corrected=advanceCompletionState(invalid,-7,1,"charge",
    listOf(22 to "2026-10-07T05:08:00Z",21 to "2026-10-07T05:09:00Z"),t)
  assertEquals(listOf(22,21),corrected.events.map { it.chargeId })
  assertEquals(setOf(21,22),corrected.seen);assertEquals(22,corrected.baseline)
 }
 @Test fun preBaselineLargeArchiveIsNotStoredInSeenAndLowerLateNaturalIdStillNotifies() {
  val t=java.time.Instant.parse("2026-10-07T05:10:00Z").toEpochMilli()
  val baseline=advanceCompletionState(CompletionState(null,cutoff,emptyList()),-7,1,"drive",
    (1..20000).map { it to "2026-10-01T05:00:00Z" },t)
  assertEquals(20000,baseline.baseline);assertTrue(baseline.events.isEmpty());assertTrue(baseline.seen.isEmpty())
  val late=advanceCompletionState(baseline,-7,1,"drive",listOf(5 to "2026-10-07T05:09:00Z"),t)
  assertEquals(listOf(5),late.events.map { it.chargeId })
  val again=advanceCompletionState(late,-7,1,"drive",listOf(5 to "2026-10-07T05:09:00Z"),t)
  assertEquals(1,again.events.size);assertEquals(setOf(5),again.seen)
 }
 @Test fun staleHistoricalRefreshCannotEraseAcknowledgementAndReplayCorrectedId() {
  val t=java.time.Instant.parse("2026-10-07T05:10:00Z").toEpochMilli()
  val old=CompletionState(100,cutoff,emptyList(),setOf(1,2,99))
  val stale=advanceCompletionState(old,-7,1,"drive",
    listOf(1 to "2026-10-01T05:00:00Z",2 to "2026-10-01T05:00:00Z",99 to "2026-10-07T05:08:00Z"),t)
  assertEquals(setOf(1,2,99),stale.seen);assertTrue(stale.events.isEmpty())
  val corrected=advanceCompletionState(stale,-7,1,"drive",listOf(1 to fresh,2 to fresh),t)
  assertTrue(corrected.events.isEmpty());assertEquals(old.seen,corrected.seen)
 }
 @Test fun subMillisecondFutureIsRejectedAndConflictingDuplicateCannotPoisonId() {
  val t=java.time.Instant.parse("2026-10-07T05:10:00Z").toEpochMilli()
  val initial=CompletionState(0,cutoff,emptyList())
  val future=advanceCompletionState(initial,-7,1,"drive",listOf(7 to "2026-10-07T05:10:00.000000001Z"),t)
  assertTrue(future.seen.isEmpty());assertTrue(future.events.isEmpty())
  val conflict=advanceCompletionState(initial,-7,1,"drive",listOf(7 to fresh,7 to "2026-10-07T05:02:00Z"),t)
  assertTrue(conflict.seen.isEmpty());assertTrue(conflict.events.isEmpty())
  val corrected=advanceCompletionState(conflict,-7,1,"drive",listOf(7 to fresh),t)
  assertEquals(7,corrected.events.single().chargeId)
 }
}
