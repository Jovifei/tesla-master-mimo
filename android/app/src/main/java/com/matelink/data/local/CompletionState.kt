package com.matelink.data.local

/** Vehicle-local namespaces are created only from a verified account/server context. */
data class CompletedChargeEvent(val carId: Int, val remoteCarId: Int, val chargeId: Int, val endDate: String,
    val systemConsumed: Boolean = false, val appConsumed: Boolean = false, val kind: String = "charge")
internal data class CompletionState(val baseline: Int?, val cutoff: Long, val events: List<CompletedChargeEvent>, val seen: Set<Int> = emptySet())

private fun completionEndMillis(end: String): Long? =
    runCatching { java.time.Instant.parse(end).toEpochMilli() }.getOrNull()

/** A failed/future completion is not seen: the same ID may later arrive with a real end time. */
internal fun isNewCompletedCharge(
    id: Int, end: String, seen: Set<Int>, cutoff: Long,
    nowMillis: Long = System.currentTimeMillis()
): Boolean = id > 0 && id !in seen &&
    completionEndMillis(end)?.let { it > cutoff && it <= nowMillis } == true

internal fun advanceCompletionState(
    state: CompletionState, carId: Int, remoteId: Int, kind: String,
    completed: List<Pair<Int, String>>, nowMillis: Long = System.currentTimeMillis()
): CompletionState {
    // A successful full-page sync alone reaches this function. Keep no historical
    // pre-baseline IDs in the growing persisted set: the cutoff already rejects them.
    // Never mark malformed/future endpoints seen; they can be corrected on retry.
    val valid = completed.mapNotNull { (id, end) ->
        completionEndMillis(end)?.takeIf { id > 0 && it <= nowMillis }?.let { id to (end to it) }
    }
    val latest = valid.maxOfOrNull { it.first } ?: 0
    val afterCutoff = valid.filter { it.second.second > state.cutoff }.distinctBy { it.first }
    val created = if (state.baseline == null) emptyList() else afterCutoff
        .filter { it.first !in state.seen && state.events.none { event -> event.chargeId == it.first } }
        .map { (id, endAndTime) -> CompletedChargeEvent(carId, remoteId, id, endAndTime.first, kind = kind) }
    val historicalIds = valid.asSequence().filter { it.second.second <= state.cutoff }.map { it.first }.toSet()
    return state.copy(
        baseline = maxOf(state.baseline ?: 0, latest),
        events = state.events + created,
        seen = (state.seen - historicalIds) + afterCutoff.map { it.first }
    )
}
internal fun consumeCompletionEvent(events: List<CompletedChargeEvent>, id: Int, system: Boolean) = events.map {
    if (it.chargeId != id) it else if (system) it.copy(systemConsumed = true) else it.copy(appConsumed = true)
}.filterNot { it.systemConsumed && it.appConsumed }
