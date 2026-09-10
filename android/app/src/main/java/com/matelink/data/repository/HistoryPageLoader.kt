package com.matelink.data.repository

import java.time.Instant
import kotlinx.coroutines.CancellationException

internal data class HistoryPageLoad<T>(val items: List<T>, val error: ApiResult.Error? = null)

/** Recover every available page; partial success remains partial, never an empty success. */
internal suspend fun <T> loadHistoryPages(
    pageSize: Int = 50,
    maxPages: Int = 2000,
    id: (T) -> Int,
    fetch: suspend (Int) -> ApiResult<List<T>>
): HistoryPageLoad<T> {
    require(pageSize > 0 && maxPages > 0)
    val collected = linkedMapOf<Int, T>()
    for (page in 1..maxPages) {
        val result = try { fetch(page) } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ApiResult.Error("history_remote_unavailable", kind = ApiErrorKind.NETWORK)
        }
        when (result) {
            is ApiResult.Error -> return HistoryPageLoad(collected.values.toList(), result)
            is ApiResult.Success -> {
                val rows = result.data
                if (rows.isEmpty()) return HistoryPageLoad(collected.values.toList())
                val before = collected.size
                rows.forEach { collected[id(it)] = it }
                if (collected.size == before) return HistoryPageLoad(
                    collected.values.toList(), ApiResult.Error("history_repeated_page", kind = ApiErrorKind.NETWORK)
                )
                if (rows.size < pageSize) return HistoryPageLoad(collected.values.toList())
            }
        }
    }
    return HistoryPageLoad(collected.values.toList(), ApiResult.Error("history_page_limit", kind = ApiErrorKind.NETWORK))
}

internal fun historyTimestamp(value: String?): Instant? =
    value?.let { runCatching { Instant.parse(it) }.getOrNull() }

internal fun sameHistorySession(start: String?, end: String?, otherStart: String?, otherEnd: String?): Boolean {
    val firstStart = historyTimestamp(start) ?: return false
    val firstEnd = historyTimestamp(end) ?: return false
    return firstStart == historyTimestamp(otherStart) && firstEnd == historyTimestamp(otherEnd)
}

internal fun historyInRange(value: String?, start: String?, end: String?): Boolean {
    if (start == null && end == null) return true
    val instant = historyTimestamp(value) ?: return false
    val from = historyTimestamp(start)
    val until = historyTimestamp(end)
    return (from == null || !instant.isBefore(from)) && (until == null || instant.isBefore(until))
}
