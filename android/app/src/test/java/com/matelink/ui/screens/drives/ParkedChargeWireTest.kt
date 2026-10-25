package com.matelink.ui.screens.drives

import com.matelink.data.api.models.ParkedDetailData
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkedChargeWireTest {
    @Test fun adapterKeepsTwoLinkedSessionsAndCompleteStoredQueryFlag() {
        val json = """
            {
              "older_drive_id":10,"newer_drive_id":11,
              "start_date":"2026-10-23T00:00:00Z","end_date":"2026-10-23T10:00:00Z",
              "source":"teslamate_archive","charge_sessions_complete":true,
              "linked_charges":[
                {"charge_id":1,"start_date":"2026-10-23T01:00:00Z","end_date":"2026-10-23T03:00:00Z","energy_added_kwh":10.0},
                {"charge_id":2,"start_date":"2026-10-23T07:00:00Z","end_date":"2026-10-23T08:00:00Z","energy_added_kwh":0.0}
              ],
              "linked_charge":{"charge_id":1,"start_date":"2026-10-23T01:00:00Z"}
            }
        """.trimIndent()
        val parked = requireNotNull(Moshi.Builder().build().adapter(ParkedDetailData::class.java).fromJson(json))
        assertEquals(listOf(1, 2), parked.linkedCharges?.map { it.chargeId })
        assertEquals(0.0, parked.linkedCharges!![1].energyAddedKwh!!, 0.0)
        assertTrue(parked.chargeSessionsComplete == true)
        assertEquals(2, parkedChargeBreakdown(parked, emptyList()).sessions.size)
    }
}
