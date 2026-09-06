from pathlib import Path


def replace_once(path, old, new):
    p = Path(path)
    text = p.read_text()
    if old not in text:
        raise SystemExit(f"anchor missing in {path}: {old[:120]!r}")
    if text.count(old) != 1:
        raise SystemExit(f"anchor not unique in {path}: {text.count(old)}")
    p.write_text(text.replace(old, new, 1))

replace_once('deploy/jourvolt-dev-mock/fleet_provider.go', 'path := "/api/1/vehicles/" + url.PathEscape(vin) + "/vehicle_data"\n\tvar payload struct {', 'path := fleetVehicleDataPath(vin)\n\tvar payload struct {')

replace_once('deploy/jourvolt-dev-mock/fleet_provider.go', 'func (p *fleetProvider) Status(ctx context.Context, userID string, vehicleID int) (vehicleStatus, error) {', 'func fleetVehicleDataPath(vin string) string {\n\tquery := url.Values{}\n\tquery.Set("endpoints", "location_data")\n\treturn "/api/1/vehicles/" + url.PathEscape(vin) + "/vehicle_data?" + query.Encode()\n}\n\nfunc (p *fleetProvider) Status(ctx context.Context, userID string, vehicleID int) (vehicleStatus, error) {')

replace_once('deploy/jourvolt-dev-mock/telemetry_store.go', "UPDATE jourvolt_telemetry_sessions\nSET quality_state='incomplete', quality_reason='local_import_unverified'\nWHERE source='local_import' AND quality_state='quarantined'\n  AND quality_reason IN ('legacy_import', 'legacy_import_without_evidence');", "UPDATE jourvolt_telemetry_sessions\nSET quality_state='incomplete', quality_reason='local_import_summary_only'\nWHERE source='local_import' AND quality_state='quarantined'\n  AND ended_at IS NOT NULL AND ended_at >= started_at;")

replace_once('android/app/src/main/java/com/matelink/data/local/VehicleContextStore.kt', '    @Synchronized\n    fun findLocalHistoryCarId(stableIdentity: String): Int? = preferences.getInt(\n        identityKey(stableIdentity),\n        Int.MIN_VALUE\n    ).takeUnless { it == Int.MIN_VALUE }\n', '    @Synchronized\n    fun findLocalHistoryCarId(stableIdentity: String): Int? = preferences.getInt(\n        identityKey(stableIdentity),\n        Int.MIN_VALUE\n    ).takeUnless { it == Int.MIN_VALUE }\n\n    @Synchronized\n    fun rememberCloudRemoteMapping(accountNamespace: String, remoteApiCarId: Int, localHistoryCarId: Int) {\n        val account = accountNamespace.trim()\n        if (account.isEmpty() || remoteApiCarId < 0 || localHistoryCarId >= 0) return\n        check(preferences.edit().putInt(cloudRemoteKey(account, remoteApiCarId), localHistoryCarId).commit()) {\n            "unable to persist cloud vehicle history mapping"\n        }\n    }\n\n    @Synchronized\n    fun findCloudLocalHistoryCarId(accountNamespace: String, remoteApiCarId: Int): Int? {\n        val account = accountNamespace.trim()\n        if (account.isEmpty() || remoteApiCarId < 0) return null\n        return preferences.getInt(cloudRemoteKey(account, remoteApiCarId), Int.MIN_VALUE)\n            .takeUnless { it == Int.MIN_VALUE }\n    }\n\n    fun cloudRemoteOpaqueIdentity(accountNamespace: String, remoteApiCarId: Int): String =\n        "cloud-cache:${sha256Hex("${accountNamespace.trim()}:$remoteApiCarId")}"\n')

replace_once('android/app/src/main/java/com/matelink/data/local/VehicleContextStore.kt', '    private fun identityKey(stableIdentity: String): String = "identity:${sha256Hex(stableIdentity)}"\n', '    private fun identityKey(stableIdentity: String): String = "identity:${sha256Hex(stableIdentity)}"\n\n    private fun cloudRemoteKey(accountNamespace: String, remoteApiCarId: Int): String =\n        "remote:${sha256Hex("cloud:${accountNamespace.trim()}:car:$remoteApiCarId")}"\n')

replace_once('android/app/src/main/java/com/matelink/data/local/VehicleContextStore.kt', '        val context = getOrAllocate(stableIdentity, car.carId, connectionSource, serverIdentity)\n        return context\n', '        val context = getOrAllocate(stableIdentity, car.carId, connectionSource, serverIdentity)\n        if (connectionSource == HistoryConnectionSource.CLOUD && !accountNamespace.isNullOrBlank()) {\n            rememberCloudRemoteMapping(accountNamespace, car.carId, context.localHistoryCarId)\n        }\n        return context\n')

