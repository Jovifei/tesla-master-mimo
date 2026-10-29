package com.matelink.ui.screens.archive

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.ArchiveEnrollmentPreview
import com.matelink.data.repository.ArchiveEnrollmentRepository
import com.matelink.data.local.HistoryIdentityUnavailableException
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.time.Instant

/** No session tokens or raw provider identity are copied into dialog state. */
data class ArchiveEnrollmentIdentity(
    val accountId: String,
    val apiOrigin: String,
    val sessionGeneration: Long,
    val modeGeneration: Long,
    val vehicleId: Int,
    val localHistoryCarId: Int,
    val vehicleIdentityDigest: String
)

class ArchiveEnrollmentDependencies(
    val captureIdentity: suspend () -> ArchiveEnrollmentIdentity?,
    val isIdentityCurrent: suspend (ArchiveEnrollmentIdentity) -> Boolean,
    val preview: suspend (String, ArchiveEnrollmentIdentity) -> ArchiveEnrollmentPreview,
    val decision: suspend (String, Boolean, String, ArchiveEnrollmentIdentity) -> ArchiveEnrollmentPreview,
    val now: () -> Instant
)

enum class ArchiveEnrollmentPhase { HIDDEN, LOADING, READY, SUBMITTING, APPROVED, REJECTED, ERROR }
enum class ArchiveEnrollmentError { IDENTITY_UNAVAILABLE, EXPIRED, FORBIDDEN, INVALID_REQUEST, UNAVAILABLE }
data class ArchiveEnrollmentUiState(
    val phase: ArchiveEnrollmentPhase = ArchiveEnrollmentPhase.HIDDEN,
    val preview: ArchiveEnrollmentPreview? = null,
    val error: ArchiveEnrollmentError? = null
)

