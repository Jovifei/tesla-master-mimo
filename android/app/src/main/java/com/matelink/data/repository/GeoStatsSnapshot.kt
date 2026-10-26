package com.matelink.data.repository

import com.matelink.data.local.HistoryReadScope
import com.matelink.data.local.VehicleContext
import com.matelink.domain.model.ChargeLocation
import com.matelink.domain.model.CountryRecord
import com.matelink.domain.model.DriveLocation
import com.matelink.domain.model.RegionRecord
import java.time.LocalDate
import java.time.ZoneId
import com.matelink.util.parseIsoInstant

/** One verified vehicle/calendar for the complete geographic page and its async children. */
internal class GeoStatsIdentity(
    val scope: HistoryReadScope? = null,
    val vehicle: VehicleContext? = null,
    val zone: ZoneId = ZoneId.systemDefault(),
    val isCurrent: () -> Boolean
)

internal data class GeoStatsSnapshot(
    val countries: List<CountryRecord> = emptyList(),
    val regions: List<RegionRecord> = emptyList(),
    val chargeLocations: List<ChargeLocation> = emptyList(),
    val driveLocations: List<DriveLocation> = emptyList(),
    val zone: ZoneId = ZoneId.systemDefault()
)

internal fun geoVisitInstant(raw: String, zone: ZoneId) = parseIsoInstant(raw)
    ?: runCatching { LocalDate.parse(raw).atStartOfDay(zone).toInstant() }.getOrNull()

internal fun geoLocalYear(raw: String, zone: ZoneId): Int? =
    geoVisitInstant(raw, zone)?.atZone(zone)?.year

internal fun validGeoCoordinates(latitude: Double, longitude: Double): Boolean =
    latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 &&
        longitude in -180.0..180.0 && (latitude != 0.0 || longitude != 0.0)
