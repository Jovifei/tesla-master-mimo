package com.matelink.data.sync

import com.matelink.data.api.models.TpmsDetails
import com.matelink.data.local.entity.TpmsPressureSample
import com.matelink.data.local.entity.TPMS_VERIFIED_COLLECTOR
import java.time.Instant

private const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000

/** Per-wheel collector instants, never fabricated by the phone polling clock.
 * Different timestamps produce partial rows, merged only by equal millisecond.
 */
internal fun verifiedTpmsSamples(carId: Int, details: TpmsDetails?, now: Long): List<TpmsPressureSample> {
    if (details?.pressureUnit != "bar" || details.pressureSource != "telemetry_mqtt" ||
        details.pressureTimeBasis != "collector_received_at") return emptyList()
    val byTime = linkedMapOf<Long,TpmsPressureSample>()
    fun add(value: Double?, timestamp: String?, f: (TpmsPressureSample,Double)->TpmsPressureSample) {
        val pressure = value?.takeIf { it.isFinite() && it in 0.0..15.0 } ?: return
        val at = timestamp?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return
        if (at <= 0 || at > now || at < now - THIRTY_DAYS_MS) return
        val current = byTime[at] ?: TpmsPressureSample(carId,at,provenance=TPMS_VERIFIED_COLLECTOR)
        byTime[at] = f(current,pressure)
    }
    add(details.pressureFl, details.pressureObservedAtFl){s,v->s.copy(pressureFl=v)}
    add(details.pressureFr, details.pressureObservedAtFr){s,v->s.copy(pressureFr=v)}
    add(details.pressureRl, details.pressureObservedAtRl){s,v->s.copy(pressureRl=v)}
    add(details.pressureRr, details.pressureObservedAtRr){s,v->s.copy(pressureRr=v)}
    return byTime.values.sortedBy { it.observedAt }
}

/** No old/unverified wheel pressure or warning can be called fresh. */
internal fun TpmsDetails.onlyVerifiedWheels(samples: List<TpmsPressureSample>): TpmsDetails {
    val fl = samples.any { it.pressureFl != null }
    val fr = samples.any { it.pressureFr != null }
    val rl = samples.any { it.pressureRl != null }
    val rr = samples.any { it.pressureRr != null }
    return copy(
        pressureFl=pressureFl.takeIf { fl }, pressureFr=pressureFr.takeIf { fr },
        pressureRl=pressureRl.takeIf { rl }, pressureRr=pressureRr.takeIf { rr },
        warningFl=null, warningFr=null, warningRl=null, warningRr=null,
        warningObservedAtFl=null, warningObservedAtFr=null,
        warningObservedAtRl=null, warningObservedAtRr=null
    )
}

/** A warning receipt is independent of pressure, including explicit false.
 * Null wheels remain unknown; consumers must merge only supplied wheel fields.
 */
internal data class VerifiedTpmsWarningObservation(val observedAt: Long, val details: TpmsDetails)

internal fun verifiedTpmsWarnings(details: TpmsDetails?, now: Long): List<VerifiedTpmsWarningObservation> {
    if (details?.warningSource != "telemetry_mqtt" ||
        details.warningTimeBasis != "collector_received_at") return emptyList()
    val byTime = linkedMapOf<Long, TpmsDetails>()
    fun add(value: Boolean?, timestamp: String?, update: (TpmsDetails, Boolean, String) -> TpmsDetails) {
        val warning = value ?: return
        val text = timestamp ?: return
        val at = runCatching { Instant.parse(text).toEpochMilli() }.getOrNull() ?: return
        if (at <= 0 || at > now || at < now - THIRTY_DAYS_MS) return
        val current = byTime[at] ?: TpmsDetails(
            warningSource = details.warningSource, warningTimeBasis = details.warningTimeBasis
        )
        byTime[at] = update(current, warning, text)
    }
    add(details.warningFl, details.warningObservedAtFl) { d, v, t -> d.copy(warningFl=v, warningObservedAtFl=t) }
    add(details.warningFr, details.warningObservedAtFr) { d, v, t -> d.copy(warningFr=v, warningObservedAtFr=t) }
    add(details.warningRl, details.warningObservedAtRl) { d, v, t -> d.copy(warningRl=v, warningObservedAtRl=t) }
    add(details.warningRr, details.warningObservedAtRr) { d, v, t -> d.copy(warningRr=v, warningObservedAtRr=t) }
    return byTime.entries.sortedBy { it.key }.map { VerifiedTpmsWarningObservation(it.key, it.value) }
}
