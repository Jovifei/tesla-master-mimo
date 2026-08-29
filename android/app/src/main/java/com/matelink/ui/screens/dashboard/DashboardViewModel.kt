package com.matelink.ui.screens.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.matelink.data.api.models.CarData
import com.matelink.data.api.models.CarStatus
import com.matelink.data.api.models.Units
import com.matelink.data.repository.ApiErrorKind
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.GeocodingRepository
import com.matelink.data.repository.SettingsRepository
import com.matelink.data.repository.TeslamateRepository
import com.matelink.data.repository.apiErrorKindFor
import com.matelink.data.sync.DataSyncWorker
import com.matelink.domain.telemetry.usableVehicleCoordinates
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
    val units: Units = Units(),
    val cachedAddress: String? = null
)

private data class TruthfulVehicleSnapshot(
    val status: CarStatus,
    val fieldSources: Map<String, String>
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: TeslamateRepository,
    private val settingsRepository: SettingsRepository,
    private val geocodingRepository: GeocodingRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        loadDashboard()
        startPolling()
    }

    private fun loadDashboard() {
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
                } else {
                    null
                }
                val adapterData = (adapterResult as? ApiResult.Success)?.data
                val rawStatus = adapterData?.status
                    ?: (statusResult as? ApiResult.Success)?.data?.status
                val truthful = rawStatus?.let {
                    truthfulSnapshot(it, adapterData?.fieldSources.orEmpty())
                }
                val units = adapterData?.units
                    ?: (statusResult as? ApiResult.Success)?.data?.units
                    ?: Units()

                val primaryError = when {
                    carsResult is ApiResult.Error -> carsResult
                    adapterResult is ApiResult.Error && statusResult is ApiResult.Error -> statusResult
                    else -> null
                }

                _uiState.value = DashboardUiState(
                    isLoading = false,
                    car = car,
                    status = truthful?.status,
                    error = primaryError?.message,
                    errorCode = primaryError?.code,
                    errorKind = primaryError?.kind,
                    snapshotSource = adapterData?.source
                        ?: if (statusResult is ApiResult.Success) "teslamate_api" else null,
                    observedAt = adapterData?.observedAt,
                    fieldSources = truthful?.fieldSources.orEmpty(),
                    units = units,
                    cachedAddress = loadCachedAddress(truthful?.status)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
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
                delay(5_000)
                try {
                    val carId = settingsRepository.currentCarId.first()
                    when (val result = repository.getAdapterSnapshot(carId)) {
                        is ApiResult.Success -> {
                            val truthful = truthfulSnapshot(
                                result.data.status,
                                result.data.fieldSources
                            )
                            _uiState.value = _uiState.value.copy(
                                status = truthful.status,
                                error = null,
                                errorCode = null,
                                errorKind = null,
                                snapshotSource = result.data.source,
                                observedAt = result.data.observedAt,
                                fieldSources = truthful.fieldSources,
                                units = result.data.units,
                                cachedAddress = loadCachedAddress(truthful.status)
                            )
                        }
                        is ApiResult.Error -> {
                            when (val legacy = repository.getCarStatus(carId)) {
                                is ApiResult.Success -> {
                                    val truthful = truthfulSnapshot(
                                        legacy.data.status,
                                        emptyMap()
                                    )
                                    _uiState.value = _uiState.value.copy(
                                        status = truthful.status,
                                        error = null,
                                        errorCode = null,
                                        errorKind = null,
                                        snapshotSource = "teslamate_api",
                                        observedAt = null,
                                        fieldSources = emptyMap(),
                                        units = legacy.data.units,
                                        cachedAddress = loadCachedAddress(truthful.status)
                                    )
                                }
                                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                                    error = legacy.message,
                                    errorCode = legacy.code,
                                    errorKind = legacy.kind
                                )
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Polling keeps the last confirmed snapshot and tries again.
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
        val coordinates = usableVehicleCoordinates(
            status?.latitude,
            status?.longitude
        ) ?: return null
        val cache = geocodingRepository.getFromCache(
            coordinates.first,
            coordinates.second
        ) ?: return null
        return listOfNotNull(cache.city, cache.regionName, cache.countryName)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .joinToString(" ")
            .takeIf(String::isNotEmpty)
    }
}

private fun truthfulSnapshot(
    status: CarStatus,
    fieldSources: Map<String, String>
): TruthfulVehicleSnapshot {
    val geodata = status.carGeodata
    val coordinates = usableVehicleCoordinates(
        geodata?.latitude,
        geodata?.longitude
    )
    if (geodata == null || coordinates != null) {
        return TruthfulVehicleSnapshot(status, fieldSources)
    }

    return TruthfulVehicleSnapshot(
        status = status.copy(
            carGeodata = geodata.copy(
                latitude = null,
                longitude = null
            )
        ),
        fieldSources = fieldSources - setOf("latitude", "longitude", "location")
    )
}
