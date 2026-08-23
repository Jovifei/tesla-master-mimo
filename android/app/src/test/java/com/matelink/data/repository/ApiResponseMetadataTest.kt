package com.matelink.data.repository

import com.matelink.data.api.models.ApiDataMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApiResponseMetadataTest {
    @Test
    fun optionalMetaMapsWithoutChangingLegacyPayload() {
        val metadata = ApiDataMeta(
            availability = "collecting",
            source = "fleet_api",
            collectionStartedAt = "2026-08-21T00:00:00Z"
        ).toApiResponseMetadata()

        assertEquals("collecting", metadata.availability)
        assertEquals("fleet_api", metadata.source)
        assertEquals("2026-08-21T00:00:00Z", metadata.collectionStartedAt)
        assertNull(metadata.coveragePercent)
    }

    @Test
    fun successStillAllowsCallersToIgnoreMetadata() {
        val legacy = ApiResult.Success(listOf(1, 2, 3))
        assertEquals(listOf(1, 2, 3), legacy.data)
        assertNull(legacy.metadata)
    }
}
