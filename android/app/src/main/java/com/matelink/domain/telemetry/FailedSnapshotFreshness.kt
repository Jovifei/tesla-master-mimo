package com.matelink.domain.telemetry

import java.time.Instant
import java.time.OffsetDateTime

/** Classify retained data after a failed read, using its observation time only. */
internal fun failedSnapshotFreshness(observedAt: String?, hasData: Boolean, now: Instant): SnapshotFreshness {
    if (!hasData) return SnapshotFreshness.UNAVAILABLE
    val observed = observedAt?.let {
        runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull()
    } ?: return SnapshotFreshness.UNAVAILABLE
    if (observed.isAfter(now.plusSeconds(5))) return SnapshotFreshness.UNAVAILABLE
    return if (now.epochSecond - observed.epochSecond <= 120) SnapshotFreshness.RECENT else SnapshotFreshness.HISTORY
}
