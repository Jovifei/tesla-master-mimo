package com.matelink.ui.screens.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BatteryReadinessPresentationTest {
    @Test
    fun sixtySevenPercentWithOneHundredTargetIsNeutralAndKeepsBothValues() {
        val presentation = classifyBatteryCharge(67, 100)

        assertEquals(BatteryChargeWarning.NONE, presentation.warning)
        assertEquals(
            "当前 67% · 充电目标 100%",
            presentation.summary?.format("当前 %d%% · 充电目标 %d%%")
        )
        assertFalse(presentation.showHighSocWarning)
    }

    @Test
    fun highSocWarningUsesObservedBatteryLevelRatherThanChargeTarget() {
        assertFalse(classifyBatteryCharge(67, 100).showHighSocWarning)
        assertEquals(BatteryChargeWarning.HIGH_SOC, classifyBatteryCharge(91, 100).warning)
        assertFalse(classifyBatteryCharge(67, 91).showHighSocWarning)
    }
}
