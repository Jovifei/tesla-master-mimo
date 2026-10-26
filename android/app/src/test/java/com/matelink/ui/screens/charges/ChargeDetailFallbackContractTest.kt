package com.matelink.ui.screens.charges

import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.ChargeDetail
import com.matelink.data.api.models.ChargePoint
import com.matelink.data.api.models.ChargerDetails
import com.matelink.data.sync.toSyncSummary
import org.junit.Assert.*
import org.junit.Test

/** Production fallback uses saved evidence, never generated electrical samples. */
class ChargeDetailFallbackContractTest {
    @Test fun remoteDetailFailureDoesNotSynthesizeElectricalTrace() {
        val row = ChargeData(64, startDate = "2026-10-20T00:00:00Z", endDate = "2026-10-20T01:00:00Z",
            source = "teslamate_archive", chargeEnergyAdded = 12.5).toSyncSummary(-1)!!
        val detail = cachedChargeDetail(row)
        assertTrue(detail.chargePoints.orEmpty().isEmpty())
        val stats = ChargeStatsCalculator.calculateStats(detail)
        assertNull(stats.powerMax); assertNull(stats.voltageMax); assertNull(stats.currentMax)
        assertNull(detail.durationMin)

        val actual = ChargePoint(date = row.startDate, chargerDetails = ChargerDetails(chargerPower = 0.0))
        val replayed = cachedChargeDetail(row, ChargeDetail(64, chargePoints = listOf(actual)))
        assertEquals(listOf(actual), replayed.chargePoints)
        assertEquals(0.0, replayed.chargePoints!!.single().chargerPowerValue!!, 0.0)
    }
}
