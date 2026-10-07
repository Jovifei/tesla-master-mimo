package com.matelink.data.local

/** Vehicle-local namespaces are created only from a verified account/server context. */
data class CompletedChargeEvent(val carId: Int, val remoteCarId: Int, val chargeId: Int, val endDate: String,
    val systemConsumed: Boolean = false, val appConsumed: Boolean = false, val kind: String = "charge")
internal data class CompletionState(val baseline: Int?, val cutoff: Long, val events: List<CompletedChargeEvent>, val seen: Set<Int> = emptySet())
internal fun isNewCompletedCharge(id: Int,end: String,seen: Set<Int>,cutoff: Long): Boolean =
    id !in seen && runCatching { java.time.Instant.parse(end).toEpochMilli() > cutoff }.getOrDefault(false)
internal fun advanceCompletionState(state: CompletionState,carId: Int,remoteId: Int,kind: String,
    completed: List<Pair<Int,String>>): CompletionState {
    val latest=completed.maxOfOrNull { it.first } ?: 0
    val created=if(state.baseline==null) emptyList() else completed.distinctBy { it.first }
        .filter { isNewCompletedCharge(it.first,it.second,state.seen,state.cutoff) && state.events.none { e -> e.chargeId==it.first } }
        .map { CompletedChargeEvent(carId,remoteId,it.first,it.second,kind=kind) }
    return state.copy(baseline=maxOf(state.baseline ?: 0,latest),events=state.events+created,seen=state.seen+completed.map { it.first })
}
internal fun consumeCompletionEvent(events: List<CompletedChargeEvent>,id: Int,system: Boolean) = events.map {
    if(it.chargeId!=id) it else if(system) it.copy(systemConsumed=true) else it.copy(appConsumed=true)
}.filterNot { it.systemConsumed && it.appConsumed }
