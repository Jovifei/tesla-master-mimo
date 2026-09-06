package com.matelink.domain.history

enum class HistoryQualityState(val wireValue: String) {
    OBSERVED("observed"),
    DERIVED("derived"),
    INCOMPLETE("incomplete"),
    QUARANTINED("quarantined")
}

data class HistoryQuality(
    val state: HistoryQualityState,
    val reason: String
)

private val quarantinedProvenance = listOf(
    "snapshot_session",
    "snapshot_consolidated",
    "snapshot_estimate",
    "physical_model",
    "snapshot_charge",
    "inferred_soc_jump"
)

fun classifyDrive(energySource: String?, apiEvidence: String?): HistoryQuality {
    quarantineReason(energySource, apiEvidence)?.let { return it }
    if (apiEvidence.isNullOrBlank() || energySource.isNullOrBlank()) {
        return HistoryQuality(HistoryQualityState.INCOMPLETE, "missing_api_evidence")
    }
    return if (energySource.equals("power_samples", ignoreCase = true)) {
        HistoryQuality(HistoryQualityState.DERIVED, "power_samples")
    } else {
        HistoryQuality(HistoryQualityState.OBSERVED, "api_evidence")
    }
}

fun classifyCharge(
    apiEvidence: String?,
    latitude: Double?,
    longitude: Double?,
    energyAdded: Double?
): HistoryQuality {
    quarantineReason(apiEvidence)?.let { return it }
    if (apiEvidence.isNullOrBlank()) {
        return HistoryQuality(HistoryQualityState.INCOMPLETE, "missing_api_evidence")
    }
    val hasCoordinates = latitude?.isFinite() == true && longitude?.isFinite() == true &&
        (latitude != 0.0 || longitude != 0.0)
    val hasEnergy = energyAdded?.isFinite() == true && energyAdded >= 0.0
    return if (hasCoordinates || hasEnergy) {
        HistoryQuality(HistoryQualityState.OBSERVED, "api_evidence")
    } else {
        HistoryQuality(HistoryQualityState.INCOMPLETE, "missing_charge_measurement")
    }
}

fun isAnalysisEligible(qualityState: String): Boolean =
    qualityState == HistoryQualityState.OBSERVED.wireValue ||
        qualityState == HistoryQualityState.DERIVED.wireValue

/** Older trusted APIs predate quality_state; explicit local imports never use this fallback. */
fun isAnalysisEligible(qualityState: String?, qualityReason: String?): Boolean =
    isAnalysisEligible(qualityState.orEmpty()) ||
        (qualityState == HistoryQualityState.INCOMPLETE.wireValue && qualityReason == "remote_quality_unavailable")

private fun quarantineReason(vararg values: String?): HistoryQuality? {
    val evidence = values.filterNotNull().joinToString(" ").lowercase()
    val source = quarantinedProvenance.firstOrNull(evidence::contains) ?: return null
    return HistoryQuality(HistoryQualityState.QUARANTINED, "synthetic_provenance:$source")
}
