package com.matelink.data.api.models

import org.junit.Assert.*
import org.junit.Test

class HistoryContextValidationTest {
    @Test fun exactVersionSelectedCarAndBoundedUntrimmedUidAreRequired() {
        assertTrue(HistoryContextData(1, 7, "provider-a").isValidFor(7))
        assertFalse(HistoryContextData(2, 7, "provider-a").isValidFor(7))
        assertFalse(HistoryContextData(1, 8, "provider-a").isValidFor(7))
        assertFalse(HistoryContextData(1, 0, "provider-a").isValidFor(0))
        listOf(null, "", " ", " provider-a", "provider-a\n", "x".repeat(257), "中".repeat(86)).forEach {
            assertFalse(validHistoryVehicleUid(it))
        }
        assertTrue(validHistoryVehicleUid("x".repeat(256)))
        assertTrue(validHistoryVehicleUid("中".repeat(85)))
    }
}
