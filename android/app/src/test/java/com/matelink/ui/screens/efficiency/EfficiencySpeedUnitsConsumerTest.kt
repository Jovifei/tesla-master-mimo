package com.matelink.ui.screens.efficiency

import com.matelink.data.api.models.DrivesResponse
import com.matelink.data.api.models.receivedDrives
import com.squareup.moshi.Moshi
import org.junit.Assert.*
import org.junit.Test

/** Tests the actual histogram consumer used by EfficiencyViewModel. */
class EfficiencySpeedUnitsConsumerTest {
    private fun receipt(unit: String, speed: String = "60.0", energy: Double = 5.0) =
        Moshi.Builder().build().adapter(DrivesResponse::class.java).fromJson("""{
            "data":{"units":{"unit_of_length":"$unit"},"drives":[{
                "drive_id":7,"source":"teslamate_archive","quality_state":"observed",
                "speed_avg":$speed,"odometer_details":{"odometer_distance":20.0},
                "energy_consumed_net":$energy
            }]}}
        """)!!.data!!.receivedDrives().single()

    @Test fun sixtyMilesPerHourGoesIntoTheActualEightyToHundredKilometreBin() {
        val drive = receipt("mi")
        val bins = buildEfficiencyBySpeed(listOf(drive))
        assertEquals(listOf("80-100"), bins.map { it.first })
        assertEquals(drive.efficiencyWhKm!!, bins.single().second, 0.0)
        assertEquals(60.0, drive.speedAvg!!, 0.0)
    }

    @Test fun metricSixtyRemainsInTheMetricSixtyToEightyBin() {
        assertEquals(listOf("60-80"), buildEfficiencyBySpeed(listOf(receipt("km"))).map { it.first })
    }

    @Test fun unknownUnitMissingAndTrueZeroSpeedDoNotProduceFakeBuckets() {
        for (drive in listOf(receipt("yards"), receipt(""), receipt("mi", "null"), receipt("mi", "0.0"))) {
            assertTrue(buildEfficiencyBySpeed(listOf(drive)).isEmpty())
        }
    }

    @Test fun regenerativeAndTrueZeroEfficiencyAreRetainedInTheirQualifiedSpeedBin() {
        for (energy in listOf(-1.0, 0.0)) {
            val drive = receipt("mi", energy = energy)
            val bin = buildEfficiencyBySpeed(listOf(drive)).single()
            assertEquals("80-100", bin.first)
            assertEquals(drive.efficiencyWhKm!!, bin.second, 0.0)
        }
    }
}
