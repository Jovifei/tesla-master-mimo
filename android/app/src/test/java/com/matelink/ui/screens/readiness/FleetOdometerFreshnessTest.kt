package com.matelink.ui.screens.readiness

import com.matelink.data.api.models.FleetReadinessObservedOdometer
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class FleetOdometerFreshnessTest {
    @Test fun onlyRecentNonfutureQualifiedSamplesAreCurrent() {
        val now = Instant.parse("2026-10-29T12:00:00Z")
        fun sample(seconds: Long, value: Double = 12.0) = FleetReadinessObservedOdometer(value, "km", now.minusSeconds(seconds).toString(), "telemetry_mqtt")
        assertEquals(0.0, readOnlyFleetOdometerKm(sample(0, 0.0), now)!!, 0.0)
        assertEquals(12.0, readOnlyFleetOdometerKm(sample(120), now)!!, 0.0)
        assertNull(readOnlyFleetOdometerKm(sample(121), now))
        assertNull(readOnlyFleetOdometerKm(sample(-1), now))
        assertNull(readOnlyFleetOdometerKm(sample(0).copy(observedAt = null), now))
        assertNull(readOnlyFleetOdometerKm(sample(0).copy(source = "unknown"), now))
    }
}
