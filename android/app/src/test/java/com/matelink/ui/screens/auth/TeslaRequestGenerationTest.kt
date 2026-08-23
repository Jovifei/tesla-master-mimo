package com.matelink.ui.screens.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeslaRequestGenerationTest {
    @Test
    fun currentRequestMayPublishResult() {
        assertTrue(shouldPublishTeslaRequest(requestId = 7L, currentRequestId = 7L))
    }

    @Test
    fun staleRequestCannotPublishResult() {
        assertFalse(shouldPublishTeslaRequest(requestId = 7L, currentRequestId = 8L))
    }
}
