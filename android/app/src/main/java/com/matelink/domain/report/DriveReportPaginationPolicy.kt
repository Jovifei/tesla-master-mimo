package com.matelink.domain.report

object DriveReportPaginationPolicy {
    fun shouldRequestNextPage(
        currentCursor: Int?,
        candidateIds: List<Int>,
        resultSize: Int,
        pageSize: Int
    ): Boolean {
        require(pageSize > 0) { "pageSize must be positive" }
        if (resultSize < pageSize) return false
        // A non-empty first run only needs one page to establish the newest
        // high-water mark. Cursor 0 is different: it represents an activation
        // that initially saw no drives, so later pages must be inspected until
        // they cross the feature-activation cutoff.
        if (currentCursor == null) return false
        if (currentCursor > 0 && candidateIds.any { it <= currentCursor }) return false
        return true
    }
}
