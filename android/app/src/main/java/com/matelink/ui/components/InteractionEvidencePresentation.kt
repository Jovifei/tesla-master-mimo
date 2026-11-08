package com.matelink.ui.components

import com.matelink.data.api.models.TpmsDetails
import com.matelink.data.local.TirePosition
import com.matelink.data.local.TpmsAlertProfile
import com.matelink.data.local.TpmsCustomPressureState
import com.matelink.domain.analytics.TpmsCustomAlertEvaluator
import com.matelink.domain.model.VisualWheelReading
import com.matelink.domain.model.VisualWheelState

/** Display boundary only. Never upgrades an unqualified report or changes stored evidence. */
internal fun qualifiedVisualReading(reading: VisualWheelReading?): VisualWheelReading {
    val pressure = reading?.pressureBar
    val time = reading?.observedAt
    if (reading == null || reading.state == VisualWheelState.UNKNOWN || pressure == null ||
        !pressure.isFinite() || pressure !in 0.0..15.0 || time == null || time <= 0L) {
        return VisualWheelReading(null, null, VisualWheelState.UNKNOWN)
    }
    return reading
}

/** The newest independent wheel timestamp is not a claim that all wheels were sampled together. */
internal fun latestVisualWheelObservation(readings: Collection<VisualWheelReading>): Long? =
    readings.mapNotNull { qualifiedVisualReading(it).observedAt }.maxOrNull()

internal enum class VisualPressureBand { UNASSESSED, IN_RANGE, LOW, HIGH }

/** Reuses the existing configured comparator; never assigns a factory/default threshold. */
internal fun visualPressureBand(input: VisualWheelReading?, profile: TpmsAlertProfile?): VisualPressureBand {
    val reading = qualifiedVisualReading(input)
    if (profile?.enabled != true || !profile.isValid ||
        reading.state !in setOf(VisualWheelState.RECORDED, VisualWheelState.WARNING)) return VisualPressureBand.UNASSESSED
    val observation = TpmsCustomAlertEvaluator().observe(profile, TpmsDetails(pressureFl = reading.pressureBar))[TirePosition.FL]
    if (observation?.observed != true) return VisualPressureBand.UNASSESSED
    return when (observation.state) {
        TpmsCustomPressureState.LOW -> VisualPressureBand.LOW
        TpmsCustomPressureState.HIGH -> VisualPressureBand.HIGH
        null -> VisualPressureBand.IN_RANGE
    }
}
