package com.matelink.ui.screens.drives

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.local.VehicleContextRepository
import com.matelink.data.local.HistoryIdentityUnavailableException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.matelink.data.api.models.ParkedDetailData
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.TeslamateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collect
import com.matelink.data.repository.HISTORY_IDENTITY_UNAVAILABLE
import javax.inject.Inject

data class ParkedDetailUiState(
    val isLoading: Boolean = true,
    val data: ParkedDetailData? = null,
    val error: String? = null
)

internal data class ParkedPageIdentity(val stillCurrent: () -> Boolean)

internal interface ParkedPageSource {
    val scopeChanges: Flow<Unit>
    suspend fun identity(carId: Int): ParkedPageIdentity
    suspend fun detail(carId: Int, olderDriveId: Int, newerDriveId: Int): ApiResult<ParkedDetailData>
}

@HiltViewModel
class ParkedDetailViewModel internal constructor(private val source: ParkedPageSource) : ViewModel() {
    @Inject constructor(repository: TeslamateRepository, vehicleContexts: VehicleContextRepository) : this(object : ParkedPageSource {
        override val scopeChanges = vehicleContexts.historyScopeChanges.map { Unit }
        override suspend fun identity(carId: Int): ParkedPageIdentity {
            val scope = vehicleContexts.captureReadScope()
            val context = vehicleContexts.cachedContextForRemote(carId, scope)
                ?: throw HistoryIdentityUnavailableException()
            return ParkedPageIdentity { vehicleContexts.isHistoryContextCurrentNow(scope, context) }
        }
        override suspend fun detail(carId: Int, olderDriveId: Int, newerDriveId: Int) =
            repository.getParkedDetail(carId, olderDriveId, newerDriveId)
    })
    private val _uiState = MutableStateFlow(ParkedDetailUiState())
    val uiState: StateFlow<ParkedDetailUiState> = _uiState.asStateFlow()

    private var generation = 0L
    private var loadJob: Job? = null
    private var activeIdentity: ParkedPageIdentity? = null
    private var request: Triple<Int, Int, Int>? = null

    init {
        viewModelScope.launch {
            source.scopeChanges.collect {
                if (activeIdentity?.stillCurrent() == false) request?.let { load(it.first, it.second, it.third) }
            }
        }
    }

    fun load(carId: Int, olderDriveId: Int, newerDriveId: Int) {
        request = Triple(carId, olderDriveId, newerDriveId)
        val currentGeneration = ++generation
        loadJob?.cancel()
        activeIdentity = null
        _uiState.value = ParkedDetailUiState(isLoading = true)
        loadJob = viewModelScope.launch {
            val execution = currentCoroutineContext()
            var identity: ParkedPageIdentity? = null
            fun requireCurrent() {
                execution.ensureActive()
                if (currentGeneration != generation || identity?.stillCurrent() != true)
                    throw HistoryIdentityUnavailableException()
            }
            try {
                identity = source.identity(carId)
                requireCurrent()
                activeIdentity = identity
                val result = source.detail(carId, olderDriveId, newerDriveId)
                requireCurrent()
                _uiState.value = when (result) {
                    is ApiResult.Success -> ParkedDetailUiState(isLoading = false, data = result.data)
                    is ApiResult.Error -> ParkedDetailUiState(isLoading = false, error = result.message)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (currentGeneration == generation && execution.isActive)
                    _uiState.value = ParkedDetailUiState(isLoading = false, error = HISTORY_IDENTITY_UNAVAILABLE)
            }
        }
    }
}
