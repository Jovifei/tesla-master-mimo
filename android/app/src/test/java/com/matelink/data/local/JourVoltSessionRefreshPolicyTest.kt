package com.matelink.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JourVoltSessionRefreshPolicyTest {
    @Test
    fun explicitAuthenticationFailuresClearSession() {
        assertTrue(shouldClearSessionAfterRefreshFailure(401))
        assertTrue(shouldClearSessionAfterRefreshFailure(403))
    }

    @Test
    fun transientAndServerFailuresPreserveSession() {
        listOf(400, 408, 429, 500, 502, 503).forEach { status ->
            assertFalse("status=$status", shouldClearSessionAfterRefreshFailure(status))
        }
    }
}
