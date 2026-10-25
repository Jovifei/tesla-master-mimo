package com.matelink.ui.screens.efficiency

import com.matelink.data.api.models.DrivesResponse
import com.matelink.data.api.models.receivedDrives
import com.squareup.moshi.Moshi
import org.junit.Assert.*
import org.junit.Test

/** Tests the actual histogram consumer used by EfficiencyViewModel. */
class EfficiencySpeedUnitsConsumerTest {
    private fun receipt(unit: String, speed: String = "60.0", energy: Double = 5.0,
                        distance: Double = 20.0, id: Int = 7,
                        date: String = "2026-10-14T01:00:00Z") =
        Moshi.Builder().build().adapter(DrivesResponse::class.java).fromJson("""{
            "data":{"units":{"unit_of_length":"$unit"},"drives":[{
                "drive_id":$id,"source":"teslamate_archive","quality_state":"observed",
                "start_date":"$date",
                "speed_avg":$speed,"odometer_details":{"odometer_distance":$distance},
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

    @Test fun speedBinUsesPairedEnergyOverPairedDistanceInsteadOfTripMean() {
        val short = receipt("km", energy = 1.0, distance = 10.0, id = 1)
        val long = receipt("km", energy = 20.0, distance = 100.0, id = 2)

        assertEquals(21.0 / 110.0 * 1000.0,
            buildEfficiencyBySpeed(listOf(short, long)).single().second, 0.0001)
    }

    @Test fun monthlyTrendWeightsTheSameQualifiedPairsAndKeepsNetRecovery() {
        val october = listOf(
            receipt("km", energy = 1.0, distance = 10.0, id = 1),
            receipt("km", energy = 20.0, distance = 100.0, id = 2),
            receipt("km", energy = 3.0, distance = 0.0, id = 3)
        )
        val november = receipt("km", energy = -0.5, distance = 5.0, id = 4,
            date = "2026-11-01T01:00:00Z")

        val trend = buildEfficiencyTrend(october + november)

        assertEquals(listOf("2026-10", "2026-11"), trend.map { it.month })
        assertEquals(21.0 / 110.0 * 1000.0, trend[0].averageWhKm, 0.0001)
        assertEquals(2, trend[0].sampleCount)
        assertEquals(-100.0, trend[1].averageWhKm, 0.0001)
        assertEquals(1, trend[1].sampleCount)
    }
}
