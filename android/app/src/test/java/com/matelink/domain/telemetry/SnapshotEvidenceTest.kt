package com.matelink.domain.telemetry

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotEvidenceTest {
    private val now = Instant.parse("2026-08-29T12:00:00Z")

    @Test
    fun freshMqttObservationIsLive() {
        val evidence = snapshotEvidence(
            source = "live_mqtt",
            observedAt = "2026-08-29T11:59:30Z",
            fieldSources = mapOf("speed" to "live_mqtt"),
            now = now
        )

        assertEquals(SnapshotFreshness.LIVE, evidence.freshness)
        assertTrue(evidence.isLive)
        assertFalse(evidence.isMixed)
    }

    @Test
    fun staleMqttObservationIsRecentNotLive() {
        val evidence = snapshotEvidence(
            source = "live_mqtt",
            observedAt = "2026-08-29T11:57:59Z",
            fieldSources = mapOf("speed" to "live_mqtt"),
            now = now
        )

        assertEquals(SnapshotFreshness.RECENT, evidence.freshness)
        assertFalse(evidence.isLive)
    }

    @Test
    fun mixedFieldSourcesArePreservedAsMixedEvidence() {
        val evidence = snapshotEvidence(
            source = "live_mqtt",
            observedAt = "2026-08-29T11:59:30Z",
            fieldSources = mapOf(
                "speed" to "live_mqtt",
                "battery" to "database_latest"
            ),
            now = now
        )

        assertEquals(SnapshotFreshness.LIVE, evidence.freshness)
        assertTrue(evidence.isMixed)
    }

    @Test
    fun databaseAndUnknownSourcesAreNeverLive() {
        assertEquals(
            SnapshotFreshness.HISTORY,
            snapshotEvidence("database_latest", null, emptyMap(), now).freshness
        )
        assertEquals(
            SnapshotFreshness.UNAVAILABLE,
            snapshotEvidence(null, null, emptyMap(), now).freshness
        )
    }

    @Test
    fun coordinateValidationRejectsSentinelAndOutOfRangeValues() {
        assertNull(usableVehicleCoordinates(0.0, 0.0))
        assertNull(usableVehicleCoordinates(91.0, 10.0))
        assertNull(usableVehicleCoordinates(10.0, 181.0))
        assertNull(usableVehicleCoordinates(Double.NaN, 10.0))
        assertEquals(0.0 to 10.0, usableVehicleCoordinates(0.0, 10.0))
    }
}
