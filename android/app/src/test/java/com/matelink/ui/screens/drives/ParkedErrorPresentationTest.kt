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
}
