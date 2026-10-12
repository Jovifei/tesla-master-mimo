package com.matelink.data.local

import com.squareup.moshi.JsonClass
import java.time.Instant

/** No route, SOC, energy, token or display name belongs in the notification journal. */
@JsonClass(generateAdapter = true)
data class CompletedChargeEvent(
    val carId: Int, val remoteCarId: Int, val chargeId: Int, val endDate: String,
    val systemConsumed: Boolean = false, val appConsumed: Boolean = false,
    val kind: String = "charge", val source: String? = null,
    val startDate: String? = null, val scopeKey: String? = null
)

@JsonClass(generateAdapter = true)
internal data class CompletionState(
    val baseline: Int?, val cutoff: Long, val events: List<CompletedChargeEvent>,
    val seen: Set<Int> = emptySet()
)

internal data class CompletionReceipt(
    val id: Int, val startDate: String, val endDate: String, val source: String?
)

private fun completionInstant(value: String): Instant? =
    runCatching { Instant.parse(value) }.getOrNull()

/** Invalid/future endpoints must not poison the ID before a corrected receipt arrives. */
internal fun isNewCompletedCharge(
    id: Int, end: String, seen: Set<Int>, cutoff: Long,
    nowMillis: Long = System.currentTimeMillis()
): Boolean = id > 0 && id !in seen && completionInstant(end)?.let {
    it.isAfter(Instant.ofEpochMilli(cutoff)) && !it.isAfter(Instant.ofEpochMilli(nowMillis))
} == true

internal fun advanceCompletionState(
    state: CompletionState, carId: Int, remoteId: Int, kind: String,
    completed: List<Pair<Int, String>>, nowMillis: Long = System.currentTimeMillis(),
    createEvent: (Int, String) -> CompletedChargeEvent = { id, end ->
        CompletedChargeEvent(carId, remoteId, id, end, kind = kind)
    }
): CompletionState {
    require(kind == "drive" || kind == "charge")
    val now = Instant.ofEpochMilli(nowMillis)
    val cutoff = Instant.ofEpochMilli(state.cutoff)
    // Conflicting endpoints for one ID in a page walk are not a completed receipt.
    // Group before filtering future dates, so a conflicting future duplicate cannot
    // silently make the otherwise valid duplicate authoritative.
    val valid = completed.groupBy { it.first }.mapNotNull { (id, rows) ->
        if (id <= 0) return@mapNotNull null
        val ends = rows.map { completionInstant(it.second) }.distinct()
        val end = ends.singleOrNull() ?: return@mapNotNull null
        if (end.isAfter(now)) null else id to end
    }
    val latest = valid.maxOfOrNull { it.first } ?: 0
    val afterCutoff = valid.filter { it.second.isAfter(cutoff) }
    val pendingIds = state.events.mapTo(hashSetOf()) { it.chargeId }
    val created = if (state.baseline == null) emptyList() else afterCutoff
        .filter { it.first !in state.seen && it.first !in pendingIds }
        .map { (id, end) -> createEvent(id, end.toString()) }
    return state.copy(
        baseline = maxOf(state.baseline ?: 0, latest),
        events = state.events + created,
        // Append only: a stale before-cutoff refresh must never erase an already
        // acknowledged ID and allow the following corrected refresh to replay it.
        // Initial pre-cutoff archive rows need not be added, but existing IDs stay.
        seen = state.seen + afterCutoff.map { it.first }
    )
}

/** Production journal path: bind each newly created event to one exact receipt. */
internal fun advanceQualifiedCompletionState(
    state: CompletionState, carId: Int, remoteId: Int, kind: String, scopeKey: String,
    completed: List<CompletionReceipt>, nowMillis: Long
): CompletionState {
    val receipts = completed.groupBy { it.id }.mapNotNull { (_, rows) ->
        val canonical = rows.map { row ->
            val start = completionInstant(row.startDate)
            val end = completionInstant(row.endDate)
            if (start == null || end == null || !start.isBefore(end)) null
            else row.copy(startDate = start.toString(), endDate = end.toString())
        }.distinct()
        canonical.singleOrNull()
    }.associateBy { it.id }

    // Room may already contain a corrected canonical row for an ID whose original
    // notification is still pending. Rebind only that existing, fully-qualified
    // receipt to the corrected same-source window; keep its ack flags and cutoff.
    val cutoff = Instant.ofEpochMilli(state.cutoff)
    val now = Instant.ofEpochMilli(nowMillis)
    val currentReceipts = receipts.values.filter { receipt ->
        val end = completionInstant(receipt.endDate) ?: return@filter false
        end.isAfter(cutoff) && !end.isAfter(now)
    }.associateBy { it.id }
    val reconciledEvents = if (state.baseline == null) state.events else state.events.map { event ->
        val receipt = currentReceipts[event.chargeId] ?: return@map event
        if (event.carId != carId || event.remoteCarId != remoteId || event.kind != kind ||
            event.scopeKey != scopeKey || event.source != receipt.source) return@map event
        val oldStart = event.startDate?.let(::completionInstant) ?: return@map event
        val oldEnd = event.endDate?.let(::completionInstant) ?: return@map event
        val newStart = completionInstant(receipt.startDate) ?: return@map event
        val newEnd = completionInstant(receipt.endDate) ?: return@map event
        if (!oldStart.isBefore(oldEnd) || !oldEnd.isAfter(cutoff) || oldEnd.isAfter(now) ||
            !newStart.isBefore(newEnd) || !newEnd.isAfter(cutoff) || newEnd.isAfter(now) ||
            oldStart == newStart && oldEnd == newEnd) return@map event
        event.copy(startDate = newStart.toString(), endDate = newEnd.toString())
    }
    val reconciledState = if (reconciledEvents == state.events) state else state.copy(events = reconciledEvents)
    return advanceCompletionState(reconciledState, carId, remoteId, kind,
        receipts.values.map { it.id to it.endDate }, nowMillis) { id, end ->
        val receipt = receipts.getValue(id)
        CompletedChargeEvent(carId, remoteId, id, end, kind = kind,
            source = receipt.source, startDate = receipt.startDate, scopeKey = scopeKey)
    }
}

internal fun consumeCompletionEvent(events: List<CompletedChargeEvent>, id: Int, system: Boolean) = events.map {
    if (it.chargeId != id) it else if (system) it.copy(systemConsumed = true) else it.copy(appConsumed = true)
}.filterNot { it.systemConsumed && it.appConsumed }

internal fun sameCompletionReceipt(a: CompletedChargeEvent, b: CompletedChargeEvent): Boolean =
    a.copy(systemConsumed = false, appConsumed = false) == b.copy(systemConsumed = false, appConsumed = false)

/** A stale UI acknowledgement cannot consume another source/window sharing an ID. */
internal fun consumeQualifiedCompletionEvent(
    state: CompletionState, event: CompletedChargeEvent, system: Boolean
): CompletionState = state.copy(events = state.events.map {
    if (!sameCompletionReceipt(it, event)) it
    else if (system) it.copy(systemConsumed = true) else it.copy(appConsumed = true)
}.filterNot { it.systemConsumed && it.appConsumed })
