package com.matelink.domain.analytics

import com.matelink.data.local.entity.TpmsPressureSample
import com.matelink.data.local.TirePosition
import kotlin.math.abs

data class TpmsChangeRule(val enabled: Boolean = true, val deltaBar: Double = 0.3, val windowHours: Int = 24) {
    val valid get() = deltaBar.isFinite() && deltaBar in 0.1..2.0 && windowHours in 1..720
}
data class TpmsPressureChange(val wheel: TirePosition, val before: Double, val after: Double, val observedAt: Long)
fun pressureChanges(previous: TpmsPressureSample?, current: TpmsPressureSample, rule: TpmsChangeRule): List<TpmsPressureChange> {
    if (!rule.enabled || !rule.valid || previous == null || previous.carId != current.carId ||
        previous.provenance != "provider_observation" || current.provenance != "provider_observation" ||
        current.observedAt <= previous.observedAt || current.observedAt-previous.observedAt > rule.windowHours*3600000L) return emptyList()
    val before = listOf(previous.pressureFl,previous.pressureFr,previous.pressureRl,previous.pressureRr)
    val after = listOf(current.pressureFl,current.pressureFr,current.pressureRl,current.pressureRr)
    return TirePosition.entries.mapIndexedNotNull { i,wheel ->
        val a=before[i]; val b=after[i]
        if(a==null || b==null || !a.isFinite() || !b.isFinite() || a<=0 || b<=0 || abs(b-a)+1e-9<rule.deltaBar) null
        else TpmsPressureChange(wheel,a,b,current.observedAt)
    }
}
