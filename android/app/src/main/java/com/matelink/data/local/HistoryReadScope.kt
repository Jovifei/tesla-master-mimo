package com.matelink.data.local

/** Identifies where a vehicle's remote data comes from. */
enum class HistoryConnectionSource {
    CLOUD,
    SELF_HOSTED
}


/** In-flight identity snapshot; effective origin does not migrate persisted Room namespaces. */
internal data class HistoryReadScope(
    val source: HistoryConnectionSource,
    val serverIdentity: String,
    val accountNamespace: String?,
    val effectiveApiOrigin: String = serverIdentity
)
