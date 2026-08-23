package com.matelink.ui.screens.auth

import com.matelink.R
import org.junit.Assert.assertEquals
import org.junit.Test

class TeslaLoginErrorMappingTest {
    @Test
    fun mapsAuthenticationResponsesToActionableCopy() {
        assertEquals(R.string.tesla_login_error_request, teslaLoginErrorMessageRes(400))
        assertEquals(R.string.tesla_login_error_authorization, teslaLoginErrorMessageRes(401))
        assertEquals(R.string.tesla_login_error_authorization, teslaLoginErrorMessageRes(403))
        assertEquals(R.string.tesla_login_error_rate_limit, teslaLoginErrorMessageRes(429))
        assertEquals(R.string.tesla_login_error_service, teslaLoginErrorMessageRes(503))
    }

    @Test
    fun unknownStatusUsesGenericRetryCopy() {
        assertEquals(R.string.tesla_login_error_generic, teslaLoginErrorMessageRes(418))
    }
}
