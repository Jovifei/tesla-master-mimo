package com.matelink.data.sync

import com.matelink.domain.model.CarImageResolver
import org.junit.Assert.*
import org.junit.Test

class SnapshotTripEngineTest {

    @Test
    fun testCompositorUrlGenerationForModelY() {
        val url = CarImageResolver.getCompositorUrl("Y", "PearlWhite", "WY19B")
        assertTrue(url.contains("static-assets.tesla.com/v1/compositor"))
        assertTrue(url.contains("model=my"))
        assertTrue(url.contains("view=STUD_3QTR"))
        assertTrue(url.contains("bkba_opt=1"))
        assertTrue(url.contains("PPSW"))
        assertTrue(url.contains("WY19B"))
    }

    @Test
    fun testCompositorUrlGenerationForModel3() {
        val url = CarImageResolver.getCompositorUrl("3", "SolidBlack", "W38B")
        assertTrue(url.contains("model=m3"))
        assertTrue(url.contains("PBSB"))
        assertTrue(url.contains("W38B"))
    }

    @Test
    fun testCompositorUrlFallbackForUnknown() {
        val url = CarImageResolver.getCompositorUrl(null, null, null)
        assertTrue(url.contains("static-assets.tesla.com/v1/compositor"))
        assertTrue(url.contains("model=my"))
        assertTrue(url.contains("PBSB"))
        assertTrue(url.contains("WY19B"))
    }

    @Test
    fun testCompositorUrlForJoviVehicle() {
        val url = CarImageResolver.getCompositorUrl("Y", "DiamondBlack", "Crossflow19", "50")
        System.err.println("Jovi Compositor URL: $url")
        assertTrue(url.contains("static-assets.tesla.com/v1/compositor"))
    }
}
