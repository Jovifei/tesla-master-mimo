package com.matelink.domain.history

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class HistoryRefreshIntegrationContractTest {
    private fun source(path: String) = File("src/main/java/com/matelink/$path").readText()
    @Test fun bothViewModelsUseInjectedClockAndCancelSupersededHistoryLoads() {
        for (kind in listOf("drives/Drives", "charges/Charges")) {
            val source = source("ui/screens/${kind}ViewModel.kt")
            assertTrue(source.contains("private val clock: Clock"))
            assertTrue(source.contains("refreshedHistoryWindow("))
            assertTrue(source.contains("latestLoad.launch(viewModelScope)"))
            assertTrue(source.contains("val result = historyRepository.load(id, startDateStr, endDateStr)\n            ensureCurrent()"))
            assertTrue(source.contains("HISTORY_IDENTITY_UNAVAILABLE"))
        }
    }
    @Test fun historyScreensWireSingleForegroundReturnEffect() {
        for (kind in listOf("drives/Drives", "charges/Charges")) {
            assertTrue(source("ui/screens/${kind}Screen.kt").contains("HistoryForegroundRefreshEffect { viewModel.refresh() }"))
        }
        val effect = source("ui/components/HistoryForegroundRefreshEffect.kt")
        assertTrue(effect.contains("ON_STOP -> gate.onStop()"))
        assertTrue(effect.contains("removeObserver(observer)"))
        assertTrue(effect.contains("rememberUpdatedState(onRefresh)"))
    }
    @Test fun verifiedIdentityOwnsRemoteEligibilityAnd404Compatibility() {
        val repository = source("data/repository/UnifiedHistoryRepository.kt")
        assertTrue(repository.contains("reads.cachedContext(remoteApiCarId, readScope)"))
        assertTrue(repository.contains("reads.getHistoryContext(remoteApiCarId)"))
        assertTrue(repository.contains("result.data.isValidFor(remoteApiCarId)"))
        assertTrue(repository.contains("if (result.code == 404) discoverCars() else result"))
        assertTrue(repository.contains("if (canReadHistory)"))
        assertFalse(repository.contains("val remoteDrives = if (car != null)"))
        assertTrue(repository.contains("if (!scopeUnchanged()) return@loadHistoryPages historyIdentityUnavailableError()\n                response"))
        assertTrue(repository.contains("val canReadHistory = resolved.remoteAuthorized"))
        assertTrue(repository.contains("historyFailureDiagnostic(stage, requestedAt, error)"))
        assertTrue(source("data/repository/HistoryDiscoveryPolicy.kt").contains("requested_at=$" + "requestedAt"))
        assertFalse(repository.contains("Log.w(\"HistorySync\", error.message"))
    }
    @Test fun vehicleContextStillScopesCachedMappingByAccountAndServer() {
        val repository = source("data/local/VehicleContextRepository.kt")
        assertTrue(repository.contains("findOriginCloudLocalHistoryCarId(account, scope.effectiveApiOrigin, remoteApiCarId)"))
        assertTrue(repository.contains("selfHostedVehicleStableIdentity(scope.serverIdentity, remoteApiCarId)"))
        assertTrue(repository.contains("if (account.isEmpty() || session.accessToken.isBlank()) throw HistoryIdentityUnavailableException()"))
        assertTrue(repository.contains("BuildConfig.JOURVOLT_API_BASE_URL"))
    }
    @Test fun dashboardFailureUsesObservedTimeRatherThanPromotingCache() {
        val source = source("ui/screens/dashboard/DashboardViewModel.kt")
        assertTrue(source.contains("failedSnapshotFreshness(current.observedAt, current.status != null, clock.instant())"))
        assertFalse(source.contains("snapshotFreshness = SnapshotFreshness.RECENT"))
    }
    @Test fun listAndDetailsShareVerifiedContextAndCostWriteGuard() {
        for (kind in listOf("drives/Drive", "charges/Charge")) {
            val vm = source("ui/screens/${kind}DetailViewModel.kt")
            assertTrue(vm.contains("historyRepository.resolveContext(carId)"))
            assertTrue(vm.contains("historyRepository.isContextCurrent(resolved)"))
            assertFalse(vm.contains("requireLocalHistoryCarId("))
            assertFalse(vm.contains("historyCarId ?: carId"))
            assertTrue(source("ui/screens/${kind}DetailScreen.kt").contains("HistoryReadNotices(localArchiveLinkPending, historySyncWarning)"))
        }
        assertTrue(source("ui/screens/charges/ChargesViewModel.kt").contains("saveVerifiedHistoryChargeCost("))
        assertTrue(source("ui/screens/charges/ChargeDetailViewModel.kt").contains("saveVerifiedHistoryChargeCost("))
    }
    @Test fun verifiedNamespaceIsDedicatedAndLegacyGlobalResolveRemains() {
        val repository = source("data/local/VehicleContextRepository.kt")
        assertTrue(repository.contains("contextStore.resolveCar("))
        assertTrue(repository.contains("contextStore.resolveVerifiedHistoryCar("))
        assertTrue(repository.contains("contextStore.findCloudLocalHistoryCarId(account, remoteApiCarId)"))
        assertTrue(repository.contains("contextStore.findOriginCloudLocalHistoryCarId(account, scope.effectiveApiOrigin, remoteApiCarId)"))
        val history = source("data/repository/UnifiedHistoryRepository.kt")
        assertTrue(history.contains("vehicleContextRepository.resolveVerifiedHistoryCar(car, scope)"))
        assertTrue(history.contains("vehicleContextRepository.cachedVerifiedHistoryContext(id, scope)"))
        assertTrue(history.contains("localArchiveLinkPending = resolved.localArchiveLinkPending"))
    }

    @Test fun switchingVehiclesClearsPriorFreeChargingAndUnitMetadata() {
        val charges = source("ui/screens/charges/ChargesViewModel.kt").substringAfter("fun setCarId(").substringBefore("private fun loadCarSettings")
        val drives = source("ui/screens/drives/DrivesViewModel.kt").substringAfter("fun setCarId(").substringBefore("fun setDateFilter")
        assertTrue(charges.contains("freeSupercharging = false"))
        assertTrue(drives.contains("units = null"))
    }

}
