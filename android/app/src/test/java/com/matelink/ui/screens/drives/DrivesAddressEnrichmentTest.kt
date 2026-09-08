package com.matelink.ui.screens.drives

import com.matelink.data.api.models.DriveData
import org.junit.Assert.assertEquals
import org.junit.Test

class DrivesAddressEnrichmentTest {
    @Test
    fun missingListAddressesUseObservedRouteEndpoints() = kotlinx.coroutines.test.runTest {
        val drive = DriveData(
            driveId = 7,
            startLatitude = 31.2,
            startLongitude = 121.4,
            endLatitude = 31.3,
            endLongitude = 121.5
        )

        val enriched = enrichDriveAddresses(listOf(drive)) { latitude, longitude ->
            "${latitude},${longitude}"
        }.single()

        assertEquals("31.2,121.4", enriched.startAddress)
        assertEquals("31.3,121.5", enriched.endAddress)
    }

    @Test
    fun existingOrMissingEndpointsRemainTruthful() = kotlinx.coroutines.test.runTest {
        val drive = DriveData(
            driveId = 8,
            startAddress = "已有起点",
            startLatitude = null,
            startLongitude = null,
            endLatitude = null,
            endLongitude = null
        )

        var calls = 0
        val enriched = enrichDriveAddresses(listOf(drive)) { _, _ ->
            calls++
            "不应覆盖"
        }.single()

        assertEquals("已有起点", enriched.startAddress)
        assertEquals(null, enriched.endAddress)
        assertEquals(0, calls)
    }

    @Test
    fun invalidCoordinatesAreNotSentToGeocoder() = kotlinx.coroutines.test.runTest {
        var calls = 0
        val drive = DriveData(
            driveId = 9,
            startLatitude = 91.0,
            startLongitude = 181.0,
            endLatitude = 0.0,
            endLongitude = 0.0
        )

        val enriched = enrichDriveAddresses(listOf(drive)) { _, _ ->
            calls++
            "不应调用"
        }.single()

        assertEquals(null, enriched.startAddress)
        assertEquals(null, enriched.endAddress)
        assertEquals(0, calls)
    }
}
