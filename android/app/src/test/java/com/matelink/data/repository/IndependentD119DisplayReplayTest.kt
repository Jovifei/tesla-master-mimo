package com.matelink.data.repository

import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveDetail
import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.analytics.HistorySummaryEvidenceCodec
import com.matelink.domain.analytics.resolveDriveEnergy
import com.matelink.domain.analytics.toAnalysisDriveData
import com.matelink.domain.analytics.toRawAnalysisDriveData
import com.matelink.domain.analytics.withResolvedDriveEnergy
import com.squareup.moshi.Moshi
import org.junit.Assert.*
import org.junit.Test

/** CI parity with the independently compiled d119735 Java/Moshi replay.
 * Only synthetic 2026-01-01 records and fake scoped IDs are used.
 * No source API, vehicle, production DB, app data or network operation.
 */
class IndependentD119DisplayReplayTest {
    private val moshi = Moshi.Builder().build()
    private val rawJson = """{"drive_id":900001,"start_date":"2026-01-01T00:00:00Z","end_date":"2026-01-01T00:00:10Z","source":"telemetry_mqtt","quality_state":"observed","odometer_details":{"odometer_distance":1.0},"battery_details":null,"energy_consumed_net":8.0,"opaque":{"fixture":true}}"""
    private val detailJson = """{"drive_id":900001,"start_date":"2026-01-01T00:00:00Z","end_date":"2026-01-01T00:00:10Z","source":"telemetry_mqtt","quality_state":"observed","odometer_details":{"odometer_distance":2.0},"battery_details":{"start_battery_level":80,"end_battery_level":70},"energy_contract":{"version":1,"net_energy":{"value_kwh":1.0,"method":"api_reported_net","measurement_point":"reported_net","source":"telemetry_mqtt","quality":"reported","start_date":"2026-01-01T00:00:00Z","end_date":"2026-01-01T00:00:10Z"}}}"""

    @Test fun exactIndependentJsonRoundTripsAcrossActualSummaryAndSameIdMerge() {
        val raw = requireNotNull(moshi.adapter(DriveData::class.java).fromJson(rawJson))
        val original = requireNotNull(raw.toSyncSummary(-900001))
            .copy(apiEvidence = rawJson)
        val detail = requireNotNull(moshi.adapter(DriveDetail::class.java).fromJson(detailJson))
        val enriched = original.withResolvedDriveEnergy(detail, detail.resolveDriveEnergy())
        assertEquals(2.0, enriched.distance, 0.0)
        assertEquals(80, enriched.startBatteryLevel)
        assertEquals(70, enriched.endBatteryLevel)
        assertEquals(500.0, enriched.efficiency!!, 0.0)
        assertEquals(rawJson, HistorySummaryEvidenceCodec.sourceJson(enriched.apiEvidence))

        val offline = enriched.toAnalysisDriveData()
        assertExpected(offline)
        val merged = mergeStoredDrive(enriched, original)
        assertExpected(merged.toAnalysisDriveData())
        assertEquals(2.0, merged.distance, 0.0)
        assertEquals(80, merged.startBatteryLevel)
        assertEquals(70, merged.endBatteryLevel)
        assertEquals(500.0, merged.efficiency!!, 0.0)
        assertEquals(rawJson, HistorySummaryEvidenceCodec.sourceJson(merged.apiEvidence))
        assertEquals(1.0, merged.toRawAnalysisDriveData().distance!!, 0.0)
        assertEquals(8.0, merged.toRawAnalysisDriveData().energyConsumedNet!!, 0.0)

        // A same-ID but unverified "observed" local import must not turn
        // the qualified Fleet detail back into old source metadata.
        val weak = raw.copy(source = "local_import", qualityState = "observed",
            energyConsumedNet = null).toSyncSummary(-900001)!!
        val replay = mergeStoredDrive(weak, merged)
        assertExpected(replay.toAnalysisDriveData())
        assertEquals(rawJson, HistorySummaryEvidenceCodec.sourceJson(replay.apiEvidence))

        val differentCar = merged.copy(carId = -900002)
        assertNull(differentCar.toAnalysisDriveData().netEnergyKwh)
        assertEquals(1.0, differentCar.toAnalysisDriveData().distance!!, 0.0)
        val otherTime = merged.copy(endDate = "2026-01-01T00:00:11Z")
        assertNull(otherTime.toAnalysisDriveData().netEnergyKwh)
        assertEquals(1.0, otherTime.toAnalysisDriveData().distance!!, 0.0)
    }

    private fun assertExpected(drive: DriveData) {
        assertEquals("telemetry_mqtt", drive.source)
        assertEquals(2.0, drive.distance!!, 0.0)
        assertEquals(80, drive.startBatteryLevel)
        assertEquals(70, drive.endBatteryLevel)
        assertEquals(1.0, drive.netEnergyKwh!!, 0.0)
        assertEquals(500.0, drive.efficiencyWhKm!!, 0.0)
        assertEquals("reported", drive.energyContract?.netEnergy?.quality)
    }
}
