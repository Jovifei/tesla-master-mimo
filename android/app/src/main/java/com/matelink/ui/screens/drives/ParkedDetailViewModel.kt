package com.matelink.ui.screens.drives

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.R
import com.matelink.data.api.models.ParkedDetailData
import com.matelink.data.local.dao.DriveSummaryDao
import com.matelink.data.local.entity.DriveSummary
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.TeslamateRepository
import com.matelink.util.toChineseDisplayAddress
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

data class ParkedDetailUiState(
    val isLoading: Boolean = true,
    val data: ParkedDetailData? = null,
    val error: String? = null,
    // True while the screen shows the locally derived fallback; cloud-only
    // details (temperatures, power, linked charge) are still pending.
    val degraded: Boolean = false,
    val syncWarning: String? = null
)

@HiltViewModel
class ParkedDetailViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: TeslamateRepository,
    private val driveSummaryDao: DriveSummaryDao
) : ViewModel() {
    private val _uiState = MutableStateFlow(ParkedDetailUiState())
    val uiState: StateFlow<ParkedDetailUiState> = _uiState.asStateFlow()

    private var currentLoad: Triple<Int, Int, Int>? = null

    companion object {
        // Weak networks can leave the cloud detail request hanging for minutes;
        // 20s is slower than any healthy request but earlier than a user gives up.
        private const val PARKED_DETAIL_TIMEOUT_MS = 20_000L
    }

    fun load(carId: Int, olderDriveId: Int, newerDriveId: Int) {
        currentLoad = Triple(carId, olderDriveId, newerDriveId)
        viewModelScope.launch {
            fun stale() = currentLoad != Triple(carId, olderDriveId, newerDriveId)

            // 1. Render immediately from the adjacent local drive rows — the same
            //    facts the parked list card already shows without any network.
            val local = localParkedDetail(carId, olderDriveId, newerDriveId)
            if (stale()) return@launch
            if (local != null) {
                _uiState.value = ParkedDetailUiState(isLoading = false, data = local, degraded = true)
            }

            // 2. Enrich from the cloud with a bounded wait; on failure keep the
            //    local fallback visible with an honest stale-data warning.
            val result = withTimeoutOrNull(PARKED_DETAIL_TIMEOUT_MS) {
                repository.getParkedDetail(carId, olderDriveId, newerDriveId)
            }
            if (stale()) return@launch
            val hasLocal = local != null
            _uiState.value = when {
                result is ApiResult.Success -> ParkedDetailUiState(
                    isLoading = false, data = result.data, degraded = false, syncWarning = null
                )
                result is ApiResult.Error -> ParkedDetailUiState(
                    isLoading = false,
                    data = local,
                    degraded = hasLocal,
                    syncWarning = if (hasLocal) appContext.getString(R.string.history_sync_cached) else null,
                    error = if (hasLocal) null else result.message
                )
                else -> ParkedDetailUiState(
                    isLoading = false,
                    data = local,
                    degraded = hasLocal,
                    syncWarning = if (hasLocal) appContext.getString(R.string.history_sync_timeout) else null,
                    error = if (hasLocal) null else appContext.getString(R.string.history_sync_timeout)
                )
            }
        }
    }

    private suspend fun localParkedDetail(carId: Int, olderDriveId: Int, newerDriveId: Int): ParkedDetailData? {
        val older = readDrive(carId, olderDriveId) ?: return null
        val newer = readDrive(carId, newerDriveId) ?: return null
        return buildLocalParkedDetail(olderDriveId, newerDriveId, older, newer)
    }

    private suspend fun readDrive(carId: Int, driveId: Int): DriveSummary? = try {
        // Rows are persisted under the scoped localHistoryCarId, which cold
        // starts cannot resolve offline; fall back to an unambiguous drive-id
        // match rather than showing nothing.
        driveSummaryDao.get(carId, driveId)
            ?: driveSummaryDao.getByDriveId(driveId).takeIf { it.size == 1 }?.first()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}

internal fun buildLocalParkedDetail(
    olderDriveId: Int,
    newerDriveId: Int,
    older: DriveSummary,
    newer: DriveSummary
): ParkedDetailData {
    val startBattery = older.endBatteryLevel.takeIf { it in 0..100 }
    val endBattery = newer.startBatteryLevel.takeIf { it in 0..100 }
    return ParkedDetailData(
        olderDriveId = olderDriveId,
        newerDriveId = newerDriveId,
        startDate = older.endDate,
        endDate = newer.startDate,
        address = older.endAddress?.toChineseDisplayAddress() ?: older.endAddress,
        startBatteryLevel = startBattery,
        endBatteryLevel = endBattery,
        batteryDelta = if (startBattery != null && endBattery != null) endBattery - startBattery else null,
        energyKwh = null,
        averagePowerKw = null,
        peakPowerKw = null,
        insideTempAverage = null,
        outsideTempAverage = null,
        sampleCount = 0,
        coverageSeconds = 0,
        coverageRatio = 0.0,
        linkedCharge = null,
        source = "local_room_cache"
    )
}
