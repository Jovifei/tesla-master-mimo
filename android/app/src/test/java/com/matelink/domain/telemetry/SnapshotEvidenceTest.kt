package com.matelink.domain.telemetry

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class SnapshotEvidenceTest {
    private val now = Instant.parse("2026-09-02T08:00:00Z")
    @Test fun freshMqttIsLive() { assertEquals(SnapshotFreshness.LIVE, snapshotEvidence("live_mqtt", "2026-09-02T07:59:30Z", mapOf("speed" to "live_mqtt"), now).freshness) }
    @Test fun staleMqttIsRecent() { assertEquals(SnapshotFreshness.RECENT, snapshotEvidence("live_mqtt", "2026-09-02T07:50:00Z", mapOf("speed" to "live_mqtt"), now).freshness) }
    @Test fun teslamateApiIsHistoryNotLive() { assertEquals(SnapshotFreshness.HISTORY, snapshotEvidence("teslamate_api", null, emptyMap(), now).freshness) }
    @Test fun mixedSourcesAreMarked() { assertTrue(snapshotEvidence("live_mqtt", "2026-09-02T07:59:30Z", mapOf("speed" to "live_mqtt", "battery" to "database_latest"), now).isMixed) }
    @Test fun sentinelCoordinateRejected() { assertNull(usableVehicleCoordinates(0.0, 0.0)); assertNull(usableVehicleCoordinates(91.0, 1.0)); assertNotNull(usableVehicleCoordinates(30.0, 120.0)) }
}
