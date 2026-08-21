package com.matelink.ui.screens.drivereport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.report.DriveReportDeliveryEntity
import com.matelink.data.report.DriveReportDeliveryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class DriveReportPrompt(
    val latest: DriveReportDeliveryEntity,
    val count: Int,
    val batchMaxDetectedAt: Long
)

@HiltViewModel
class DriveReportPromptViewModel @Inject constructor(
    repository: DriveReportDeliveryRepository
) : ViewModel() {
    private val hiddenThroughDetectedAt = MutableStateFlow(Long.MIN_VALUE)

    val prompt = repository.observeUnseenReports()
        .combine(hiddenThroughDetectedAt) { reports, hiddenThrough ->
            val visible = reports.filter { it.detectedAt > hiddenThrough }
            visible.firstOrNull()?.let {
                DriveReportPrompt(
                    latest = it,
                    count = visible.size,
                    batchMaxDetectedAt = visible.maxOf { report -> report.detectedAt }
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    fun hideBatchForSession(prompt: DriveReportPrompt) {
        hiddenThroughDetectedAt.value = maxOf(
            hiddenThroughDetectedAt.value,
            prompt.batchMaxDetectedAt
        )
    }
}
