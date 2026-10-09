package com.matelink.data.local

import com.matelink.data.api.models.CarData
import com.matelink.data.repository.LegacyHistoryMigrationRepository
import com.matelink.data.repository.TeslamateRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first


@Singleton
class VehicleContextRepository @Inject constructor(
    private val contextStore: VehicleContextStore,
    private val sessionStore: JourVoltSessionStore,
    private val connectionModeStore: ConnectionModeStore,
    private val settingsDataStore: SettingsDataStore,
    private val teslamateRepository: TeslamateRepository,
    private val legacyHistoryMigrationRepository: LegacyHistoryMigrationRepository
) : HistoryCarIdResolver, VehicleContextResolver {
    internal suspend fun captureReadScope(): HistoryReadScope {
        val mode = connectionModeStore.mode.first() ?: ConnectionMode.SELF_HOSTED
        return if (mode == ConnectionMode.TESLA_CLOUD) {
            val session = sessionStore.current() ?: throw HistoryIdentityUnavailableException()
            val account = session.userId.trim()
            if (account.isEmpty() || session.accessToken.isBlank()) throw HistoryIdentityUnavailableException()
            HistoryReadScope(HistoryConnectionSource.CLOUD, "cloud", account,
                requireSelfHostedServerIdentity(com.matelink.BuildConfig.JOURVOLT_API_BASE_URL))
        } else {
            HistoryReadScope(HistoryConnectionSource.SELF_HOSTED,
                requireSelfHostedServerIdentity(settingsDataStore.settings.first().serverUrl), null)
        }
    }

    override suspend fun resolve(car: CarData): VehicleContext = resolve(car, captureReadScope())

    internal fun resolve(car: CarData, scope: HistoryReadScope): VehicleContext = contextStore.resolveCar(
        car, scope.accountNamespace, scope.source, scope.serverIdentity
    )

    suspend fun resolveAll(cars: List<CarData>): List<VehicleContext> = cars.map { resolve(it) }

    suspend fun resolveRemote(remoteApiCarId: Int): VehicleContext {
        val scope = captureReadScope()
        val car = when (val result = teslamateRepository.getCars()) {
            is com.matelink.data.repository.ApiResult.Success -> result.data.firstOrNull { it.carId == remoteApiCarId }
            is com.matelink.data.repository.ApiResult.Error -> null
        }
        if (captureReadScope() != scope) throw HistoryIdentityUnavailableException()
        if (car != null) return resolve(car, scope)
        cachedContextForRemote(remoteApiCarId, scope)?.let { return it }
        if (scope.source == HistoryConnectionSource.SELF_HOSTED) {
            val serverUrl = scope.serverIdentity
            val serverIdentity = scope.serverIdentity
            return contextStore.getOrAllocate(
                remoteApiCarId = remoteApiCarId,
                stableIdentity = selfHostedVehicleStableIdentity(serverUrl, remoteApiCarId),
                connectionSource = HistoryConnectionSource.SELF_HOSTED,
                serverIdentity = serverIdentity
            )
        }
        throw HistoryIdentityUnavailableException()
    }

    suspend fun cachedContextForRemote(remoteApiCarId: Int): VehicleContext? =
        cachedContextForRemote(remoteApiCarId, captureReadScope())

    internal fun cachedContextForRemote(remoteApiCarId: Int, scope: HistoryReadScope): VehicleContext? {
        if (scope.source == HistoryConnectionSource.CLOUD) {
            val account = scope.accountNamespace.orEmpty()
            val localId = contextStore.findCloudLocalHistoryCarId(account, remoteApiCarId) ?: return null
            return VehicleContext(remoteApiCarId,
                contextStore.cloudRemoteOpaqueIdentity(account, remoteApiCarId), localId, scope.source, scope.serverIdentity)
        }
        val stableIdentity = selfHostedVehicleStableIdentity(scope.serverIdentity, remoteApiCarId)
        val localId = contextStore.findLocalHistoryCarId(stableIdentity) ?: return null
        return VehicleContext(remoteApiCarId, stableIdentity, localId, scope.source, scope.serverIdentity)
    }

    /** Used only by UnifiedHistoryRepository; other business data keeps its existing namespace. */
    internal fun resolveVerifiedHistoryCar(car: CarData, scope: HistoryReadScope): VehicleContext =
        if (scope.source == HistoryConnectionSource.CLOUD) {
            contextStore.resolveVerifiedHistoryCar(car, scope.accountNamespace, scope.source,
                scope.serverIdentity, scope.effectiveApiOrigin)
        } else resolve(car, scope)

    internal fun cachedVerifiedHistoryContext(remoteApiCarId: Int, scope: HistoryReadScope): VehicleContext? {
        if (scope.source != HistoryConnectionSource.CLOUD) return cachedContextForRemote(remoteApiCarId, scope)
        val account = scope.accountNamespace.orEmpty()
        val localId = contextStore.findOriginCloudLocalHistoryCarId(account, scope.effectiveApiOrigin, remoteApiCarId)
            ?: return null
        return VehicleContext(remoteApiCarId,
            contextStore.originCloudRemoteOpaqueIdentity(account, scope.effectiveApiOrigin, remoteApiCarId),
            localId, scope.source, scope.serverIdentity)
    }

    /** Existence only: never import or read unknown-origin archive contents automatically. */
    internal fun hasUnlinkedLegacyHistoryContext(remoteApiCarId: Int, scope: HistoryReadScope, context: VehicleContext): Boolean {
        if (scope.source != HistoryConnectionSource.CLOUD) return false
        val legacyId = contextStore.findCloudLocalHistoryCarId(scope.accountNamespace.orEmpty(), remoteApiCarId)
        return legacyId != null && legacyId != context.localHistoryCarId
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
