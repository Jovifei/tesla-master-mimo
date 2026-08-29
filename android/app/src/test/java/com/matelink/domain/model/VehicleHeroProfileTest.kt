package com.matelink.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleHeroProfileTest {
    @Test
    fun model3PerformanceUsesModelColorWheelAndPerformanceSignals() {
        val profile = resolveVehicleHeroProfile(
            model = "3",
            exteriorColor = "Red",
            wheelType = "WY18P",
            trimBadging = "P74D"
        )

        assertEquals(VehicleHeroModel.MODEL_3, profile.model)
        assertEquals("PPMR", profile.colorCode)
        assertEquals(18, profile.wheelDiameterInches)
        assertTrue(profile.isPerformance)
    }

    @Test
    fun unknownModelUsesGenericHeroInsteadOfPretendingToBeModel3() {
        val profile = resolveVehicleHeroProfile("Cybertruck", null, null, null)

        assertEquals(VehicleHeroModel.UNKNOWN, profile.model)
    }

    @Test
    fun allFourSupportedModelsResolveToTheirOwnHeroModel() {
        assertEquals(VehicleHeroModel.MODEL_3, resolveVehicleHeroProfile("3", null, null, null).model)
        assertEquals(VehicleHeroModel.MODEL_Y, resolveVehicleHeroProfile("Y", null, null, null).model)
        assertEquals(VehicleHeroModel.MODEL_S, resolveVehicleHeroProfile("S", null, null, null).model)
        assertEquals(VehicleHeroModel.MODEL_X, resolveVehicleHeroProfile("X", null, null, null).model)
    }
}
