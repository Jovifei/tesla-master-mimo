package com.matelink.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TpmsCustomAlertStateStoreTest {
    @Test
    fun finitePendingAlertRejectsNonFiniteValues() {
        assertEquals(TpmsCustomPendingAlert(2.5, 2.6), finiteTpmsCustomPendingAlert(2.5, 2.6))
        assertNull(finiteTpmsCustomPendingAlert(Double.NaN, 2.6))
        assertNull(finiteTpmsCustomPendingAlert(2.5, Double.POSITIVE_INFINITY))
    }
}
