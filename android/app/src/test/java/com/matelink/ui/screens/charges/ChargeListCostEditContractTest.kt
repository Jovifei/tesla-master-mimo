package com.matelink.ui.screens.charges

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeListCostEditContractTest {
    @Test
    fun listEditCostUsesTheSharedDialogWithoutNavigatingToDetail() {
        val source = File("src/main/java/com/matelink/ui/screens/charges/ChargesScreen.kt").readText()

        assertTrue(source.contains("ChargePriceDialog("))
        assertTrue(source.contains("onEditCost = { onEditCost(charge) }"))
        assertFalse(source.contains("onEditCost = {\n                        onChargeClick("))
    }
}
