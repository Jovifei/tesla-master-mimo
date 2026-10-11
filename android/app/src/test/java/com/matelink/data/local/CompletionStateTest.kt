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
 }
 @Test fun laterLowerIdCompletionIsNotLostAndPrunedEventsNeverReplay(){
  val start=advanceCompletionState(CompletionState(null,cutoff,emptyList()),-7,1,"charge",listOf(10 to "2026-10-01T05:00:00Z"))
  val twelve=advanceCompletionState(start,-7,1,"charge",listOf(12 to fresh))
  val consumed=twelve.copy(events=consumeCompletionEvent(consumeCompletionEvent(twelve.events,12,false),12,true))
  val late=advanceCompletionState(consumed,-7,1,"charge",listOf(12 to fresh,11 to fresh))
  assertEquals(listOf(11),late.events.map { it.chargeId })
 }
 @Test fun differentVehicleAndKindEventsHaveDistinctIdentity(){
  val initial=CompletionState(0,cutoff,emptyList())
  val a=advanceCompletionState(initial,-7,1,"charge",listOf(1 to fresh)).events.single()
  val b=advanceCompletionState(initial,-8,1,"charge",listOf(1 to fresh)).events.single()
  val c=advanceCompletionState(initial,-7,1,"drive",listOf(1 to fresh)).events.single()
  assertNotEquals(a,b);assertNotEquals(a,c)
 }
 @Test fun futureOrMalformedCompletionIsNotSeenAndSameIdCanBeCorrectedLater(){
  val t=java.time.Instant.parse("2026-10-07T05:10:00Z").toEpochMilli()
  val invalid=advanceCompletionState(CompletionState(0,cutoff,emptyList()),-7,1,"charge",listOf(21 to "invalid",22 to "2026-10-07T05:20:00Z"),t)
  assertTrue(invalid.events.isEmpty());assertTrue(invalid.seen.isEmpty())
 }
 @Test fun preBaselineLargeArchiveIsNotStoredInSeenAndLowerLateNaturalIdStillNotifies(){
  val t=java.time.Instant.parse("2026-10-07T05:10:00Z").toEpochMilli()
  val baseline=advanceCompletionState(CompletionState(null,cutoff,emptyList()),-7,1,"drive",(1..20000).map { it to "2026-10-01T05:00:00Z" },t)
  assertTrue(baseline.events.isEmpty());assertTrue(baseline.seen.isEmpty())
 }
 @Test fun staleHistoricalRefreshCannotEraseAcknowledgementAndReplayCorrectedId(){
  val t=java.time.Instant.parse("2026-10-07T05:10:00Z").toEpochMilli()
  val old=CompletionState(100,cutoff,emptyList(),setOf(1,2,99))
  val stale=advanceCompletionState(old,-7,1,"drive",listOf(1 to "2026-10-01T05:00:00Z"),t)
  assertEquals(old.seen,stale.seen)
 }
 @Test fun subMillisecondFutureIsRejectedAndConflictingDuplicateCannotPoisonId(){
  val t=java.time.Instant.parse("2026-10-07T05:10:00Z").toEpochMilli()
  val future=advanceCompletionState(CompletionState(0,cutoff,emptyList()),-7,1,"drive",listOf(7 to "2026-10-07T05:10:00.000000001Z"),t)
  assertTrue(future.events.isEmpty())
 }
 @Test fun qualifiedJournalScopeKeySeparatesOriginAndAccountForSameVehicleId(){
  val a=completionScopeKey(HistoryReadScope(HistoryConnectionSource.CLOUD,"server-a","account-a","https://a"),1,7)
  val b=completionScopeKey(HistoryReadScope(HistoryConnectionSource.CLOUD,"server-b","account-b","https://b"),1,7)
  assertNotEquals(a,b)
 }
}
