package com.matelink.util

import java.util.Locale

/**
 * Map utility functions for determining map provider and coordinate conversion.
 */
object MapUtils {

    /**
     * Determine if the current locale is Chinese (zh-CN).
     * If true, use AMap (高德地图); otherwise, use system default maps.
     */
    fun isChineseLocale(): Boolean {
        val locale = Locale.getDefault()
        return locale.language.startsWith("zh")
    }

    /**
     * Convert WGS-84 coordinates to the appropriate coordinate system for the current map.
     * - zh-CN: Convert to GCJ-02 (for AMap)
     * - Others: Keep WGS-84 (for MapKit)
     */
    fun convertCoordinates(lat: Double, lng: Double): Pair<Double, Double> {
        return if (isChineseLocale()) {
            GCJ02Converter.wgs84ToGcj02(lat, lng)
        } else {
            Pair(lat, lng)
        }
    }

    /**
     * Get map provider name for display.
     */
    fun getMapProviderName(): String {
        return if (isChineseLocale()) "高德地图" else "OpenStreetMap"
    }
}
