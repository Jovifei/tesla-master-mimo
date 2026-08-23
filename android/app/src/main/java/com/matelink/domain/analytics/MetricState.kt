package com.matelink.domain.analytics

import java.time.Instant

/**
 * Describes both a metric and the evidence behind it.
 *
 * P0 introduces the shared vocabulary. Existing API models continue to work
 * unchanged; history response metadata is mapped into page-level collection
 * states where an empty response needs more explanation than a zero value.
 */
enum class MetricEvidence {
    OBSERVED,
    DERIVED,
    ESTIMATED
}

enum class MetricSource {
    FLEET_API,
    FLEET_TELEMETRY,
    TESLAMATE,
    MANUAL,
    LOCAL_CALCULATION
}

sealed interface MetricState<out T> {
    data class Available<T>(
        val value: T,
        val evidence: MetricEvidence,
        val source: MetricSource,
        val observedAt: Instant? = null,
        val sampleCount: Int? = null,
        val coveragePercent: Double? = null,
        val confidencePercent: Int? = null
    ) : MetricState<T>

    data class Collecting(
        val startedAt: Instant? = null,
        val progressPercent: Int? = null
    ) : MetricState<Nothing>

    data class Unavailable(
        val reason: String
    ) : MetricState<Nothing>

    data class Failed(
        val message: String,
        val retryable: Boolean
    ) : MetricState<Nothing>
}
