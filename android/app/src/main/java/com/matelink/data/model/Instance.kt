package com.matelink.data.model

import java.util.UUID

/**
 * Represents a TeslaMate server instance configuration.
 *
 * Each instance connects to a separate TeslaMate server or vehicle.
 * Instances are stored locally and can be switched between.
 *
 * @param id Unique identifier (UUID)
 * @param name User-visible display name (e.g., "My Model 3")
 * @param serverUrl TeslaMate API base URL
 * @param carId TeslaMate car ID for this instance
 */
data class Instance(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val serverUrl: String,
    val carId: Int = 1
) {
    companion object {
        /** Create an Instance from the current single-instance settings. */
        fun fromLegacy(serverUrl: String, carId: Int?): Instance = Instance(
            name = "Default",
            serverUrl = serverUrl,
            carId = carId ?: 1
        )
    }
}
