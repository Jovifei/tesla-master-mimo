package com.matelink.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmapCoordinateTransformerTest {
    @Test
    fun chinaWgs84IsConvertedToGcj02Once() {
        val converted = AmapCoordinateTransformer.normalize(31.2304, 121.4737)
        requireNotNull(converted)
        assertEquals(CoordinateSystem.GCJ02, converted.coordinateSystem)
        assertNotEquals(31.2304, converted.latitude, 1e-6)
        assertNotEquals(121.4737, converted.longitude, 1e-6)
        val unchanged = AmapCoordinateTransformer.normalize(
            converted.latitude, converted.longitude, CoordinateSystem.GCJ02
        )
        assertEquals(converted.latitude, unchanged?.latitude ?: Double.NaN, 0.0)
        assertEquals(converted.longitude, unchanged?.longitude ?: Double.NaN, 0.0)
    }

    @Test
    fun outsideChinaKeepsWgs84() {
        val coordinate = AmapCoordinateTransformer.normalize(37.7749, -122.4194)
        assertEquals(CoordinateSystem.WGS84, coordinate?.coordinateSystem)
        assertEquals(37.7749, coordinate?.latitude ?: Double.NaN, 0.0)
    }

    @Test
    fun invalidPairsAreRejected() {
        assertNull(AmapCoordinateTransformer.normalize(31.0, null))
        assertNull(AmapCoordinateTransformer.normalize(Double.NaN, 121.0))
        assertNull(AmapCoordinateTransformer.normalize(0.0, 0.0))
    }
}
