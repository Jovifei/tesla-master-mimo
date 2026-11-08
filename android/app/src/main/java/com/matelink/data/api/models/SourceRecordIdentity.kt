package com.matelink.data.api.models

/** Two endpoint spellings of one opaque record identity. Conflicts are never legacy unknown. */
data class SourceRecordIdentity(val value: String?, val conflicted: Boolean)

fun resolveSourceRecordIdentity(sourceId: String?, sourceRecordId: String?): SourceRecordIdentity {
    val old = sourceId?.trim()?.takeIf(String::isNotBlank)
    val current = sourceRecordId?.trim()?.takeIf(String::isNotBlank)
    return SourceRecordIdentity(current ?: old, old != null && current != null && old != current)
}
