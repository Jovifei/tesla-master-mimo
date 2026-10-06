package com.matelink.ui.screens.drives

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkedChargePresentationTest {
    @Test
    fun derivedIntervalUsesAnExplicitCaveatAndDistinctEndpointLabels() {
        val source = File("src/main/java/com/matelink/ui/screens/drives/ParkedDetailScreen.kt").readText()

        assertTrue(source.contains("data.source == \"drive_history_interval\""))
        assertTrue(source.contains("if (derivedInterval)"))
        assertTrue(source.contains("drive_interval_partial_explanation"))
        assertTrue(source.contains("drive_interval_start_endpoint"))
        assertTrue(source.contains("drive_interval_end_endpoint"))
        assertTrue(source.contains("drive_interval_source"))
        assertTrue(source.contains("parkedAddressLabel(data.startAddress)"))
        assertTrue(source.contains("parkedAddressLabel(data.endAddress)"))
        assertTrue(source.contains("404 -> stringResource(R.string.drive_interval_not_confirmed)"))
        assertTrue(source.contains("503 -> stringResource(R.string.drive_interval_read_unavailable)"))
        val viewModel = File("src/main/java/com/matelink/ui/screens/drives/ParkedDetailViewModel.kt").readText()
        assertTrue(viewModel.contains("errorCode = result.code"))
    }

    @Test
    fun linkedChargeGetsAChargingParkedActionInsteadOfAPlainParkingCard() {
        val source = File("src/main/java/com/matelink/ui/screens/drives/ParkedDetailScreen.kt").readText()

        assertTrue(source.contains("data.linkedCharge"))
        assertTrue(source.contains("onNavigateToChargeDetail"))
        assertTrue(source.contains("charge_parked_title"))
    }
}
