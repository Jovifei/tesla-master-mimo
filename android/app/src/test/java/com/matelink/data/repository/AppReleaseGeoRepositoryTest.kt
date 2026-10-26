package com.matelink.data.repository

import android.app.Application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import com.matelink.ui.screens.stats.*
import com.matelink.data.api.models.*
import com.matelink.data.local.*
import com.matelink.data.local.dao.*
import com.matelink.data.local.entity.*
import com.matelink.data.sync.HistoryResourceFixture
import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.model.YearFilter
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.util.TimeZone

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class AppReleaseGeoRepositoryTest {
    @get:Rule val temp = TemporaryFolder()
    private fun repository(f: HistoryResourceFixture, driveDao: DriveSummaryDao = f.db.driveSummaryDao(),
        aggregateDao: AggregateDao = f.db.aggregateDao()) = StatsRepository(driveDao, f.db.chargeSummaryDao(),
        aggregateDao, f.syncManager, f.geocodingRepository, f.vehicleContexts,
        ChargeCostOverrideStore(f.db.chargeCostOverrideDao(), f.settings), f.db, f.settings)
    private fun drive(car: Int, id: Int, distance: Double?, at: String = "2026-06-01T12:00:00Z", quality: String = "observed") =
        DriveData(id, startDate = at, endDate = Instant.parse(at).plusSeconds(60).toString(),
            odometerDetails = DriveOdometerDetails(distance = distance), source = "teslamate_archive",
            qualityState = quality, qualityReason = "api", lengthUnit = "km").toSyncSummary(car)!!
    private fun charge(car: Int, id: Int, energy: Double?, at: String = "2026-06-01T12:00:00Z", quality: String = "observed",
        latitude: Double = 30.0) = ChargeData(id, startDate = at, endDate = Instant.parse(at).plusSeconds(60).toString(),
            chargeEnergyAdded = energy, source = "teslamate_archive", qualityState = quality, qualityReason = "api",
            chargeType = "ac", latitude = latitude, longitude = 120.0).toSyncSummary(car)!!
    private fun driveAggregate(car: Int, id: Int, lat: Double = 30.0) = DriveDetailAggregate(
        id, car, 1, 1L, null, null, null, null, null, null, false, null, null, null, null,
        null, null, 0, 1, startLatitude = lat, startLongitude = 120.0,
        startCountryCode = "CN", startCountryName = "China", startRegionName = "Region")
    private fun chargeAggregate(car: Int, id: Int, phases: Int? = 1, fast: Boolean = false) = ChargeDetailAggregate(
        id, car, 1, 1L, fast, null, null, null, null, null, phases, null, null, 1,
        countryCode = "CN", countryName = "China", regionName = "Region")
    private suspend fun fixture(name: String): HistoryResourceFixture = HistoryResourceFixture(File(temp.root, name)).also {
        it.initialize(cloud = true); SettingsRepository(it.context).setMockMode(false)
    }

    @Test fun unknownPrimitiveDistanceAndEnergyStayUnknownButExplicitZeroCountsAsSample() = runBlocking(Dispatchers.IO) {
        val f = fixture("geo-unknown.db")
        try {
            val car = f.localId(1)
            f.db.driveSummaryDao().upsert(drive(car, 1, null))
            f.db.aggregateDao().upsertDriveAggregate(driveAggregate(car, 1))
            f.db.chargeSummaryDao().upsert(charge(car, 1, null))
            f.db.aggregateDao().upsertChargeAggregate(chargeAggregate(car, 1))
            val first = repository(f).getCountriesVisited(1, YearFilter.AllTime).single()
            assertNull(first.qualifiedDistanceKm); assertNull(first.qualifiedChargeEnergyKwh)
            assertEquals(0, first.distanceSampleCount); assertEquals(0, first.chargeEnergySampleCount)
            f.db.driveSummaryDao().upsert(drive(car, 2, 0.0)); f.db.aggregateDao().upsertDriveAggregate(driveAggregate(car, 2))
            f.db.chargeSummaryDao().upsert(charge(car, 2, 0.0)); f.db.aggregateDao().upsertChargeAggregate(chargeAggregate(car, 2))
            val zero = repository(f).getCountriesVisited(1, YearFilter.AllTime).single()
            assertEquals(0.0, zero.qualifiedDistanceKm!!, 0.0); assertEquals(0.0, zero.qualifiedChargeEnergyKwh!!, 0.0)
            assertEquals(1, zero.distanceSampleCount); assertEquals(1, zero.chargeEnergySampleCount)
        } finally { f.close() }
    }

    @Test fun localYearUsesInstantsInsteadOfRawSqlYearStrings() = runBlocking(Dispatchers.IO) {
        val previous = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        val f = fixture("geo-local-year.db")
        try {
            val car = f.localId(1)
            f.db.driveSummaryDao().upsert(drive(car, 1, 10.0, "2025-12-31T20:00:00Z"))
            f.db.aggregateDao().upsertDriveAggregate(driveAggregate(car, 1))
            val country = repository(f).getCountriesVisited(1, YearFilter.Year(2026)).single()
            assertEquals(10.0, country.qualifiedDistanceKm!!, 0.0)
            assertEquals(1, repository(f).getRegionsVisited(1, "CN", YearFilter.Year(2026)).single().driveCount)
        } finally { f.close(); TimeZone.setDefault(previous) }
    }

    @Test fun countryReadCannotReturnThePreviousVehicleAfterAccountAbaOrUidRebinding() = runBlocking(Dispatchers.IO) {
        for (rebind in listOf(false, true)) {
            val f = fixture("geo-scope-$rebind.db")
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            try {
                val car = f.localId(1)
                f.db.driveSummaryDao().upsert(drive(car, 1, 10.0)); f.db.aggregateDao().upsertDriveAggregate(driveAggregate(car, 1))
                var gated = false
                suspend fun gate() { if (!gated) { gated = true; entered.complete(Unit); release.await() } }
                val realD = f.db.driveSummaryDao(); val realA = f.db.aggregateDao()
                val drives = object : DriveSummaryDao by realD {
                    override suspend fun getAllChronological(carId: Int): List<DriveSummary> = realD.getAllChronological(carId).also { gate() }
                }
                val aggregates = object : AggregateDao by realA {
                    override suspend fun getCountriesVisited(carId: Int): List<CountryVisitResult> = realA.getCountriesVisited(carId).also { gate() }
                }
                val pending = async { runCatching { repository(f, drives, aggregates).getCountriesVisited(1, YearFilter.AllTime) } }
                withTimeout(5000) { entered.await() }
                if (rebind) f.vehicleContexts.resolveVerifiedHistoryCar(CarData(1, vehicleUid = "replacement"), f.vehicleContexts.captureReadScope())
                else {
                    f.sessions.save("b", "br", 3600, "bob")
                    f.sessions.save("a", "ar", 3600, "synthetic-account")
                }
                release.complete(Unit)
                assertTrue(pending.await().exceptionOrNull() is HistoryIdentityUnavailableException)
            } finally { release.complete(Unit); f.close() }
        }
    }

    @Test fun mapConsumersRejectQuarantineAndInvalidCoordinatesFromOtherwiseVisibleCountry() = runBlocking(Dispatchers.IO) {
        val f = fixture("geo-map.db")
        try {
            val car = f.localId(1)
            f.db.driveSummaryDao().upsertAll(listOf(drive(car, 1, 10.0), drive(car, 2, 99.0, quality = "quarantined"), drive(car, 3, 10.0)))
            f.db.aggregateDao().upsertDriveAggregates(listOf(driveAggregate(car, 1), driveAggregate(car, 2), driveAggregate(car, 3, 91.0)))
            f.db.chargeSummaryDao().upsertAll(listOf(charge(car, 1, 5.0), charge(car, 2, 99.0, quality = "quarantined"), charge(car, 3, 5.0, latitude = 91.0)))
            f.db.aggregateDao().upsertChargeAggregates((1..3).map { chargeAggregate(car, it) })
            val repo = repository(f)
            assertEquals(listOf(1), repo.getDriveLocationsForCountry(1, "CN", YearFilter.AllTime).map { it.driveId })
            assertEquals(listOf(1), repo.getChargeLocationsForCountry(1, "CN", YearFilter.AllTime).map { it.chargeId })
            assertEquals(2, repo.getCountriesVisited(1, YearFilter.AllTime).single().driveCount)
        } finally { f.close() }
    }

    @Test fun knownAcModeWithNoEnergyHasNoNumericSubtotalButRealZeroIsRetained() = runBlocking(Dispatchers.IO) {
        val f = fixture("geo-deep-energy.db")
        try {
            val car = f.localId(1)
            f.db.chargeSummaryDao().upsert(charge(car, 1, null)); f.db.aggregateDao().upsertChargeAggregate(chargeAggregate(car, 1))
            val before = repository(f).getStats(1, YearFilter.AllTime).deepStats!!
            assertEquals(1, before.acChargeCount); assertNull(before.qualifiedAcChargeEnergyKwh); assertEquals(0, before.acEnergySampleCount)
            f.db.chargeSummaryDao().upsert(charge(car, 2, 0.0)); f.db.aggregateDao().upsertChargeAggregate(chargeAggregate(car, 2))
            val after = repository(f).getStats(1, YearFilter.AllTime).deepStats!!
            assertEquals(0.0, after.qualifiedAcChargeEnergyKwh!!, 0.0); assertEquals(1, after.acEnergySampleCount)
            assertNull(after.qualifiedDcChargeEnergyKwh)
        } finally { f.close() }
    }

    @Test fun acBadgeIdsRequireExplicitOneToThreePhasesAndRejectFastOrQuarantinedRows() = runBlocking(Dispatchers.IO) {
        val f = fixture("geo-ac-ids.db")
        try {
            val car = f.localId(1)
            f.db.chargeSummaryDao().upsertAll((1..7).map { charge(car, it, 1.0, quality = if (it == 7) "quarantined" else "observed") })
            f.db.aggregateDao().upsertChargeAggregates(listOf(chargeAggregate(car, 1, null), chargeAggregate(car, 2, 0),
                chargeAggregate(car, 3, 1), chargeAggregate(car, 4, 3), chargeAggregate(car, 5, 4),
                chargeAggregate(car, 6, 1, true), chargeAggregate(car, 7, 1)))
            assertEquals(listOf(3, 4), f.db.aggregateDao().getAcChargeIds(car).sorted())
        } finally { f.close() }
    }
    @Test fun normalCredentialRotationReloadsBothPagesWithFreshRoomAndSameLoginIdentity() = runBlocking(Dispatchers.IO) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = fixture("geo-credential-refresh.db")
        var countries: CountriesVisitedViewModel? = null
        var regions: RegionsVisitedViewModel? = null
        try {
            val car = f.localId(1)
            f.db.driveSummaryDao().upsert(drive(car, 1, 10.0))
            f.db.aggregateDao().upsertDriveAggregate(driveAggregate(car, 1))
            val repo = repository(f)
            // Actual Room, identity repository and SessionStore; only optional status/boundary I/O is fake.
            val source = object : GeoVisitedPageSource {
                override val scopeChanges get() = repo.geoScopeChanges
                override suspend fun identity(carId: Int) = repo.captureGeoIdentity(carId)
                override suspend fun load(carId: Int, countryCode: String?, yearFilter: YearFilter, identity: GeoStatsIdentity?) =
                    repo.getGeoSnapshot(carId, yearFilter, countryCode, identity)
                override suspend fun units(carId: Int) = Units("km", "bar", "C")
                override suspend fun boundary(countryCode: String): CountryBoundary? = null
            }
            val countryVm = CountriesVisitedViewModel(f.context, source); countries = countryVm
            val regionVm = RegionsVisitedViewModel(f.context, source); regions = regionVm
            countryVm.loadCountries(1, YearFilter.AllTime)
            regionVm.loadRegions(1, "CN", YearFilter.AllTime)
            withTimeout(5000) { combine(countryVm.uiState, regionVm.uiState) { c, r ->
                !c.isLoading && c.countries.singleOrNull()?.driveCount == 1 && !r.isLoading && r.countryRecord?.driveCount == 1
            }.first { it } }
            // New local history is picked up by the reload triggered by a normal token rotation.
            f.db.driveSummaryDao().upsert(drive(car, 2, 20.0))
            f.db.aggregateDao().upsertDriveAggregate(driveAggregate(car, 2))
            val login = f.sessions.loginIdentitySnapshot()
            val credentials = f.sessions.identitySnapshot()
            assertTrue(f.sessions.rotateIfCurrent(credentials.first!!, credentials.second, "rotated-a", "rotated-r", 3600))
            assertEquals(login.second, f.sessions.loginIdentitySnapshot().second)
            val refreshed = withTimeoutOrNull(3000) { combine(countryVm.uiState, regionVm.uiState) { c, r ->
                !c.isLoading && c.countries.singleOrNull()?.driveCount == 2 && !r.isLoading && r.countryRecord?.driveCount == 2
            }.first { it } }
            assertNotNull("A normal same-login credential rotation must reload both pages without re-entry", refreshed)
            assertEquals(30.0, countryVm.uiState.value.countries.single().qualifiedDistanceKm!!, 0.0)
            assertEquals(30.0, regionVm.uiState.value.countryRecord!!.qualifiedDistanceKm!!, 0.0)
            assertTrue("No provider HTTP or external geocoding belongs in this recovery test", f.calls.isEmpty())
        } finally {
            countries?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            regions?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            f.close(); Dispatchers.resetMain()
        }
    }
}
