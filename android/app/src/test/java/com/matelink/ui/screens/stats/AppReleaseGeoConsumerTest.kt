package com.matelink.ui.screens.stats

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.Units
import com.matelink.data.repository.*
import com.matelink.domain.model.*
import com.matelink.ui.screens.timeline.timelineDurationText
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.TimeZone

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class AppReleaseGeoConsumerTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private fun country(code: String, first: String = "2026-01-01T00:00:00Z") =
        CountryRecord(code, code, "", first, first, 1, 160.9344, 1.0, 1)
    private class Source : GeoVisitedPageSource {
        var epoch = 0
        override val scopeChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        fun change() { epoch++; scopeChanges.tryEmit(Unit) }
        override suspend fun identity(carId: Int): GeoStatsIdentity {
            val captured = epoch
            return GeoStatsIdentity(isCurrent = { epoch == captured })
        }
        var body: suspend (Int, String?) -> GeoStatsSnapshot = { _, _ -> GeoStatsSnapshot() }
        var unitBody: suspend (Int) -> Units? = { Units("km", "bar", "C") }
        var boundaryBody: suspend (String) -> CountryBoundary? = { null }
        override suspend fun load(carId: Int, countryCode: String?, yearFilter: YearFilter, identity: GeoStatsIdentity?) = body(carId, countryCode)
        override suspend fun units(carId: Int) = unitBody(carId)
        override suspend fun boundary(countryCode: String) = boundaryBody(countryCode)
    }

    @Test fun countriesRejectLateOldRequestAfterSameNumericCarAndAccountAba() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = Source(); val release = CompletableDeferred<Unit>()
        var reads = 0
        source.body = { _, _ ->
            val old = ++reads == 1
            if (old) withContext(NonCancellable) { release.await() }
            GeoStatsSnapshot(countries = listOf(country(if (old) "AA" else "BB")))
        }
        val vm = CountriesVisitedViewModel(app, source)
        try {
            vm.loadCountries(1, YearFilter.AllTime); runCurrent()
            source.change(); source.change()
            vm.loadCountries(1, YearFilter.AllTime); runCurrent()
            release.complete(Unit); runCurrent()
            assertEquals(listOf("BB"), vm.uiState.value.countries.map { it.countryCode })
        } finally { release.complete(Unit); vm.viewModelScope.cancel(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun scopeChangeClearsAlreadyPublishedCountriesAndDelayedUnitsCannotRestoreOldLabels() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = Source(); val release = CompletableDeferred<Unit>()
        val freshScopeRead = CompletableDeferred<Unit>()
        source.body = { _, _ ->
            if (source.epoch > 0) freshScopeRead.await()
            GeoStatsSnapshot(countries = listOf(country("AA")))
        }
        source.unitBody = {
            val old = source.epoch == 0
            if (old) withContext(NonCancellable) { release.await() }
            if (old) Units("mi", "psi", "F") else null
        }
        val vm = CountriesVisitedViewModel(app, source)
        try {
            vm.loadCountries(1, YearFilter.AllTime); runCurrent()
            assertEquals(1, vm.uiState.value.countries.size)
            source.change(); runCurrent()
            release.complete(Unit); runCurrent()
            assertTrue(vm.uiState.value.countries.isEmpty())
            assertNull(vm.uiState.value.units)
        } finally { freshScopeRead.cancel(); release.complete(Unit); vm.viewModelScope.cancel(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun countryBoundaryFromOldCountryCannotReplaceNewPage() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = Source(); val release = CompletableDeferred<Unit>()
        source.body = { _, code -> GeoStatsSnapshot(countries = listOf(country(code!!))) }
        source.boundaryBody = { code ->
            if (code == "AA") withContext(NonCancellable) { release.await() }
            CountryBoundary(code, emptyList())
        }
        val vm = RegionsVisitedViewModel(app, source)
        try {
            vm.loadRegions(1, "AA", YearFilter.AllTime); runCurrent()
            vm.loadRegions(1, "BB", YearFilter.AllTime); runCurrent()
            release.complete(Unit); runCurrent()
            assertEquals("BB", vm.uiState.value.countryRecord?.countryCode)
            assertEquals("BB", vm.uiState.value.countryBoundary?.countryCode)
        } finally { release.complete(Unit); vm.viewModelScope.cancel(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun unknownChargeModeIsExcludedFromAcMapFilterAndInvalidPointsAreRejected() {
        val locations = listOf(
            ChargeLocation(1, 30.0, 120.0, 0.0, "2026-01-01T00:00:00Z", false, "unknown"),
            ChargeLocation(2, 30.0, 120.0, 0.0, "2026-01-01T00:00:00Z", false, "ac", "ac"),
            ChargeLocation(3, 91.0, 120.0, 0.0, "2026-01-01T00:00:00Z", false, "invalid", "ac"),
            ChargeLocation(4, Double.NaN, 120.0, 0.0, "2026-01-01T00:00:00Z", false, "NaN", "ac"),
            ChargeLocation(5, 30.0, Double.POSITIVE_INFINITY, 0.0, "2026-01-01T00:00:00Z", false, "infinite", "ac"),
            ChargeLocation(6, 0.0, 0.0, 0.0, "2026-01-01T00:00:00Z", false, "sentinel", "ac"),
            ChargeLocation(7, 0.0, 120.0, 0.0, "2026-01-01T00:00:00Z", false, "valid equator", "ac"))
        val state = RegionsVisitedUiState(chargeLocations = locations, chargeTypeFilter = ChargeTypeFilter.AC_ONLY)
        assertEquals(listOf(2, 7), state.filteredChargeLocations.map { it.chargeId })
    }

    @Test fun mapYearUsesSameLocalCalendarAsQualifiedRepository() {
        val previous = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        try {
            val state = RegionsVisitedUiState(selectedMapYear = 2026,
                chargeLocations = listOf(ChargeLocation(1, 30.0, 120.0, 1.0, "2025-12-31T20:00:00Z", false, "ac", "ac")),
                driveLocations = listOf(DriveLocation(2, 30.0, 120.0, 1.0, "2025-12-31T20:00:00Z", "drive")))
            assertEquals(1, state.filteredChargeLocations.size)
            assertEquals(1, state.filteredDriveLocations.size)
        } finally { TimeZone.setDefault(previous) }
    }

    @Test fun firstVisitSortUsesChronologyAcrossOffsets() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = Source()
        source.body = { _, _ -> GeoStatsSnapshot(countries = listOf(
            country("AA", "2026-01-01T00:30:00+14:00"), country("BB", "2025-12-31T23:00:00-12:00"))) }
        val vm = CountriesVisitedViewModel(app, source)
        try {
            vm.loadCountries(1, YearFilter.AllTime); runCurrent()
            assertEquals(listOf("AA", "BB"), vm.uiState.value.countries.map { it.countryCode })
        } finally { vm.viewModelScope.cancel(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun canonicalGeoDistanceConvertsOnceAndUnknownMetricRemainsUnknown() {
        assertEquals("100 mi", geoDistanceText(160.9344, Units("mi")))
        assertEquals("0 km", geoDistanceText(0.0, Units("km")))
        assertEquals("--", geoDistanceText(null, Units("mi")))
        assertEquals("--", geoEnergyText(null))
        assertEquals("0 kWh", geoEnergyText(0.0))
    }

    @Test fun timelineUsesOnlyValidKnownMinutesWhenDurationStringIsMissing() {
        assertEquals("0:15", timelineDurationText(null, 15))
        assertEquals("0:00", timelineDurationText(null, 0))
        assertEquals("1:00", timelineDurationText("1:00", 15))
        assertNull(timelineDurationText(null, null)); assertNull(timelineDurationText(null, -1))
    }
    @Test fun statsCurrencySymbolIsPublishedWithTheSameComputedPriceSnapshot() {
        val quick = QuickStats(0, 0.0, null, null, null, null, null,
            0, 0.0, 12.0, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null)
        val next = CarStats(7, YearFilter.AllTime, quick, null, null, currencyCode = "USD")
        val published = StatsUiState(currencySymbol = "¥").publishStats(StatsLoadResult(next, emptyList(), 1f))
        assertEquals(12.0, published.carStats!!.quickStats.totalCost!!, 0.0)
        assertEquals(com.matelink.data.model.Currency.USD.symbol, published.currencySymbol)
    }
}
