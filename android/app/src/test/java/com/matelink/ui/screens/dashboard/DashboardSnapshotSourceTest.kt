package com.matelink.ui.screens.dashboard

import com.matelink.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardSnapshotSourceTest {
    @Test
    fun fleetApiIsPresentedAsLiveEvidence() {
        assertEquals(SnapshotSourceKind.LIVE, snapshotSourceKind("fleet_api"))
    }

    @Test
    fun teslamateApiIsHistoricalEvidenceNotLive() {
        assertEquals(SnapshotSourceKind.HISTORY, snapshotSourceKind("teslamate_api"))
    }

    @Test
    fun mockFixtureIsPresentedAsMockEvidence() {
        val expected = if (BuildConfig.JOURVOLT_MOCK_LOGIN) {
            SnapshotSourceKind.MOCK
        } else {
            SnapshotSourceKind.UNAVAILABLE
        }
        assertEquals(expected, snapshotSourceKind("mock_fixture"))
    }

    @Test
    fun unknownSourceRemainsUnavailable() {
        assertEquals(SnapshotSourceKind.UNAVAILABLE, snapshotSourceKind("unexpected"))
        assertEquals(SnapshotSourceKind.UNAVAILABLE, snapshotSourceKind(null))
    }

    @Test
    fun cachedPositionKeepsLiveSnapshotAndMarksMixedEvidence() {
        val live = com.matelink.domain.telemetry.snapshotEvidence(
            "fleet_api",
            "2026-09-07T10:00:00Z",
            mapOf("battery_level" to "fleet_api")
        )
        val mixed = withCachedPositionEvidence(live)
        assertEquals("fleet_api", mixed.source)
        assertEquals("fleet_api", mixed.fieldSources["battery_level"])
        assertEquals("database_latest", mixed.fieldSources["latitude"])
        assertEquals("database_latest", mixed.fieldSources["longitude"])
        org.junit.Assert.assertTrue(mixed.isMixed)
    }
}
