package com.matelink.data.sync

import com.matelink.data.repository.ApiResponseMetadata
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryMetadataStoreTest {
    @Test
    fun collectingAndUnsupportedStatesAreExplicit() {
        val collecting = HistoryMetadataState(
            drives = ApiResponseMetadata(availability = "collecting")
        )
        assertTrue(collecting.isCollecting)
        assertFalse(collecting.isUnsupported)

        val unsupported = HistoryMetadataState(
            drives = ApiResponseMetadata(availability = "unsupported"),
            charges = ApiResponseMetadata(availability = "unsupported")
        )
        assertFalse(unsupported.isCollecting)
        assertTrue(unsupported.isUnsupported)
    }
}