replace_once('android/app/src/main/java/com/matelink/data/local/VehicleContextRepository.kt', '    suspend fun localHistoryCarIdFor(remoteApiCarId: Int): Int? {\n        val mode = connectionModeStore.mode.first() ?: ConnectionMode.SELF_HOSTED\n        val serverUrl = settingsRepository.serverUrl.first()\n        val source = if (mode == ConnectionMode.TESLA_CLOUD) {\n            HistoryConnectionSource.CLOUD\n        } else {\n            HistoryConnectionSource.SELF_HOSTED\n        }\n        if (source == HistoryConnectionSource.CLOUD) return null\n        return contextStore.findLocalHistoryCarId(\n            selfHostedVehicleStableIdentity(serverUrl, remoteApiCarId)\n        )\n    }\n', '    suspend fun cachedContextForRemote(remoteApiCarId: Int): VehicleContext? {\n        val mode = connectionModeStore.mode.first() ?: ConnectionMode.SELF_HOSTED\n        if (mode == ConnectionMode.TESLA_CLOUD) {\n            val account = sessionStore.current()?.userId?.trim().orEmpty()\n            val localId = contextStore.findCloudLocalHistoryCarId(account, remoteApiCarId) ?: return null\n            return VehicleContext(\n                remoteApiCarId = remoteApiCarId,\n                stableIdentity = contextStore.cloudRemoteOpaqueIdentity(account, remoteApiCarId),\n                localHistoryCarId = localId,\n                connectionSource = HistoryConnectionSource.CLOUD,\n                serverIdentity = "cloud"\n            )\n        }\n        val serverUrl = settingsRepository.serverUrl.first()\n        val serverIdentity = requireSelfHostedServerIdentity(serverUrl)\n        val stableIdentity = selfHostedVehicleStableIdentity(serverUrl, remoteApiCarId)\n        val localId = contextStore.findLocalHistoryCarId(stableIdentity) ?: return null\n        return VehicleContext(\n            remoteApiCarId = remoteApiCarId,\n            stableIdentity = stableIdentity,\n            localHistoryCarId = localId,\n            connectionSource = HistoryConnectionSource.SELF_HOSTED,\n            serverIdentity = serverIdentity\n        )\n    }\n\n    suspend fun localHistoryCarIdFor(remoteApiCarId: Int): Int? =\n        cachedContextForRemote(remoteApiCarId)?.localHistoryCarId\n')

replace_once('android/app/src/main/java/com/matelink/data/repository/UnifiedHistoryRepository.kt', '        if (car == null) {\n            return when (carResult) {\n                is ApiResult.Error -> carResult\n                is ApiResult.Success -> ApiResult.Error(message = "vehicle_not_found", code = 404)\n            }\n        }\n        val context = try {\n            vehicleContextRepository.resolve(car)\n        } catch (_: HistoryIdentityUnavailableException) {\n            return historyIdentityUnavailableError()\n        }\n', '        val context = try {\n            if (car != null) {\n                vehicleContextRepository.resolve(car)\n            } else {\n                vehicleContextRepository.cachedContextForRemote(remoteApiCarId)\n                    ?: return when (carResult) {\n                        is ApiResult.Error -> carResult\n                        is ApiResult.Success -> ApiResult.Error(message = "vehicle_not_found", code = 404)\n                    }\n            }\n        } catch (_: HistoryIdentityUnavailableException) {\n            return historyIdentityUnavailableError()\n        }\n')

replace_once('android/app/src/main/java/com/matelink/data/repository/UnifiedHistoryRepository.kt', '        val remoteDrives = teslamateRepository.getDrives(context.remoteApiCarId, startDate, endDate)\n        val remoteCharges = teslamateRepository.getCharges(context.remoteApiCarId, startDate, endDate)\n', '        val remoteDrives: ApiResult<List<DriveData>> = if (car != null) {\n            teslamateRepository.getDrives(context.remoteApiCarId, startDate, endDate)\n        } else {\n            ApiResult.Error("vehicle_discovery_unavailable", kind = ApiErrorKind.NETWORK)\n        }\n        val remoteCharges: ApiResult<List<ChargeData>> = if (car != null) {\n            teslamateRepository.getCharges(context.remoteApiCarId, startDate, endDate)\n        } else {\n            ApiResult.Error("vehicle_discovery_unavailable", kind = ApiErrorKind.NETWORK)\n        }\n')

