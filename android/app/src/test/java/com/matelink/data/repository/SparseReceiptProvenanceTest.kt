package com.matelink.data.repository

import android.app.Application
import androidx.room.Room
import com.matelink.data.api.models.*
import com.matelink.data.local.*
import com.matelink.data.local.dao.DriveSummaryDao
import com.matelink.data.local.entity.DriveSummary
import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.analytics.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Synthetic provider inputs; real production merge and isolated disk Room, never owner data. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class SparseReceiptProvenanceTest {
    @get:Rule val temp = TemporaryFolder()
    private val start = "2026-10-13T00:00:00Z"
    private val end = "2026-10-13T00:01:00Z"
    private val original = DriveData(4, startDate = start, endDate = end,
        source = "telemetry_mqtt", qualityState = "observed",
        startAddress = "Old provider address", energyConsumedNet = 8.0,
        batteryDetails = DriveBatteryDetails(80, 79),
        odometerDetails = DriveOdometerDetails(distance = 2.0))
    private val sparse = original.copy(startAddress = "New provider address",
        energyConsumedNet = null, batteryDetails = null)

    private fun savedDetail(): DriveSummary {
        val detail = original.asCachedDetail()
        return original.toSyncSummary(-7)!!.withResolvedDriveEnergy(detail,
            detail.resolveDriveEnergy(model = "Y", trimBadging = "Long Range AWD"))
    }

    private fun assertGenuineReceipt(row: DriveSummary, vararg inputs: DriveSummary) {
        val source = requireNotNull(HistorySummaryEvidenceCodec.sourceJson(row.apiEvidence))
        assertTrue("Raw source must equal one complete received receipt, never merged field JSON",
            inputs.any { source == HistorySummaryEvidenceCodec.sourceJson(it.apiEvidence) })
    }

    private fun open() = Room.databaseBuilder(RuntimeEnvironment.getApplication(), StatsDatabase::class.java,
        File(temp.root, "sparse-receipt.db").absolutePath).allowMainThreadQueries().build()

    @Test fun sparseMergeKeepsOneGenuineReceiptAndScopedCapacityDetail() {
        val cached = savedDetail()
        val incoming = sparse.toSyncSummary(-7)!!
        val merged = mergeStoredDrive(incoming, cached)
        assertGenuineReceipt(merged, cached, incoming)
        assertEquals(0.784, merged.energyConsumed!!, 1e-12)
        assertEquals(78.4, merged.toAnalysisDriveData().energyContract!!.netEnergy!!.capacityKwh!!, 1e-12)
        assertEquals(79, merged.toAnalysisDriveData().endBatteryLevel)
        assertEquals("New provider address", merged.startAddress)
    }

    @Test fun sparseMergeWithoutDetailSnapshotStillKeepsAnActualRawReceipt() {
        val cached = original.toSyncSummary(-7)!!
        val incoming = sparse.toSyncSummary(-7)!!
        val merged = mergeStoredDrive(incoming, cached)
        assertGenuineReceipt(merged, cached, incoming)
        assertEquals("New provider address", merged.startAddress)
        assertNull(merged.energyConsumed)
        assertNull(merged.toAnalysisDriveData().netEnergyKwh)
    }

    @Test fun repeatedSummaryInputsPreserveRicherDetailWhileNewAddressApplies() {
        val detail = original.asCachedDetail().copy(
            odometerDetails = DriveOdometerDetails(distance = 3.0))
        val cached = original.toSyncSummary(-7)!!.withResolvedDriveEnergy(detail,
            detail.resolveDriveEnergy(model = "Y", trimBadging = "Long Range AWD"))
        val incoming = sparse.toSyncSummary(-7)!!
        val merged = mergeStoredDrive(incoming, cached)
        assertGenuineReceipt(merged, cached, incoming)
        assertEquals(3.0, merged.distance, 0.0)
        assertEquals("New provider address", merged.startAddress)
        assertEquals(0.784, merged.energyConsumed!!, 1e-12)
    }

    @Test fun repeatedSummaryCoordinatesPreserveActualDetailCoordinates() {
        val raw = original.copy(startLatitude = 1.0, startLongitude = 2.0,
            endLatitude = 3.0, endLongitude = 4.0)
        val detail = raw.asCachedDetail().copy(startLatitude = 11.0, startLongitude = 12.0,
            endLatitude = 13.0, endLongitude = 14.0)
        val cached = raw.toSyncSummary(-7)!!.withResolvedDriveEnergy(detail,
            detail.resolveDriveEnergy(model = "Y", trimBadging = "Long Range AWD"))
        val incoming = raw.copy(startAddress = "New provider address", energyConsumedNet = null,
            batteryDetails = null).toSyncSummary(-7)!!
        val merged = mergeStoredDrive(incoming, cached)
        assertEquals(incoming.apiEvidence, HistorySummaryEvidenceCodec.sourceJson(merged.apiEvidence))
        val displayed = merged.toAnalysisDriveData()
        assertEquals(11.0, displayed.startLatitude!!, 0.0)
        assertEquals(12.0, displayed.startLongitude!!, 0.0)
        assertEquals(13.0, displayed.endLatitude!!, 0.0)
        assertEquals(14.0, displayed.endLongitude!!, 0.0)
        assertEquals("New provider address", displayed.startAddress)
    }

    @Test fun diskUpsertReopenAndActualRepositoryLoadCannotInventRawReceipt() = runBlocking {
        var db = open()
        val cached = savedDetail()
        val incoming = sparse.toSyncSummary(-7)!!
        try {
            db.driveSummaryDao().upsert(cached)
            db.driveSummaryDao().upsertPreservingEvidence(listOf(incoming))
            val written = db.driveSummaryDao().get(-7, 4)!!
            db.close()
            db = open()
            val reopened = db.driveSummaryDao().get(-7, 4)!!
            assertEquals(written.apiEvidence, reopened.apiEvidence)
            val loaded = repository(db.driveSummaryDao()).load(7)
            assertTrue(loaded is ApiResult.Success)
            assertGenuineReceipt(db.driveSummaryDao().get(-7, 4)!!, cached, incoming)
            assertEquals(0.784, db.driveSummaryDao().get(-7, 4)!!.energyConsumed!!, 1e-12)
        } finally { db.close() }
    }

    @Test fun offlineLoadScrubsUncontractedFleetAnalysisColumnsButKeepsRawInRoom() = runBlocking {
        var db = open()
        val legacyFleet = original.toSyncSummary(-7)!!.copy(energyConsumed = 8.0,
            efficiency = 4000.0, energySource = "api", energyCoverageSeconds = 60L, energyCoverageRatio = 1.0)
        val source = requireNotNull(legacyFleet.apiEvidence)
        try {
            db.driveSummaryDao().upsert(legacyFleet)
            repeat(3) {
                val result = repository(db.driveSummaryDao(), emptyList()).load(7) as ApiResult.Success
                assertEquals(original.startAddress, result.data.drives.single().startAddress)
                assertNull(result.data.drives.single().netEnergyKwh)
                val saved = requireNotNull(db.driveSummaryDao().get(-7, original.driveId))
                assertNull(saved.energyConsumed)
                assertNull(saved.efficiency)
                assertNull(saved.energySource)
                assertEquals(0L, saved.energyCoverageSeconds)
                assertEquals(0.0, saved.energyCoverageRatio, 0.0)
                assertEquals(source, saved.apiEvidence)
                assertEquals(8.0, saved.toRawAnalysisDriveData().energyConsumedNet!!, 0.0)
            }
            assertEquals(0L, db.driveSummaryDao().archiveUsedBytes())
            db.close(); db = open()
            val reopened = requireNotNull(db.driveSummaryDao().get(-7, original.driveId))
            assertNull(reopened.energyConsumed)
            assertEquals(source, reopened.apiEvidence)
            assertEquals(8.0, reopened.toRawAnalysisDriveData().energyConsumedNet!!, 0.0)
        } finally { db.close() }
    }

    @Test fun offlineLoadKeepsQualifiedScopedFleetDetailAndOriginalRawInRoom() = runBlocking {
        var db = open()
        val qualified = savedDetail()
        val source = HistorySummaryEvidenceCodec.sourceJson(qualified.apiEvidence)
        try {
            db.driveSummaryDao().upsert(qualified)
            repeat(3) {
                val result = repository(db.driveSummaryDao(), emptyList()).load(7) as ApiResult.Success
                assertEquals(0.784, result.data.drives.single().netEnergyKwh!!, 1e-12)
                val saved = requireNotNull(db.driveSummaryDao().get(-7, original.driveId))
                assertEquals(0.784, saved.energyConsumed!!, 1e-12)
                assertEquals(source, HistorySummaryEvidenceCodec.sourceJson(saved.apiEvidence))
                assertEquals(8.0, saved.toRawAnalysisDriveData().energyConsumedNet!!, 0.0)
            }
            assertEquals(0L, db.driveSummaryDao().archiveUsedBytes())
            db.close(); db = open()
            val reopened = requireNotNull(db.driveSummaryDao().get(-7, original.driveId))
            assertEquals(0.784, reopened.toAnalysisDriveData().netEnergyKwh!!, 1e-12)
            assertEquals(source, HistorySummaryEvidenceCodec.sourceJson(reopened.apiEvidence))
        } finally { db.close() }
    }

    @Test fun identicalLegacyRowWithoutRawStaysUnchangedInRoom() = runBlocking {
        var db = open()
        val legacy = original.toSyncSummary(-7)!!.copy(apiEvidence = null,
            startBatteryLevel = 80, outsideTempAvg = 15.0, qualityState = "incomplete",
            qualityReason = "legacy_unverified")
        try {
            db.driveSummaryDao().upsert(legacy)
            assertTrue(repository(db.driveSummaryDao(), emptyList()).load(7) is ApiResult.Success)
            db.close(); db = open()
            assertEquals(legacy, db.driveSummaryDao().get(-7, original.driveId))
            assertEquals(0L, db.driveSummaryDao().archiveUsedBytes())
        } finally { db.close() }
    }

    @Test fun explicitUnknownReceiptRevokesOldDetailWithoutSynthesizingRawFields() {
        val cached = savedDetail()
        val incoming = sparse.copy(energyContract = EnergyContract(netEnergy = EnergyMetric(
            quality = "unknown", source = original.source, startDate = start, endDate = end,
            reason = "provider_energy_unsupported"))).toSyncSummary(-7)!!
        val merged = mergeStoredDrive(incoming, cached)
        assertEquals(incoming.apiEvidence, HistorySummaryEvidenceCodec.sourceJson(merged.apiEvidence))
        assertNull(merged.energyConsumed)
        assertNull(merged.toAnalysisDriveData().netEnergyKwh)
    }

    @Test fun completeNewSocReceiptRecomputesCapacityAndRemainsByteExact() {
        val cached = savedDetail()
        val incoming = original.copy(startAddress = "Corrected provider address",
            energyConsumedNet = null, batteryDetails = DriveBatteryDetails(80, 78)).toSyncSummary(-7)!!
        val merged = mergeStoredDrive(incoming, cached)
        assertEquals(incoming.apiEvidence, HistorySummaryEvidenceCodec.sourceJson(merged.apiEvidence))
        assertEquals(1.568, merged.energyConsumed!!, 1e-12)
        assertEquals(78, merged.toAnalysisDriveData().endBatteryLevel)
    }

    @Test fun unknownThenRepeatedSparseRefreshKeepsOneBoundedGenuinePriorReceipt() {
        val cached = original.toSyncSummary(-7)!!
        val unknown = sparse.copy(energyContract = EnergyContract(netEnergy = EnergyMetric(
            quality = "unknown", source = original.source, startDate = start, endDate = end,
            reason = "provider_energy_unsupported"))).toSyncSummary(-7)!!
        var saved = mergeStoredDrive(unknown, cached)
        repeat(3) {
            saved = mergeStoredDrive(sparse.toSyncSummary(-7)!!, saved)
            assertGenuineReceipt(saved, cached, unknown, sparse.toSyncSummary(-7)!!)
            val archived = HistorySummaryEvidenceCodec.priorDriveSourceJson(saved.apiEvidence, -7)
            assertEquals(cached.apiEvidence, archived)
            assertNotNull(archived)
            assertEquals(8.0, HistorySummaryEvidenceCodec.decodeDrive(requireNotNull(archived))!!.energyConsumedNet!!, 0.0)
            assertNull(saved.energyConsumed)
            assertNull(saved.toAnalysisDriveData().netEnergyKwh)
            assertNull(HistorySummaryEvidenceCodec.priorDriveSourceJson(saved.apiEvidence, 7))
        }
        val foreignWindow = sparse.copy(endDate = "2026-10-13T00:02:00Z").toSyncSummary(-7)!!
        val changed = mergeStoredDrive(foreignWindow, saved)
        assertNull(HistorySummaryEvidenceCodec.priorDriveSourceJson(changed.apiEvidence, -7))
    }

    private fun richSyntheticDrive() = original.copy(
        source = "teslamate_archive", sourceInstanceId = "synthetic-instance-a", sourceId = "synthetic-record-4",
        energyConsumedNet = null, batteryDetails = DriveBatteryDetails(80, 79),
        rangeRated = DriveRange(320.0, 315.0), outsideTempAvg = -4.0,
        lengthUnit = "km", temperatureUnit = "C"
    )

    private fun assertOriginalReceiptRecoverable(db: StatsDatabase, row: DriveSummary, expected: String) {
        val evidence = mutableListOf<String>()
        HistorySummaryEvidenceCodec.sourceEvidence(row.apiEvidence)?.let(evidence::add)
        HistorySummaryEvidenceCodec.priorDriveSourceJson(row.apiEvidence, row.carId)
            ?.let(HistorySummaryEvidenceCodec::sourceEvidence)?.let(evidence::add)
        db.openHelper.readableDatabase.query(
            "SELECT headerJson FROM history_detail_receipts WHERE localHistoryCarId = ? AND recordId = ? AND kind = ?",
            arrayOf(row.carId, row.driveId, "archived_drive_source")
        ).use { cursor ->
            while (cursor.moveToNext()) if (!cursor.isNull(0))
                HistorySummaryEvidenceCodec.sourceEvidence(cursor.getString(0))?.let(evidence::add)
        }
        assertTrue("The original same-session receipt and its length-unit metadata must remain recoverable",
            expected in evidence)
    }

    @Test fun sameSessionSparseReceiptKeepsOriginalSourceEvidenceInRoom() = runBlocking {
        var db = open()
        val rich = richSyntheticDrive()
        val first = rich.toSyncSummary(-7)!!
        val expectedOriginal = requireNotNull(HistorySummaryEvidenceCodec.sourceEvidence(first.apiEvidence))
        val sparseNext = rich.copy(startAddress = "Updated address", batteryDetails = null,
            rangeRated = null, outsideTempAvg = null).toSyncSummary(-7)!!
        try {
            db.driveSummaryDao().upsert(first)
            db.driveSummaryDao().upsertPreservingEvidence(listOf(sparseNext))
            db.close()
            db = open()
            val saved = requireNotNull(db.driveSummaryDao().get(-7, rich.driveId))
            assertEquals(HistorySummaryEvidenceCodec.sourceEvidence(sparseNext.apiEvidence),
                HistorySummaryEvidenceCodec.sourceEvidence(saved.apiEvidence))
            assertOriginalReceiptRecoverable(db, saved, expectedOriginal)
        } finally { db.close() }
    }

    @Test fun sparseThenExplicitEnergyUnknownKeepsOtherFieldsAcrossRoomReopen() = runBlocking {
        var db = open()
        val rich = richSyntheticDrive()
        val first = rich.toSyncSummary(-7)!!
        val otherCar = rich.copy(sourceInstanceId = "synthetic-vehicle-b",
            batteryDetails = DriveBatteryDetails(42, 41), startAddress = "Other vehicle").toSyncSummary(-8)!!
        val sparseNext = rich.copy(startAddress = "Updated address", batteryDetails = null,
            rangeRated = null, outsideTempAvg = null)
        val unknown = sparseNext.copy(energyContract = EnergyContract(netEnergy = EnergyMetric(
            quality = "unknown", source = rich.source, startDate = start, endDate = end,
            reason = "provider_energy_unsupported"))).toSyncSummary(-7)!!
        try {
            db.driveSummaryDao().upsert(first)
            db.driveSummaryDao().upsert(otherCar)
            db.driveSummaryDao().upsertPreservingEvidence(listOf(sparseNext.toSyncSummary(-7)!!))
            db.close()
            db = open()
            val afterSparse = requireNotNull(db.driveSummaryDao().get(-7, rich.driveId)).toAnalysisDriveData()
            assertEquals(80, afterSparse.startBatteryLevel)
            assertEquals(320.0, afterSparse.startRatedRangeKm!!, 0.0)
            assertEquals(-4.0, afterSparse.outsideTempAvg!!, 0.0)

            db.driveSummaryDao().upsertPreservingEvidence(listOf(unknown))
            db.close()
            db = open()
            val saved = requireNotNull(db.driveSummaryDao().get(-7, rich.driveId))
            val display = saved.toAnalysisDriveData()
            assertEquals(80, display.startBatteryLevel)
            assertEquals(79, display.endBatteryLevel)
            assertEquals(320.0, display.startRatedRangeKm!!, 0.0)
            assertEquals(315.0, display.endRatedRangeKm!!, 0.0)
            assertEquals("C", display.temperatureUnit)
            assertEquals(-4.0, display.outsideTempAvg!!, 0.0)
            assertNull(display.netEnergyKwh)
            assertEquals("unknown", display.energyContract?.netEnergy?.quality)
            assertEquals(HistorySummaryEvidenceCodec.sourceEvidence(unknown.apiEvidence),
                HistorySummaryEvidenceCodec.sourceEvidence(saved.apiEvidence))
            assertOriginalReceiptRecoverable(db, saved,
                requireNotNull(HistorySummaryEvidenceCodec.sourceEvidence(first.apiEvidence)))
            assertEquals(otherCar, db.driveSummaryDao().get(-8, rich.driveId))
        } finally { db.close() }
    }

    @Test fun equivalentOffsetTimestampsKeepScopedDetailFieldsAfterRoomReopen() = runBlocking {
        var db = open()
        val summary = richSyntheticDrive().copy(batteryDetails = null, rangeRated = null, outsideTempAvg = null)
        val detail = summary.asCachedDetail().copy(
            batteryDetails = DriveBatteryDetails(80, 79), rangeRated = DriveRange(320.0, 315.0),
            outsideTempAvg = -4.0, temperatureUnit = "C", lengthUnit = "km")
        val cached = summary.toSyncSummary(-7)!!.withResolvedDriveEnergy(detail,
            detail.resolveDriveEnergy(model = "Y", trimBadging = "Long Range AWD"))
        val offsetReceipt = summary.copy(startDate = "2026-10-13T08:00:00+08:00",
            endDate = "2026-10-13T08:01:00+08:00", startAddress = "Updated address").toSyncSummary(-7)!!
        try {
            db.driveSummaryDao().upsert(cached)
            val before = requireNotNull(db.driveSummaryDao().get(-7, summary.driveId)).toAnalysisDriveData()
            assertEquals(80, before.startBatteryLevel)
            assertEquals(320.0, before.startRatedRangeKm!!, 0.0)
            db.driveSummaryDao().upsertPreservingEvidence(listOf(offsetReceipt))
            db.close()
            db = open()
            val after = requireNotNull(db.driveSummaryDao().get(-7, summary.driveId)).toAnalysisDriveData()
            assertEquals(80, after.startBatteryLevel)
            assertEquals(79, after.endBatteryLevel)
            assertEquals(320.0, after.startRatedRangeKm!!, 0.0)
            assertEquals("C", after.temperatureUnit)
            assertEquals(-4.0, after.outsideTempAvg!!, 0.0)
        } finally { db.close() }
    }

    @Test fun unitAndSourceInstanceChangesNeverRelabelOrBorrowAnotherVehicle() = runBlocking {
        var db = open()
        val rich = richSyntheticDrive().copy(batteryDetails = DriveBatteryDetails(0, 0))
        val otherCar = rich.copy(sourceInstanceId = "synthetic-vehicle-b",
            batteryDetails = DriveBatteryDetails(42, 41)).toSyncSummary(-8)!!
        val changedUnit = rich.copy(startAddress = "New address", batteryDetails = null,
            rangeRated = null, outsideTempAvg = null, lengthUnit = "mi", temperatureUnit = "F")
        try {
            db.driveSummaryDao().upsert(rich.toSyncSummary(-7)!!)
            db.driveSummaryDao().upsert(otherCar)
            db.driveSummaryDao().upsertPreservingEvidence(listOf(changedUnit.toSyncSummary(-7)!!))
            db.close()
            db = open()
            val display = requireNotNull(db.driveSummaryDao().get(-7, rich.driveId)).toAnalysisDriveData()
            assertEquals(0, display.startBatteryLevel)
            display.startRatedRangeKm?.let { assertEquals(320.0, it, 1e-9) }
            display.outsideTempAvg?.let { value ->
                val celsius = when (display.temperatureUnit) {
                    "C" -> value
                    "F" -> (value - 32.0) * 5.0 / 9.0
                    else -> fail("A displayed temperature must have a proven unit").let { Double.NaN }
                }
                assertEquals(-4.0, celsius, 1e-9)
            }
            assertEquals(otherCar, db.driveSummaryDao().get(-8, rich.driveId))

            val replacement = changedUnit.copy(sourceInstanceId = "synthetic-instance-b")
            db.driveSummaryDao().upsertPreservingEvidence(listOf(replacement.toSyncSummary(-7)!!))
            db.close()
            db = open()
            val newSession = requireNotNull(db.driveSummaryDao().get(-7, rich.driveId)).toAnalysisDriveData()
            assertEquals("synthetic-instance-b", newSession.sourceInstanceId)
            assertNull(newSession.startBatteryLevel)
            assertNull(newSession.startRatedRangeKm)
            assertNull(newSession.outsideTempAvg)
            assertEquals(otherCar, db.driveSummaryDao().get(-8, rich.driveId))
        } finally { db.close() }
    }

    @Test fun missingResponseUnitsAndEnergyUnknownRetainPriorQualifiedFieldsInRoom() = runBlocking {
        var db = open()
        val rich = richSyntheticDrive()
        val originalRow = rich.toSyncSummary(-7)!!
        val expectedSource = requireNotNull(HistorySummaryEvidenceCodec.sourceEvidence(originalRow.apiEvidence))
        val incoming = rich.copy(startAddress = "Updated address", batteryDetails = null,
            rangeRated = null, odometerDetails = null, outsideTempAvg = null, insideTempAvg = null,
            lengthUnit = "", temperatureUnit = "", energyContract = EnergyContract(netEnergy = EnergyMetric(
                quality = "unknown", source = rich.source, startDate = start, endDate = end,
                reason = "provider_energy_unsupported"))).toSyncSummary(-7)!!
        try {
            db.driveSummaryDao().upsert(originalRow)
            db.driveSummaryDao().upsertPreservingEvidence(listOf(incoming))
            db.close(); db = open()
            val saved = requireNotNull(db.driveSummaryDao().get(-7, rich.driveId))
            val display = saved.toAnalysisDriveData()
            assertEquals(80, display.startBatteryLevel)
            assertEquals(79, display.endBatteryLevel)
            assertEquals("km", display.lengthUnit)
            assertEquals(320.0, display.startRatedRangeKm!!, 0.0)
            assertEquals("C", display.temperatureUnit)
            assertEquals(-4.0, display.outsideTempAvg!!, 0.0)
            assertNull(display.netEnergyKwh)
            assertEquals("unknown", display.energyContract?.netEnergy?.quality)
            assertEquals(HistorySummaryEvidenceCodec.sourceEvidence(incoming.apiEvidence),
                HistorySummaryEvidenceCodec.sourceEvidence(saved.apiEvidence))
            assertOriginalReceiptRecoverable(db, saved, expectedSource)
        } finally { db.close() }
    }

    @Test fun unknownUnitNumbersStayRawAndNeverBorrowOldDisplayUnits() = runBlocking {
        var db = open()
        val rich = richSyntheticDrive()
        val incoming = rich.copy(startAddress = "Updated address", batteryDetails = null,
            rangeRated = DriveRange(999.0, 888.0), odometerDetails = DriveOdometerDetails(distance = 777.0),
            outsideTempAvg = 123.0, lengthUnit = "", temperatureUnit = "")
        try {
            db.driveSummaryDao().upsert(rich.toSyncSummary(-7)!!)
            db.driveSummaryDao().upsertPreservingEvidence(listOf(incoming.toSyncSummary(-7)!!))
            db.close(); db = open()
            val saved = requireNotNull(db.driveSummaryDao().get(-7, rich.driveId))
            val raw = saved.toRawAnalysisDriveData()
            val display = saved.toAnalysisDriveData()
            assertEquals("", raw.lengthUnit)
            assertEquals("", raw.temperatureUnit)
            assertEquals(999.0, raw.rangeRated!!.startRange!!, 0.0)
            assertEquals(123.0, raw.outsideTempAvg!!, 0.0)
            display.startRatedRangeKm?.let { assertEquals(320.0, it, 1e-9) }
            display.outsideTempAvg?.let { value ->
                val celsius = when (display.temperatureUnit) {
                    "C" -> value
                    "F" -> (value - 32.0) * 5.0 / 9.0
                    else -> fail("Unknown source unit cannot become a display unit").let { Double.NaN }
                }
                assertEquals(-4.0, celsius, 1e-9)
            }
            Unit
        } finally { db.close() }
    }

    @Test fun explicitNewKnownUnitsAcceptNewValuesWithoutRelabelingOld() = runBlocking {
        var db = open()
        val rich = richSyntheticDrive()
        val originalRow = rich.toSyncSummary(-7)!!
        val corrected = rich.copy(startAddress = "Updated address", rangeRated = DriveRange(200.0, 190.0),
            outsideTempAvg = 32.0, lengthUnit = "mi", temperatureUnit = "F").toSyncSummary(-7)!!
        try {
            db.driveSummaryDao().upsert(originalRow)
            db.driveSummaryDao().upsertPreservingEvidence(listOf(corrected))
            db.close(); db = open()
            val saved = requireNotNull(db.driveSummaryDao().get(-7, rich.driveId))
            val display = saved.toAnalysisDriveData()
            assertEquals("mi", display.lengthUnit)
            assertEquals(321.8688, display.startRatedRangeKm!!, 1e-9)
            assertEquals("F", display.temperatureUnit)
            assertEquals(32.0, display.outsideTempAvg!!, 0.0)
            assertEquals(0.0, display.outsideTempC!!, 0.0)
            assertEquals(HistorySummaryEvidenceCodec.sourceEvidence(corrected.apiEvidence),
                HistorySummaryEvidenceCodec.sourceEvidence(saved.apiEvidence))
            assertOriginalReceiptRecoverable(db, saved,
                requireNotNull(HistorySummaryEvidenceCodec.sourceEvidence(originalRow.apiEvidence)))
        } finally { db.close() }
    }

    @Test fun legacyTypedRowsWithoutRawFailClosedAndRemainCached() = runBlocking {
        var db = open()
        val rich = richSyntheticDrive()
        val legacy = rich.toSyncSummary(-7)!!.copy(apiEvidence = null, startBatteryLevel = 80,
            endBatteryLevel = 79, outsideTempAvg = -4.0, distance = 12.5,
            qualityState = "incomplete", qualityReason = "legacy_unverified")
        val otherCar = legacy.copy(carId = -8, startBatteryLevel = 42)
        val incoming = rich.copy(source = null, sourceInstanceId = null, sourceId = null,
            batteryDetails = null, rangeRated = null, outsideTempAvg = null, lengthUnit = "", temperatureUnit = "")
        try {
            db.driveSummaryDao().upsert(legacy)
            db.driveSummaryDao().upsert(otherCar)
            try {
                db.driveSummaryDao().upsertPreservingEvidence(listOf(incoming.toSyncSummary(-7)!!))
                fail("Unverifiable legacy typed values cannot be overwritten without a local archive")
            } catch (_: HistorySourceArchiveException) { }
            val result = repository(db.driveSummaryDao(), listOf(incoming)).load(7)
            assertTrue(result is ApiResult.Success)
            assertEquals("history_cached", (result as ApiResult.Success).data.drivesSyncError)
            db.close(); db = open()
            assertEquals(legacy, db.driveSummaryDao().get(-7, rich.driveId))
            assertEquals(otherCar, db.driveSummaryDao().get(-8, rich.driveId))
            assertNull(db.driveSummaryDao().get(-7, rich.driveId)!!.apiEvidence)
            assertEquals(0L, db.historyDetailDao().storedByteCount())
        } finally { db.close() }
    }

    @Test fun repositoryNeverEncodesReconciledFieldsAsRawProviderReceipt() = runBlocking {
        var db = open()
        val old = richSyntheticDrive()
        val incoming = old.copy(driveId = 5, startAddress = null, endAddress = "New destination",
            batteryDetails = null, rangeRated = null, outsideTempAvg = null)
        val oldRow = old.toSyncSummary(-7)!!
        val actualRow = incoming.toSyncSummary(-7)!!
        try {
            db.driveSummaryDao().upsert(oldRow)
            val result = repository(db.driveSummaryDao(), listOf(incoming)).load(7)
            assertTrue(result is ApiResult.Success)
            db.close(); db = open()
            assertEquals(oldRow, db.driveSummaryDao().get(-7, old.driveId))
            val written = requireNotNull(db.driveSummaryDao().get(-7, incoming.driveId))
            assertEquals("Raw evidence must be an actual provider receipt, never fused JSON",
                HistorySummaryEvidenceCodec.sourceEvidence(actualRow.apiEvidence),
                HistorySummaryEvidenceCodec.sourceEvidence(written.apiEvidence))
        } finally { db.close() }
    }

    @Test fun sameWindowDifferentSourcesStaySeparateInRepositoryAndRoom() = runBlocking {
        var db = open()
        val old = richSyntheticDrive()
        val incoming = old.copy(driveId = 5, source = "telemetry_mqtt", sourceInstanceId = "other-source",
            sourceId = "other-record", batteryDetails = null, rangeRated = null, outsideTempAvg = null)
        val oldRow = old.toSyncSummary(-7)!!
        val otherCar = old.copy(sourceInstanceId = "other-vehicle").toSyncSummary(-8)!!
        try {
            db.driveSummaryDao().upsert(oldRow)
            db.driveSummaryDao().upsert(otherCar)
            val result = repository(db.driveSummaryDao(), listOf(incoming)).load(7)
            assertTrue(result is ApiResult.Success)
            val visible = (result as ApiResult.Success).data.drives
            assertEquals(setOf(old.driveId, incoming.driveId), visible.map { it.driveId }.toSet())
            db.close(); db = open()
            assertEquals(oldRow, db.driveSummaryDao().get(-7, old.driveId))
            val remote = requireNotNull(db.driveSummaryDao().get(-7, incoming.driveId))
            assertEquals(HistorySummaryEvidenceCodec.sourceEvidence(incoming.toSyncSummary(-7)!!.apiEvidence),
                HistorySummaryEvidenceCodec.sourceEvidence(remote.apiEvidence))
            assertNull(remote.toAnalysisDriveData().startBatteryLevel)
            assertEquals(otherCar, db.driveSummaryDao().get(-8, old.driveId))
        } finally { db.close() }
    }

    @Test fun repeatedSparseReceiptIsDeduplicatedAndDoesNotConsumeChargeCurveBudget() = runBlocking {
        var db = open()
        val rich = richSyntheticDrive()
        val sparseNext = rich.copy(startAddress = "Updated address", batteryDetails = null,
            rangeRated = null, outsideTempAvg = null).toSyncSummary(-7)!!
        val context = VehicleContext(7,
            cloudOriginVehicleStableIdentity("receipt-test", "https://receipt.invalid", "vehicle-test"),
            -7, HistoryConnectionSource.CLOUD, "cloud")
        fun charge(id: Int) = ChargeDetail(id, startDate = start, endDate = end,
            source = "teslamate_archive", sourceInstanceId = "synthetic-charge-source",
            chargePoints = listOf(ChargePoint(date = start, chargeEnergyAdded = 0.0),
                ChargePoint(date = end, chargeEnergyAdded = 2.0)))
        fun identity(id: Int) = HistoryDetailIdentity.create(context, HistoryDetailIdentity.CHARGE, id,
            "teslamate_archive", "synthetic-charge-source", start, end)
        val codec = HistoryDetailCodec()
        val first = charge(11)
        val second = charge(12)
        val limits = HistoryDetailStorageLimits(maxTotalBytes =
            codec.encodeCharge(first, HistoryDetailStorageLimits()).encodedByteCount +
                codec.encodeCharge(second, HistoryDetailStorageLimits()).encodedByteCount)
        try {
            val points = HistoryDetailRepository(db.historyDetailDao(), codec, limits) { 1L }
            assertTrue(points.saveCharge(identity(11), first) is HistoryDetailWriteResult.Stored)
            val beforeBytes = db.historyDetailDao().storedByteCount()
            db.driveSummaryDao().upsert(rich.toSyncSummary(-7)!!)
            repeat(5) { db.driveSummaryDao().upsertPreservingEvidence(listOf(sparseNext)) }
            db.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM history_detail_receipts WHERE localHistoryCarId = ? AND recordId = ? AND kind = ?",
                arrayOf(-7, rich.driveId, "archived_drive_source")
            ).use { rows -> assertTrue(rows.moveToFirst()); assertEquals(1, rows.getInt(0)) }
            assertEquals(beforeBytes, db.historyDetailDao().storedByteCount())
            assertTrue(points.saveCharge(identity(12), second) is HistoryDetailWriteResult.Stored)
            db.close()
            db = open()
            assertEquals(first, HistoryDetailRepository(db.historyDetailDao()).readCharge(identity(11)).detail)
            assertEquals(second, HistoryDetailRepository(db.historyDetailDao()).readCharge(identity(12)).detail)
        } finally { db.close() }
    }

    @Test fun fullSourceArchiveRollsBackDriveBatchShowsCachedWarningAndStillLoadsCharges() = runBlocking {
        var db = open()
        val rich = richSyntheticDrive()
        val original = rich.toSyncSummary(-7)!!
        val incoming = rich.copy(startAddress = "Updated address", batteryDetails = null,
            rangeRated = null, outsideTempAvg = null)
        val extra = rich.copy(driveId = 5, sourceId = "synthetic-record-5").toSyncSummary(-7)!!
        val quotaMarker = requireNotNull(HistorySourceReceiptArchive.capture(original)).copy(
            receiptId = "synthetic-full-archive", identityKey = "synthetic-full-archive",
            encodedByteCount = 64L * 1024 * 1024)
        try {
            db.driveSummaryDao().upsert(original)
            db.driveSummaryDao().insertConflictArchive(quotaMarker)
            try {
                db.driveSummaryDao().upsertPreservingEvidence(listOf(extra, incoming.toSyncSummary(-7)!!))
                fail("An exhausted source archive must reject rather than discard the original")
            } catch (_: HistorySourceArchiveException) { }
            assertEquals(original, db.driveSummaryDao().get(-7, rich.driveId))
            assertNull("The entire failed Room transaction must roll back", db.driveSummaryDao().get(-7, 5))
            assertEquals(0L, db.historyDetailDao().storedByteCount())

            val scope = HistoryReadScope(HistoryConnectionSource.CLOUD, "cloud", "receipt-test", "https://receipt.invalid")
            val context = VehicleContext(7,
                cloudOriginVehicleStableIdentity("receipt-test", "https://receipt.invalid", "vehicle-test"),
                -7, HistoryConnectionSource.CLOUD, "cloud")
            val charge = ChargeData(99, startDate = start, endDate = end,
                source = "teslamate_archive", sourceInstanceId = "synthetic-charge-source")
            val repo = UnifiedHistoryRepository(HistoryReadDependencies(
                captureScope = { scope }, getCars = { ApiResult.Success(listOf(CarData(7, vehicleUid = "vehicle-test"))) },
                getHistoryContext = { ApiResult.Success(HistoryContextData(1, 7, "vehicle-test")) },
                resolveCar = { _, _ -> context }, cachedContext = { _, _ -> context },
                localDrives = { db.driveSummaryDao().getAllChronological(it) },
                localCharges = { db.chargeSummaryDao().getAllForCar(it) },
                getDrives = { _, _, _, page -> ApiResult.Success(if (page == 1) listOf(incoming) else emptyList()) },
                getCharges = { _, _, _, page -> ApiResult.Success(if (page == 1) listOf(charge) else emptyList()) },
                persistDrives = { db.driveSummaryDao().upsertPreservingEvidence(it) },
                persistCharges = { db.chargeSummaryDao().upsertPreservingEvidence(it) }
            ))
            val result = repo.load(7)
            assertTrue(result is ApiResult.Success)
            val history = (result as ApiResult.Success).data
            assertEquals("history_cached", history.drivesSyncError)
            assertFalse(history.drivesFromRemote)
            assertEquals("Old provider address", history.drives.single().startAddress)
            assertEquals(99, history.charges.single().chargeId)
            assertNotNull(db.chargeSummaryDao().get(-7, 99))
            db.close()
            db = open()
            assertEquals(original, db.driveSummaryDao().get(-7, rich.driveId))
            assertNull(db.driveSummaryDao().get(-7, 5))
            assertNotNull(db.chargeSummaryDao().get(-7, 99))
        } finally { db.close() }
    }

    private fun repository(dao: DriveSummaryDao, remote: List<DriveData> = listOf(sparse)): UnifiedHistoryRepository {
        val scope = HistoryReadScope(HistoryConnectionSource.CLOUD, "cloud", "receipt-test", "https://receipt.invalid")
        val context = VehicleContext(7,
            cloudOriginVehicleStableIdentity("receipt-test", "https://receipt.invalid", "vehicle-test"),
            -7, HistoryConnectionSource.CLOUD, "cloud")
        return UnifiedHistoryRepository(HistoryReadDependencies(
            captureScope = { scope }, getCars = { ApiResult.Success(listOf(CarData(7, vehicleUid = "vehicle-test"))) },
            getHistoryContext = { ApiResult.Success(HistoryContextData(1, 7, "vehicle-test")) },
            resolveCar = { _, _ -> context }, cachedContext = { _, _ -> context },
            localDrives = { dao.getAllChronological(it) }, localCharges = { emptyList() },
            getDrives = { _, _, _, page -> ApiResult.Success(if (page == 1) remote else emptyList()) },
            getCharges = { _, _, _, _ -> ApiResult.Success(emptyList()) },
            persistDrives = { dao.upsertPreservingEvidence(it) }, persistCharges = {}
        ))
    }
}
