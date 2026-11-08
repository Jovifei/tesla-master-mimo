package com.matelink.domain.history

/**
 * Placement decision for the message shown when a cloud history load exceeds its
 * deadline: with cached rows already on screen the message is a non-blocking
 * stale-data warning; without cache it becomes the blocking list error.
 */
data class HistoryTimeoutMessage(
    val error: String?,
    val historySyncWarning: String?
)

fun historyTimeoutMessage(
    hasCache: Boolean,
    timeoutText: String,
    currentWarning: String?
): HistoryTimeoutMessage = HistoryTimeoutMessage(
    error = if (hasCache) null else timeoutText,
    historySyncWarning = if (hasCache) timeoutText else currentWarning
)
