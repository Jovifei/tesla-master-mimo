package com.matelink.domain

import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDayBoundariesTest {
    @Test fun selectedDayEndsAtNextLocalMidnightExclusively() {
        val zone = ZoneId.of("Asia/Shanghai")
        val end = LocalDayBoundaries.endOfDay(LocalDate.of(2026, 10, 25), zone)
        assertEquals("2026-10-26T00:00:00+08:00", end)
        assertTrue(OffsetDateTime.parse(end).toInstant().isAfter(
            OffsetDateTime.parse("2026-10-25T23:59:59.999+08:00").toInstant()
        ))
    }

    @Test fun nextMidnightUsesItsOwnOffsetAcrossDst() {
        val zone = ZoneId.of("America/Chicago")
        val springStart = OffsetDateTime.parse(LocalDayBoundaries.startOfDay(LocalDate.of(2026, 3, 8), zone)).toInstant()
        val springEnd = OffsetDateTime.parse(LocalDayBoundaries.endOfDay(LocalDate.of(2026, 3, 8), zone)).toInstant()
        assertEquals(Duration.ofHours(23), Duration.between(springStart, springEnd))
        val fallStart = OffsetDateTime.parse(LocalDayBoundaries.startOfDay(LocalDate.of(2026, 11, 1), zone)).toInstant()
        val fallEnd = OffsetDateTime.parse(LocalDayBoundaries.endOfDay(LocalDate.of(2026, 11, 1), zone)).toInstant()
        assertEquals(Duration.ofHours(25), Duration.between(fallStart, fallEnd))
    }
}
