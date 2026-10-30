package com.matelink.ui.screens.drives

import org.junit.Assert.assertEquals
import org.junit.Test

class ParkedErrorPresentationTest {
    @Test
    fun boundaryTimeUsesPhoneZoneAndPreservesUnknown() {
        val original = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
            assertEquals("2026-10-07 00:30", parkedBoundaryTimeLabel("2026-10-06T16:30:00Z", "不可用"))
            assertEquals("不可用", parkedBoundaryTimeLabel(null, "不可用"))
        } finally {
            java.util.TimeZone.setDefault(original)
        }
    }
    @Test
    fun unavailableParkingDoesNotClaimTripHistoryDisconnected() {
        assertEquals("暂无可验证的驻车详情；行程历史仍可查看",
            parkedErrorLabel("history_not_collected", "不可用"))
        assertEquals("不可用", parkedErrorLabel("untrusted_server_message", "不可用"))
    }
    @Test
    fun dateRangeFormatsWithoutYearAndOmitDuplicateDay() {
        val original = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
            val locale = java.util.Locale.CHINA
            // Same day
            val sameDay = formatParkedDateRange("2026-10-26T11:43:00Z", "2026-10-26T13:08:00Z", "不可用", locale)
            assertEquals("10月26日 19:43 → 21:08", sameDay)

            // Cross day
            val crossDay = formatParkedDateRange("2026-10-26T11:43:00Z", "2026-10-27T00:30:00Z", "不可用", locale)
            assertEquals("10月26日 19:43 → 10月27日 08:30", crossDay)

            // Missing end
            val missingEnd = formatParkedDateRange("2026-10-26T11:43:00Z", null, "不可用", locale)
            assertEquals("10月26日 19:43 → 不可用", missingEnd)

            // Both null
            val bothNull = formatParkedDateRange(null, null, "不可用", locale)
            assertEquals("不可用", bothNull)
        } finally {
            java.util.TimeZone.setDefault(original)
        }
    }
}
