package com.matelink.data.repository

import com.matelink.data.local.ConnectionMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeocodingAccessPolicyTest {
    @Test
    fun selfHostedMayUseExternalGeocoding() {
        assertTrue(allowsExternalGeocoding(ConnectionMode.SELF_HOSTED))
    }

    @Test
    fun cloudAndUnresolvedModesFailClosed() {
        assertFalse(allowsExternalGeocoding(ConnectionMode.TESLA_CLOUD))
        assertFalse(allowsExternalGeocoding(null))
    }

    @Test
    fun amapAllowedRegardlessOfConnectionMode() {
        assertTrue(allowsExternalGeocoding(ConnectionMode.TESLA_CLOUD, isAmap = true))
        assertTrue(allowsExternalGeocoding(null, isAmap = true))
        assertTrue(allowsExternalGeocoding(ConnectionMode.SELF_HOSTED, isAmap = true))
    }
}
