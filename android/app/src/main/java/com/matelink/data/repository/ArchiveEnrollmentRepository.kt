package com.matelink.data.repository

import com.matelink.BuildConfig
import com.matelink.data.api.ArchiveEnrollmentRequestFence
import com.matelink.data.api.models.ArchiveEnrollmentDecision
import com.matelink.data.api.models.ArchiveEnrollmentPreview
import com.matelink.data.api.models.ArchiveEnrollmentResponse
import com.matelink.data.local.HistoryConnectionSource
import com.matelink.data.local.HistoryIdentityUnavailableException
import com.matelink.data.local.VehicleContextRepository
import com.matelink.di.TeslamateApiFactory
import com.matelink.ui.screens.archive.ArchiveEnrollmentDependencies
import com.matelink.ui.screens.archive.ArchiveEnrollmentIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import retrofit2.HttpException
import retrofit2.Response
import java.time.Instant
import javax.inject.Inject

/** Uses only the current authenticated cloud and previously verified local vehicle proof. */
class ArchiveEnrollmentRepository @Inject constructor(
    private val contexts: VehicleContextRepository,
    private val settings: SettingsRepository,
    private val factory: TeslamateApiFactory
) {
    internal val identityChanges = combine(contexts.historyScopeChanges, settings.currentCarId) { scope, car -> scope to car }
        .distinctUntilChanged()
    internal fun dependencies() = ArchiveEnrollmentDependencies(
        captureIdentity = { captureIdentity() }, isIdentityCurrent = { isCurrent(it) },
        preview = { handle, identity -> preview(handle, identity) },
        decision = { handle, approve, code, identity -> decision(handle, approve, code, identity) },
        now = { Instant.now() }
    )

    internal suspend fun captureIdentity(): ArchiveEnrollmentIdentity? = try {
        val scope = contexts.captureReadScope()
        if (scope.source != HistoryConnectionSource.CLOUD) null else {
            val carId = settings.currentCarId.first()
            val context = contexts.cachedContextForRemote(carId, scope)
            val proof = context?.let { contexts.cachedVerifiedStatusIdentityDigest(carId, scope, it) }
            if (context == null || proof == null || settings.currentCarId.first() != carId ||
                contexts.captureReadScope() != scope || !contexts.isHistoryContextCurrentNow(scope, context)) null
            else ArchiveEnrollmentIdentity(scope.accountNamespace.orEmpty(), scope.effectiveApiOrigin,
                scope.sessionGeneration, scope.modeGeneration, carId, context.localHistoryCarId, proof)
        }
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }

    internal suspend fun isCurrent(identity: ArchiveEnrollmentIdentity): Boolean = captureIdentity() == identity

    private suspend fun preview(handle: String, identity: ArchiveEnrollmentIdentity): ArchiveEnrollmentPreview {
        val api = client(identity)
        return checked(identity, api.getArchiveEnrollment(handle, fence(identity)))
    }

    private suspend fun decision(handle: String, approve: Boolean, code: String,
                                 identity: ArchiveEnrollmentIdentity): ArchiveEnrollmentPreview {
        val api = client(identity)
        requireCurrent(identity)
        return checked(identity, api.decideArchiveEnrollment(handle, ArchiveEnrollmentDecision(approve, code), fence(identity)))
    }

    private suspend fun client(identity: ArchiveEnrollmentIdentity): com.matelink.data.api.TeslamateApi {
        requireCurrent(identity)
        val api = factory.create(BuildConfig.JOURVOLT_API_BASE_URL, requiredCloudMode = true, noRetry = true)
        requireCurrent(identity)
        return api
    }
    private suspend fun fence(identity: ArchiveEnrollmentIdentity): ArchiveEnrollmentRequestFence {
        val requestContext = currentCoroutineContext()
        return ArchiveEnrollmentRequestFence(identity.accountId, identity.sessionGeneration,
            isCurrent = { requestContext.isActive && isCurrent(identity) })
    }

    private suspend fun checked(identity: ArchiveEnrollmentIdentity,
                                response: Response<ArchiveEnrollmentResponse>): ArchiveEnrollmentPreview {
        requireCurrent(identity)
        if (!response.isSuccessful) {
            response.errorBody()?.close()
            throw HttpException(response)
        }
        return response.body()?.data ?: throw IllegalStateException("archive_preview_unavailable")
    }
    private suspend fun requireCurrent(identity: ArchiveEnrollmentIdentity) {
        currentCoroutineContext().ensureActive()
        if (!isCurrent(identity)) throw HistoryIdentityUnavailableException()
    }
}
