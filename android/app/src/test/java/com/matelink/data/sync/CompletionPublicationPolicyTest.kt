package com.matelink.data.sync

import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.DriveData
import com.matelink.data.local.CompletedChargeEvent
import com.matelink.data.local.HistoryConnectionSource
import com.matelink.domain.analytics.HistorySummaryEvidenceCodec
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** Compiled production serializer + Room summary + notification source policy. */
class CompletionPublicationPolicyTest {
    private val start = "2026-10-07T01:00:00.417Z"
    private val end = "2026-10-07T01:05:00.417Z"
    private val now = Instant.parse("2026-10-07T01:06:00Z")
    private val cloud = HistoryConnectionSource.CLOUD
    private val self = HistoryConnectionSource.SELF_HOSTED
    private fun drive(id: Int = 17, source: String? = "telemetry_mqtt", quality: String? = "observed") =
        DriveData(id, startDate = start, endDate = end, source = source, qualityState = quality)
    private fun charge(id: Int = 19, source: String? = "telemetry_mqtt", quality: String? = "observed") =
        ChargeData(id, startDate = start, endDate = end, source = source, qualityState = quality)
    private fun event(id: Int, kind: String, ended: String = end) = CompletedChargeEvent(-7, 10, id, ended,
        kind = kind, source = "telemetry_mqtt", startDate = start, scopeKey = "synthetic-scope")

    @Test fun actualSummarySerializersAcceptObservedFleetAndPreserveFractionalInstantIdentity() {
        val d = drive().toSyncSummary(-7)!!
        val c = charge().toSyncSummary(-7)!!
        assertTrue(d.canPublishCompletion(cloud, now));assertTrue(c.canPublishCompletion(cloud, now))
        assertTrue(event(17, "drive", "2026-10-07T09:05:00.417+08:00").matchesDrive(d))
        assertTrue(event(19, "charge").matchesCharge(c))
        assertFalse(event(17, "drive", "2026-10-07T01:05:00Z").matchesDrive(d))
        assertFalse(event(17, "drive").copy(carId = -8).matchesDrive(d))
        assertFalse(event(19, "drive").matchesCharge(c))
        assertFalse(event(17, "drive").copy(source = "fleet_api").matchesDrive(d))
        assertFalse(event(19, "charge").copy(startDate = "2026-10-07T00:00:00Z").matchesCharge(c))
        assertFalse(CompletedChargeEvent(-7, 10, 17, end, kind = "drive").matchesDrive(d))
    }
    @Test fun unverifiedImportArchiveWrongQualityAndMissingRawCannotBecomeCloudNaturalNotifications() {
        assertFalse(drive(source = "local_import", quality = "observed").toSyncSummary(-7)!!.canPublishCompletion(cloud, now))
        assertFalse(charge(source = "local_import", quality = "observed").toSyncSummary(-7)!!.canPublishCompletion(self, now))
        assertFalse(drive(source = "teslamate_archive").toSyncSummary(-7)!!.canPublishCompletion(cloud, now))
        assertTrue(drive(source = "teslamate_archive").toSyncSummary(-7)!!.canPublishCompletion(self, now))
        assertFalse(drive(quality = "incomplete").toSyncSummary(-7)!!.canPublishCompletion(cloud, now))
        assertFalse(charge(quality = "quarantined").toSyncSummary(-7)!!.canPublishCompletion(cloud, now))
        assertFalse(drive(source = null).toSyncSummary(-7)!!.canPublishCompletion(cloud, now))
        assertTrue(drive(source = null).toSyncSummary(-7)!!.canPublishCompletion(self, now))
        assertFalse(drive().toSyncSummary(-7)!!.copy(apiEvidence = null).canPublishCompletion(cloud, now))
        assertFalse(charge().toSyncSummary(-7)!!.copy(apiEvidence =
            HistorySummaryEvidenceCodec.encode(charge(id = 123))).canPublishCompletion(cloud, now))
    }
    @Test fun incompleteFutureAndInvalidWindowWillNotPublishThenCorrectedSameIdCan() {
        val future = drive().copy(endDate = "2026-10-07T01:10:00.417Z").toSyncSummary(-7)!!
        assertFalse(future.canPublishCompletion(cloud, now))
        assertTrue(drive().toSyncSummary(-7)!!.canPublishCompletion(cloud, now))
        assertFalse(drive().copy(startDate = end).toSyncSummary(-7)!!.canPublishCompletion(cloud, now))
        assertFalse(charge().copy(endDate = "unverified").toSyncSummary(-7)!!.canPublishCompletion(cloud, now))
        assertFalse(charge().copy(endDate = start).toSyncSummary(-7)!!.canPublishCompletion(cloud, now))
    }
    @Test fun verifiedFleetChargeCompletionCanNotifyWithUnknownEnergyButAnImportCannot() {
        val partial = charge().copy(qualityState = "incomplete",
            qualityReason = "partial_or_missing_charge_counter", chargeEnergyAdded = null, chargeEnergyUsed = null)
        val summary = partial.toSyncSummary(-7)!!
        assertNull(partial.batteryInputKwh);assertTrue(summary.canPublishCompletion(cloud, now))
        assertTrue(event(19, "charge").matchesCharge(summary))
        assertFalse(partial.copy(source = "local_import", qualityReason = "local_import_unverified")
            .toSyncSummary(-7)!!.canPublishCompletion(cloud, now))
        assertFalse(partial.copy(qualityReason = "arbitrary_failure").toSyncSummary(-7)!!.canPublishCompletion(cloud, now))
        assertFalse(partial.copy(endDate = "2026-10-07T01:10:00Z").toSyncSummary(-7)!!.canPublishCompletion(cloud, now))
        assertTrue(partial.copy(source = "fleet_api", qualityReason = "missing_charge_measurement")
            .toSyncSummary(-7)!!.canPublishCompletion(cloud, now))
        assertFalse(drive(quality = "incomplete").toSyncSummary(-7)!!.canPublishCompletion(cloud, now))
    }
    @Test fun eventReceiptUsesImmutableEndpointsNotAnUnrelatedCurrentDisplayProjection() {
        val row = drive().toSyncSummary(-7)!!.copy(endDate = "2026-10-07T01:05:01Z")
        assertEquals(end, row.completionReceipt(cloud, now)!!.endDate)
        assertTrue(event(17, "drive").matchesDrive(row))
        assertFalse(event(17, "drive", row.endDate).matchesDrive(row))
    }
}
