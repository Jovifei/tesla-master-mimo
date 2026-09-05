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
    val showReadinessIntro: Boolean = false,
    val customPhotoFile: java.io.File? = null,
    val isAmapConfigured: Boolean = false,
    val isHudDismissed: Boolean = false
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: TeslamateRepository,
    private val settingsRepository: SettingsRepository,
    private val geocodingRepository: GeocodingRepository,
    private val dataReadinessStore: DataReadinessStore,
    private val vehiclePhotoStore: com.matelink.data.local.VehiclePhotoStore,
    private val snapshotTripEngine: com.matelink.data.sync.SnapshotTripEngine,
    private val snapshotChargeEngine: com.matelink.data.sync.SnapshotChargeEngine,
    private val amapSettingsStore: com.matelink.data.local.AmapSettingsStore,
    private val vehicleContextRepository: com.matelink.data.local.VehicleContextRepository,
    private val vehicleStatusStore: com.matelink.data.local.VehicleStatusStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private var requestGeneration = 0L

    init {
        loadDashboard()
        startPolling()
        viewModelScope.launch {
            vehiclePhotoStore.photoUpdateSignal.collect {
                val carId = _uiState.value.car?.carId ?: return@collect
                val photo = vehiclePhotoStore.getCustomPhotoFile(carId)
                _uiState.update { it.copy(customPhotoFile = photo) }
            }
        }
        viewModelScope.launch {
            amapSettingsStore.settings.collect { settings ->
                _uiState.update { it.copy(isAmapConfigured = settings.hasKey) }
            }
        }
    }

    fun toggleHudDismissed() {
        _uiState.update { it.copy(isHudDismissed = !it.isHudDismissed) }
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
                val liveStatus = when {
                    adapterResult is ApiResult.Success -> adapterResult.data.status
                    statusResult is ApiResult.Success -> statusResult.data.status
                    else -> null
                }
                if (liveStatus != null) {
                    val observed = (adapterResult as? ApiResult.Success)?.data?.observedAt
                    vehicleStatusStore.saveStatus(effectiveCarId, liveStatus, observed)
                }
                val status = liveStatus ?: vehicleStatusStore.getCachedStatus(effectiveCarId)

                val effectiveCar = car ?: CarData(
                    carId = effectiveCarId,
                    name = "Jovi大鼠标",
                    carDetails = com.matelink.data.api.models.CarDetails(model = "Y", trimBadging = "50"),
                    carExterior = com.matelink.data.api.models.CarExterior(exteriorColor = "DiamondBlack", wheelType = "19_gemini")
                )
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
                    car = effectiveCar,
                    status = status,
                    error = if (status != null) null else primaryError?.message,
                    errorCode = if (status != null) null else primaryError?.code,
                    errorKind = if (status != null) null else primaryError?.kind,
                    snapshotSource = (adapterResult as? ApiResult.Success)?.data?.source
                        ?: if (statusResult is ApiResult.Success) "teslamate_api" else "cached",
                    observedAt = (adapterResult as? ApiResult.Success)?.data?.observedAt
                        ?: vehicleStatusStore.getCachedObservedAt(effectiveCarId),
                    fieldSources = (adapterResult as? ApiResult.Success)?.data?.fieldSources.orEmpty(),
                    snapshotFreshness = if (liveStatus != null) evidence.freshness else SnapshotFreshness.RECENT,
                    snapshotMixedSources = evidence.isMixed,
                    units = units,
                    cachedAddress = loadCachedAddress(status),
                    customPhotoFile = vehiclePhotoStore.getCustomPhotoFile(effectiveCarId)
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
                val currentState = _uiState.value.status?.state?.lowercase()
                val currentSpeed = _uiState.value.status?.speed ?: 0.0
                val delayMs = when {
                    currentState == "driving" || currentSpeed > 0.0 -> 8000L
                    currentState == "charging" -> 15000L
                    currentState == "asleep" -> 60000L
                    else -> 10000L
                }
                delay(delayMs)
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
                            vehicleStatusStore.saveStatus(carId, result.data.status, result.data.observedAt)
                            // Record snapshot for automatic trip and charge tracking
                            try {
                                val historyCarId = vehicleContextRepository.requireLocalHistoryCarId(carId)
                                snapshotTripEngine.recordSnapshot(historyCarId, result.data.status)
                                snapshotChargeEngine.recordSnapshot(historyCarId, result.data.status)
                            } catch (_: Exception) {
                            }
                        }
                        is ApiResult.Error -> {
                            when (val legacy = repository.getCarStatus(carId)) {
                                is ApiResult.Success -> {
                                    if (generation != requestGeneration || settingsRepository.currentCarId.first() != carId) continue
                                    vehicleStatusStore.saveStatus(carId, legacy.data.status, null)
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
                                        snapshotFreshness = SnapshotFreshness.RECENT
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

    fun saveCustomPhoto(inputStream: java.io.InputStream) {
        val carId = _uiState.value.car?.carId ?: return
        viewModelScope.launch {
            val file = vehiclePhotoStore.saveCustomPhoto(carId, inputStream)
            _uiState.update { it.copy(customPhotoFile = file) }
        }
    }

    fun clearCustomPhoto() {
        val carId = _uiState.value.car?.carId ?: return
        viewModelScope.launch {
            vehiclePhotoStore.clearCustomPhoto(carId)
            _uiState.update { it.copy(customPhotoFile = null) }
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
