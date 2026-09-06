package com.matelink.data.local

import com.matelink.data.api.models.CarData
import com.matelink.data.repository.LegacyHistoryMigrationRepository
import com.matelink.data.repository.SettingsRepository
import com.matelink.data.repository.TeslamateRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class VehicleContextRepository @Inject constructor(
    private val contextStore: VehicleContextStore,
    private val sessionStore: JourVoltSessionStore,
    private val connectionModeStore: ConnectionModeStore,
    private val settingsRepository: SettingsRepository,
    private val teslamateRepository: TeslamateRepository,
    private val legacyHistoryMigrationRepository: LegacyHistoryMigrationRepository
) : HistoryCarIdResolver, VehicleContextResolver {
    override suspend fun resolve(car: CarData): VehicleContext {
        val mode = connectionModeStore.mode.first() ?: ConnectionMode.SELF_HOSTED
        val serverUrl = settingsRepository.serverUrl.first()
        val source = if (mode == ConnectionMode.TESLA_CLOUD) {
            HistoryConnectionSource.CLOUD
        } else {
            HistoryConnectionSource.SELF_HOSTED
        }
        val serverIdentity = if (source == HistoryConnectionSource.CLOUD) {
            "cloud"
        } else {
            requireSelfHostedServerIdentity(serverUrl)
        }
        return contextStore.resolveCar(
            car = car,
            accountNamespace = sessionStore.current()?.userId,
            connectionSource = source,
            serverIdentity = serverIdentity
        )
    }

    suspend fun resolveAll(cars: List<CarData>): List<VehicleContext> = cars.map { resolve(it) }

    suspend fun resolveRemote(remoteApiCarId: Int): VehicleContext {
        val source = connectionModeStore.mode.first() ?: ConnectionMode.SELF_HOSTED
        val car = when (val result = teslamateRepository.getCars()) {
            is com.matelink.data.repository.ApiResult.Success -> result.data.firstOrNull { it.carId == remoteApiCarId }
            is com.matelink.data.repository.ApiResult.Error -> null
        }
        if (car != null) return resolve(car)
        if (source == ConnectionMode.SELF_HOSTED) {
            val serverUrl = settingsRepository.serverUrl.first()
            val serverIdentity = requireSelfHostedServerIdentity(serverUrl)
            return contextStore.getOrAllocate(
                remoteApiCarId = remoteApiCarId,
                stableIdentity = selfHostedVehicleStableIdentity(serverUrl, remoteApiCarId),
                connectionSource = HistoryConnectionSource.SELF_HOSTED,
                serverIdentity = serverIdentity
            )
        }
        throw HistoryIdentityUnavailableException()
    }

    suspend fun cachedContextForRemote(remoteApiCarId: Int): VehicleContext? {
        val mode = connectionModeStore.mode.first() ?: ConnectionMode.SELF_HOSTED
        if (mode == ConnectionMode.TESLA_CLOUD) {
            val account = sessionStore.current()?.userId?.trim().orEmpty()
            val localId = contextStore.findCloudLocalHistoryCarId(account, remoteApiCarId) ?: return null
            return VehicleContext(
                remoteApiCarId = remoteApiCarId,
                stableIdentity = contextStore.cloudRemoteOpaqueIdentity(account, remoteApiCarId),
                localHistoryCarId = localId,
                connectionSource = HistoryConnectionSource.CLOUD,
                serverIdentity = "cloud"
            )
        }
        val serverUrl = settingsRepository.serverUrl.first()
        val serverIdentity = requireSelfHostedServerIdentity(serverUrl)
        val stableIdentity = selfHostedVehicleStableIdentity(serverUrl, remoteApiCarId)
        val localId = contextStore.findLocalHistoryCarId(stableIdentity) ?: return null
        return VehicleContext(
            remoteApiCarId = remoteApiCarId,
            stableIdentity = stableIdentity,
            localHistoryCarId = localId,
            connectionSource = HistoryConnectionSource.SELF_HOSTED,
            serverIdentity = serverIdentity
        )
    }

    suspend fun localHistoryCarIdFor(remoteApiCarId: Int): Int? =
        cachedContextForRemote(remoteApiCarId)?.localHistoryCarId

    override suspend fun requireLocalHistoryCarId(remoteApiCarId: Int): Int =
        resolveRemote(remoteApiCarId).localHistoryCarId

    /**
     * Records provenance only after an explicit user migration-binding action.
     * Resolving ordinary self-hosted data must never rewrite a V17 unknown marker.
     */
    override suspend fun recordExplicitUpgradeOrigin(car: CarData): Boolean {
        val context = resolve(car)
        if (context.connectionSource != HistoryConnectionSource.SELF_HOSTED) return false
        return legacyHistoryMigrationRepository.recordExplicitUpgradeOrigin(
            legacyCarId = car.carId,
            actualModel = car.carDetails?.model,
            actualVehicleFingerprint = context.stableIdentity
        )
    }
}