@HiltViewModel
class ArchiveEnrollmentViewModel internal constructor(
    private val dependencies: ArchiveEnrollmentDependencies
) : ViewModel() {
    @Inject constructor(repository: ArchiveEnrollmentRepository) : this(repository.dependencies()) {
        viewModelScope.launch {
            repository.identityChanges.drop(1).collect { onContextChanged() }
        }
    }

    private val mutableState = MutableStateFlow(ArchiveEnrollmentUiState())
    val uiState = mutableState.asStateFlow()
    private var epoch = 0L
    private var job: Job? = null
    private var pinnedIdentity: ArchiveEnrollmentIdentity? = null
    // The Activity-scoped ViewModel survives configuration changes and Compose disposal.
    // An explicit onNewIntent receives a different Intent instance, even for the same handle.
    private var lastDeliveredIntent: Intent? = null

    internal fun claimIntent(intent: Intent): Boolean {
        if (lastDeliveredIntent === intent) return false
        lastDeliveredIntent = intent
        return true
    }

    fun open(handle: String) {
        invalidate()
        val requestEpoch = epoch
        if (!handle.matches(Regex("[0-9a-f]{64}"))) {
            fail(ArchiveEnrollmentError.INVALID_REQUEST)
            return
        }
        mutableState.value = ArchiveEnrollmentUiState(ArchiveEnrollmentPhase.LOADING)
        job = viewModelScope.launch {
            try {
                val identity = dependencies.captureIdentity() ?: throw HistoryIdentityUnavailableException()
                if (requestEpoch != epoch) return@launch
                requireCurrent(identity)
                pinnedIdentity = identity
                val preview = dependencies.preview(handle, identity)
                currentCoroutineContext().ensureActive()
                if (requestEpoch != epoch) return@launch
                requireCurrent(identity)
                validatePreview(preview, handle, identity)
                mutableState.value = ArchiveEnrollmentUiState(ArchiveEnrollmentPhase.READY, preview)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (requestEpoch == epoch) fail(errorFor(e)) }
        }
    }

    /** These are invoked exclusively by the two visible dialog buttons. */
    fun approve() = decide(true)
    fun reject() = decide(false)

    private fun decide(approve: Boolean) {
        val state = mutableState.value
        if (state.phase != ArchiveEnrollmentPhase.READY) return
        val preview = state.preview ?: return
        val identity = pinnedIdentity ?: return
        val requestEpoch = epoch
        // Lock out a second click before launching the first suspending check.
        mutableState.value = state.copy(phase = ArchiveEnrollmentPhase.SUBMITTING)
        job = viewModelScope.launch {
            try {
                requireCurrent(identity)
                validatePreview(preview, preview.handle, identity)
                currentCoroutineContext().ensureActive()
                if (requestEpoch != epoch) return@launch
                val result = dependencies.decision(preview.handle, approve, preview.verificationCode, identity)
                currentCoroutineContext().ensureActive()
                if (requestEpoch != epoch) return@launch
                requireCurrent(identity)
                val expectedStatus = if (approve) "approved" else "rejected"
                if (result.handle != preview.handle || result.verificationCode != preview.verificationCode ||
                    result.vehicleId != preview.vehicleId || result.scopeFingerprint != preview.scopeFingerprint ||
                    result.expiresAt != preview.expiresAt || result.sourceLabel != "TeslaMate" ||
                    result.bindingState !in setOf("active", "expired") || result.status != expectedStatus)
                    throw EnrollmentRejectedException()
                if (!dependencies.now().isBefore(Instant.parse(result.expiresAt))) throw EnrollmentExpiredException()
                mutableState.value = ArchiveEnrollmentUiState(
                    if (approve) ArchiveEnrollmentPhase.APPROVED else ArchiveEnrollmentPhase.REJECTED, result)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (requestEpoch == epoch) fail(errorFor(e)) }
        }
    }

    fun dismiss() { invalidate(); mutableState.value = ArchiveEnrollmentUiState() }
    fun onBackground() = dismiss()
    fun onContextChanged() = dismiss()

    suspend fun verifyCurrentContext() {
        val identity = pinnedIdentity ?: return
        val checkedEpoch = epoch
        val current = try { dependencies.isIdentityCurrent(identity) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { false }
        if (!current && checkedEpoch == epoch) onContextChanged()
    }

    fun checkExpiry() {
        if (mutableState.value.phase != ArchiveEnrollmentPhase.READY) return
        val expiresAt = mutableState.value.preview?.expiresAt ?: return
        if (!dependencies.now().isBefore(Instant.parse(expiresAt))) {
            invalidate(); fail(ArchiveEnrollmentError.EXPIRED)
        }
    }

    private fun invalidate() { epoch++; job?.cancel(); job = null; pinnedIdentity = null }
    private suspend fun requireCurrent(identity: ArchiveEnrollmentIdentity) {
        if (!dependencies.isIdentityCurrent(identity)) throw HistoryIdentityUnavailableException()
    }
    private fun validatePreview(preview: ArchiveEnrollmentPreview, handle: String, identity: ArchiveEnrollmentIdentity) {
        if (preview.handle != handle || preview.vehicleId != identity.vehicleId || preview.status != "pending" ||
            preview.bindingState !in setOf("active", "expired") || preview.sourceLabel != "TeslaMate" ||
            preview.vehicleName.isBlank() || !preview.scopeFingerprint.matches(Regex("[0-9a-f]{16}")) ||
            !preview.verificationCode.matches(Regex("[0-9]{6}"))) throw EnrollmentRejectedException()
        if (!dependencies.now().isBefore(Instant.parse(preview.expiresAt))) throw EnrollmentExpiredException()
    }
    private fun fail(error: ArchiveEnrollmentError) {
        pinnedIdentity = null
        mutableState.value = ArchiveEnrollmentUiState(ArchiveEnrollmentPhase.ERROR, error = error)
    }
    private fun errorFor(error: Exception) = when (error) {
        is HistoryIdentityUnavailableException -> ArchiveEnrollmentError.IDENTITY_UNAVAILABLE
        is EnrollmentExpiredException -> ArchiveEnrollmentError.EXPIRED
        is EnrollmentRejectedException -> ArchiveEnrollmentError.FORBIDDEN
        is HttpException -> when (error.code()) {
            401 -> ArchiveEnrollmentError.IDENTITY_UNAVAILABLE
            403, 404 -> ArchiveEnrollmentError.FORBIDDEN
            410 -> ArchiveEnrollmentError.EXPIRED
            400 -> ArchiveEnrollmentError.INVALID_REQUEST
            else -> ArchiveEnrollmentError.UNAVAILABLE
        }
        else -> ArchiveEnrollmentError.UNAVAILABLE
    }
}

private class EnrollmentExpiredException : IllegalStateException()
private class EnrollmentRejectedException : IllegalStateException()
