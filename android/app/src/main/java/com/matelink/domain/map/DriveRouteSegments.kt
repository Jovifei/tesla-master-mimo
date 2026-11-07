package com.matelink.domain.map

import com.matelink.data.api.models.DrivePosition
import com.matelink.data.api.models.DriveDetail
import com.matelink.util.parseIsoInstant

/** Observed routes keep return visits and break wherever the clock/GPS evidence is missing. */
fun observedDriveRouteSegments(
    positions: List<DrivePosition>, startDate: String? = null, endDate: String? = null,
    maxGapMs: Long = 60_000L
): List<List<Pair<Double, Double>>> {
    require(maxGapMs > 0)
    val start = parseIsoInstant(startDate)?.toEpochMilli()
    val end = parseIsoInstant(endDate)?.toEpochMilli()
    if (start != null && end != null && end < start) return emptyList()
    val untimed = positions.indices.filter { parseIsoInstant(positions[it].date) == null }
    val timed = positions.mapIndexedNotNull { index, point ->
        val at = parseIsoInstant(point.date)?.toEpochMilli() ?: return@mapIndexedNotNull null
        if ((start != null && at < start) || (end != null && at > end)) null else Triple(at, index, point)
    }.groupBy { it.first }.toSortedMap()
    val segments = mutableListOf<List<Pair<Double, Double>>>()
    var segment = mutableListOf<Pair<Double, Double>>()
    var previousAt: Long? = null
    var previousIndex: Int? = null
    fun flush() { if (segment.isNotEmpty()) segments += segment.toList(); segment = mutableListOf() }
    timed.forEach { (at, group) ->
        val coordinates = group.mapNotNull { (_, _, point) ->
            val lat = point.latitude ?: return@mapNotNull null
            val lon = point.longitude ?: return@mapNotNull null
            (lat to lon).takeIf { AmapConfiguration.isUsableCoordinate(lat, lon) }
        }.distinct()
        val point = coordinates.singleOrNull()
        val index = group.first().second
        val missingTimeBetween = previousIndex?.let { previous ->
            val low = minOf(previous, index)
            val high = maxOf(previous, index)
            if (high <= low) false else {
                val slot = untimed.binarySearch(low + 1).let { if (it >= 0) it else -it - 1 }
                untimed.getOrNull(slot)?.let { it < high } == true
            }
        } == true
        if (point == null || missingTimeBetween || previousAt?.let { at.toDouble() - it > maxGapMs } == true) flush()
        if (point != null && segment.lastOrNull() != point) segment += point
        previousAt = if (point == null) null else at
        previousIndex = index
    }
    flush()
    return segments
}

/** Reverse-geocoding a boundary never combines scalar latitude with another observation's longitude. */
fun driveBoundaryCoordinates(detail: DriveDetail, startBoundary: Boolean): Pair<Double, Double>? {
    val latitude = if (startBoundary) detail.startLatitude else detail.endLatitude
    val longitude = if (startBoundary) detail.startLongitude else detail.endLongitude
    if (latitude != null && longitude != null && AmapConfiguration.isUsableCoordinate(latitude, longitude)) return latitude to longitude
    val boundary = parseIsoInstant(if (startBoundary) detail.startDate else detail.endDate) ?: return null
    val start = parseIsoInstant(detail.startDate) ?: return null
    val end = parseIsoInstant(detail.endDate) ?: return null
    val candidates = detail.positions.orEmpty().mapNotNull { point ->
        val at = parseIsoInstant(point.date) ?: return@mapNotNull null
        val lat = point.latitude ?: return@mapNotNull null
        val lon = point.longitude ?: return@mapNotNull null
        if (at.isBefore(start) || at.isAfter(end) || !AmapConfiguration.isUsableCoordinate(lat, lon)) return@mapNotNull null
        val distance = java.time.Duration.between(at, boundary).abs()
        if (distance > java.time.Duration.ofSeconds(60)) null else distance to (lat to lon)
    }
    val nearest = candidates.minOfOrNull { it.first } ?: return null
    return candidates.filter { it.first == nearest }.map { it.second }.distinct().singleOrNull()
}
