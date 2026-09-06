package com.matelink.domain.map

import com.matelink.data.api.models.CarGeodata
import com.matelink.data.api.models.CarStatus

/** Selects a usable position without allowing an invalid live value to erase a valid cache. */
data class VehiclePosition(
    val latitude: Double,
    val longitude: Double,
    val source: String = "unknown",
    val observedAt: String? = null
)

object VehiclePositionResolver {
    fun resolve(
        telemetry: VehiclePosition?,
        fleet: VehiclePosition?,
        cached: VehiclePosition?
    ): VehiclePosition? = listOf(telemetry, fleet, cached).firstOrNull { it != null && usable(it.latitude, it.longitude) != null }

    fun resolve(
        liveLatitude: Double?,
        liveLongitude: Double?,
        cachedLatitude: Double?,
        cachedLongitude: Double?
    ): VehiclePosition? {
        val live = usable(liveLatitude, liveLongitude)
        if (live != null) return live
        return usable(cachedLatitude, cachedLongitude)
    }

    private fun usable(latitude: Double?, longitude: Double?): VehiclePosition? =
        if (AmapConfiguration.isUsableCoordinate(latitude, longitude)) {
            VehiclePosition(latitude!!, longitude!!)
        } else null
}

/** Merges a fresh status with cached coordinates without allowing an empty snapshot to erase GPS. */
internal fun mergeCarStatusPosition(live: CarStatus?, cached: CarStatus?): CarStatus? {
    if (live == null) return cached
    val position = VehiclePositionResolver.resolve(
        live.latitude, live.longitude, cached?.latitude, cached?.longitude
    ) ?: return live
    if (position.latitude == live.latitude && position.longitude == live.longitude) return live
    return live.copy(carGeodata = (live.carGeodata ?: CarGeodata()).copy(
        latitude = position.latitude,
        longitude = position.longitude
    ))
}
