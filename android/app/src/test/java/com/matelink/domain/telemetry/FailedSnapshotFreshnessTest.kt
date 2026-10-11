package com.matelink.domain.telemetry

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class FailedSnapshotFreshnessTest {
    private val now = Instant.parse("2026-10-04T02:00:00Z")
    @Test fun threeDayOldSnapshotStaysHistoricalOnFailure() {
        assertEquals(SnapshotFreshness.HISTORY, failedSnapshotFreshness("2026-10-01T02:00:00Z", true, now))
    }
    @Test fun recentSnapshotIsNotPromotedToLive() {
        assertEquals(SnapshotFreshness.RECENT, failedSnapshotFreshness("2026-10-04T01:59:00Z", true, now))
    }
    @Test fun missingOrInvalidEvidenceIsUnavailable() {
        listOf(null, "invalid", "2026-10-05T02:00:00Z").forEach {
            assertEquals(SnapshotFreshness.UNAVAILABLE, failedSnapshotFreshness(it, true, now))
        }
        assertEquals(SnapshotFreshness.UNAVAILABLE, failedSnapshotFreshness("2026-10-04T01:59:00Z", false, now))
    }
    @Test fun timestampOffsetRepresentsSameInstant() {
        assertEquals(SnapshotFreshness.RECENT, failedSnapshotFreshness("2026-10-04T09:59:00+08:00", true, now))
    }
}
