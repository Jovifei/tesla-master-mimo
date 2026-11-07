package com.matelink.data.api

/** An in-memory request tag; never serialized or added to HTTP headers. */
class ArchiveEnrollmentRequestFence(
    val accountId: String,
    val sessionGeneration: Long,
    val isCurrent: suspend () -> Boolean
)
