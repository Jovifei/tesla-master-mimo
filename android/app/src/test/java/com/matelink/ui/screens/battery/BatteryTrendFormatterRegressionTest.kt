package com.matelink.ui.screens.battery

import com.matelink.data.api.models.Units
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryTrendFormatterRegressionTest {
    @Test fun canonicalTrendKilometresAreConvertedForMilesAndZeroStaysKnown() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals("310.7 mi", formatBatteryTrendRangeKm(500.0, Units(unitOfLength = "mi")))
            assertEquals("500.0 km", formatBatteryTrendRangeKm(500.0, Units(unitOfLength = "km")))
            assertEquals("0.0 km", formatBatteryTrendRangeKm(0.0, null))
        } finally { Locale.setDefault(old) }
    }
}
