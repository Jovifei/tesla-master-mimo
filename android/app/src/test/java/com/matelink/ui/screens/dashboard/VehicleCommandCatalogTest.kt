package com.matelink.ui.screens.dashboard

import com.matelink.data.api.models.CarStatus
import com.matelink.data.api.models.CommandAuthorization
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleCommandCatalogTest {

    @Test
    fun catalogMirrorsBackendPhaseOneSet() {
        val names = vehicleCommandCatalog.map { it.name }.toSet()
        assertEquals(16, names.size)
        assertTrue(names.containsAll(listOf("lock", "unlock", "charge_start", "set_charge_limit", "windows_vent", "sentry_on")))
        val unlock = vehicleCommandCatalog.first { it.name == "unlock" }
        assertEquals(CommandTier.HIGH, unlock.tier)
        assertTrue(unlock.needsConfirmation)
        assertTrue(vehicleCommandCatalog.filter { it.name != "unlock" }.none { it.needsConfirmation })
    }

    @Test
    fun commandsOfferedOnlyWhenScopesReady() {
        assertTrue(offeredCommands(CarStatus()).isEmpty())
        val missing = CarStatus(
            commandAuthorization = CommandAuthorization(ready = false, missingScopes = listOf("vehicle_cmds"))
        )
        assertTrue(offeredCommands(missing).isEmpty())
        assertTrue(needsCommandReauthorization(missing))

        val ready = CarStatus(commandAuthorization = CommandAuthorization(ready = true, missingScopes = emptyList()))
        assertEquals(vehicleCommandCatalog.size, offeredCommands(ready).size)
        assertFalse(needsCommandReauthorization(ready))
        assertFalse(needsCommandReauthorization(CarStatus()))
    }
}
