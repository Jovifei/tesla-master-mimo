package com.matelink.data.local
import org.junit.Assert.*
import org.junit.Test
class CompletedChargePolicyTest {
 @Test fun restoredOlderHistoryDoesNotReplayAndNewCompletionDoes(){
  val cutoff=java.time.Instant.parse("2026-10-07T05:00:00Z").toEpochMilli()
  assertFalse(isNewCompletedCharge(12,"2026-10-01T05:00:00Z",setOf(10),cutoff))
  assertTrue(isNewCompletedCharge(12,"2026-10-07T13:01:00+08:00",setOf(10),cutoff))
  assertFalse(isNewCompletedCharge(10,"2026-10-07T05:01:00Z",setOf(10),cutoff))
  assertFalse(isNewCompletedCharge(12,"invalid",setOf(10),cutoff))
 }
 @Test fun firstEmptyBaselineDoesNotSuppressFirstPositiveId(){
  assertTrue(isNewCompletedCharge(1,"2026-10-07T05:00:01Z",emptySet(),java.time.Instant.parse("2026-10-07T05:00:00Z").toEpochMilli()))
 }
}
