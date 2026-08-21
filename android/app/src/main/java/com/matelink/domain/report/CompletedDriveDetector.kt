package com.matelink.domain.report

data class CompletedDriveCandidate(
    val carId: Int,
    val driveId: Int,
    val endDate: String,
    val durationMinutes: Int,
    val distanceKm: Double
) {
    val isEligible: Boolean
        get() = carId > 0 &&
            driveId > 0 &&
            endDate.isNotBlank() &&
            durationMinutes > 0 &&
            distanceKm.isFinite() &&
            distanceKm > 0.0
}

data class CompletedDriveDetectionPlan(
    val initialized: Boolean,
    val nextCursor: Int,
    val newDrives: List<CompletedDriveCandidate>
)

object CompletedDriveDetector {
    fun evaluate(
        carId: Int,
        currentCursor: Int?,
        candidates: List<CompletedDriveCandidate>
    ): CompletedDriveDetectionPlan {
        require(carId > 0) { "carId must be positive" }

        val eligible = candidates
            .asSequence()
            .filter { it.carId == carId && it.isEligible }
            .distinctBy { it.driveId }
            .sortedBy { it.driveId }
            .toList()

        val highestEligibleId = eligible.maxOfOrNull { it.driveId } ?: 0
        if (currentCursor == null) {
            return CompletedDriveDetectionPlan(
                initialized = true,
                nextCursor = highestEligibleId,
                newDrives = emptyList()
            )
        }

        val safeCursor = currentCursor.coerceAtLeast(0)
        val newDrives = eligible.filter { it.driveId > safeCursor }
        return CompletedDriveDetectionPlan(
            initialized = false,
            nextCursor = maxOf(safeCursor, highestEligibleId),
            newDrives = newDrives
        )
    }
}
