package com.matelink.ui.screens.drives

import com.matelink.data.api.models.ParkedDetailData
import com.squareup.moshi.Moshi
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test

class ParkedWireCoverageEvidenceTest {
    private fun read(extra:String="")=Moshi.Builder().build().adapter(ParkedDetailData::class.java).fromJson("""{
        "older_drive_id":10,"newer_drive_id":11,"start_date":"2026-10-27T00:00:00Z",
        "end_date":"2026-10-27T01:00:00Z","source":"database_latest"$extra} """)!!
    @Test fun absentWireCoverageAndSampleCountCannotBecomeObservedZero() {
        val raw=read()
        assertNull(raw.coverageSeconds)
        val value=parkedTelemetryPresentation(raw,Duration.ofHours(1),false)
        assertNull(value.sampleCount)
        assertNull(value.coveragePercent)
    }
    @Test fun explicitZeroRemainsKnownAndInvalidRangesRemainUnavailable() {
        val raw=read(",\"sample_count\":0,\"coverage_seconds\":0,\"coverage_ratio\":0.0")
        assertEquals(0L,raw.coverageSeconds)
        val zero=parkedTelemetryPresentation(raw,Duration.ofHours(1),false)
        assertEquals(0,zero.sampleCount)
        assertEquals(0.0,zero.coveragePercent!!,0.0)
        val invalid=parkedTelemetryPresentation(read(",\"sample_count\":-1,\"coverage_ratio\":2.0"),Duration.ofHours(1),false)
        assertNull(invalid.sampleCount)
        assertNull(invalid.coveragePercent)
    }
}
