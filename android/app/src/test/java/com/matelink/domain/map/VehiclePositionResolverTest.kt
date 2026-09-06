package com.matelink.domain.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VehiclePositionResolverTest {
    @Test fun validLivePositionWins() {
        assertEquals(VehiclePosition(30.1, 120.2), VehiclePositionResolver.resolve(30.1, 120.2, 31.1, 121.2))
    }

    @Test fun invalidLivePositionDoesNotEraseCache() {
        assertEquals(VehiclePosition(31.1, 121.2), VehiclePositionResolver.resolve(0.0, 0.0, 31.1, 121.2))
        assertEquals(VehiclePosition(31.1, 121.2), VehiclePositionResolver.resolve(Double.NaN, 120.0, 31.1, 121.2))
    }

    @Test fun noUsablePositionReturnsNull() {
        assertNull(VehiclePositionResolver.resolve(null, null, 0.0, 0.0))
    }
}
