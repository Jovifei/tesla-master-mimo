package com.matelink.data.repository

import android.app.Application
import com.matelink.data.api.models.*
import com.matelink.data.local.*
import com.matelink.data.sync.HistoryResourceFixture
import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.analytics.*
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class AppReleaseHistoryUnitsTest {
    @get:Rule val temp = TemporaryFolder()
    private val start = "2026-10-14T01:00:00Z"
    private val end = "2026-10-14T02:00:00Z"
    private fun charge(unit: String = "C", value: Double? = 0.0) = ChargeData(7,
        startDate = start, endDate = end, source = "teslamate_archive", qualityState = "observed",
        outsideTempAvg = value, temperatureUnit = unit, rangeRated = ChargeRange(100.0, 150.0), odometer = 200.0)
    private fun drive(unit: String = "C", outside: Double? = 0.0, inside: Double? = -5.0) = DriveData(7,
        startDate = start, endDate = end, source = "teslamate_archive", qualityState = "observed",
        outsideTempAvg = outside, insideTempAvg = inside, temperatureUnit = unit,
        odometerDetails = DriveOdometerDetails(distance = 10.0), lengthUnit = "km")

    @Test fun chargeResponseCapturesLengthAndCanonicalGettersDoNotBorrowLaterPreferences() {
        val detail = ChargeDetail(7, startDate = start, endDate = end, source = "teslamate_archive",
            rangeRated = ChargeRange(100.0, 150.0), odometer = 200.0)
        val result = chargeDetailResultForResponse(Response.success(ChargeDetailResponse(
            ChargeDetailData(ChargeDetailCar(1), detail, Units("mi", "psi", "F")))), 1, 7) as ApiResult.Success
        assertEquals("mi", result.data.lengthUnit)
        assertEquals("F", result.data.temperatureUnit)
        assertEquals(160.9344, result.data.startRatedRangeKm!!, 1e-9)
        assertEquals(321.8688, result.data.odometerKm!!, 1e-9)
    }

    @Test fun chargeSummaryRoomReconstructionKeepsRawMilesAndCanonicalRange() = runBlocking(Dispatchers.IO) {
        val f = HistoryResourceFixture(File(temp.root, "charge-units.db"))
        try {
            f.initialize(cloud = true); SettingsRepository(f.context).setMockMode(false)
            val raw = charge("F", -4.0).copy(lengthUnit = "mi")
            val row = raw.toSyncSummary(f.localId(1))!!
            f.db.chargeSummaryDao().upsert(row)
            val before = row.apiEvidence
            f.reopen()
            val restored = f.db.chargeSummaryDao().get(row.carId, 7)!!.toAnalysisChargeData()
            assertEquals("mi", restored.lengthUnit); assertEquals("F", restored.temperatureUnit)
            assertEquals(100.0, restored.rangeRated!!.startRange!!, 0.0)
            assertEquals(160.9344, restored.startRatedRangeKm!!, 1e-9)
            assertEquals(before, f.db.chargeSummaryDao().get(row.carId, 7)!!.apiEvidence)
        } finally { f.close() }
    }

    @Test fun sparseChargeAndDriveTemperatureCannotCrossCelsiusFahrenheitOrUnknownUnits() {
        val oldCharge = charge("C", -5.0)
        val nextCharge = charge("F", null)
        val mergedCharge = UnifiedHistoryRepository.mergeChargesRaw(listOf(nextCharge), listOf(oldCharge)).single()
        assertEquals("F", mergedCharge.temperatureUnit); assertNull(mergedCharge.outsideTempAvg)
        val oldDrive = drive("C")
        val mergedDrive = UnifiedHistoryRepository.mergeDrivesRaw(listOf(drive("F", null, null)), listOf(oldDrive)).single()
        assertEquals("F", mergedDrive.temperatureUnit); assertNull(mergedDrive.outsideTempAvg); assertNull(mergedDrive.insideTempAvg)
        assertNull(UnifiedHistoryRepository.mergeChargesRaw(listOf(charge("", null)), listOf(oldCharge)).single().outsideTempAvg)
    }

    @Test fun sameUnitSparseTemperaturesPreserveRealZeroAndNegativeValues() {
        val mergedCharge = UnifiedHistoryRepository.mergeChargesRaw(listOf(charge("C", null)), listOf(charge("C", 0.0))).single()
        assertEquals(0.0, mergedCharge.outsideTempAvg!!, 0.0)
        val mergedDrive = UnifiedHistoryRepository.mergeDrivesRaw(listOf(drive("C", null, null)), listOf(drive("C", 0.0, -5.0))).single()
        assertEquals(0.0, mergedDrive.outsideTempAvg!!, 0.0); assertEquals(-5.0, mergedDrive.insideTempAvg!!, 0.0)
    }

    @Test fun chargeSparseLengthCannotMoveCachedKilometersUnderNewMileMetadata() {
        val old = charge().copy(lengthUnit = "km")
        val incoming = charge().copy(lengthUnit = "mi", rangeRated = null, rangeIdeal = null, odometer = null)
        val merged = UnifiedHistoryRepository.mergeChargesRaw(listOf(incoming), listOf(old)).single()
        assertEquals("mi", merged.lengthUnit); assertNull(merged.rangeRated); assertNull(merged.odometer)
        val unknown = UnifiedHistoryRepository.mergeChargesRaw(listOf(incoming.copy(lengthUnit = "")), listOf(old)).single()
        assertNull(unknown.startRatedRangeKm)
    }

    @Test fun chargeDetailHeaderAndDriveHeaderKeepTheirReceivedTemperatureMetadata() = runBlocking(Dispatchers.IO) {
        val f = HistoryResourceFixture(File(temp.root, "detail-unit-header.db"))
        try {
            f.initialize(cloud = true); SettingsRepository(f.context).setMockMode(false)
            val scope = f.vehicleContexts.captureReadScope()
            val vehicle = f.vehicleContexts.cachedContextForRemote(1, scope)!!
            val details = HistoryDetailRepository(f.db.historyDetailDao())
            val charge = ChargeDetail(7, startDate = start, endDate = end, source = "teslamate_archive",
                temperatureUnit = "F", lengthUnit = "mi", outsideTempAvg = -4.0,
                chargePoints = listOf(ChargePoint(outsideTemp = 0.0)))
            val ci = HistoryDetailIdentity.create(vehicle, HistoryDetailIdentity.CHARGE, 7,
                charge.source, null, start, end)
            assertTrue(details.saveCharge(ci, charge) is HistoryDetailWriteResult.Stored)
            val restored = details.readCharge(ci).detail!!
            assertEquals("mi", restored.lengthUnit); assertEquals("F", restored.temperatureUnit)
            assertEquals(-4.0, restored.outsideTempAvg!!, 0.0); assertEquals(0.0, restored.chargePoints!!.single().outsideTemp!!, 0.0)
            val drive = drive("F", 0.0, -4.0).asCachedDetail().copy(positions = listOf(DrivePosition(climateInfo = DriveClimateInfo(outsideTemp = 0.0))))
            val di = HistoryDetailIdentity.create(vehicle, HistoryDetailIdentity.DRIVE, 7, drive.source, null, start, end)
            assertTrue(details.saveDrive(di, drive) is HistoryDetailWriteResult.Stored)
            assertEquals("F", details.readDrive(di).detail!!.temperatureUnit)
        } finally { f.close() }
    }

    @Test fun persistedSparseRefreshCannotReLabelOldTemperatureWithTheNewUnit() = runBlocking(Dispatchers.IO) {
        val f = HistoryResourceFixture(File(temp.root, "sparse-unit-room.db"))
        try {
            f.initialize(cloud = true); SettingsRepository(f.context).setMockMode(false)
            val car = f.localId(1)
            f.db.driveSummaryDao().upsert(drive("C", -5.0, 0.0).toSyncSummary(car)!!)
            f.db.driveSummaryDao().upsertPreservingEvidence(listOf(drive("F", null, null).toSyncSummary(car)!!))
            f.db.chargeSummaryDao().upsert(charge("C", -5.0).toSyncSummary(car)!!)
            f.db.chargeSummaryDao().upsertPreservingEvidence(listOf(charge("F", null).toSyncSummary(car)!!))
            f.reopen()
            val d = f.db.driveSummaryDao().get(car, 7)!!.toAnalysisDriveData()
            val c = f.db.chargeSummaryDao().get(car, 7)!!.toAnalysisChargeData()
            assertTrue("New F metadata may retain only a new F number, never old Celsius", d.temperatureUnit != "F" || d.outsideTempAvg == null)
            assertTrue(c.temperatureUnit != "F" || c.outsideTempAvg == null)
        } finally { f.close() }
    }

    @Test fun detailPresentationCarriesItsUnitsAndNeverRewritesOriginalSourceReceipt() {
        val raw = charge("C", 20.0).copy(lengthUnit = "km")
        val original = raw.toSyncSummary(-7)!!
        val detail = ChargeDetail(7, startDate = start, endDate = end, source = "teslamate_archive",
            temperatureUnit = "F", lengthUnit = "mi", outsideTempAvg = 0.0,
            rangeRated = ChargeRange(100.0, 150.0), odometer = 200.0)
        val stored = original.withDetailEvidence(detail)
        val projected = stored.toAnalysisChargeData()
        assertEquals("F", projected.temperatureUnit); assertEquals("mi", projected.lengthUnit)
        assertEquals(0.0, projected.outsideTempAvg!!, 0.0)
        assertEquals(160.9344, projected.startRatedRangeKm!!, 1e-9)
        assertEquals(HistorySummaryEvidenceCodec.sourceJson(original.apiEvidence),
            HistorySummaryEvidenceCodec.sourceJson(stored.apiEvidence))
        val sparse = original.withDetailEvidence(detail.copy(outsideTempAvg = null, rangeRated = null, odometer = null))
            .toAnalysisChargeData()
        assertNull(sparse.outsideTempAvg); assertNull(sparse.rangeRated); assertNull(sparse.odometerKm)
    }

    @Test fun allChargeResponseBoundariesKeepUnitsAndUnknownLengthsRemainRawOnly() {
        val raw = charge("C", 0.0)
        val received = ChargesData(listOf(raw), units = Units("mi", "psi", "F")).receivedCharges().single()
        assertEquals("mi", received.lengthUnit); assertEquals("F", received.temperatureUnit)
        assertEquals(160.9344, received.startRatedRangeKm!!, 1e-9)
        val detail = ChargeDetail(7, source = "telemetry_mqtt", rangeRated = ChargeRange(100.0, 150.0))
        val active = currentChargeResultForResponse(Response.success(ChargeDetailResponse(
            ChargeDetailData(ChargeDetailCar(1), detail, Units("mi", "psi", "F")))), 1) as ApiResult.Success
        val current = (active.data as CurrentChargeOutcome.Active).detail
        assertEquals("mi", current.lengthUnit); assertEquals(160.9344, current.startRatedRangeKm!!, 1e-9)
        for (unit in listOf(null, "", "yards")) {
            val unknown = raw.copy(lengthUnit = unit)
            assertEquals(100.0, unknown.rangeRated!!.startRange!!, 0.0)
            assertNull(unknown.startRatedRangeKm); assertNull(unknown.odometerKm)
        }
        val zero = raw.copy(lengthUnit = "mi", rangeRated = ChargeRange(0.0, 0.0), odometer = 0.0)
        assertEquals(0.0, zero.startRatedRangeKm!!, 0.0); assertEquals(0.0, zero.odometerKm!!, 0.0)
    }

    @Test fun retainedAverageSpeedKeepsItsOwnMethodAndCountButNewNumberDoesNotBorrowThem() {
        val old = drive().copy(speedAvg = 20.0, speedMetricsSource = "position_samples", speedSampleCount = 4)
        val sparse = UnifiedHistoryRepository.mergeDrivesRaw(listOf(drive().copy(speedAvg = null)), listOf(old)).single()
        assertEquals(20.0, sparse.speedAvg!!, 0.0)
        assertEquals("position_samples", sparse.speedMetricsSource); assertEquals(4, sparse.speedSampleCount)
        val fresh = UnifiedHistoryRepository.mergeDrivesRaw(listOf(drive().copy(speedAvg = 0.0)), listOf(old)).single()
        assertEquals(0.0, fresh.speedAvg!!, 0.0); assertNull(fresh.speedMetricsSource); assertNull(fresh.speedSampleCount)
        val serialized = Moshi.Builder().build().adapter(DriveData::class.java).fromJson(
            Moshi.Builder().build().adapter(DriveData::class.java).toJson(old))!!
        assertEquals(4, serialized.speedSampleCount)
    }
}
