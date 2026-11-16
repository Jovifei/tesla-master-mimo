package com.matelink.domain.analytics

import com.matelink.data.local.entity.DriveSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class BatterySocTrendTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-11-16T04:00:00Z") // 12:00 Asia/Shanghai

    private fun drive(
        start: String,
        end: String?,
        startSoc: Int,
        endSoc: Int,
        quality: String = "observed"
    ) = DriveSummary(
        driveId = start.hashCode(),
        carId = 1,
        startDate = start,
        endDate = end ?: "",
        durationMin = 30,
        startAddress = "",
        endAddress = "",
        distance = 10.0,
        speedMax = 80,
        speedAvg = 40,
        powerMax = 100,
        powerMin = -20,
        startBatteryLevel = startSoc,
        endBatteryLevel = endSoc,
        outsideTempAvg = null,
        insideTempAvg = null,
        energyConsumed = null,
        efficiency = null,
        qualityState = quality
    )

    @Test
    fun keepsOnlyTheLastRecordedSocPerDay() {
        val result = dailySocTrend(
            listOf(
                drive("2026-11-15T03:00:00Z", "2026-11-15T03:30:00Z", 52, 48),
                drive("2026-11-15T06:00:00Z", "2026-11-15T06:30:00Z", 48, 45),
                drive("2026-11-16T00:00:00Z", "2026-11-16T01:00:00Z", 45, 41)
            ),
            windowDays = 7,
            now = now,
            zone = zone
        )
        assertEquals(listOf(45, 41), result.points.map { it.soc })
        assertEquals(-4, result.change)
        assertEquals(41, result.latestSoc)
        assertNull(result.unavailableReason)
    }

    @Test
    fun fallsBackToStartSocWhenTheEndingLevelIsMissing() {
        val result = dailySocTrend(
            listOf(drive("2026-11-16T00:00:00Z", "2026-11-16T01:00:00Z", 63, 0)),
            windowDays = 7,
            now = now,
            zone = zone
        )
        assertEquals(listOf(63), result.points.map { it.soc })
        assertNull(result.change)
    }

    @Test
    fun skipsQuarantinedImplausibleAndOutOfWindowRows() {
        val result = dailySocTrend(
            listOf(
                drive("2026-11-16T00:00:00Z", "2026-11-16T01:00:00Z", 60, 55),
                drive("2026-11-16T02:00:00Z", "2026-11-16T02:30:00Z", 55, 50, quality = "quarantined"),
                drive("2026-11-16T02:00:00Z", "2026-11-16T02:30:00Z", 55, 101),
                drive("2026-10-01T00:00:00Z", "2026-10-01T01:00:00Z", 80, 70)
            ),
            windowDays = 7,
            now = now,
            zone = zone
        )
        assertEquals(listOf(55), result.points.map { it.soc })
        assertEquals(2, result.drivesInWindow)
    }

    @Test
    fun separatesNoHistoryFromNoQualifiedSamples() {
        assertEquals(
            SocTrendUnavailable.NO_HISTORY_IN_WINDOW,
            dailySocTrend(emptyList(), 7, now, zone).unavailableReason
        )
        val noSoc = dailySocTrend(
            listOf(drive("2026-11-16T00:00:00Z", "2026-11-16T01:00:00Z", 0, 0)),
            7,
            now,
            zone
        )
        assertEquals(SocTrendUnavailable.NO_QUALIFIED_SOC_SAMPLES, noSoc.unavailableReason)
        assertEquals(1, noSoc.drivesInWindow)
    }

    @Test
    fun acceptsTheThirtyDayWindow() {
        val result = dailySocTrend(
            listOf(drive("2026-10-25T00:00:00Z", "2026-10-25T01:00:00Z", 70, 66)),
            windowDays = 30,
            now = now,
            zone = zone
        )
        assertEquals(listOf(66), result.points.map { it.soc })
    }
}