package com.matelink.data.repository

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeocodingExternalBoundaryTest {
    @Test
    fun historicalAddressResolutionUsesAmapAndNotNominatim() {
        val source = File("src/main/java/com/matelink/data/repository/GeocodingRepository.kt").readText()

        assertTrue(source.contains("amapReverseGeocoder.reverse"))
        assertFalse(source.contains("nominatimApi.reverseGeocode(item.latitude, item.longitude)"))
    }
}
