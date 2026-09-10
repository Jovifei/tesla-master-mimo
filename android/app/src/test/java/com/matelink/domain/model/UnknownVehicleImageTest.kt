package com.matelink.domain.model

import org.junit.Assert.*
import org.junit.Test

class UnknownVehicleImageTest {
    @Test fun unknownModelsNeverResolveToAnotherModel() {
        listOf(null, "", "unknown", "future-model").forEach { model ->
            assertFalse(CarImageResolver.isKnownModel(model))
            assertEquals("", CarImageResolver.getAssetPath(model, null, null))
            assertEquals("", CarImageResolver.getDefaultAssetPath(model))
            assertEquals("", CarImageResolver.getCompositorUrl(model, null, null))
            assertEquals("", CarImageResolver.getFallbackAssetPath(model, null, null, assetExists = { true }))
            assertEquals(VehicleHeroModel.UNKNOWN, resolveVehicleHeroProfile(model, null, null, null).model)
        }
    }
    @Test fun confirmedModelsStillResolveTheirOwnImage() {
        assertTrue(CarImageResolver.getAssetPath("Y", null, null).contains("/my_"))
        assertTrue(CarImageResolver.getCompositorUrl("3", null, null).contains("model=m3"))
    }
}
