package com.matelink.domain.analytics

import com.matelink.data.local.TirePosition
import com.matelink.data.local.entity.DriveSummary
import com.matelink.data.local.entity.TpmsPressureSample
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
data class TpmsPressurePoint(val observedAt: Long, val pressureBar: Double?)

data class TpmsCoverage(
    val sampleCount: Int,
    val coverageByWheel: Map<TirePosition, Int>
)

enum class TpmsTrendFactor {
    AMBIENT,
    HIGHWAY,
    PARKING,
    INSUFFICIENT_EVIDENCE
}

data class TpmsTrendEvidence(
    val factor: TpmsTrendFactor,
    val recommendation: String? = null
)

data class TpmsTrendAnalysis(
    val coverage: TpmsCoverage,
    val series: Map<TirePosition, List<TpmsPressurePoint>>,
    val deltas: Map<TirePosition, Double?>,
    val possibleFactors: List<TpmsTrendEvidence>
)

/** Pure, evidence-only interpretation of locally persisted TPMS samples. */
class TpmsTrendAnalyzer {
    fun analyze(
        samples: List<TpmsPressureSample>,
        drives: List<DriveSummary>
    ): TpmsTrendAnalysis {
        val ordered = samples.sortedBy { it.observedAt }
        val series = linkedMapOf(
            TirePosition.FL to ordered.map { TpmsPressurePoint(it.observedAt, it.pressureFl.finiteOrNull()) },
            TirePosition.FR to ordered.map { TpmsPressurePoint(it.observedAt, it.pressureFr.finiteOrNull()) },
            TirePosition.RL to ordered.map { TpmsPressurePoint(it.observedAt, it.pressureRl.finiteOrNull()) },
            TirePosition.RR to ordered.map { TpmsPressurePoint(it.observedAt, it.pressureRr.finiteOrNull()) }
        )
        val coverage = TpmsCoverage(
            sampleCount = ordered.size,
            coverageByWheel = series.mapValues { (_, points) -> points.count { it.pressureBar != null } }
        )
        val deltas = TirePosition.entries.associateWith { wheel ->
            val values = series.getValue(wheel).mapNotNull { it.pressureBar }
            if (values.size >= 2) values.last() - values.first() else null
        }

        val factors = buildList {
            if (hasAmbientEvidence(ordered)) add(TpmsTrendEvidence(TpmsTrendFactor.AMBIENT))
            if (hasHighwayEvidence(ordered, drives)) add(TpmsTrendEvidence(TpmsTrendFactor.HIGHWAY))
            if (hasParkingEvidence(ordered)) {
                add(
                    TpmsTrendEvidence(
                        TpmsTrendFactor.PARKING,
                        recommendation = "Perform a manual cold check."
                    )
                )
            }
        }.ifEmpty { listOf(TpmsTrendEvidence(TpmsTrendFactor.INSUFFICIENT_EVIDENCE)) }

        return TpmsTrendAnalysis(coverage, series, deltas, factors)
    }

    private fun hasAmbientEvidence(samples: List<TpmsPressureSample>): Boolean {
        val eligible = samples.filter {
            it.outsideTempC.finiteOrNull() != null && it.pressures().all { value -> value != null }
        }
        if (eligible.size < 2) return false
        val first = eligible.first()
        val last = eligible.last()
        val temperatureDelta = last.outsideTempC!! - first.outsideTempC!!
        val pressureDeltas = last.pressures().zip(first.pressures()).map { (new, old) -> new!! - old!! }
        return temperatureDelta != 0.0 && sameStrictDirection(pressureDeltas)
    }

    private fun hasHighwayEvidence(samples: List<TpmsPressureSample>, drives: List<DriveSummary>): Boolean {
        val first = samples.firstOrNull { it.pressures().all { value -> value != null } } ?: return false
        val last = samples.lastOrNull { it.pressures().all { value -> value != null } } ?: return false
        if (first == last || !rising(first, last)) return false
        val latestCompleteObservedAt = last.observedAt
        return drives.any { drive ->
            drive.speedMax >= 90 && drive.endDate.toEpochMillis()?.let { end ->
                end <= latestCompleteObservedAt && latestCompleteObservedAt - end <= SIX_HOURS_MS
            } == true
        }
    }

    private fun hasParkingEvidence(samples: List<TpmsPressureSample>): Boolean {
        val complete = samples.filter { it.pressures().all { value -> value != null } }
        return complete.zipWithNext().any { (before, after) ->
            if (after.observedAt - before.observedAt < TWENTY_FOUR_HOURS_MS) return@any false
            val deltas = after.pressures().zip(before.pressures()).map { (new, old) -> new!! - old!! }
            val candidates = deltas.indices.filter { candidate ->
                val candidateDelta = deltas[candidate]
                candidateDelta <= -0.2 + EPSILON && deltas.indices
                    .filter { other -> other != candidate }
                    .all { other -> deltas[other] - candidateDelta >= 0.2 - EPSILON }
            }
            candidates.size == 1
        }
    }

    private fun rising(first: TpmsPressureSample, last: TpmsPressureSample): Boolean =
        last.pressures().zip(first.pressures()).all { (new, old) -> new!! > old!! }

    private fun sameStrictDirection(values: List<Double>): Boolean {
        if (values.any { it == 0.0 }) return false
        return values.all { it > 0.0 } || values.all { it < 0.0 }
    }

    private fun TpmsPressureSample.pressures() = listOf(
        pressureFl.finiteOrNull(), pressureFr.finiteOrNull(),
        pressureRl.finiteOrNull(), pressureRr.finiteOrNull()
    )

    private fun String.toEpochMillis(): Long? = runCatching { Instant.parse(this).toEpochMilli() }
        .recoverCatching { LocalDateTime.parse(this).toInstant(ZoneOffset.UTC).toEpochMilli() }
        .getOrNull()

    private fun Double?.finiteOrNull(): Double? = this?.takeIf { it.isFinite() }

    private companion object {
        const val SIX_HOURS_MS = 6 * 60 * 60 * 1_000L
        const val TWENTY_FOUR_HOURS_MS = 24 * 60 * 60 * 1_000L
        const val EPSILON = 1e-9
    }
}
