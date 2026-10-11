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

    @Test fun actualSummarySerializersAcceptObservedFleetAndPreserveFractionalInstantIdentity() {
        val d = drive().toSyncSummary(-7)!!
        val c = charge().toSyncSummary(-7)!!
        assertTrue(d.canPublishCompletion(cloud, now))
        assertTrue(c.canPublishCompletion(cloud, now))
        assertTrue(CompletedChargeEvent(-7, 10, 17, "2026-10-07T09:05:00.417+08:00", kind = "drive").matchesDrive(d))
        assertTrue(CompletedChargeEvent(-7, 10, 19, end).matchesCharge(c))
        assertFalse(CompletedChargeEvent(-7, 10, 17, "2026-10-07T01:05:00Z", kind = "drive").matchesDrive(d))
        assertFalse(CompletedChargeEvent(-8, 10, 17, end, kind = "drive").matchesDrive(d))
        assertFalse(CompletedChargeEvent(-7, 10, 19, end, kind = "drive").matchesCharge(c))
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
        val corrected = drive().toSyncSummary(-7)!!
        assertTrue(corrected.canPublishCompletion(cloud, now))
        assertFalse(drive().copy(startDate = end).toSyncSummary(-7)!!.canPublishCompletion(cloud, now))
        assertFalse(charge().copy(endDate = "unverified").toSyncSummary(-7)!!.canPublishCompletion(cloud, now))
        assertFalse(charge().copy(endDate = start).toSyncSummary(-7)!!.canPublishCompletion(cloud, now))
    }
}
