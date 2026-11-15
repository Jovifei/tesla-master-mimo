package com.matelink.ui.screens.range

import com.matelink.data.api.models.DrivesResponse
import com.matelink.data.api.models.receivedDrives
import com.matelink.domain.analytics.PersonalizedRangeSource
import com.matelink.domain.analytics.RangeSpeedBand
import com.matelink.domain.analytics.estimatePersonalizedRange
import com.matelink.domain.analytics.speedBand
import com.squareup.moshi.Moshi
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

/** Upstream-shaped receipt -> the actual VM mappers -> real speed-group consumers. */
class RangeSpeedUnitsConsumerTest {
    private val date = LocalDate.of(2026, 10, 14)
    private fun receipt(unit: String, speed: String = "60.0", energy: Double = 5.0) =
        Moshi.Builder().build().adapter(DrivesResponse::class.java).fromJson("""{
            "data":{"units":{"unit_of_length":"$unit","unit_of_temperature":"C"},"drives":[{
                "drive_id":7,"source":"teslamate_archive","quality_state":"observed",
                "start_date":"2026-10-14T01:00:00Z","end_date":"2026-10-14T02:00:00Z",
                "speed_avg":$speed,"odometer_details":{"odometer_distance":20.0},
                "range_rated":{"start_range":100.0,"end_range":80.0},
                "outside_temp_avg":20.0,"energy_consumed_net":$energy
            }]}}
        """)!!.data!!.receivedDrives().single()

    @Test fun thirtyMilesPerHourBelongsToTheActualHighRangeGroup() {
        val drive = receipt("mi", "30.0")
        val trip = drive.toRangeTrip()!!
        assertEquals(48.28032, trip.speedAvgKmh!!, 0.000001)
        val (low, high) = rangeSpeedDeviations(listOf(trip))
        assertNull(low)
        assertEquals(trip.deviationPercent, high!!, 0.0)
        assertEquals(30.0, drive.speedAvg!!, 0.0) // Raw receipt remains unchanged.
    }

    @Test fun imperialSamplesUseHighBandAndMeetTheActualPersonalizedGroupGate() {
        val sample = receipt("mi").toPersonalizedRangeSample(date)
        assertEquals(96.56064, sample.speedKmh!!, 0.000001)
        assertEquals(RangeSpeedBand.HIGH, sample.speedKmh.speedBand())
        val result = estimatePersonalizedRange(List(5) { sample }, 60.0, 20.0, 100.0, date)
        assertEquals(PersonalizedRangeSource.GROUPED, result.source)
        assertEquals(5, result.sampleCount)
        assertNotNull(result.rangeKm)
    }

    @Test fun kilometresAreNotConvertedAndTrueZeroRemainsKnownLowSpeed() {
        val metric = receipt("km").toPersonalizedRangeSample(date)
        assertEquals(60.0, metric.speedKmh!!, 0.0)
        assertEquals(RangeSpeedBand.CRUISE, metric.speedKmh.speedBand())
        val zero = receipt("mi", "0.0")
        assertEquals(0.0, zero.toPersonalizedRangeSample(date).speedKmh!!, 0.0)
        val trip = zero.toRangeTrip()!!
        val (low, high) = rangeSpeedDeviations(listOf(trip))
        assertEquals(trip.deviationPercent, low!!, 0.0)
        assertNull(high)
    }

    @Test fun explicitUnknownAndMissingSpeedDoNotInventLowSpeed() {
        val unknown = receipt("yards")
        assertNull(unknown.toRangeTrip())
        assertNull(unknown.toPersonalizedRangeSample(date).speedKmh)
        assertNull(unknown.toPersonalizedRangeSample(date).speedKmh.speedBand())
        val missing = receipt("mi", "null")
        assertNull(missing.toPersonalizedRangeSample(date).speedKmh)
        val groups = rangeSpeedDeviations(listOf(missing.toRangeTrip()!!))
        assertNull(groups.first)
        assertNull(groups.second)
    }

    @Test fun signedAndZeroEnergyArePreservedWithoutWeakeningRangeModelGates() {
        for (energy in listOf(-1.0, 0.0)) {
            val drive = receipt("mi", energy = energy)
            assertEquals(energy, drive.toRangeTrip()!!.energyConsumedKwh!!, 0.0)
            val sample = drive.toPersonalizedRangeSample(date)
            assertEquals(energy, sample.energyKwh!!, 0.0)
            val result = estimatePersonalizedRange(List(5) { sample }, 60.0, 20.0, 100.0, date)
            assertEquals(PersonalizedRangeSource.UNAVAILABLE, result.source)
            assertNull(result.rangeKm)
        }
    }

    @Test fun fiveCandidatesWithUnknownTemperatureUnitsCannotClaimAQualifiedConditionGroup() {
        val raw = receipt("mi").copy(temperatureUnit = "")
        val sample = raw.toPersonalizedRangeSample(date)
        assertEquals(20.0, raw.outsideTempAvg!!, 0.0)
        assertNull(sample.temperatureC)
        val result = estimatePersonalizedRange(List(5) { sample }, 60.0, 20.0, 100.0, date)
        assertEquals(PersonalizedRangeSource.UNAVAILABLE, result.source)
        assertEquals(5, result.candidateSampleCount)
        assertEquals(0, result.sampleCount)
        assertNull(result.rangeKm)
    }
}
