package com.matelink.ui.screens.timeline

import com.matelink.data.api.models.DrivesResponse
import com.matelink.data.api.models.receivedDrives
import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.analytics.HistorySummaryEvidenceCodec
import com.matelink.domain.analytics.toAnalysisDriveData
import com.squareup.moshi.Moshi
import org.junit.Assert.*
import org.junit.Test

/** Same-response units through the actual timeline mapper, including cached restart. */
class TimelineUnitsConsumerTest {
    private val moshi = Moshi.Builder().build()

    private fun receipt(unit: String, distance: String = "10.0", energy: Double = 1.0) =
        moshi.adapter(DrivesResponse::class.java).fromJson("""{
            "data":{"units":{"unit_of_length":"$unit"},"drives":[{
                "drive_id":7,"source":"teslamate_archive","quality_state":"observed",
                "start_date":"2026-10-14T01:00:00Z","end_date":"2026-10-14T02:00:00Z",
                "duration_min":60,"odometer_details":{"odometer_distance":$distance},
                "energy_consumed_net":$energy
            }]}}
        """)!!.data!!.receivedDrives().single()

    @Test fun milesAreConvertedOnceForTheExistingKilometreLabel() {
        val drive = receipt("mi")
        val before = HistorySummaryEvidenceCodec.encode(drive)
        val event = drive.toTimelineDriveEvent()!!
        assertEquals(16.09344, event.distance!!, 0.000001)
        assertEquals(10.0, drive.distance!!, 0.0)
        assertEquals(before, HistorySummaryEvidenceCodec.encode(drive))
    }

    @Test fun kilometresAreNotConvertedAgain() {
        assertEquals(10.0, receipt("km").toTimelineDriveEvent()!!.distance!!, 0.0)
    }

    @Test fun unknownUnitsAndMissingDistancesStayUnavailable() {
        assertNull(receipt("yards").toTimelineDriveEvent()!!.distance)
        assertNull(receipt("").toTimelineDriveEvent()!!.distance)
        assertNull(receipt("mi", "null").toTimelineDriveEvent()!!.distance)
    }

    @Test fun trueZeroIsRetained() {
        assertEquals(0.0, receipt("mi", "0.0").toTimelineDriveEvent()!!.distance!!, 0.0)
    }

    @Test fun negativeDistanceIsUnavailable() {
        assertNull(receipt("mi", "-1.0").toTimelineDriveEvent()!!.distance)
    }

    @Test fun regenerativeEnergyAndRecordIdentityArePreserved() {
        val drive = receipt("mi", energy = -1.0)
        val event = drive.toTimelineDriveEvent()!!
        assertEquals(drive.efficiencyWhKm!!, event.efficiencyWhKm!!, 0.0)
        assertTrue(event.efficiencyWhKm!! < 0.0)
        assertEquals(7, event.driveId)
        assertEquals("2026-10-14T01:00:00Z", event.timestamp)
        assertEquals("2026-10-14", event.date)
        assertEquals(60, event.durationMin)
    }

    @Test fun cachedRestartPreservesMilesEvidenceAndProducesKilometres() {
        val drive = receipt("mi", energy = -1.0)
        val stored = drive.toSyncSummary(9)!!
        val restarted = stored.toAnalysisDriveData()
        val event = restarted.toTimelineDriveEvent()!!
        assertEquals(9, stored.carId)
        assertEquals("mi", restarted.lengthUnit)
        assertEquals(16.09344, event.distance!!, 0.000001)
        assertEquals(drive.efficiencyWhKm!!, event.efficiencyWhKm!!, 0.000001)
        assertEquals(HistorySummaryEvidenceCodec.sourceJson(HistorySummaryEvidenceCodec.encode(drive)),
            HistorySummaryEvidenceCodec.sourceJson(stored.apiEvidence))
    }
}
