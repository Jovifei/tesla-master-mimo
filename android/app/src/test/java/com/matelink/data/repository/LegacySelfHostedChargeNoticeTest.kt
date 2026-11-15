package com.matelink.data.repository

import com.matelink.data.api.models.ChargeDetail
import com.matelink.data.local.HistoryConnectionSource
import com.matelink.data.local.VehicleContext
import com.matelink.data.local.cloudVehicleStableIdentity
import com.matelink.data.local.selfHostedVehicleStableIdentity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacySelfHostedChargeNoticeTest {
    private val server = "https://adapter.example"
    private val start = "2026-11-03T08:10:00Z"
    private val end = "2026-11-03T09:05:00Z"
    private val legacy = VehicleContext(
        remoteApiCarId = 7,
        stableIdentity = selfHostedVehicleStableIdentity(server, 7),
        localHistoryCarId = -71,
        connectionSource = HistoryConnectionSource.SELF_HOSTED,
        serverIdentity = server
    )
    private val cloud = VehicleContext(
        remoteApiCarId = 7,
        stableIdentity = cloudVehicleStableIdentity("account", "vehicle"),
        localHistoryCarId = 71,
        connectionSource = HistoryConnectionSource.CLOUD,
        serverIdentity = server
    )

    private fun detail(source: String? = null, sourceId: String? = null) =
        ChargeDetail(71, startDate = start, endDate = end, source = source, sourceId = sourceId)

    @Test
    fun legacySelfHostedDetailWithoutAnySourceMetadataIsReportedUnverified() {
        assertTrue(detail().isLegacySourceUnverified(legacy))
    }

    @Test
    fun anyProviderIdentityKeepsTheLegacyDetailVerifiable() {
        assertFalse(detail(source = "teslamate_archive").isLegacySourceUnverified(legacy))
        assertFalse(detail(sourceId = "8f1c").isLegacySourceUnverified(legacy))
        assertFalse(detail().copy(sourceInstanceId = "instance").isLegacySourceUnverified(legacy))
        assertFalse(detail().copy(sourceRecordId = "record").isLegacySourceUnverified(legacy))
    }

    @Test
    fun cloudScopeAndMissingWindowAreNotLegacySelfHostedEvidence() {
        assertFalse(detail().isLegacySourceUnverified(cloud))
        assertFalse(detail().copy(startDate = null).isLegacySourceUnverified(legacy))
        assertFalse(detail().copy(endDate = "not-a-date").isLegacySourceUnverified(legacy))
    }
}