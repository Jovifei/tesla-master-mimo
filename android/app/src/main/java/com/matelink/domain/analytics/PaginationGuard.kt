package com.matelink.domain.analytics

data class PaginationDecision<Id>(
    val stop: Boolean,
    val seenIds: Set<Id>
)

object PaginationGuard {

    fun <Id> evaluate(
        pageSize: Int,
        seenIds: Set<Id>,
        pageIds: List<Id>
    ): PaginationDecision<Id> {
        val newIds = pageIds.filterNot(seenIds::contains).toSet()
        val shouldStop = pageIds.isEmpty() || pageIds.size < pageSize || newIds.isEmpty()

        return if (shouldStop) {
            PaginationDecision(stop = true, seenIds = seenIds)
        } else {
            PaginationDecision(stop = false, seenIds = seenIds + newIds)
        }
    }
}
