package com.matelink.ui.screens.range

import com.matelink.data.api.models.CarStatusResponse
import com.matelink.data.api.models.DrivesResponse
import com.matelink.data.api.models.Units
import com.matelink.data.api.models.receivedDrives
import com.matelink.data.repository.CarStatusWithUnits
import com.matelink.domain.analytics.PersonalizedRangeSource
import com.matelink.domain.analytics.RangeSpeedBand
import com.matelink.domain.analytics.estimatePersonalizedRange
import com.matelink.domain.analytics.speedBand
import com.squareup.moshi.Moshi
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

/** Actual upstream-shaped status and drive consumers meet in the real range model. */
class RangeStatusUnitsConsumerTest {
    private val moshi = Moshi.Builder().build()

    private fun status(unit: String, speed: String = "60.0", range: String = "200.0"): CarStatusWithUnits {
        val data = moshi.adapter(CarStatusResponse::class.java).fromJson("""{
            "data":{"units":{"unit_of_length":"$unit"},"status":{
                "driving_details":{"speed":$speed},"battery_details":{"rated_battery_range":$range}
            }}}
        """)!!.data!!
        return CarStatusWithUnits(data.status!!, data.units ?: Units())
    }

    @Test fun currentMilesAndHistoricalMilesSelectTheSameActualHighSpeedCohort() {
        val current = rangeStatusInputs(status("mi"))
        val drive = moshi.adapter(DrivesResponse::class.java).fromJson("""{
            "data":{"units":{"unit_of_length":"mi","unit_of_temperature":"C"},"drives":[{
                "drive_id":7,"source":"teslamate_archive","quality_state":"observed",
                "start_date":"2026-10-14T01:00:00Z","end_date":"2026-10-14T02:00:00Z",
                "speed_avg":60.0,"odometer_details":{"odometer_distance":20.0},
                "energy_consumed_net":5.0,"outside_temp_avg":20.0
            }]}}
        """)!!.data!!.receivedDrives().single()
        val date = LocalDate.of(2026, 10, 14)
        val sample = drive.toPersonalizedRangeSample(date)
        assertEquals(96.56064, current.speedKmh!!, 0.000001)
        assertEquals(sample.speedKmh!!, current.speedKmh!!, 0.0)
        assertEquals(RangeSpeedBand.HIGH, current.speedKmh.speedBand())
        val result = estimatePersonalizedRange(List(5) { sample }, 60.0, 20.0, current.speedKmh, date)
        assertEquals(PersonalizedRangeSource.GROUPED, result.source)
        assertEquals(5, result.sampleCount)
        assertNotNull(result.rangeKm)
    }

    @Test fun currentRatedMilesBecomeKilometresWithoutChangingTheRawResponse() {
        val response = status("mi")
        val current = rangeStatusInputs(response)
        assertEquals(321.8688, current.ratedRangeKm!!, 0.000001)
        assertEquals(200.0, response.status.ratedBatteryRangeKm!!, 0.0)
        assertEquals(60.0, response.status.speed!!, 0.0)
    }

    @Test fun explicitKilometresRemainCanonicalAndZeroSpeedRemainsKnown() {
        val metric = rangeStatusInputs(status("km"))
        assertEquals(60.0, metric.speedKmh!!, 0.0)
        assertEquals(200.0, metric.ratedRangeKm!!, 0.0)
        val stopped = rangeStatusInputs(status("mi", "0.0"))
        assertEquals(0.0, stopped.speedKmh!!, 0.0)
        assertEquals(RangeSpeedBand.LOW, stopped.speedKmh.speedBand())
    }

    @Test fun explicitUnknownUnitsDoNotInventCanonicalMotionOrRatedRange() {
        for (unit in listOf("yards", "", "unknown")) {
            val current = rangeStatusInputs(status(unit))
            assertNull(current.speedKmh)
            assertNull(current.ratedRangeKm)
            assertNull(current.speedKmh.speedBand())
        }
    }

    @Test fun absentAndNegativeStatusFieldsDoNotInventKnownMeasurements() {
        for (response in listOf(null, status("mi", "null", "null"), status("km", "-1.0", "-1.0"))) {
            val current = rangeStatusInputs(response)
            assertNull(current.speedKmh)
            assertNull(current.ratedRangeKm)
        }
    }

    @Test fun missingNullAndEmptyStatusUnitsNeverBorrowAnUnprovenUnit() {
        for (metadata in listOf("", ",\"units\":null", ",\"units\":{}")) {
            val data = moshi.adapter(CarStatusResponse::class.java).fromJson("""{
                "data":{"status":{"driving_details":{"speed":60.0},
                    "battery_details":{"rated_battery_range":200.0}}$metadata}}
            """)!!.data!!
            val current = rangeStatusInputs(CarStatusWithUnits(data.status!!, data.units ?: Units()))
            assertNull(current.speedKmh)
            assertNull(current.ratedRangeKm)
            assertNull(current.speedKmh.speedBand())
        }
    }
}
