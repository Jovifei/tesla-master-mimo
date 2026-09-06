package com.matelink.data.repository

import com.matelink.util.GCJ02Converter

enum class CoordinateSystem { WGS84, GCJ02 }

data class AmapCoordinate(
    val latitude: Double,
    val longitude: Double,
    val coordinateSystem: CoordinateSystem
)

/** Converts a provider WGS-84 point exactly once before AMap rendering/geocoding. */
object AmapCoordinateTransformer {
    fun normalize(latitude: Double?, longitude: Double?, source: CoordinateSystem = CoordinateSystem.WGS84): AmapCoordinate? {
        if (latitude == null || longitude == null || !latitude.isFinite() || !longitude.isFinite() ||
            latitude !in -90.0..90.0 || longitude !in -180.0..180.0 || (latitude == 0.0 && longitude == 0.0)
        ) return null
        if (source == CoordinateSystem.GCJ02 || !GCJ02Converter.isInChina(latitude, longitude)) {
            return AmapCoordinate(latitude, longitude, source)
        }
        val (convertedLatitude, convertedLongitude) = GCJ02Converter.wgs84ToGcj02(latitude, longitude)
        return AmapCoordinate(convertedLatitude, convertedLongitude, CoordinateSystem.GCJ02)
    }
}
