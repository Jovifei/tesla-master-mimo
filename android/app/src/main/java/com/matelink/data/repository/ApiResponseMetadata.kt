package com.matelink.data.repository

import com.matelink.data.api.models.ApiDataMeta

/**
 * Optional server evidence attached to a compatible response.
 * Unknown values are preserved so older/newer providers remain compatible.
 */
data class ApiResponseMetadata(
    val availability: String? = null,
    val source: String? = null,
    val observedAt: String? = null,
    val collectionStartedAt: String? = null,
    val coveragePercent: Double? = null
)

fun ApiDataMeta.toApiResponseMetadata(): ApiResponseMetadata = ApiResponseMetadata(
    availability = availability,
    source = source,
    observedAt = observedAt,
    collectionStartedAt = collectionStartedAt,
    coveragePercent = coveragePercent
)
