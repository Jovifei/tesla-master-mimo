package com.matelink.ui.screens.dashboard

import com.matelink.R
import com.matelink.data.repository.ApiErrorKind
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardErrorMappingTest {
    @Test
    fun mapsCloudFailureKindsToSpecificDashboardCopy() {
        assertEquals(R.string.dashboard_error_auth_title, dashboardErrorTitleRes(ApiErrorKind.AUTH_REQUIRED))
        assertEquals(R.string.dashboard_error_rate_limit_body, dashboardErrorBodyRes(ApiErrorKind.RATE_LIMITED))
        assertEquals(R.string.dashboard_error_service_title, dashboardErrorTitleRes(ApiErrorKind.SERVICE_UNAVAILABLE))
        assertEquals(R.string.dashboard_error_network_body, dashboardErrorBodyRes(ApiErrorKind.NETWORK))
    }

    @Test
    fun unknownFailureUsesSafeGenericCopy() {
        assertEquals(R.string.dashboard_error_generic_title, dashboardErrorTitleRes(null))
        assertEquals(R.string.dashboard_error_generic_body, dashboardErrorBodyRes(ApiErrorKind.UNKNOWN))
    }
}
