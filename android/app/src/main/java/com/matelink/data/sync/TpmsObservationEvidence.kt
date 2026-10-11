package com.matelink.data.sync

import com.matelink.data.api.models.TpmsDetails
import com.matelink.data.local.entity.TpmsPressureSample
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
        val current = byTime[at] ?: TpmsPressureSample(carId,at,provenance="provider_observation")
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
    val all = fl && fr && rl && rr
    return copy(
        pressureFl=pressureFl.takeIf { fl }, pressureFr=pressureFr.takeIf { fr },
        pressureRl=pressureRl.takeIf { rl }, pressureRr=pressureRr.takeIf { rr },
        warningFl=warningFl.takeIf { all }, warningFr=warningFr.takeIf { all },
        warningRl=warningRl.takeIf { all }, warningRr=warningRr.takeIf { all }
    )
}
