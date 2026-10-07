package com.matelink.data.api.models

import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryFieldJsonContractTest {
    private val moshi = Moshi.Builder().build()

    @Test
    fun driveJsonKeepsMissingFieldsDistinctFromObservedZero() {
        val adapter = moshi.adapter(DriveData::class.java)
        val missing = requireNotNull(adapter.fromJson("""{"drive_id":1,"speed_max":null,"outside_temp_avg":null,"battery_details":{"start_battery_level":null,"end_battery_level":null}}"""))
        assertNull(missing.speedMax)
        assertNull(missing.outsideTempAvg)
        assertNull(missing.startBatteryLevel)
        val zero = requireNotNull(adapter.fromJson("""{"drive_id":1,"speed_max":0,"outside_temp_avg":0,"battery_details":{"start_battery_level":0,"end_battery_level":80}}"""))
        assertEquals(0, zero.speedMax)
        assertEquals(0.0, zero.outsideTempAvg!!, 0.0001)
        assertEquals(0, zero.startBatteryLevel)
        assertEquals(80, zero.endBatteryLevel)
    }

    @Test
    fun chargeJsonKeepsEitherSocEndpointAndObservedZero() {
        val adapter = moshi.adapter(ChargeData::class.java)
        val partial = requireNotNull(adapter.fromJson("""{"charge_id":1,"battery_details":{"start_battery_level":0,"end_battery_level":null}}"""))
        assertEquals(0, partial.startBatteryLevel)
        assertNull(partial.endBatteryLevel)
        val complete = requireNotNull(adapter.fromJson("""{"charge_id":1,"battery_details":{"start_battery_level":0,"end_battery_level":80}}"""))
        assertEquals(0, complete.startBatteryLevel)
        assertEquals(80, complete.endBatteryLevel)
    }
}
