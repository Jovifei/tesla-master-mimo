package com.matelink.domain.model

import com.matelink.data.api.models.Units
import org.junit.Assert.assertEquals
import org.junit.Test

class UnitFormatterTest {
    @Test
    fun socRangeKeepsZeroAndEitherObservedEndpoint() {
        assertEquals("0% → 65%", UnitFormatter.formatSocRange(0, 65, "不可用"))
        assertEquals("— → 65%", UnitFormatter.formatSocRange(null, 65, "不可用"))
        assertEquals("0% → —", UnitFormatter.formatSocRange(0, 101, "不可用"))
        assertEquals("不可用", UnitFormatter.formatSocRange(null, -1, "不可用"))
    }

    @Test
    fun metricEnergyPerDistanceConvertsValueAndUnitOnce() {
        val units = Units(unitOfLength = "km")
        assertEquals("18.0 kWh/100km", UnitFormatter.formatEfficiency(180.0, units))
        assertEquals("18.0 kWh/100km", UnitFormatter.formatEfficiency(180.0, units, 0))
        assertEquals(18.0, UnitFormatter.efficiencyDisplayValue(180.0, units), 0.0001)
        assertEquals("kWh/100km", UnitFormatter.getEfficiencyUnit(units))
        assertEquals("0.0 kWh/100km", UnitFormatter.formatEfficiency(0.0, null))
        val imperial = Units(unitOfLength = "mi")
        assertEquals("180.0 Wh/mi", UnitFormatter.formatEfficiency(180.0, imperial))
        assertEquals(180.0, UnitFormatter.efficiencyDisplayValue(180.0, imperial), 0.0001)
    }

    @Test
    fun missingElevationIsNotFormattedAsZero() {
        assertEquals("—", UnitFormatter.formatElevation(null, Units(unitOfLength = "km")))
    }

    @Test
    fun observedElevationKeepsItsUnit() {
        assertEquals("1,000 m", UnitFormatter.formatElevation(1000, Units(unitOfLength = "km")))
    }
}
