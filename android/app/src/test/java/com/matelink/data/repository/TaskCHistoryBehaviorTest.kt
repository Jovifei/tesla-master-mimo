package com.matelink.data.repository

import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.DriveBatteryDetails
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveOdometerDetails
import com.matelink.data.local.cloudVehicleStableIdentity
import com.matelink.data.local.HistoryIdentityUnavailableException
import com.matelink.data.local.selfHostedVehicleStableIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskCHistoryBehaviorTest {
    @Test
    fun stableIdentitySeparatesCloudAccountsAndSelfHostedServers() {
        val first = cloudVehicleStableIdentity("account-a", "uid-1")
        val otherAccount = cloudVehicleStableIdentity("account-b", "uid-1")
        val otherVehicle = cloudVehicleStableIdentity("account-a", "uid-2")

        assertTrue(first != otherAccount)
        assertTrue(first != otherVehicle)
        assertFalse(first.contains("account-a"))
        assertEquals(
            selfHostedVehicleStableIdentity("HTTP://example.test/", 7),
            selfHostedVehicleStableIdentity("http://example.test", 7)
        )
    }

    @Test
    fun offlineSelfHostedHistoryIdentityRequiresServerAndRemainsServerScoped() {
        val firstServer = selfHostedVehicleStableIdentity("https://first.example/", 7)
        val secondServer = selfHostedVehicleStableIdentity("https://second.example/", 7)

        assertFalse(firstServer == secondServer)
        try {
            selfHostedVehicleStableIdentity("   ", 7)
            throw AssertionError("blank self-hosted server identity must fail closed")
        } catch (_: HistoryIdentityUnavailableException) {
            // Expected: offline allocation cannot use a cross-server positive remote id.
        }
    }

    @Test
    fun missingCloudVehicleIdentityFailsClosedWithDomainError() {
        try {
            cloudVehicleStableIdentity("account-a", "")
            throw AssertionError("missing cloud vehicle identity must fail closed")
        } catch (_: HistoryIdentityUnavailableException) {
            // Missing provider identity must not be replaced by the numeric car id.
        }
    }

    @Test
    fun sameRemoteIdInDifferentLocalNamespacesIsNotAReadMatch() {
        val legacy = listOf(DriveData(7))
        val newVehicleLocalHistory = emptyList<DriveData>()

        assertTrue(UnifiedHistoryRepository.mergeDrives(newVehicleLocalHistory, newVehicleLocalHistory).isEmpty())
        assertEquals(1, legacy.size)
    }

    @Test
    fun remoteEmptyKeepsLocalAndObservedZeroOrFalseWinsWithoutNullCoercion() {
        val cached = DriveData(
            driveId = 7,
            startDate = "old",
            odometerDetails = DriveOdometerDetails(distance = 10.0),
            batteryDetails = DriveBatteryDetails(startBatteryLevel = 50)
        )
        val merged = UnifiedHistoryRepository.mergeDrives(emptyList(), listOf(cached))
        assertEquals(listOf(7), merged.map { it.driveId })

        val fresh = cached.copy(
            startDate = "fresh",
            odometerDetails = DriveOdometerDetails(distance = 0.0),
            batteryDetails = DriveBatteryDetails(startBatteryLevel = 0)
        )
        val observed = UnifiedHistoryRepository.mergeDrives(listOf(fresh), listOf(cached)).single()
        assertEquals("fresh", observed.startDate)
        assertEquals(0.0, observed.distance ?: -1.0, 0.0)
        assertEquals(0, observed.startBatteryLevel)
    }

    @Test
    fun migrationRequiresMatchingModelAndNonRollbackOdometer() {
        val eligible = evaluateLegacyHistoryMigration(
            legacyDriveCount = 2,
            legacyChargeCount = 1,
            legacyModel = "Y",
            currentModel = "y",
            legacyMaxOdometer = 100.0,
            currentObservedOdometer = 100.0,
            legacyVehicleFingerprint = "fp",
            currentVehicleFingerprint = "fp",
            explicitUpgradeOrigin = true
        )
        assertTrue(eligible.eligible)

        val mismatch = evaluateLegacyHistoryMigration(
            legacyDriveCount = 2,
            legacyChargeCount = 1,
            legacyModel = "3",
            currentModel = "Y",
            legacyMaxOdometer = 100.0,
            currentObservedOdometer = 120.0,
            legacyVehicleFingerprint = "fp",
            currentVehicleFingerprint = "fp",
            explicitUpgradeOrigin = true
        )
        assertFalse(mismatch.eligible)
        assertEquals(LegacyHistoryMigrationBlockReason.MODEL_MISMATCH, mismatch.reason)

        val rollback = evaluateLegacyHistoryMigration(
            legacyDriveCount = 2,
            legacyChargeCount = 1,
            legacyModel = "Y",
            currentModel = "Y",
            legacyMaxOdometer = 120.0,
            currentObservedOdometer = 100.0,
            legacyVehicleFingerprint = "fp",
            currentVehicleFingerprint = "fp",
            explicitUpgradeOrigin = true
        )
        assertFalse(rollback.eligible)
        assertEquals(LegacyHistoryMigrationBlockReason.ODOMETER_ROLLBACK, rollback.reason)
    }

    @Test
    fun chargeCountsRemainVehicleScopedWhenSourceIdsRepeat() {
        val local = listOf(ChargeData(chargeId = 9))
        val remoteEmpty = UnifiedHistoryRepository.mergeCharges(emptyList(), local)
        assertEquals(1, remoteEmpty.size)
        assertEquals(9, remoteEmpty.single().chargeId)
    }

    @Test
    fun partialRemoteNestedEvidenceMergesWithCachedEvidenceWithoutReplacingZeros() {
        val cached = DriveData(
            driveId = 7,
            odometerDetails = DriveOdometerDetails(
                odometerStart = 100.0,
                distance = 0.0
            ),
            batteryDetails = DriveBatteryDetails(startBatteryLevel = 0, endBatteryLevel = 80)
        )
        val partialRemote = DriveData(
            driveId = 7,
            odometerDetails = DriveOdometerDetails(odometerEnd = 101.0),
            batteryDetails = DriveBatteryDetails(endBatteryLevel = 79)
        )

        val merged = UnifiedHistoryRepository.mergeDrives(listOf(partialRemote), listOf(cached)).single()

        assertEquals(100.0, merged.odometerDetails?.odometerStart ?: -1.0, 0.0)
        assertEquals(101.0, merged.odometerDetails?.odometerEnd ?: -1.0, 0.0)
        assertEquals(0.0, merged.distance ?: -1.0, 0.0)
        assertEquals(0, merged.startBatteryLevel)
        assertEquals(79, merged.endBatteryLevel)
    }
}
