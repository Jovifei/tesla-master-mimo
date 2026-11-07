package com.matelink.domain.map

import com.matelink.data.api.models.DrivePosition
import org.junit.Assert.*
import org.junit.Test

class DriveRouteSegmentsTest {
    private fun point(second: Int, lat: Double? = 31.0, lon: Double? = 121.0) = DrivePosition(
        date = java.time.Instant.parse("2026-10-27T00:00:00Z").plusSeconds(second.toLong()).toString(), latitude=lat, longitude=lon)

    @Test fun returnToStartAndOutOfOrderValidSamplesKeepTheObservedLoop() {
        val a = 31.0 to 121.0
        val b = 31.1 to 121.1
        assertEquals(listOf(listOf(a, b, a)), observedDriveRouteSegments(listOf(point(20), point(0), point(10, b.first, b.second))))
    }

    @Test fun missingCoordinatesTimeAndLongGapsCannotBeJoinedByALine() {
        for (middle in listOf(point(10, null, null), point(10, 99.0, 121.0), point(10).copy(date=null))) {
            val segments = observedDriveRouteSegments(listOf(point(0), middle, point(20,31.1,121.1)))
            assertEquals(listOf(1,1), segments.map { it.size })
        }
        assertEquals(listOf(1,1), observedDriveRouteSegments(listOf(point(0), point(120,31.1,121.1))).map { it.size })
    }

    @Test fun sameInstantConflictingGpsAndOutOfWindowEvidenceStayUnavailable() {
        val samples = listOf(point(0), point(0,31.1,121.1), point(10), point(20,31.2,121.2))
        assertEquals(listOf(listOf(31.0 to 121.0)), observedDriveRouteSegments(samples,
            "2026-10-27T00:00:00Z", "2026-10-27T00:00:15Z"))
    }

    @Test fun eachBoundaryUsesOneRealCoordinatePairAndCannotBorrowAMidJourneyLocation() {
        val detail = com.matelink.data.api.models.DriveDetail(7,startDate="2026-10-27T00:00:00Z",endDate="2026-10-27T01:00:00Z",
            startLatitude=30.0,positions=listOf(point(0,31.0,121.0),point(600,32.0,122.0)))
        assertEquals(31.0 to 121.0,driveBoundaryCoordinates(detail,true))
        assertNull(driveBoundaryCoordinates(detail,false))
    }
}
