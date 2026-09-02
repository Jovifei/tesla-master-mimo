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
}
