package com.matelink.ui.screens.drives

import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveOdometerDetails
import com.matelink.data.api.models.Units
import com.matelink.domain.model.UnitFormatter
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.round
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivesSummaryEvidenceTest {
    @Test
    fun distanceFiltersUseTheDisplayedTenAndHundredKilometreBoundaries() {
        val cases = listOf(
            9.999 to DriveDistanceFilter.COMMUTE,
            10.0 to DriveDistanceFilter.DAY_TRIP,
            99.999 to DriveDistanceFilter.DAY_TRIP,
            100.0 to DriveDistanceFilter.ROAD_TRIP
        )
        for ((kilometres, expected) in cases) {
            for ((distance, unit) in listOf(kilometres to "km", (kilometres / 1.609344) to "mi")) {
                val matching = DriveDistanceFilter.entries.filter {
                    it != DriveDistanceFilter.ALL && drive(distance).copy(lengthUnit = unit).matchesDistanceFilter(it)
                }
                assertEquals("distance=$distance $unit", listOf(expected), matching)
            }
        }
    }

    @Test
    fun distanceFilterLabelsMatchTheActualBoundariesInBothLanguages() {
        val miles = Units(unitOfLength = "mi")
        val first = round(UnitFormatter.distanceKmDisplayValue(10.0, miles) * 10) / 10
        val second = round(UnitFormatter.distanceKmDisplayValue(100.0, miles) * 10) / 10
        assertEquals(6.2, first, 0.0)
        assertEquals(62.1, second, 0.0)
        for (path in listOf("src/main/res/values/strings.xml", "src/main/res/values-zh/strings.xml")) {
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))
                .getElementsByTagName("string")
            val labels = (0 until nodes.length).map { nodes.item(it) }
                .associate { it.attributes.getNamedItem("name").nodeValue to it.textContent }
            assertTrue(labels.getValue("filter_commute_km").contains("10 km"))
            assertTrue(labels.getValue("filter_day_trip_km").contains("10–<100 km"))
            assertTrue(labels.getValue("filter_road_trip_km").contains("100 km"))
            assertTrue(labels.getValue("filter_commute_mi").contains("6.2 mi"))
            assertTrue(labels.getValue("filter_day_trip_mi").contains("6.2–<62.1 mi"))
            assertTrue(labels.getValue("filter_road_trip_mi").contains("62.1 mi"))
            assertTrue(labels.getValue("filter_commute_mi").contains(if (path.contains("values-zh")) "约" else "approx."))
            assertTrue(labels.getValue("filter_road_trip_mi").contains(if (path.contains("values-zh")) "约" else "approx."))
        }
    }

    @Test
    fun unknownDistanceIsRetainedOnlyInUnfilteredWindowAndMilesUseKilometreThresholds() {
        assertEquals(true, drive().matchesDistanceFilter(DriveDistanceFilter.ALL))
        assertEquals(false, drive().matchesDistanceFilter(DriveDistanceFilter.COMMUTE))
        assertEquals(false, drive(distance = 7.0).copy(lengthUnit = "mi").matchesDistanceFilter(DriveDistanceFilter.COMMUTE))
        assertEquals(true, drive(distance = 7.0).copy(lengthUnit = "mi").matchesDistanceFilter(DriveDistanceFilter.DAY_TRIP))
    }
    @Test
    fun mixedReceiptUnitsHaveOneCanonicalSummaryAndUnknownDoesNotContribute() {
        val summary = calculateDrivesSummary(listOf(
            drive(distance = 10.0, duration = 5).copy(lengthUnit = "mi", speedMax = 60),
            drive(distance = 10.0, duration = 5).copy(lengthUnit = "km", speedMax = 80),
            drive(distance = 99.0).copy(lengthUnit = "", speedMax = 999)
        ))
        assertEquals(26.09344, summary.totalDistanceKm!!, 0.000001)
        assertEquals(2, summary.distanceSampleCount)
        assertEquals(96, summary.maxSpeedKmh)
    }

    @Test
    fun missingMaximumSpeedDoesNotBecomeZero() {
        val summary = calculateDrivesSummary(
            listOf(
                DriveData(
                    driveId = 1,
                    durationMin = 12,
                    odometerDetails = DriveOdometerDetails(distance = 8.5)
                )
            )
        )

        assertEquals(1, summary.totalDrives)
        assertEquals(8.5, summary.totalDistanceKm!!, 0.0001)
        assertNull(summary.maxSpeedKmh)
    }

    @Test
    fun emptySummaryKeepsMetricsUnavailable() {
        val summary = calculateDrivesSummary(emptyList())

        assertEquals(0, summary.totalDrives)
        assertNull(summary.totalDistanceKm)
        assertNull(summary.totalDurationMin)
        assertNull(summary.maxSpeedKmh)
    }

    @Test
    fun missingDistanceAndDurationAreUnavailableDespiteExistingRecords() {
        val summary = calculateDrivesSummary(listOf(drive()))
        assertNull(summary.totalDistanceKm)
        assertNull(summary.totalDurationMin)
        assertNull(summary.avgDistancePerDrive)
        assertNull(summary.avgDurationPerDrive)
    }

    @Test
    fun partialAveragesUseOnlyKnownMetricRecords() {
        val summary = calculateDrivesSummary(listOf(drive(distance = 10.0, duration = 20), drive()))
        assertEquals(10.0, summary.avgDistancePerDrive!!, 0.0001)
        assertEquals(20, summary.avgDurationPerDrive)
        assertEquals(1, summary.distanceSampleCount)
        assertEquals(1, summary.durationSampleCount)
        assertEquals(2, summary.totalDrives)
    }

    @Test
    fun realZeroRemainsKnown() {
        val summary = calculateDrivesSummary(listOf(drive(distance = 0.0, duration = 0)))
        assertEquals(0.0, summary.totalDistanceKm!!, 0.0001)
        assertEquals(0, summary.totalDurationMin)
        assertEquals(1, summary.distanceSampleCount)
        assertEquals(1, summary.durationSampleCount)
    }

    @Test
    fun invalidMetricInputsAreUnavailable() {
        val summary = calculateDrivesSummary(listOf(
            drive(distance = Double.NaN, duration = -1),
            drive(distance = Double.POSITIVE_INFINITY),
            drive(distance = -2.0)
        ))
        assertNull(summary.totalDistanceKm)
        assertNull(summary.totalDurationMin)
        assertEquals(0, summary.distanceSampleCount)
        assertEquals(0, summary.durationSampleCount)
    }

    @Test
    fun overflowDoesNotBecomeAnObservedTotal() {
        val summary = calculateDrivesSummary(listOf(
            drive(distance = Double.MAX_VALUE, duration = Int.MAX_VALUE),
            drive(distance = Double.MAX_VALUE, duration = Int.MAX_VALUE)
        ))
        assertNull(summary.totalDistanceKm)
        assertNull(summary.totalDurationMin)
    }

    @Test
    fun eachMetricHasIndependentCoverageAndZerosCount() {
        val summary = calculateDrivesSummary(listOf(
            drive(distance = 10.0), drive(duration = 20), drive(distance = 0.0, duration = 0)
        ))
        assertEquals(3, summary.totalDrives)
        assertEquals(2, summary.distanceSampleCount)
        assertEquals(2, summary.durationSampleCount)
        assertEquals(10.0, summary.totalDistanceKm!!, 0.0001)
        assertEquals(20, summary.totalDurationMin)
        assertEquals(5.0, summary.avgDistancePerDrive!!, 0.0001)
        assertEquals(10, summary.avgDurationPerDrive)
    }

    private fun drive(distance: Double? = null, duration: Int? = null) = DriveData(
        driveId = 1,
        durationMin = duration,
        odometerDetails = DriveOdometerDetails(distance = distance)
    )
}