replace_once('android/app/src/main/java/com/matelink/ui/screens/battery/BatteryViewModel.kt', '    fun computeStats(): BatteryStats? {\n        val state = _uiState.value\n        val health = state.batteryHealth\n        val status = state.carStatus\n        if (health == null) return null\n\n        // Health/degradation requires measured capacity inputs. Live SOC/range is\n        // useful elsewhere, but must not be turned into a fabricated capacity.\n        val baseOriginalCapacity = health.maxCapacity?.takeIf { it > 0.0 } ?: return null\n        val currentCapacity = health.currentCapacity?.takeIf { it > 0.0 } ?: return null\n        val apiHealthPercent = health.batteryHealthPercentage?.takeIf { it in 0.0..100.0 }\n', '    fun computeStats(): BatteryStats? {\n        val state = _uiState.value\n        val health = state.batteryHealth\n        val status = state.carStatus\n\n        // Capacity health remains unavailable without measured capacity inputs, but\n        // real live SOC/range and history-derived trend evidence must still render.\n        val baseOriginalCapacity = health?.maxCapacity?.takeIf { it > 0.0 }\n        val currentCapacity = health?.currentCapacity?.takeIf { it > 0.0 }\n        val hasCapacityInputs = baseOriginalCapacity != null && currentCapacity != null\n        val apiHealthPercent = health?.batteryHealthPercentage?.takeIf { it in 0.0..100.0 }\n')

replace_once('android/app/src/main/java/com/matelink/ui/screens/battery/BatteryViewModel.kt', '        val healthPercent = apiHealthPercent ?: (currentCapacity / baseOriginalCapacity) * 100.0\n\n        val originalCapacity = baseOriginalCapacity\n        val effectiveCurrentCapacity = currentCapacity\n        val lossKwh = (originalCapacity - effectiveCurrentCapacity).coerceAtLeast(0.0)\n        val lossPercent = (100.0 - healthPercent).coerceAtLeast(0.0)\n', '        val healthPercent = when {\n            !hasCapacityInputs -> 0.0\n            apiHealthPercent != null -> apiHealthPercent\n            else -> (currentCapacity!! / baseOriginalCapacity!!) * 100.0\n        }\n\n        val originalCapacity = baseOriginalCapacity ?: 0.0\n        val effectiveCurrentCapacity = currentCapacity ?: 0.0\n        val lossKwh = if (hasCapacityInputs) (originalCapacity - effectiveCurrentCapacity).coerceAtLeast(0.0) else 0.0\n        val lossPercent = if (hasCapacityInputs) (100.0 - healthPercent).coerceAtLeast(0.0) else 0.0\n')

replace_once('android/app/src/main/java/com/matelink/ui/screens/battery/BatteryViewModel.kt', '        // Efficiency from API (Wh/km)\n        val ratedEfficiency = health.ratedEfficiency?.takeIf { it > 0.0 } ?: return null\n\n        return BatteryStats(\n', '        // Efficiency is optional when only live/trend evidence is available.\n        val ratedEfficiency = health?.ratedEfficiency?.takeIf { it > 0.0 }\n            ?: state.ratedEfficiency?.takeIf { it > 0.0 }\n            ?: 0.0\n        val hasLiveEvidence = status?.batteryLevel != null || status?.estBatteryRangeKm != null ||\n            status?.ratedBatteryRangeKm != null || status?.idealBatteryRangeKm != null\n        val hasRangeEvidence = maxRangeNew != null || maxRangeNow != null\n        if (!hasCapacityInputs && !hasLiveEvidence && !hasRangeEvidence && state.batteryTrend == null) return null\n\n        return BatteryStats(\n')

replace_once('android/app/src/main/java/com/matelink/ui/screens/battery/BatteryScreen.kt', '                Text(\n                    text = stringResource(R.string.battery_service_mode_desc, stats.healthPercent),\n                    style = MaterialTheme.typography.bodySmall,\n                    color = MaterialTheme.colorScheme.onSurfaceVariant\n                )\n', '                Text(\n                    text = if (stats.hasCapacityEstimate) {\n                        stringResource(R.string.battery_service_mode_desc, stats.healthPercent)\n                    } else {\n                        stringResource(R.string.battery_health_unsupported)\n                    },\n                    style = MaterialTheme.typography.bodySmall,\n                    color = MaterialTheme.colorScheme.onSurfaceVariant\n                )\n')

