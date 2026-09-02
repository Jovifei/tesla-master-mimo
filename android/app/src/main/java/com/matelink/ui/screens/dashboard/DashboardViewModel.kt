package com.matelink.ui.screens.dashboard

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.CarData
import com.matelink.data.api.models.CarStatus
import com.matelink.data.api.models.DataReadiness
import com.matelink.data.api.models.Units
import com.matelink.data.repository.ApiErrorKind
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.GeocodingRepository
import com.matelink.data.repository.apiErrorKindFor
import com.matelink.data.repository.SettingsRepository
import com.matelink.data.repository.TeslamateRepository
import com.matelink.data.local.DataReadinessStore
import com.matelink.data.sync.DataSyncWorker
import com.matelink.domain.telemetry.SnapshotFreshness
import com.matelink.domain.telemetry.snapshotEvidence
import com.matelink.domain.telemetry.usableVehicleCoordinates
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DashboardUiState(
    val isLoading: Boolean = true,
    val car: CarData? = null,
    val status: CarStatus? = null,
    val error: String? = null,
    val errorCode: Int? = null,
    val errorKind: ApiErrorKind? = null,
    val snapshotSource: String? = null,
    val observedAt: String? = null,
    val fieldSources: Map<String, String> = emptyMap(),
    val snapshotFreshness: SnapshotFreshness = SnapshotFreshness.UNAVAILABLE,
    val snapshotMixedSources: Boolean = false,
    val units: Units = Units(),
    val cachedAddress: String? = null,
    val dataReadiness: DataReadiness? = null,
    val dataReadinessCarId: Int? = null,
    val showReadinessIntro: Boolean = false
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: TeslamateRepository,
    private val settingsRepository: SettingsRepository,
    private val geocodingRepository: GeocodingRepository,
    private val dataReadinessStore: DataReadinessStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private var requestGeneration = 0L

    init {
        loadDashboard()
        startPolling()
    }

    private fun loadDashboard() {
        val generation = ++requestGeneration
        viewModelScope.launch {
            try {
                val carId = settingsRepository.currentCarId.first()

                val carsResult = repository.getCars()
                val car = when (carsResult) {
                    is ApiResult.Success -> {
                        val cars = carsResult.data
                        cars.find { it.carId == carId } ?: cars.firstOrNull()
                    }
                    is ApiResult.Error -> null
                }

                val effectiveCarId = car?.carId ?: carId
                if (car != null && car.carId != carId) {
                    settingsRepository.setCurrentCarId(car.carId)
                }
                val adapterResult = repository.getAdapterSnapshot(effectiveCarId)
                val statusResult = if (adapterResult is ApiResult.Error) {
                    repository.getCarStatus(effectiveCarId)
                } else null
                val status = when {
                    adapterResult is ApiResult.Success -> adapterResult.data.status
                    statusResult is ApiResult.Success -> statusResult.data.status
                    else -> null
                }
                val units = when {
                    adapterResult is ApiResult.Success -> adapterResult.data.units
                    statusResult is ApiResult.Success -> statusResult.data.units
                    else -> Units()
                }
                val evidence = when {
                    adapterResult is ApiResult.Success -> snapshotEvidence(
                        adapterResult.data.source,
                        adapterResult.data.observedAt,
                        adapterResult.data.fieldSources
                    )
                    statusResult is ApiResult.Success -> snapshotEvidence(
                        "teslamate_api", null, emptyMap()
                    )
                    else -> snapshotEvidence(null, null, emptyMap())
                }

                val primaryError = when {
                    carsResult is ApiResult.Error -> carsResult
                    adapterResult is ApiResult.Error && statusResult is ApiResult.Error -> statusResult
                    else -> null
                }
                if (generation != requestGeneration) return@launch
                _uiState.value = DashboardUiState(
                    isLoading = false,
                    car = car,
                    status = status,
                    error = primaryError?.message,
                    errorCode = primaryError?.code,
                    errorKind = primaryError?.kind,
                    snapshotSource = (adapterResult as? ApiResult.Success)?.data?.source
                        ?: if (statusResult is ApiResult.Success) "teslamate_api" else null,
                    observedAt = (adapterResult as? ApiResult.Success)?.data?.observedAt,
                    fieldSources = (adapterResult as? ApiResult.Success)?.data?.fieldSources.orEmpty(),
                    snapshotFreshness = evidence.freshness,
                    snapshotMixedSources = evidence.isMixed,
                    units = units,
                    cachedAddress = loadCachedAddress(status)
                )

                launch {
                    val readinessResult = try {
                        repository.getDataReadiness(effectiveCarId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    val readiness = (readinessResult as? ApiResult.Success)?.data ?: return@launch
                    if (generation != requestGeneration) return@launch
                    _uiState.update { current ->
                        if (current.car?.carId != effectiveCarId) {
                            current
                        } else {
                            current.copy(
                                dataReadiness = readiness,
                                dataReadinessCarId = effectiveCarId,
                                showReadinessIntro = !dataReadinessStore.hasSeen(readiness, effectiveCarId)
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation != requestGeneration) return@launch
                _uiState.value = DashboardUiState(
                    isLoading = false,
                    error = e.message,
                    errorKind = apiErrorKindFor(null, e.message)
                )
            }
        }
    }

    private fun startPolling() {
        viewModelScope.launch {
            while (true) {
                delay(5000)
                try {
                    val generation = requestGeneration
                    val carId = settingsRepository.currentCarId.first()
                    when (val result = repository.getAdapterSnapshot(carId)) {
                        is ApiResult.Success -> {
                            val evidence = snapshotEvidence(result.data.source, result.data.observedAt, result.data.fieldSources)
                            if (generation != requestGeneration || settingsRepository.currentCarId.first() != carId) continue
                            _uiState.value = _uiState.value.copy(
                                status = result.data.status,
                                error = null,
                                errorCode = null,
                                errorKind = null,
                                snapshotSource = result.data.source,
                                observedAt = result.data.observedAt,
                                fieldSources = result.data.fieldSources,
                                snapshotFreshness = evidence.freshness,
                                snapshotMixedSources = evidence.isMixed,
                                units = result.data.units,
                                cachedAddress = loadCachedAddress(result.data.status)
                            )
                        }
                        is ApiResult.Error -> {
                            when (val legacy = repository.getCarStatus(carId)) {
                                is ApiResult.Success -> {
                                    if (generation != requestGeneration || settingsRepository.currentCarId.first() != carId) continue
                                    _uiState.value = _uiState.value.copy(
                                    status = legacy.data.status,
                                    error = null,
                                    errorCode = null,
                                    errorKind = null,
                                    snapshotSource = "teslamate_api",
                                    observedAt = null,
                                    fieldSources = emptyMap(),
                                    snapshotFreshness = SnapshotFreshness.HISTORY,
                                    snapshotMixedSources = false,
                                    units = legacy.data.units,
                                    cachedAddress = loadCachedAddress(legacy.data.status)
                                )
                                }
                                is ApiResult.Error -> {
                                    if (generation != requestGeneration || settingsRepository.currentCarId.first() != carId) continue
                                    _uiState.value = _uiState.value.copy(
                                    error = legacy.message,
                                    errorCode = legacy.code,
                                    errorKind = legacy.kind,
                                    snapshotFreshness = SnapshotFreshness.UNAVAILABLE
                                )
                                }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Silently fail on polling errors
                }
            }
        }
    }

    fun switchCar(carId: Int) {
        viewModelScope.launch {
            settingsRepository.setCurrentCarId(carId)
            loadDashboard()
        }
    }

    fun refresh() {
        triggerDataSync()
        loadDashboard()
    }

    fun dismissReadinessIntro() {
        val readiness = _uiState.value.dataReadiness ?: return
        val carId = _uiState.value.dataReadinessCarId ?: return
        _uiState.update { it.copy(showReadinessIntro = false) }
        viewModelScope.launch {
            dataReadinessStore.markSeen(readiness, carId)
        }
    }

    private fun triggerDataSync() {
        val request = OneTimeWorkRequestBuilder<DataSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag(DataSyncWorker.TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            DataSyncWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    private suspend fun loadCachedAddress(status: CarStatus?): String? {
        val coordinates = usableVehicleCoordinates(status?.latitude, status?.longitude) ?: return null
        val cache = geocodingRepository.getFromCache(coordinates.first, coordinates.second) ?: return null
        return listOfNotNull(cache.city, cache.regionName, cache.countryName)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .joinToString(" ")
            .takeIf { it.isNotEmpty() }
    }
}
