package com.matelink.data.sync

import com.matelink.data.repository.ApiResponseMetadata
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

data class HistoryMetadataState(
    val drives: ApiResponseMetadata? = null,
    val charges: ApiResponseMetadata? = null
) {
    val isCollecting: Boolean
        get() = listOfNotNull(drives, charges).any { it.availability == "collecting" }

    val isUnsupported: Boolean
        get() = listOfNotNull(drives, charges).all {
            it.availability == "unsupported"
        } && (drives != null || charges != null)
}

/**
 * Keeps optional server history evidence available to the current process.
 * Legacy TeslaMate responses simply leave this store empty.
 */
@Singleton
class HistoryMetadataStore @Inject constructor() {
    private val states = MutableStateFlow<Map<Int, HistoryMetadataState>>(emptyMap())

    fun observe(carId: Int): Flow<HistoryMetadataState?> = states.map { it[carId] }

    fun updateDrives(carId: Int, metadata: ApiResponseMetadata) {
        states.update { current ->
            val previous = current[carId] ?: HistoryMetadataState()
            current + (carId to previous.copy(drives = metadata))
        }
    }

    fun updateCharges(carId: Int, metadata: ApiResponseMetadata) {
        states.update { current ->
            val previous = current[carId] ?: HistoryMetadataState()
            current + (carId to previous.copy(charges = metadata))
        }
    }
}
