package com.matelink.domain.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistorySyncTimeoutTest {

    private val timeoutText = "cloud sync timed out"

    @Test fun timeoutWithCacheKeepsListVisibleAndWarns() {
        val placement = historyTimeoutMessage(hasCache = true, timeoutText = timeoutText, currentWarning = "old warning")
        assertNull(placement.error)
        assertEquals(timeoutText, placement.historySyncWarning)
    }

    @Test fun timeoutWithoutCacheSurfacesBlockingError() {
        val placement = historyTimeoutMessage(hasCache = false, timeoutText = timeoutText, currentWarning = "old warning")
        assertEquals(timeoutText, placement.error)
        assertEquals("old warning", placement.historySyncWarning)
    }

    @Test fun blankWarningStaysBlankWhenCacheIsEmpty() {
        val placement = historyTimeoutMessage(hasCache = false, timeoutText = timeoutText, currentWarning = null)
        assertEquals(timeoutText, placement.error)
        assertNull(placement.historySyncWarning)
    }
}
