package com.matelink.domain.history

import com.matelink.data.local.HistoryConnectionSource
import com.matelink.data.local.HistoryReadScope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopeFirstEmissionInvalidationTest {
    private val alice = HistoryReadScope(HistoryConnectionSource.CLOUD,
        "cloud","alice","https://api.example")
    private val bob = HistoryReadScope(HistoryConnectionSource.CLOUD,
        "cloud","bob","https://api.example")

    @Test fun firstObservedNewAccountMustInvalidatePreviouslyVisibleMapAndReport() {
        assertTrue(shouldInvalidateScopedView(alice,bob))
        assertTrue(shouldInvalidateScopedView(alice,null))
        assertFalse(shouldInvalidateScopedView(alice,alice))
        assertFalse(shouldInvalidateScopedView(null,bob))
    }

    @Test fun sameNumericCarAcrossOriginsIsNotTheSameScope() {
        val other = HistoryReadScope(HistoryConnectionSource.CLOUD,
            "cloud","alice","https://different.example")
        assertTrue(shouldInvalidateScopedView(alice,other))
    }
}
