package com.matelink.domain.model

import com.matelink.data.api.models.Units
import org.junit.Assert.assertEquals
import org.junit.Test

class UnitFormatterTest {

    @Test
    fun missingElevationIsNotFormattedAsZero() {
        assertEquals("—", UnitFormatter.formatElevation(null, Units(unitOfLength = "km")))
    }

    @Test
    fun observedElevationKeepsItsUnit() {
        assertEquals("1,000 m", UnitFormatter.formatElevation(1000, Units(unitOfLength = "km")))
    }
}
