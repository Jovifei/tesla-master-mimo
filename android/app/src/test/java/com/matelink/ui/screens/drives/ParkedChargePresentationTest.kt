package com.matelink.ui.screens.drives

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkedChargePresentationTest {
    @Test
    fun linkedChargeGetsAChargingParkedActionInsteadOfAPlainParkingCard() {
        val source = File("src/main/java/com/matelink/ui/screens/drives/ParkedDetailScreen.kt").readText()

        assertTrue(source.contains("data.linkedCharge"))
        assertTrue(source.contains("onNavigateToChargeDetail"))
        assertTrue(source.contains("charge_parked_title"))
    }
}
