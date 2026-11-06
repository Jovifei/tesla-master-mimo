package com.matelink.ui.components

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
