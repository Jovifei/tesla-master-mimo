package com.matelink.data.local

import com.matelink.data.api.models.CarData
import com.matelink.data.api.models.isValidFor
import com.matelink.data.api.models.validHistoryVehicleUid
import com.matelink.data.repository.ApiResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.matelink.data.repository.LegacyHistoryMigrationRepository
import com.matelink.data.repository.TeslamateRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.CancellationException


@Singleton
class VehicleContextRepository @Inject constructor(
    private val contextStore: VehicleContextStore,
    private val sessionStore: JourVoltSessionStore,
    private val connectionModeStore: ConnectionModeStore,
    private val settingsDataStore: SettingsDataStore,
    private val teslamateRepository: TeslamateRepository,
    private val legacyHistoryMigrationRepository: LegacyHistoryMigrationRepository
) : HistoryCarIdResolver, VehicleContextResolver {
    internal val historyScopeChanges get() = combine(
        connectionModeStore.mode, sessionStore.session, settingsDataStore.settings
    ) { _, _, _ ->
        try { captureReadScope() }
        catch (e: CancellationException) { throw e }
        catch (_: HistoryIdentityUnavailableException) { null }
    }.distinctUntilChanged()

    internal suspend fun captureReadScope(): HistoryReadScope {
        val (selectedMode, modeGeneration) = connectionModeStore.identitySnapshot()
        val mode = selectedMode ?: ConnectionMode.SELF_HOSTED
        val scope = if (mode == ConnectionMode.TESLA_CLOUD) {
            val (currentSession, sessionGeneration) = sessionStore.identitySnapshot()
            val session = currentSession ?: throw HistoryIdentityUnavailableException()
            val account = session.userId.trim()
            if (account.isEmpty() || session.accessToken.isBlank()) throw HistoryIdentityUnavailableException()
            HistoryReadScope(HistoryConnectionSource.CLOUD, "cloud", account,
                requireSelfHostedServerIdentity(com.matelink.BuildConfig.JOURVOLT_API_BASE_URL),
                sessionGeneration, modeGeneration)
        } else {
            val (serverUrl, settingsGeneration) = settingsDataStore.historyIdentitySnapshot {
                settingsDataStore.settings.first().serverUrl
            }
            HistoryReadScope(HistoryConnectionSource.SELF_HOSTED,
                requireSelfHostedServerIdentity(serverUrl), null,
                modeGeneration = modeGeneration, settingsGeneration = settingsGeneration)
        }
        currentCoroutineContext().ensureActive()
        if (!isHistoryGenerationCurrent(scope)) throw HistoryIdentityUnavailableException()
        return scope
    }

    /** Durable continuation fence; process generations still guard the live batch only. */
    internal suspend fun captureContinuationFence(): String {
        val before = captureReadScope()
        suspend fun epochs() = listOf(connectionModeStore.persistentIdentityEpoch(),
            settingsDataStore.persistentIdentityEpoch(), sessionStore.persistentIdentityEpoch())
        val capturedEpochs = epochs()
        if (before != captureReadScope() || capturedEpochs != epochs() || before != captureReadScope())
            throw HistoryIdentityUnavailableException()
        val parts = listOf("history-continuation-v1", before.source.name,
            before.serverIdentity, before.effectiveApiOrigin, before.accountNamespace.orEmpty()) + capturedEpochs.map(Long::toString)
        val payload = parts.joinToString("") { "${it.length}:$it" }
        return java.security.MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    override suspend fun resolve(car: CarData): VehicleContext {
        val scope = captureReadScope()
        return resolveCurrentCar(car, scope)
    }

    internal fun resolve(car: CarData, scope: HistoryReadScope): VehicleContext =
        resolveVerifiedHistoryCar(car, scope)

    suspend fun resolveAll(cars: List<CarData>): List<VehicleContext> {
        val scope = captureReadScope()
        if (cars.map { it.carId }.distinct().size != cars.size) throw HistoryIdentityUnavailableException()
        val result = cars.map { resolveCurrentCar(it, scope) }
        if (captureReadScope() != scope) throw HistoryIdentityUnavailableException()
        if (result.any { !isHistoryContextCurrent(scope, it) }) throw HistoryIdentityUnavailableException()
        return result
    }

    /** A cached authenticated UID proof can be reused without another provider request. */
    internal fun cachedProofForCar(car: CarData, scope: HistoryReadScope): VehicleContext? {
        if (scope.source != HistoryConnectionSource.CLOUD) return null
        if (car.carId <= 0) throw HistoryIdentityUnavailableException()
        val stable = cloudOriginVehicleStableIdentity(scope.accountNamespace.orEmpty(), scope.effectiveApiOrigin,
            car.vehicleUid.orEmpty())
        val identityId = contextStore.findLocalHistoryCarId(stable) ?: return null
        val mapped = cachedContextForRemote(car.carId, scope) ?: return null
        if (identityId != mapped.localHistoryCarId) return null
        return mapped.copy(stableIdentity = stable)
    }

    internal suspend fun resolveCurrentCar(
        car: CarData, scope: HistoryReadScope,
        currentScope: suspend () -> HistoryReadScope = { captureReadScope() },
        discover: suspend (Int, HistoryReadScope) -> CarData? = { id, expected -> discoverHistoryCar(id, expected) }
    ): VehicleContext {
        currentCoroutineContext().ensureActive()
        suspend fun current(context: VehicleContext): Boolean = currentScope() == scope &&
            cachedContextForRemote(context.remoteApiCarId, scope)?.localHistoryCarId == context.localHistoryCarId
        if (currentScope() != scope) throw HistoryIdentityUnavailableException()
        val cached = cachedProofForCar(car, scope)
        if (cached != null) {
            if (!current(cached)) throw HistoryIdentityUnavailableException()
            return cached
        }
        val verified = discover(car.carId, scope) ?: throw HistoryIdentityUnavailableException()
        if (scope.source == HistoryConnectionSource.CLOUD && verified.vehicleUid != car.vehicleUid)
            throw HistoryIdentityUnavailableException()
        if (currentScope() != scope) throw HistoryIdentityUnavailableException()
        currentCoroutineContext().ensureActive()
        val context = resolve(verified, scope)
        if (!current(context)) throw HistoryIdentityUnavailableException()
        return context
    }

    suspend fun resolveRemote(remoteApiCarId: Int): VehicleContext {
        val scope = captureReadScope()
        val car = discoverHistoryCar(remoteApiCarId, scope)
        if (captureReadScope() != scope) throw HistoryIdentityUnavailableException()
        if (car != null) return resolve(car, scope)
        cachedContextForRemote(remoteApiCarId, scope)?.let { return it }
        throw HistoryIdentityUnavailableException()
    }

    /** Fresh authenticated identity before API history writes; cached IDs are for offline reads only. */
    internal suspend fun discoverHistoryCar(remoteApiCarId: Int, scope: HistoryReadScope): CarData? {
        if (remoteApiCarId <= 0) throw HistoryIdentityUnavailableException()
        suspend fun cars(): CarData? = when (val result = teslamateRepository.getCars()) {
            is ApiResult.Success -> {
                val matches = result.data.filter { it.carId == remoteApiCarId }
                if (matches.size > 1) throw HistoryIdentityUnavailableException()
                matches.singleOrNull()?.also {
                    if (scope.source == HistoryConnectionSource.CLOUD && !validHistoryVehicleUid(it.vehicleUid))
                        throw HistoryIdentityUnavailableException()
                }
            }
            is ApiResult.Error -> null
        }
        val car = if (scope.source == HistoryConnectionSource.CLOUD) {
            when (val result = teslamateRepository.getHistoryContext(remoteApiCarId)) {
                is ApiResult.Success -> {
                    if (!result.data.isValidFor(remoteApiCarId)) throw HistoryIdentityUnavailableException()
                    CarData(remoteApiCarId, vehicleUid = result.data.vehicleUid)
                }
                is ApiResult.Error -> if (result.code == 404) cars() else null
            }
        } else cars()
        currentCoroutineContext().ensureActive()
        if (captureReadScope() != scope) throw HistoryIdentityUnavailableException()
        return car
    }

    internal suspend fun isHistoryContextCurrent(scope: HistoryReadScope, context: VehicleContext): Boolean {
        currentCoroutineContext().ensureActive()
        return isHistoryContextCurrentNow(scope, context)
    }

    /** Final source fence after the last suspending token/DAO read. Captured
     * identity fields are immutable; every owned identity mutation first
     * advances its process epoch, while stable storage namespaces ignore it.
     */
    internal fun isHistoryContextCurrentNow(scope: HistoryReadScope, context: VehicleContext): Boolean {
        if (context.connectionSource != scope.source || context.serverIdentity != scope.serverIdentity ||
            !isHistoryGenerationCurrent(scope)) return false
        return try {
            cachedContextForRemote(context.remoteApiCarId, scope)?.localHistoryCarId == context.localHistoryCarId &&
                isHistoryGenerationCurrent(scope)
        } catch (_: HistoryIdentityUnavailableException) { false }
    }

    private fun isHistoryGenerationCurrent(scope: HistoryReadScope): Boolean =
        connectionModeStore.currentIdentityGeneration() == scope.modeGeneration &&
            if (scope.source == HistoryConnectionSource.CLOUD)
                sessionStore.currentIdentityGeneration() == scope.sessionGeneration
            else settingsDataStore.currentIdentityGeneration() == scope.settingsGeneration

    suspend fun cachedContextForRemote(remoteApiCarId: Int): VehicleContext? =
        cachedContextForRemote(remoteApiCarId, captureReadScope())

    internal fun cachedContextForRemote(remoteApiCarId: Int, scope: HistoryReadScope): VehicleContext? {
        if (scope.source == HistoryConnectionSource.CLOUD) {
            val account = scope.accountNamespace.orEmpty()
            val localId = contextStore.findOriginCloudLocalHistoryCarId(account, scope.effectiveApiOrigin, remoteApiCarId) ?: return null
            return VehicleContext(remoteApiCarId,
                contextStore.originCloudRemoteOpaqueIdentity(account, scope.effectiveApiOrigin, remoteApiCarId), localId, scope.source, scope.serverIdentity)
        }
        val stableIdentity = selfHostedVehicleStableIdentity(scope.serverIdentity, remoteApiCarId)
        val localId = contextStore.findLocalHistoryCarId(stableIdentity) ?: return null
        return VehicleContext(remoteApiCarId, stableIdentity, localId, scope.source, scope.serverIdentity)
    }

    /** All current history consumers share this namespace; unknown-origin legacy data stays unlinked. */
    internal fun resolveVerifiedHistoryCar(car: CarData, scope: HistoryReadScope): VehicleContext =
        if (scope.source == HistoryConnectionSource.CLOUD) {
            if (car.carId <= 0) throw HistoryIdentityUnavailableException()
            contextStore.resolveVerifiedHistoryCar(car, scope.accountNamespace, scope.source,
                scope.serverIdentity, scope.effectiveApiOrigin)
        } else contextStore.resolveCar(car, scope.accountNamespace, scope.source, scope.serverIdentity)

    internal fun cachedVerifiedHistoryContext(remoteApiCarId: Int, scope: HistoryReadScope): VehicleContext? {
        return cachedContextForRemote(remoteApiCarId, scope)
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