replace_once('android/app/src/main/java/com/matelink/ui/screens/readiness/DataReadinessViewModel.kt', '    /** Returning from Tesla only refreshes authoritative status; it never configures telemetry. */\n    fun onScreenResumed() {\n        pageIsActive = true\n        if (screenWasPaused) refresh()\n    }\n', '    /** Returning from Tesla refreshes authoritative status; load() can then auto-retry configuration. */\n    fun onScreenResumed() {\n        pageIsActive = true\n        if (screenWasPaused) refresh()\n    }\n')

replace_once('android/app/src/main/java/com/matelink/ui/screens/readiness/DataReadinessViewModel.kt', '@HiltViewModel\nclass DataReadinessViewModel', 'internal fun shouldAutoConfigureTelemetry(\n    pairing: TelemetryPairingStatus?,\n    errorCode: String?\n): Boolean = pairing != null &&\n    errorCode == null &&\n    pairing.configSynced != true &&\n    pairing.status.equals("pairing_required", ignoreCase = true)\n\n@HiltViewModel\nclass DataReadinessViewModel')

path = "android/app/src/main/java/com/matelink/ui/screens/readiness/DataReadinessViewModel.kt"
p = Path(path)
text = p.read_text()
marker = "    private fun load(carId: Int) {"
idx = text.index(marker)
tail = text[idx:]
anchor = "                }\n            } catch (e: CancellationException) {\n"
injection = """                }
                val currentPairing = _uiState.value.pairing
                if (
                    isCurrentLoad(generation, carId) &&
                    shouldAutoConfigureTelemetry(currentPairing, _uiState.value.telemetryErrorCode) &&
                    !_uiState.value.isConfiguringTelemetry &&
                    !_uiState.value.isTelemetryActivationPending
                ) {
                    configureTelemetry()
                }
            } catch (e: CancellationException) {
"""
if anchor not in tail:
    raise SystemExit("load catch anchor missing")
tail = tail.replace(anchor, injection, 1)
p.write_text(text[:idx] + tail)

p = Path("deploy/jourvolt-dev-mock/fleet_provider_test.go")
p.write_text(p.read_text() + """
func TestFleetVehicleDataPathRequestsLocationData(t *testing.T) {
\tpath := fleetVehicleDataPath("VIN/with space")
\tif !strings.Contains(path, "/api/1/vehicles/VIN%2Fwith%20space/vehicle_data?") {
\t\tt.Fatalf("vehicle data path = %q", path)
\t}
\tif !strings.Contains(path, "endpoints=location_data") {
\t\tt.Fatalf("location_data endpoint missing: %q", path)
\t}
}
""")

p = Path("deploy/jourvolt-dev-mock/telemetry_import_test.go")
p.write_text(p.read_text() + """
func TestTelemetrySchemaRestoresCompletedLocalImportsFromQuarantine(t *testing.T) {
\tif !strings.Contains(telemetrySchema, "WHERE source='local_import' AND quality_state='quarantined'") {
\t\tt.Fatal("schema must restore structurally completed local imports")
\t}
\tif !strings.Contains(telemetrySchema, "ended_at IS NOT NULL AND ended_at >= started_at") {
\t\tt.Fatal("schema restoration must require a structurally completed interval")
\t}
\tif strings.Contains(telemetrySchema, "quality_reason IN ('legacy_import', 'legacy_import_without_evidence')") {
\t\tt.Fatal("schema must not hide valid local imports merely because an older reason string differs")
\t}
}
""")

Path("android/app/src/test/java/com/matelink/ui/screens/readiness/TelemetryAutoConfigurePolicyTest.kt").write_text("""package com.matelink.ui.screens.readiness

import com.matelink.data.api.models.TelemetryPairingStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryAutoConfigurePolicyTest {
    @Test fun pairingRequiredAutoAttemptsConfiguration() {
        assertTrue(
            shouldAutoConfigureTelemetry(
                TelemetryPairingStatus(status = "pairing_required", configSynced = false),
                null
            )
        )
    }

    @Test fun errorsAndSyncedStateDoNotAutoConfigure() {
        assertFalse(
            shouldAutoConfigureTelemetry(
                TelemetryPairingStatus(status = "pairing_required", configSynced = false),
                "permission_required"
            )
        )
        assertFalse(
            shouldAutoConfigureTelemetry(
                TelemetryPairingStatus(status = "available", configSynced = true),
                null
            )
        )
        assertFalse(shouldAutoConfigureTelemetry(null, null))
    }
}
""")
