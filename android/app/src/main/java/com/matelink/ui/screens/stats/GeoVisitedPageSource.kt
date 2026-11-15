package com.matelink.ui.screens.stats

import com.matelink.data.api.models.Units
import com.matelink.data.repository.*
import com.matelink.domain.model.YearFilter
import kotlinx.coroutines.flow.Flow

/** Minimal data boundary shared by the two existing geographic page consumers. */
internal interface GeoVisitedPageSource {
    val scopeChanges: Flow<Unit>
    suspend fun identity(carId: Int): GeoStatsIdentity
    suspend fun load(carId: Int, countryCode: String?, yearFilter: YearFilter,
                     identity: GeoStatsIdentity? = null): GeoStatsSnapshot
    suspend fun units(carId: Int): Units?
    suspend fun boundary(countryCode: String): CountryBoundary?
}

internal class RepositoryGeoVisitedPageSource(
    private val stats: StatsRepository,
    private val telemetry: TeslamateRepository
) : GeoVisitedPageSource {
    override val scopeChanges get() = stats.geoScopeChanges
    override suspend fun identity(carId: Int) = stats.captureGeoIdentity(carId)
    override suspend fun load(carId: Int, countryCode: String?, yearFilter: YearFilter, identity: GeoStatsIdentity?) =
        stats.getGeoSnapshot(carId, yearFilter, countryCode, identity)
    override suspend fun units(carId: Int) = (telemetry.getCarStatus(carId) as? ApiResult.Success)?.data?.units
    override suspend fun boundary(countryCode: String) = stats.getCountryBoundary(countryCode)
}
