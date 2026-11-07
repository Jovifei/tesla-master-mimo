package com.matelink.data.repository

import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.ChargeBatteryDetails
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.EnergyContract
import com.matelink.data.api.models.EnergyMetric
import com.matelink.data.api.models.HistoryImportSession
import com.matelink.data.sync.HistoryUploadFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HistoryRecoveryTest {

    /** Synthetic source-proven whole-window power integral (not claimed as Fleet
     * provider-reported net energy). Quality of the trip alone is insufficient.
     */
    private fun strongMeasuredDrive(id: Int): DriveData {
        val start = "2026-09-01T00:00:00Z"
        val end = "2026-09-01T01:00:00Z"
        val proof = EnergyContract(netEnergy = EnergyMetric(
            valueKwh = 8.0, method = "drive_power_integral",
            measurementPoint = "drive_power", source = "telemetry_mqtt",
            quality = "estimated", timeBasis = "collector_received_at",
            coverageKind = "time", coverageRatio = 1.0,
            coverageSeconds = 3600.0, startDate = start, endDate = end
        ))
        return DriveData(id, startDate = start, endDate = end,
            energyConsumedNet = 8.0, energyContract = proof,
            source = "telemetry_mqtt", qualityState = "observed")
    }
    @Test fun boundedPagesRecover101DrivesAndCharges() = runBlocking {
        val drives = (1..101).map { DriveData(it) }
        val charges = (1..101).map { ChargeData(it) }
        val drivePages = mutableListOf<Int>()
        val chargePages = mutableListOf<Int>()
        val recoveredDrives = loadHistoryPages(id = DriveData::driveId) { page ->
            drivePages += page
            ApiResult.Success(drives.drop((page - 1) * 50).take(50))
        }
        val recoveredCharges = loadHistoryPages(id = ChargeData::chargeId) { page ->
            chargePages += page
            ApiResult.Success(charges.drop((page - 1) * 50).take(50))
        }
        assertEquals(drives, recoveredDrives.items)
        assertEquals(charges, recoveredCharges.items)
        assertEquals(listOf(1, 2, 3), drivePages)
        assertEquals(listOf(1, 2, 3), chargePages)
        assertNull(recoveredDrives.error)
        assertNull(recoveredCharges.error)
    }

    @Test fun everyPageIsRecoveredAndDuplicateIdsAreIdempotent() = runBlocking {
        val calls = mutableListOf<Int>()
        val result = loadHistoryPages(pageSize = 2, id = { it: Int -> it }) { page ->
            calls += page
            ApiResult.Success(when(page) { 1 -> listOf(1, 2); 2 -> listOf(2, 3); else -> emptyList() })
        }
        assertEquals(listOf(1, 2, 3), result.items)
        assertEquals(listOf(1, 2, 3), calls)
        assertNull(result.error)
    }

    @Test fun failedLaterPagePreservesEarlierRowsAndReportsPartial() = runBlocking {
        val result = loadHistoryPages(pageSize = 2, id = { it: Int -> it }) { page ->
            if (page == 1) ApiResult.Success(listOf(1, 2)) else ApiResult.Error("offline")
        }
        assertEquals(listOf(1, 2), result.items)
        assertNotNull(result.error)
    }

    @Test fun repeatedPageIsNotReportedAsACompleteArchive() = runBlocking {
        val result = loadHistoryPages(pageSize = 2, id = { it: Int -> it }) { ApiResult.Success(listOf(1, 2)) }
        assertEquals(2, result.items.size)
        assertEquals("history_repeated_page", result.error?.message)
    }

    @Test fun cancellationIsNotSwallowedAsOfflineHistory() = runBlocking {
        try {
            loadHistoryPages<Int>(id = { it }) { throw CancellationException("cancel") }
            fail("cancellation must propagate")
        } catch (_: CancellationException) { }
    }

    @Test fun incompleteCloudSummaryCannotDowngradeMeasuredLocalRecord() {
        val measured = strongMeasuredDrive(10)
        val weak = measured.copy(energyConsumedNet = null, energyContract = null,
            source = "local_import", qualityState = "incomplete")
        assertNull(weak.netEnergyKwh)
        val result = UnifiedHistoryRepository.mergeDrives(listOf(weak), listOf(measured)).single()
        assertEquals(8.0, result.energyConsumedNet!!, 0.0)
        assertEquals("telemetry_mqtt", result.energyContract?.netEnergy?.source)
        assertEquals("observed", result.qualityState)
        assertEquals("telemetry_mqtt", result.source)
    }

    @Test fun uncontractedObservedFleetScalarNeverBecomesMeasuredEnergy() {
        val raw = strongMeasuredDrive(11).copy(energyContract = null)
        val merged = UnifiedHistoryRepository.mergeDrives(emptyList(), listOf(raw)).single()
        assertEquals("observed", merged.qualityState)
        assertEquals("telemetry_mqtt", merged.source)
        assertNull(merged.energyConsumedNet)
        assertNull(merged.netEnergyKwh)
    }

    @Test fun measuredRemoteDoesNotLaunderUnverifiedLocalFields() {
        val local = DriveData(10, energyConsumedNet = 8.0, source = "local_import", qualityState = "incomplete")
        val measured = local.copy(energyConsumedNet = null, source = "telemetry_mqtt", qualityState = "observed")
        val result = UnifiedHistoryRepository.mergeDrives(listOf(measured), listOf(local)).single()
        assertNull(result.energyConsumedNet)
    }

    @Test fun emptyCloudResponsePreservesOldPhoneHistory() {
        val local = ChargeData(1, startDate = "2025-01-01T00:00:00Z", qualityState = "incomplete", source = "local_import")
        assertEquals(listOf(local), UnifiedHistoryRepository.mergeCharges(emptyList(), listOf(local)))
    }

    @Test fun equalInstantsDoNotMergeDifferentIdsButSameIdRemainsIdempotent() {
        val cached = DriveData(1, startDate = "2026-09-01T08:00:00+08:00", endDate = "2026-09-01T09:00:00+08:00")
        val remote = DriveData(2, startDate = "2026-09-01T00:00:00Z", endDate = "2026-09-01T01:00:00Z")
        assertEquals(setOf(1, 2), UnifiedHistoryRepository.mergeDrives(listOf(remote), listOf(cached)).map { it.driveId }.toSet())
        val sameDrive = remote.copy(driveId = cached.driveId, endAddress = "Updated end")
        val priorDrive = cached.copy(startAddress = "Saved start")
        val mergedDrive = UnifiedHistoryRepository.mergeDrives(listOf(sameDrive), listOf(priorDrive)).single()
        assertEquals("Saved start", mergedDrive.startAddress)
        assertEquals("Updated end", mergedDrive.endAddress)
        val cachedCharge = ChargeData(10, startDate = cached.startDate, endDate = cached.endDate,
            source = "teslamate_archive", batteryDetails = ChargeBatteryDetails(startBatteryLevel = 0))
        val otherCharge = ChargeData(11, startDate = remote.startDate, endDate = remote.endDate,
            source = "teslamate_archive")
        assertEquals(setOf(10, 11), UnifiedHistoryRepository.mergeCharges(listOf(otherCharge), listOf(cachedCharge))
            .map { it.chargeId }.toSet())
        val sameCharge = otherCharge.copy(chargeId = cachedCharge.chargeId,
            batteryDetails = ChargeBatteryDetails(endBatteryLevel = 79))
        val mergedCharge = UnifiedHistoryRepository.mergeCharges(listOf(sameCharge), listOf(cachedCharge)).single()
        assertEquals(0, mergedCharge.startBatteryLevel)
        assertEquals(79, mergedCharge.endBatteryLevel)
        assertFalse(sameHistorySession(null, null, null, null))
    }

    @Test fun regularWindowUsesTwoDistinctUtcDataDaysWithoutDeletingArchive() {
        fun row(id: String, date: String) = HistoryImportSession(id, date, date)
        val drives = listOf(row("old", "2026-07-01T00:00:00Z"), row("new", "2026-09-01T00:00:00Z"))
        val charges = listOf(row("gap", "2026-08-01T08:00:00+08:00"))
        val window = HistoryUploadFilter.boundToLatestTwoDataDays(drives, charges)
        assertEquals(listOf("new"), window.drives.map { it.sessionId })
        assertEquals(listOf("gap"), window.charges.map { it.sessionId })
        assertEquals(2, drives.size)
        assertEquals(2, HistoryUploadFilter.keepValidatedArchive(drives, charges).drives.size)
    }

    @Test fun localIncompleteSummariesAreNotFilteredOutOfHistoryDateRange() {
        assertTrue(historyInRange("2026-09-01T08:00:00+08:00", "2026-09-01T00:00:00Z", "2026-09-02T00:00:00Z"))
        assertFalse(historyInRange("2026-09-02T00:00:00Z", "2026-09-01T00:00:00Z", "2026-09-02T00:00:00Z"))
    }

    @Test fun sameWindowOfflineRecordsWithDifferentIdsRemainSeparate() {
        val measured = strongMeasuredDrive(1)
        val alias = measured.copy(driveId = 2, energyConsumedNet = null,
            energyContract = null, source = "local_import", qualityState = "incomplete")
        assertNull(alias.netEnergyKwh)
        val result = UnifiedHistoryRepository.mergeDrives(emptyList(), listOf(alias, measured)).associateBy { it.driveId }
        assertEquals(setOf(1, 2), result.keys)
        assertEquals("observed", result.getValue(1).qualityState)
        assertEquals(8.0, result.getValue(1).energyConsumedNet!!, 0.0)
        assertNull(result.getValue(2).netEnergyKwh)
    }
}
