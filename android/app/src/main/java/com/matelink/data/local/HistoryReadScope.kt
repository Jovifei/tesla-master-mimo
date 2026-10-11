package com.matelink.data.local

/** Identifies where a vehicle's remote data comes from. */
enum class HistoryConnectionSource {
    CLOUD,
    SELF_HOSTED
}


/** Process generations invalidate suspended ABA requests; they never belong in stable storage keys. */
internal data class HistoryReadScope(
    val source: HistoryConnectionSource,
    val serverIdentity: String,
    val accountNamespace: String?,
    val effectiveApiOrigin: String = serverIdentity,
    val sessionGeneration: Long = 0L,
    val modeGeneration: Long = 0L,
    val settingsGeneration: Long = 0L
)
