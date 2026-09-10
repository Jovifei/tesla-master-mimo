package com.matelink.ui.screens.dashboard

import com.matelink.data.api.models.CarData
import com.matelink.data.api.models.CarDetails
import org.junit.Assert.*
import org.junit.Test

class DashboardCarMetadataTest {
    @Test fun refreshEnrichesOnlyTheSelectedVehicle() {
        val original = CarData(1, vehicleUid = "opaque-a", name = "Saved name")
        val fresh = original.copy(carDetails = CarDetails(model = "Y"))
        assertEquals("Y", refreshedDashboardCar(original, listOf(fresh))?.carDetails?.model)
        assertEquals("Saved name", refreshedDashboardCar(original, listOf(fresh.copy(name = "")))?.name)
    }
    @Test fun mismatchedIdentityCannotReplaceTheDashboardVehicle() {
        val original = CarData(1, vehicleUid = "opaque-a")
        val other = CarData(1, vehicleUid = "opaque-b", carDetails = CarDetails(model = "3"))
        assertEquals(original, refreshedDashboardCar(original, listOf(other)))
        assertEquals(original, refreshedDashboardCar(original, listOf(other.copy(carId = 2))))
        assertNull(refreshedDashboardCar(null, listOf(other)))
    }
}
