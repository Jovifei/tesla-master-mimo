package com.matelink.data.sync

import com.matelink.data.local.CompletedChargeEvent
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Shared by system delivery and foreground prompt, including after suspended Room reads. */
internal suspend fun <T> resolvePendingCompletion(
    event: CompletedChargeEvent, carId: Int, remoteId: Int, scopeKey: String,
    scopeStillCurrent: suspend () -> Boolean, load: suspend () -> T?, qualifies: (T) -> Boolean
): T? {
    currentCoroutineContext().ensureActive()
    if (event.carId != carId || event.remoteCarId != remoteId || event.scopeKey != scopeKey ||
        !scopeStillCurrent()) return null
    val row = load() ?: return null
    currentCoroutineContext().ensureActive()
    if (!scopeStillCurrent() || !qualifies(row)) return null
    return row
}

/** False/denied post never acknowledges. A scope change also stops the remaining batch. */
internal suspend fun <T> deliverPendingCompletions(
    events: List<CompletedChargeEvent>, scopeStillCurrent: suspend () -> Boolean,
    resolve: suspend (CompletedChargeEvent) -> T?,
    post: (CompletedChargeEvent, T) -> Boolean,
    acknowledge: suspend (CompletedChargeEvent) -> Unit
) {
    for (event in events.filterNot { it.systemConsumed }) {
        currentCoroutineContext().ensureActive()
        if (!scopeStillCurrent()) return
        val row = resolve(event) ?: continue
        if (!scopeStillCurrent()) return
        if (!post(event, row)) return
        currentCoroutineContext().ensureActive()
        if (!scopeStillCurrent()) return
        acknowledge(event)
    }
}

internal enum class CompletionPageDecision { CONTINUE, COMPLETE, INCOMPLETE }

/** A repeated page is a failed traversal, never evidence of an empty/completed baseline. */
internal class CompletionPageGuard(private val pageSize: Int = 50, private val maxPages: Int = 1000) {
    private val seen = hashSetOf<Int>()
    private var pages = 0
    fun accept(ids: List<Int>): CompletionPageDecision {
        require(pageSize > 0 && maxPages > 0)
        pages++
        if (pages > maxPages || ids.size > pageSize || ids.any { it <= 0 } ||
            ids.toSet().size != ids.size || ids.any { it in seen }) return CompletionPageDecision.INCOMPLETE
        seen.addAll(ids)
        return when {
            ids.size < pageSize -> CompletionPageDecision.COMPLETE
            pages >= maxPages -> CompletionPageDecision.INCOMPLETE
            else -> CompletionPageDecision.CONTINUE
        }
    }
}
