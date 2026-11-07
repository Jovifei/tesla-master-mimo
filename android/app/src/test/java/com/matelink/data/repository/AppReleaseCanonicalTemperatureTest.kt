package com.matelink.data.repository

import android.app.Application
import com.matelink.data.api.models.*
import com.matelink.data.local.*
import com.matelink.data.local.entity.*
import com.matelink.data.sync.*
import com.matelink.domain.analytics.toAnalysisDriveData
import com.matelink.domain.model.YearFilter
import com.matelink.ui.screens.stats.formatStatsTemperatureC
import com.matelink.ui.screens.charges.ChargeStatsCalculator
import com.matelink.ui.screens.charges.ChargeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class AppReleaseCanonicalTemperatureTest {
    @get:Rule val temp = TemporaryFolder()
    private val start = "2026-10-14T00:00:00Z"
    private val end = "2026-10-14T00:01:00Z"
    private fun drive(unit: String?) = DriveDetail(1, startDate = start, endDate = end,
        source = "teslamate_archive", temperatureUnit = unit, lengthUnit = "km",
        positions = listOf(DrivePosition(date = start, latitude = 30.0, longitude = 120.0,
            elevation = 100, power = 10.0, climateInfo = DriveClimateInfo(insideTemp = 32.0, outsideTemp = -4.0)),
            DrivePosition(date = end, latitude = 30.0, longitude = 120.0,
                elevation = 120, power = 20.0, climateInfo = DriveClimateInfo(insideTemp = 68.0, outsideTemp = 68.0))))
    private fun charge(unit: String?) = ChargeDetail(1, startDate = start, endDate = end,
        source = "teslamate_archive", temperatureUnit = unit, lengthUnit = "km", latitude = 30.0, longitude = 120.0,
        chargePoints = listOf(ChargePoint(date = start, outsideTemp = -4.0,
            chargerDetails = ChargerDetails(chargerPower = 11.0, chargerPhases = 1)),
            ChargePoint(date = end, outsideTemp = 68.0, chargerDetails = ChargerDetails(chargerPower = 11.0, chargerPhases = 1))))
    private suspend fun fixture(name: String) = HistoryResourceFixture(File(temp.root, name)).also {
        it.initialize(cloud = true); SettingsRepository(it.context).setMockMode(false)
    }
    private fun stats(f: HistoryResourceFixture) = StatsRepository(f.db.driveSummaryDao(), f.db.chargeSummaryDao(),
        f.db.aggregateDao(), f.syncManager, f.geocodingRepository, f.vehicleContexts,
        ChargeCostOverrideStore(f.db.chargeCostOverrideDao(), f.settings), f.db, f.settings)

    @Test fun actualPointAggregatesNormalizeSameResponseFahrenheitToCanonicalCWithoutChangingRawPoints() {
        val input = drive("F"); val aggregated = input.toAggregate(-7, 1)
        assertEquals(20.0, aggregated.maxInsideTemp!!, 1e-9); assertEquals(0.0, aggregated.minInsideTemp!!, 1e-9)
        assertEquals(20.0, aggregated.maxOutsideTemp!!, 1e-9); assertEquals(-20.0, aggregated.minOutsideTemp!!, 1e-9)
        assertEquals(68.0, input.positions!!.last().outsideTemp!!, 0.0)
        val charged = charge("F").toAggregate(-7, 1)
        assertEquals(20.0, charged.maxOutsideTemp!!, 1e-9); assertEquals(-20.0, charged.minOutsideTemp!!, 1e-9)
    }

    @Test fun unknownPointUnitsCannotBecomeCanonicalTemperaturesWhileNonTemperatureDataStaysAvailable() {
        for (unit in listOf(null, "", "K")) {
            val d = drive(unit).toAggregate(-7, 1)
            assertNull(d.maxInsideTemp); assertNull(d.minInsideTemp); assertNull(d.maxOutsideTemp); assertNull(d.minOutsideTemp)
            assertEquals(120, d.maxElevation); assertEquals(20, d.maxPower); assertEquals(2, d.positionCount)
            val c = charge(unit).toAggregate(-7, 1)
            assertNull(c.maxOutsideTemp); assertNull(c.minOutsideTemp)
            assertEquals(11, c.maxChargerPower); assertEquals(2, c.chargePointCount)
        }
    }

    @Test fun canonicalCPointZeroAndNegativeControlRemainExact() {
        val input = drive("C").copy(positions = listOf(DrivePosition(
            climateInfo = DriveClimateInfo(insideTemp = 0.0, outsideTemp = -5.0))))
        val aggregated = input.toAggregate(-7, 1)
        assertEquals(0.0, aggregated.maxInsideTemp!!, 0.0); assertEquals(-5.0, aggregated.minOutsideTemp!!, 0.0)
    }

    @Test fun actualStatsFormatterConvertsCelsiusToPreferredFahrenheitExactlyOnce() {
        assertEquals("68.0°F", formatStatsTemperatureC(20.0, Units(unitOfTemperature = "F"), 1))
        assertEquals("32.0°F", formatStatsTemperatureC(0.0, Units(unitOfTemperature = "F"), 1))
        assertEquals("-40.0°F", formatStatsTemperatureC(-40.0, Units(unitOfTemperature = "F"), 1))
        assertEquals("-5.0°C", formatStatsTemperatureC(-5.0, Units(unitOfTemperature = "C"), 1))
    }

    @Test fun legacyAggregateTemperaturesAreUnknownInStatsButOtherFieldsAndStoredRowsAreRetained() = runBlocking(Dispatchers.IO) {
        val f = fixture("canonical-legacy-reader.db")
        try {
            val car = f.localId(1)
            val summary = DriveData(1, startDate = start, endDate = end, source = "teslamate_archive",
                qualityState = "observed", lengthUnit = "km", temperatureUnit = "C",
                outsideTempAvg = 20.0, odometerDetails = DriveOdometerDetails(distance = 10.0))
            f.db.driveSummaryDao().upsert(summary.toSyncSummary(car)!!)
            val legacy = drive("F").toAggregate(car, 1).copy(schemaVersion = 6,
                maxOutsideTemp = 68.0, minOutsideTemp = -4.0, startCountryCode = "CN", startCountryName = "China")
            f.db.aggregateDao().upsertDriveAggregate(legacy)
            val deep = stats(f).getStats(1, YearFilter.AllTime).deepStats!!
            assertNull("A later C summary cannot certify old point units", deep.maxOutsideTempDrivingC)
            assertNull(deep.maxCabinTempC); assertEquals(120, deep.maxElevationM); assertEquals(1, deep.countriesVisitedCount)
            assertEquals(68.0, f.db.aggregateDao().getDriveAggregate(car, 1)!!.maxOutsideTemp!!, 0.0)
            f.db.aggregateDao().upsertDriveAggregate(legacy.copy(schemaVersion = SchemaVersion.TEMPERATURE_C,
                maxOutsideTemp = 0.0, minOutsideTemp = -5.0, maxInsideTemp = 0.0, minInsideTemp = -10.0))
            val current = stats(f).getStats(1, YearFilter.AllTime).deepStats!!
            assertEquals(0.0, current.maxOutsideTempDrivingC!!, 0.0); assertEquals(-5.0, current.minOutsideTempDrivingC!!, 0.0)
        } finally { f.close() }
    }

    @Test fun processingVersionSelectsOldUnitUnprovenRowsForBoundedReprocessing() = runBlocking(Dispatchers.IO) {
        val f = fixture("canonical-processing-version.db")
        try {
            val car = f.localId(1)
            f.db.driveSummaryDao().upsert(DriveData(1, startDate = start, endDate = end,
                source = "teslamate_archive", qualityState = "observed").toSyncSummary(car)!!)
            f.db.chargeSummaryDao().upsert(ChargeData(1, startDate = start, endDate = end,
                source = "teslamate_archive", qualityState = "observed").toSyncSummary(car)!!)
            f.db.aggregateDao().upsertDriveAggregate(drive("F").toAggregate(car, 1).copy(schemaVersion = 6))
            f.db.aggregateDao().upsertChargeAggregate(charge("F").toAggregate(car, 1).copy(schemaVersion = 5))
            assertEquals(listOf(1), f.db.driveSummaryDao().getUnprocessedDriveBatchIds(car, SchemaVersion.DRIVE_ENERGY, 20))
            assertEquals(listOf(1), f.db.chargeSummaryDao().getUnprocessedChargeBatchIds(car, SchemaVersion.CURRENT, 20))
            assertEquals(1, f.db.driveSummaryDao().count(car)); assertEquals(1, f.db.chargeSummaryDao().count(car))
        } finally { f.close() }
    }

    @Test fun conflictingPointModesCannotWriteAffirmativeDcOrAcPhaseHints() {
        val cases = listOf(
            listOf(ChargerDetails(chargerPower = 11.0, chargerPhases = 1), ChargerDetails(chargerPower = 80.0, chargerPhases = 0)),
            listOf(ChargerDetails(chargerPower = 11.0, chargerPhases = 3, fastChargerPresent = true)),
            listOf(ChargerDetails(fastChargerPresent = true), ChargerDetails(fastChargerPresent = false)))
        for (points in cases) {
            val detail = charge("C").copy(chargePoints = points.map { ChargePoint(chargerDetails = it) })
            assertEquals(ChargeType.UNKNOWN, ChargeStatsCalculator.detectChargeType(detail))
            val aggregate = detail.toAggregate(-7, 1)
            assertFalse(aggregate.isFastCharger); assertNull(aggregate.chargerPhases)
        }
        val ac = charge("C").toAggregate(-7, 1)
        assertFalse(ac.isFastCharger); assertEquals(1, ac.chargerPhases)
        val dc = charge("C").copy(chargePoints = listOf(ChargePoint(chargerDetails =
            ChargerDetails(chargerPower = 80.0, chargerPhases = 0)))).toAggregate(-7, 1)
        assertTrue(dc.isFastCharger)
    }

    @Test fun actualReprocessingRestoresGeographyFromCurrentCoordinateCacheNotOldWrongLocation() = runBlocking(Dispatchers.IO) {
        val f = fixture("canonical-local-geocode.db")
        try {
            val car = f.localId(1)
            f.db.driveSummaryDao().upsert(DriveData(1, startDate = start, endDate = end,
                source = "teslamate_archive", qualityState = "observed", lengthUnit = "km").toSyncSummary(car)!!)
            f.db.chargeSummaryDao().upsert(ChargeData(1, startDate = start, endDate = end,
                source = "teslamate_archive", qualityState = "observed", latitude = 10.0, longitude = 20.0).toSyncSummary(car)!!)
            f.db.aggregateDao().upsertDriveAggregate(drive("C").toAggregate(car, 1).copy(schemaVersion = 1,
                startLatitude = 10.0, startLongitude = 20.0, startCountryCode = "ZZ", startCountryName = "Wrong"))
            f.db.aggregateDao().upsertChargeAggregate(charge("C").toAggregate(car, 1).copy(schemaVersion = 1,
                countryCode = "ZZ", countryName = "Wrong"))
            f.db.geocodeCacheDao().upsert(GeocodeCache(f.geocodingRepository.toGridCoord(30.0),
                f.geocodingRepository.toGridCoord(120.0), "CN", "China", "Region", "City", 1))
            f.detailResponse = { path -> when {
                path.endsWith("/drives/1") -> """{"data":{"drive":{"drive_id":1,"start_date":"$start","end_date":"$end","source":"teslamate_archive","drive_details":[{"date":"$start","latitude":30.0,"longitude":120.0,"climate_info":{"outside_temp":68.0}}]},"units":{"unit_of_length":"km","unit_of_temperature":"F"}}}"""
                path.endsWith("/charges/1") -> """{"data":{"charge":{"charge_id":1,"start_date":"$start","end_date":"$end","source":"teslamate_archive","latitude":30.0,"longitude":120.0,"charge_details":[{"date":"$start","outside_temp":68.0,"charger_details":{"charger_phases":1,"charger_power":11.0}}]},"units":{"unit_of_length":"km","unit_of_temperature":"F"}}}"""
                else -> null
            } }
            assertEquals(CarSyncResult.Complete, f.repo.syncDetailsOnly(1, DriveDetailRequestBudget(2)))
            val d = f.db.aggregateDao().getDriveAggregate(car, 1)!!
            val c = f.db.aggregateDao().getChargeAggregate(car, 1)!!
            assertEquals("CN", d.startCountryCode); assertEquals("Region", d.startRegionName)
            assertEquals("CN", c.countryCode); assertEquals("Region", c.regionName)
            assertEquals(20.0, d.maxOutsideTemp!!, 1e-9); assertEquals(20.0, c.maxOutsideTemp!!, 1e-9)
        } finally { f.close() }
    }
}
