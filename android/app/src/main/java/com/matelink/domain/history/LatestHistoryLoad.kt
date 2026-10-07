package com.matelink.domain.history

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Main-thread owned: only the newest request may publish, even if an API swallows cancellation. */
internal class LatestHistoryLoad {
    private var generation = 0L
    private var job: Job? = null

    fun launch(scope: CoroutineScope, block: suspend Request.() -> Unit) {
        job?.cancel()
        val request = Request(++generation)
        job = scope.launch { request.block() }
    }

    inner class Request internal constructor(private val expected: Long) {
        suspend fun ensureCurrent() {
            currentCoroutineContext().ensureActive()
            if (expected != generation) throw CancellationException("History request superseded")
        }
    }
}
