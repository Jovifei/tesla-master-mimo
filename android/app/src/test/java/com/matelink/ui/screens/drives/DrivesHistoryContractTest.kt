package com.matelink.ui.screens.drives

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivesHistoryContractTest {
    @Test
    fun historyUsesAllTimeAndMoreRoutesToDrives() {
        val viewModel = File("src/main/java/com/matelink/ui/screens/drives/DrivesViewModel.kt").readText()
        val more = File("src/main/java/com/matelink/ui/screens/more/MoreScreen.kt").readText()
        val nav = File("src/main/java/com/matelink/ui/navigation/NavGraph.kt").readText()

        assertTrue(viewModel.contains("dateFilter: DriveDateFilter = DriveDateFilter.ALL_TIME"))
        assertTrue(viewModel.contains("?: DriveDateFilter.ALL_TIME"))
        assertTrue(more.contains("onNavigateToDrives(carId)"))
        assertTrue(nav.contains("onNavigateToDrives = { navController.navigate(Screen.Drives"))
        assertFalse(more.contains("more_item_vehicle_3d_preview"))
        assertFalse(more.contains("R.string.current_charge"))
    }
}
