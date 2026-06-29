package com.matelink.domain.model

/**
 * Represents the current sync progress for a car.
 */
data class SyncProgress(
    val carId: Int,
    val phase: SyncPhase,
    val currentItem: Int,
    val totalItems: Int,
    val message: String? = null
) {
    val percentage: Float
        get() = if (totalItems > 0) currentItem.toFloat() / totalItems else 0f

    val isComplete: Boolean
        get() = phase == SyncPhase.COMPLETE

    val percentageInt: Int
        get() = (percentage * 100).toInt()
}

enum class SyncPhase {
    IDLE,
    SYNCING_SUMMARIES,
    SYNCING_DRIVE_DETAILS,
    SYNCING_CHARGE_DETAILS,
    COMPLETE,
    ERROR
}

/**
 * Overall sync status across all cars.
 */
data class OverallSyncStatus(
    val carProgresses: Map<Int, SyncProgress>,
    val isAnySyncing: Boolean,
    val allComplete: Boolean
) {
    companion object {
        val IDLE = OverallSyncStatus(
            carProgresses = emptyMap(),
            isAnySyncing = false,
            allComplete = false
        )
    }
}
