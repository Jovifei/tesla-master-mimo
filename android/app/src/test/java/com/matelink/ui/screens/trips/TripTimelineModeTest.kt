package com.matelink.ui.screens.trips

import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.DriveData
import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.TripAggregator
import com.matelink.ui.components.TripTimelineSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripTimelineModeTest {
    private fun trip() = requireNotNull(TripAggregator.buildTrip(
        listOf(requireNotNull(DriveData(1, startDate = "2026-10-14T10:00:00Z",
            endDate = "2026-10-14T11:00:00Z", source = "teslamate_archive").toSyncSummary(-701))),
        listOf(requireNotNull(ChargeData(9, startDate = "2026-10-14T11:00:00Z",
            endDate = "2026-10-14T11:30:00Z", source = "teslamate_archive").toSyncSummary(-701)))))

    @Test fun absenceOfDcProofIsUnknownRatherThanAc() {
        val segment = buildTimelineSegments(trip()).filterIsInstance<TripTimelineSegment.Charge>().single()
        assertNull(segment.mode)
    }

    @Test fun explicitLegacyDcProofKeepsTheDcClassification() {
        val segment = buildTimelineSegments(trip(), setOf(9)).filterIsInstance<TripTimelineSegment.Charge>().single()
        assertEquals("dc", segment.mode)
    }
}
