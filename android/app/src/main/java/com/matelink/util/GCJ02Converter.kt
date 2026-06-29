package com.matelink.util

/**
 * GCJ-02 coordinate converter (eviltransform algorithm).
 * Ported from iOS GCJ02Converter.swift.
 *
 * Converts between WGS-84 (GPS) and GCJ-02 (Chinese map) coordinate systems.
 * Accuracy: < 1m
 */
object GCJ02Converter {

    private const val PI = Math.PI
    private const val A = 6378245.0 // Semi-major axis
    private const val EE = 0.00669342162296594323 // Eccentricity squared

    // China boundary box
    private const val LAT_MIN = 0.8293
    private const val LAT_MAX = 55.8271
    private const val LNG_MIN = 72.004
    private const val LNG_MAX = 137.8347

    /**
     * Check if a point is in China (approximate).
     */
    fun isInChina(lat: Double, lng: Double): Boolean {
        return lat in LAT_MIN..LAT_MAX && lng in LNG_MIN..LNG_MAX
    }

    /**
     * Convert WGS-84 to GCJ-02.
     * @return Pair(lat, lng) in GCJ-02
     */
    fun wgs84ToGcj02(lat: Double, lng: Double): Pair<Double, Double> {
        if (!isInChina(lat, lng)) return Pair(lat, lng)

        var dLat = transformLat(lng - 105.0, lat - 35.0)
        var dLng = transformLng(lng - 105.0, lat - 35.0)

        val radLat = lat / 180.0 * PI
        var magic = Math.sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = Math.sqrt(magic)

        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLng = (dLng * 180.0) / (A / sqrtMagic * Math.cos(radLat) * PI)

        return Pair(lat + dLat, lng + dLng)
    }

    /**
     * Convert GCJ-02 to WGS-84 (approximate, 3 iterations).
     * @return Pair(lat, lng) in WGS-84
     */
    fun gcj02ToWgs84(lat: Double, lng: Double): Pair<Double, Double> {
        if (!isInChina(lat, lng)) return Pair(lat, lng)

        var wgsLat = lat
        var wgsLng = lng
        repeat(3) {
            val gcj = wgs84ToGcj02(wgsLat, wgsLng)
            wgsLat += lat - gcj.first
            wgsLng += lng - gcj.second
        }
        return Pair(wgsLat, wgsLng)
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x))
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * Math.sin(y * PI) + 40.0 * Math.sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * Math.sin(y / 12.0 * PI) + 320 * Math.sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x))
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * Math.sin(x * PI) + 40.0 * Math.sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * Math.sin(x / 12.0 * PI) + 300.0 * Math.sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }
}
