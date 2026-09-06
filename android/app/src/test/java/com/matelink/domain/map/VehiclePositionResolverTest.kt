package com.matelink.domain.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import com.matelink.data.api.models.CarGeodata
import com.matelink.data.api.models.CarStatus
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

    @Test fun emptyLiveSnapshotKeepsCachedStatusPosition() {
        val live = CarStatus(carGeodata = CarGeodata(latitude = 0.0, longitude = 0.0))
        val cached = CarStatus(carGeodata = CarGeodata(latitude = 31.2, longitude = 121.5))

        val merged = mergeCarStatusPosition(live, cached)

        assertEquals(31.2, merged?.latitude)
        assertEquals(121.5, merged?.longitude)
    }
}
