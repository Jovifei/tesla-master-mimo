package com.matelink.charges

import com.matelink.domain.analytics.chargeTotalOverrideKey
import com.matelink.domain.analytics.validManualChargeTotal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManualChargeAmountTest {
    @Test
    fun totalAmountIsStoredAsProvidedAndDoesNotDependOnEnergy() {
        assertEquals(38.5, validManualChargeTotal(38.5)!!, 0.0)
        assertEquals("4:12", chargeTotalOverrideKey(4, 12))
    }

    @Test
    fun invalidTotalsAreRejected() {
        assertNull(validManualChargeTotal(-1.0))
        assertNull(validManualChargeTotal(Double.NaN))
        assertNull(validManualChargeTotal(Double.POSITIVE_INFINITY))
    }
}
