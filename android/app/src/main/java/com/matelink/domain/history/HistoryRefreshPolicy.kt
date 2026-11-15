package com.matelink.domain.history

import java.time.Clock
import java.time.LocalDate

internal data class HistoryDateWindow(val start: LocalDate?, val end: LocalDate?)

internal fun refreshedHistoryWindow(days: Long?, custom: Boolean, start: LocalDate?, end: LocalDate?, clock: Clock): HistoryDateWindow {
    if (custom) return HistoryDateWindow(start, end)
    if (days == null) return HistoryDateWindow(null, null)
    val today = LocalDate.now(clock)
    return HistoryDateWindow(if (days > 0) today.minusDays(days - 1) else today, today)
}

/** A resume following a stop refreshes once; suppresses rapid re-entry within cooldown. */
internal class HistoryResumeGate(
    private val minIntervalMs: Long = 60_000L,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private var stopped = false
    private var lastResumeTime = 0L

    fun onStop() { stopped = true }

    fun onResume(): Boolean {
        if (!stopped) return false
        stopped = false
        val now = clock()
        if (minIntervalMs > 0 && lastResumeTime > 0 && now - lastResumeTime < minIntervalMs) {
            return false
        }
        lastResumeTime = now
        return true
    }
}
