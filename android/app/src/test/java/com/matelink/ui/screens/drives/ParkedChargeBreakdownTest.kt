package com.matelink.ui.screens.drives

import com.matelink.data.api.models.LinkedCharge
import com.matelink.data.api.models.ParkedDetailData
import com.matelink.data.local.entity.ChargeSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class ParkedChargeBreakdownTest {
    private fun parked(charges: List<LinkedCharge>?, complete: Boolean? = null) = ParkedDetailData(
        olderDriveId = 10, newerDriveId = 11,
        startDate = "2026-10-23T00:00:00Z", endDate = "2026-10-23T10:00:00Z",
        startBatteryLevel = 50, endBatteryLevel = 70,
        linkedCharges = charges, chargeSessionsComplete = complete,
        source = "teslamate_archive"
    )

    @Test fun localChargeReceiptMustMatchParkedCollectorBeforeBecomingASession() {
        val start = "2026-10-23T01:00:00Z"
        val end = "2026-10-23T03:00:00Z"
        fun row(instance: String?, source: String = "teslamate_archive") = ChargeSummary(
            chargeId = 501, carId = -101, startDate = start, endDate = end,
            durationMin = 120, address = "", latitude = 0.0, longitude = 0.0,
            energyAdded = 0.0, energyUsed = null, cost = null,
            startBatteryLevel = 31, endBatteryLevel = 32, outsideTempAvg = null,
            odometer = 0.0, qualityState = "observed",
            apiEvidence = instance?.let {
                """{"charge_id":501,"start_date":"$start","end_date":"$end","source":"$source","source_instance_id":"$it","charge_energy_added":0.0}"""
            }
        )
        val proof = ParkedSourceProof("teslamate_archive", "instance-A")
        assertEquals(501, row("instance-A").toParkedLinkedCharge(proof)?.chargeId)
        assertNull(row("instance-B").toParkedLinkedCharge(proof))
        assertNull(row("instance-A", source = "fleet_telemetry").toParkedLinkedCharge(proof))
        assertNull(row(null).toParkedLinkedCharge(proof))
        assertNull(row("instance-A").copy(endDate = "2026-10-23T03:01:00Z")
            .toParkedLinkedCharge(proof))
    }

    @Test fun twoSeparateChargesRetainSeparateRatesAndNonchargeTime() {
        val first = LinkedCharge(1, "2026-10-23T01:00:00Z", "2026-10-23T03:00:00Z", 10.0)
        val second = LinkedCharge(2, "2026-10-23T07:00:00Z", "2026-10-23T08:00:00Z", 3.0)
        val result = parkedChargeBreakdown(parked(listOf(first, second), true), emptyList())
        assertEquals(listOf(1, 2), result.sessions.map { it.chargeId })
        assertEquals(5.0, result.sessions[0].energyPerHourKwh!!, 0.0)
        assertEquals(3.0, result.sessions[1].energyPerHourKwh!!, 0.0)
        assertEquals(Duration.ofHours(3), result.recordedChargingDuration)
        assertEquals(Duration.ofHours(7), result.outsideRecordedChargingDuration)
        assertTrue(result.storedSessionsComplete)
    }

    @Test fun localSessionsRemainUsefulButDoNotProvePhysicalArchiveCompleteness() {
        val first = LinkedCharge(1, "2026-10-23T01:00:00Z", "2026-10-23T03:00:00Z", null)
        val second = LinkedCharge(2, "2026-10-23T07:00:00Z", "2026-10-23T08:00:00Z", 0.0)
        val result = parkedChargeBreakdown(parked(null), listOf(first, second))
        assertEquals(2, result.sessions.size)
        assertNull(result.sessions[0].energyPerHourKwh)
        assertEquals(0.0, result.sessions[1].energyPerHourKwh!!, 0.0)
        assertFalse(result.storedSessionsComplete)
        assertNull(result.nonChargingConsumptionPointsPerHour)
    }

    @Test fun separatelyRecordedChargeBoundariesPermitClearlyEstimatedNonchargingSocDrain() {
        val first = LinkedCharge(1, "2026-10-23T01:00:00Z", "2026-10-23T03:00:00Z", 10.0,
            startBatteryLevel = 50, endBatteryLevel = 68,
            startSocObservedAt = "2026-10-23T01:01:00Z", endSocObservedAt = "2026-10-23T02:59:00Z")
        val second = LinkedCharge(2, "2026-10-23T07:00:00Z", "2026-10-23T08:00:00Z", 3.0,
            startBatteryLevel = 67, endBatteryLevel = 69,
            startSocObservedAt = "2026-10-23T07:01:00Z", endSocObservedAt = "2026-10-23T07:59:00Z")
        val window = parked(listOf(first, second), true).copy(endBatteryLevel = 69)
        val result = parkedChargeBreakdown(window, emptyList())
        assertEquals(-1, result.nonChargingSocDeltaPoints)
        assertEquals(1.0 / 7.0, result.nonChargingConsumptionPointsPerHour!!, 0.0001)
        val gainOutsideCharging = parkedChargeBreakdown(window.copy(endBatteryLevel = 72), emptyList())
        assertEquals(2, gainOutsideCharging.nonChargingSocDeltaPoints)
        assertEquals(-2.0 / 7.0, gainOutsideCharging.nonChargingConsumptionPointsPerHour!!, 0.0001)
        assertNull(parkedChargeBreakdown(window.copy(linkedCharges = listOf(first.copy(startSocObservedAt = null), second)),
            emptyList()).nonChargingConsumptionPointsPerHour)
        assertNull(parkedChargeBreakdown(window.copy(linkedCharges = listOf(first.copy(startSocObservedAt = "2026-10-23T01:06:00Z"), second)),
            emptyList()).nonChargingConsumptionPointsPerHour)
    }

    @Test fun serverAndLocalSameSessionDoNotDoubleCountAndConflictsRevokeCompleteness() {
        val first = LinkedCharge(1, "2026-10-23T01:00:00Z", "2026-10-23T03:00:00Z", 10.0)
        val extra = LinkedCharge(2, "2026-10-23T07:00:00Z", "2026-10-23T08:00:00Z", 3.0)
        val matching = parkedChargeBreakdown(parked(listOf(first), true), listOf(first))
        assertEquals(1, matching.sessions.size)
        assertTrue(matching.storedSessionsComplete)
        val conflict = parkedChargeBreakdown(parked(listOf(first), true), listOf(first, extra))
        assertEquals(2, conflict.sessions.size)
        assertFalse(conflict.storedSessionsComplete)
        val changedWindow = parkedChargeBreakdown(parked(listOf(first), true),
            listOf(first.copy(endDate = "2026-10-23T03:05:00Z")))
        assertFalse(changedWindow.storedSessionsComplete)
        assertEquals(1, changedWindow.sessions.size)
        assertEquals(Duration.ofHours(2), changedWindow.recordedChargingDuration)
        val duplicatedServer = parkedChargeBreakdown(parked(
            listOf(first, first.copy(startDate = "2026-10-23T01:10:00Z")), true), emptyList())
        assertEquals(1, duplicatedServer.sessions.size)
        assertFalse(duplicatedServer.storedSessionsComplete)
        val noIdentity = parkedChargeBreakdown(parked(listOf(first.copy(chargeId = -2)), true), emptyList())
        assertTrue(noIdentity.sessions.isEmpty())
        assertFalse(noIdentity.storedSessionsComplete)
    }

    @Test fun overlappingOrOpenSessionsNeverInflateChargeDurationOrInventRate() {
        val first = LinkedCharge(1, "2026-10-23T01:00:00Z", "2026-10-23T03:00:00Z", 10.0)
        val overlap = LinkedCharge(2, "2026-10-23T02:00:00Z", "2026-10-23T04:00:00Z", 5.0)
        val joined = parkedChargeBreakdown(parked(listOf(first, overlap), true), emptyList())
        assertEquals(Duration.ofHours(3), joined.recordedChargingDuration)
        assertEquals(Duration.ofHours(7), joined.outsideRecordedChargingDuration)
        val open = parkedChargeBreakdown(parked(listOf(first.copy(endDate = null)), true), emptyList())
        assertFalse(open.storedSessionsComplete)
        assertNull(open.recordedChargingDuration)
        assertNull(open.sessions.single().energyPerHourKwh)
    }

    @Test fun invalidParkedWindowAndOutOfWindowRecordsCannotClaimCompleteData() {
        val invalid = parkedChargeBreakdown(
            parked(emptyList(), true).copy(endDate = "2026-10-22T23:00:00Z"), emptyList()
        )
        assertTrue(invalid.sessions.isEmpty())
        assertFalse(invalid.storedSessionsComplete)
        assertNull(invalid.outsideRecordedChargingDuration)
        val outside = LinkedCharge(1, "2026-10-24T01:00:00Z", "2026-10-24T02:00:00Z", 2.0)
        val checked = parkedChargeBreakdown(parked(listOf(outside), true), emptyList())
        assertTrue(checked.sessions.isEmpty())
        assertFalse(checked.storedSessionsComplete)
    }

    @Test fun boundedSessionListReportsPartialInsteadOfSilentlyDroppingACharge() {
        val start = Instant.parse("2026-10-23T00:00:00Z")
        val sessions = (1..101).map { id ->
            val from = start.plusSeconds(id.toLong() * 60)
            LinkedCharge(id, from.toString(), from.plusSeconds(30).toString(), 0.0)
        }
        val result = parkedChargeBreakdown(parked(sessions, true), emptyList())
        assertEquals(MAX_PARKED_CHARGE_SESSIONS, result.sessions.size)
        assertFalse(result.storedSessionsComplete)
        assertTrue(result.hasPartialOrInvalidSessions)
    }
}
