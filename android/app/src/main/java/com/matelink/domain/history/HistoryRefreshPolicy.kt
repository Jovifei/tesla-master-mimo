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

/** A resume following a stop refreshes once; initial composition already loads. */
internal class HistoryResumeGate {
    private var stopped = false
    fun onStop() { stopped = true }
    fun onResume(): Boolean = stopped.also { stopped = false }
}
