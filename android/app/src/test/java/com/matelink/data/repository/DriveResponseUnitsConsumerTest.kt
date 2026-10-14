package com.matelink.data.repository

import com.matelink.data.api.models.*
import com.matelink.data.local.HistoryConnectionSource
import com.matelink.data.local.HistoryReadScope
import com.matelink.data.local.VehicleContext
import com.matelink.data.local.dao.persistDriveRowsWithoutEvidenceLoss
import com.matelink.data.local.entity.DriveSummary
import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.TripAggregator
import com.matelink.domain.analytics.*
import com.squareup.moshi.Moshi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Synthetic upstream-shaped responses through the actual repository boundary,
 * persistence merge and restart projection. No provider or owner data is used.
 */
class DriveResponseUnitsConsumerTest {
    @Test fun sparseKilometreDetailPreservesLegacyDistanceButExplicitUnknownCannotBorrowIt() {
        val original = list(unit = "km", distance = 10.0).copy(lengthUnit = null).toSyncSummary(9)!!
        for (unit in listOf("km", "unknown")) {
            val sparse = detail(energy = 1.0, unit = unit).copy(odometerDetails = null, speedMax = null, speedAvg = null)
            val stored = original.withResolvedDriveEnergy(sparse, sparse.resolveDriveEnergy())
            val restarted = stored.toAnalysisDriveData()
            if (unit == "km") {
                assertEquals(10.0, restarted.distanceKm!!, 0.0)
                assertEquals(100.0, restarted.efficiencyWhKm!!, 0.0)
            } else {
                assertNull(restarted.distanceKm)
                assertNull(restarted.efficiencyWhKm)
            }
            assertEquals(HistorySummaryEvidenceCodec.sourceJson(original.apiEvidence),
                HistorySummaryEvidenceCodec.sourceJson(stored.apiEvidence))
        }
    }
    @Test fun explicitKilometreSparseRefreshDoesNotEraseCompatibleLegacyKilometreDistance() = runBlocking {
        val original = list(unit = "km", distance = 10.0).copy(lengthUnit = null).toSyncSummary(9)!!
        val rows = mutableMapOf((9 to 7) to original)
        val next = list(unit = "km", distance = 10.0).copy(odometerDetails = null, energyConsumedNet = null).toSyncSummary(9)!!
        persistDriveRowsWithoutEvidenceLoss(listOf(next),
            { car, id -> rows[car to id] }, { rows[it.carId to it.driveId] = it })
        assertEquals(10.0, rows[9 to 7]!!.toAnalysisDriveData().distanceKm!!, 0.0)
        assertEquals(1000.0, rows[9 to 7]!!.toAnalysisDriveData().efficiencyWhKm!!, 0.0)
        assertEquals(HistorySummaryEvidenceCodec.sourceJson(original.apiEvidence),
            HistorySummaryEvidenceCodec.sourceJson(rows[9 to 7]!!.apiEvidence))
    }
    @Test fun moshiMissingNullEmptyMetricAndImperialUnitsStayDistinctThroughRoomAndDetail() {
        val cases = listOf(
            "" to "km",
            ",\"units\":null" to "",
            ",\"units\":{}" to "",
            ",\"units\":{\"unit_of_length\":\"km\"}" to "km",
            ",\"units\":{\"unit_of_length\":\"mi\"}" to "mi")
        for ((metadata, expectedUnit) in cases) {
            val received = moshi.adapter(DrivesResponse::class.java).fromJson(
                "{\"data\":{\"drives\":[${raw()}]$metadata}}")!!.data!!.receivedDrives().single()
            val current = moshi.adapter(DriveDetailResponse::class.java).fromJson(
                "{\"data\":{\"drive\":${raw()}$metadata}}")!!.data!!.receivedDrive()!!
            val row = received.toSyncSummary(9)!!
            val restarted = row.toAnalysisDriveData()
            assertEquals(expectedUnit, restarted.lengthUnit)
            assertEquals(expectedUnit, current.lengthUnit)
            val resolved = current.resolveDriveEnergy()
            val detailedRestart = row.withResolvedDriveEnergy(current, resolved).toAnalysisDriveData()
            assertEquals(expectedUnit, detailedRestart.lengthUnit)
            if (expectedUnit.isEmpty()) {
                assertNull(received.distanceKm)
                assertNull(restarted.efficiencyWhKm)
                assertNull(resolved.estimate.efficiencyWhKm)
                assertNull(detailedRestart.efficiencyWhKm)
            } else {
                val expectedKm = if (expectedUnit == "mi") 100 * kmPerMile else 100.0
                assertEquals(expectedKm, row.distance, 1e-10)
                assertEquals(10_000 / expectedKm, restarted.efficiencyWhKm!!, 1e-10)
                assertEquals(10_000 / expectedKm, resolved.estimate.efficiencyWhKm!!, 1e-10)
                assertEquals(10_000 / expectedKm, detailedRestart.efficiencyWhKm!!, 1e-10)
            }
        }
    }
    @Test fun responseUnitCorrectionReopensDetailRecoveryEvenWhenRawNumbersAreEqual() {
        val old = HistorySummaryEvidenceCodec.encode(list(distance = 10.0))
        val stopped = HistorySummaryEvidenceCodec.markDetailTerminal404(old, 9)
        val same = HistorySummaryEvidenceCodec.carryDetailTerminal404(old, stopped, 9)
        assertTrue(HistorySummaryEvidenceCodec.isDetailTerminal404(same, 9))
        val corrected = HistorySummaryEvidenceCodec.encode(list(unit = "km", distance = 10.0))
        assertEquals(HistorySummaryEvidenceCodec.sourceJson(old), HistorySummaryEvidenceCodec.sourceJson(corrected))
        val retryable = HistorySummaryEvidenceCodec.carryDetailTerminal404(corrected, stopped, 9)
        assertFalse(HistorySummaryEvidenceCodec.isDetailTerminal404(retryable, 9))
    }
    @Test fun weakUnknownUnitReceiptCannotRetainKnownUnitFromEarlierEnergy() = runBlocking {
        val rows = mutableMapOf((9 to 7) to list(distance = 10.0).toSyncSummary(9)!!)
        val correction = list(unit = "", distance = 10.0).copy(energyConsumedNet = null).toSyncSummary(9)!!
        persistDriveRowsWithoutEvidenceLoss(listOf(correction),
            { car, id -> rows[car to id] }, { rows[it.carId to it.driveId] = it })
        assertEquals("", rows[9 to 7]!!.toRawAnalysisDriveData().lengthUnit)
        assertNull(rows[9 to 7]!!.toAnalysisDriveData().efficiencyWhKm)
    }

    @Test fun unitChangeWithSameNumberRebasesDetailWithoutDeletingValidDistance() {
        val first = list(distance = 10.0).toSyncSummary(9)!!
        val richer = detail(distance = 10.0)
        val saved = first.withResolvedDriveEnergy(richer, richer.resolveDriveEnergy())
        val next = list(unit = "km", distance = 10.0)
        val rebased = HistorySummaryEvidenceCodec.rebaseDriveRaw(saved.apiEvidence, HistorySummaryEvidenceCodec.encode(next))!!
        val presentation = HistorySummaryEvidenceCodec.drivePresentation(rebased, 9, 7,
            next.source, next.startDate, next.endDate)!!
        assertEquals("km", presentation.lengthUnit)
        assertEquals(10.0, presentation.distanceKm!!, 0.0)
        assertEquals(60.0, presentation.speedMaxKmh!!, 0.0)
        assertEquals(HistorySummaryEvidenceCodec.sourceJson(HistorySummaryEvidenceCodec.encode(next)),
            HistorySummaryEvidenceCodec.sourceJson(rebased))
    }
    @Test fun receiptLengthOverridesLaterPreferencesForRawDisplay() {
        assertEquals("mi", drivePresentationUnits("mi", Units(unitOfLength = "km")).unitOfLength)
        assertEquals("km", drivePresentationUnits(null, Units(unitOfLength = "mi")).unitOfLength)
    }
    private val moshi = Moshi.Builder().build()
    private val kmPerMile = 1.609344
    private val start = "2026-10-14T01:00:00Z"
    private val end = "2026-10-14T02:00:00Z"

    private fun raw(energy: Double = 10.0, distance: Double = 100.0) = """{
        "drive_id":7,"start_date":"$start","end_date":"$end",
        "duration_min":60,"source":"teslamate_archive","quality_state":"observed",
        "speed_max":60,"speed_avg":30.0,
        "odometer_details":{"odometer_distance":$distance},"energy_consumed_net":$energy
    }""".trimIndent()

    private fun list(energy: Double = 10.0, unit: String? = "mi", distance: Double = 100.0 / kmPerMile): DriveData {
        val metadata = unit?.let { ",\"units\":{\"unit_of_length\":\"$it\"}" }.orEmpty()
        val body = moshi.adapter(DrivesResponse::class.java)
            .fromJson("{\"data\":{\"drives\":[${raw(energy, distance)}]$metadata}}")!!.data!!
        return body.receivedDrives().single()
    }

    private fun detail(energy: Double = 10.0, unit: String? = "mi", distance: Double = 100.0 / kmPerMile): DriveDetail {
        val metadata = unit?.let { ",\"units\":{\"unit_of_length\":\"$it\"}" }.orEmpty()
        return moshi.adapter(DriveDetailResponse::class.java)
            .fromJson("{\"data\":{\"drive\":${raw(energy, distance)}$metadata}}")!!.data!!.receivedDrive()!!
    }

    @Test fun imperialResponseComputesCanonicalEfficiencyWithoutChangingDisplayReceipt() {
        val drive = list()
        assertEquals(100.0 / kmPerMile, drive.distance!!, 1e-10)
        assertEquals(100.0, drive.distanceKm!!, 1e-10)
        assertEquals(100.0, drive.efficiencyWhKm!!, 1e-10)
        assertEquals(60, drive.speedMax)
        assertEquals(60 * kmPerMile, drive.speedMaxKmh!!, 1e-10)
        val stored = drive.toSyncSummary(9)!!
        assertEquals(100.0, stored.distance, 1e-10)
        assertEquals(96, stored.speedMax)
        assertEquals(100.0, stored.toAnalysisDriveData().efficiencyWhKm!!, 1e-10)
        assertEquals("mi", stored.toRawAnalysisDriveData().lengthUnit)
        assertEquals(moshi.adapter(DriveData::class.java).toJson(drive),
            HistorySummaryEvidenceCodec.sourceJson(stored.apiEvidence))
        assertFalse(HistorySummaryEvidenceCodec.sourceJson(stored.apiEvidence)!!.contains("length_unit"))
    }

    @Test fun negativeImperialEnergyRemainsNegativeAfterPersistence() {
        val stored = list(-1.0).toLocalSummary(9)!!
        assertEquals(-10.0, stored.efficiency!!, 1e-10)
        assertEquals(-10.0, stored.toAnalysisDriveData().efficiencyWhKm!!, 1e-10)
        assertEquals(-1.0, TripAggregator.buildTrip(listOf(stored), emptyList())!!.totalEnergyConsumed!!, 0.0)
    }

    @Test fun zeroImperialEnergyIsKnownZeroRatherThanMissing() {
        val stored = list(0.0).toSyncSummary(9)!!
        assertEquals(0.0, stored.toAnalysisDriveData().efficiencyWhKm!!, 0.0)
        assertEquals(0.0, TripAggregator.buildTrip(listOf(stored), emptyList())!!.avgEfficiency!!, 0.0)
    }

    @Test fun cloudMissingUnitsAndExplicitKilometresAreNotConvertedTwice() {
        for (unit in listOf(null, "km")) {
            val drive = list(unit = unit, distance = 100.0)
            assertEquals(100.0, drive.distanceKm!!, 0.0)
            assertEquals(100.0, drive.toSyncSummary(9)!!.efficiency!!, 0.0)
            assertEquals(60.0, drive.speedMaxKmh!!, 0.0)
        }
    }

    @Test fun explicitUnsupportedLengthCannotProduceEfficiencyOrRelabelDistance() {
        for (unit in listOf("yards", "", "unknown")) {
            val drive = list(unit = unit)
            assertNull(drive.distance)
            assertNull(drive.distanceKm)
            assertNull(drive.speedMaxKmh)
            assertNull(drive.efficiencyWhKm)
            val restored = drive.toSyncSummary(9)!!.toAnalysisDriveData()
            assertEquals(unit, restored.lengthUnit)
            assertNull(restored.distanceKm)
            assertNull(restored.efficiencyWhKm)
            assertEquals(10.0, restored.netEnergyKwh!!, 0.0) // kWh does not depend on length.
        }
    }

    @Test fun unitsObjectWithoutLengthIsExplicitUnknownInsteadOfLegacyDefault() {
        val body = moshi.adapter(DrivesResponse::class.java).fromJson(
            "{\"data\":{\"drives\":[${raw()}],\"units\":{}}}")!!.data!!
        val drive = body.receivedDrives().single()
        assertEquals("", drive.lengthUnit)
        assertNull(drive.efficiencyWhKm)
    }

    @Test fun detailResolverAndScopedRestartUseSameResponseUnit() {
        val first = list().toSyncSummary(9)!!
        val details = detail(-1.0)
        val estimate = details.resolveDriveEnergy()
        assertEquals(-10.0, estimate.estimate.efficiencyWhKm!!, 1e-10)
        val enriched = first.withResolvedDriveEnergy(details, estimate)
        assertEquals(100.0, enriched.distance, 1e-10)
        assertEquals(-10.0, enriched.efficiency!!, 1e-10)
        assertEquals("mi", enriched.toAnalysisDriveData().lengthUnit)
        assertEquals(-10.0, enriched.toAnalysisDriveData().efficiencyWhKm!!, 1e-10)
        assertEquals(HistorySummaryEvidenceCodec.sourceJson(first.apiEvidence),
            HistorySummaryEvidenceCodec.sourceJson(enriched.apiEvidence))
    }

    @Test fun unknownDetailUnitKeepsRawEnergyButDoesNotBorrowKnownCachedDistance() {
        val first = list().toSyncSummary(9)!!
        val details = detail(unit = "yards")
        val estimate = details.resolveDriveEnergy()
        assertNull(estimate.estimate.efficiencyWhKm)
        val enriched = first.withResolvedDriveEnergy(details, estimate)
        assertNull(enriched.efficiency)
        assertEquals(0.0, enriched.distance, 0.0)
        assertNull(enriched.toAnalysisDriveData().distance)
        assertNull(enriched.toAnalysisDriveData().efficiencyWhKm)
    }

    @Test fun sameIdUpsertKeepsUnitsThroughTheActualPersistenceMerge() = runBlocking {
        val initial = list().toSyncSummary(9)!!
        val rows = mutableMapOf((9 to 7) to initial)
        persistDriveRowsWithoutEvidenceLoss(listOf(list().toSyncSummary(9)!!),
            { c, id -> rows[c to id] }, { rows[it.carId to it.driveId] = it })
        val restored = rows[9 to 7]!!.toAnalysisDriveData()
        assertEquals("mi", restored.lengthUnit)
        assertEquals(100.0, restored.distanceKm!!, 1e-10)
        assertEquals(100.0, restored.efficiencyWhKm!!, 1e-10)
    }

    @Test fun unitChangeUsesNewReceiptInsteadOfMergingMixedNumeratorsAndDistance() = runBlocking {
        val rows = mutableMapOf((9 to 7) to list().toSyncSummary(9)!!)
        persistDriveRowsWithoutEvidenceLoss(listOf(list(unit = "km", distance = 100.0).toSyncSummary(9)!!),
            { c, id -> rows[c to id] }, { rows[it.carId to it.driveId] = it })
        val restored = rows[9 to 7]!!.toAnalysisDriveData()
        assertEquals("km", restored.lengthUnit)
        assertEquals(100.0, restored.distanceKm!!, 1e-10)
        assertEquals(100.0, restored.efficiencyWhKm!!, 1e-10)
    }

    @Test fun realHistoryRepositoryLoadThenOfflineRestartHasCanonicalCombinedEfficiency() = runBlocking {
        val scope = HistoryReadScope(HistoryConnectionSource.SELF_HOSTED, "synthetic-host", null)
        val context = VehicleContext(7, "synthetic-vehicle", -7, HistoryConnectionSource.SELF_HOSTED, "synthetic-host")
        val rows = mutableListOf<DriveSummary>()
        var online = true
        fun repository() = UnifiedHistoryRepository(HistoryReadDependencies(
            captureScope = { scope }, getCars = { ApiResult.Success(listOf(CarData(7))) },
            getHistoryContext = { ApiResult.Error("unused") }, resolveCar = { _, _ -> context },
            cachedContext = { _, _ -> context }, localDrives = { rows.toList() }, localCharges = { emptyList() },
            getDrives = { _, _, _, _ -> if (online) ApiResult.Success(listOf(list())) else ApiResult.Error("offline", 503) },
            getCharges = { _, _, _, _ -> ApiResult.Success(emptyList()) },
            persistDrives = { rows.clear(); rows.addAll(it) }, persistCharges = {}))
        val first = repository().load(7) as ApiResult.Success
        assertEquals(100.0, first.data.drives.single().efficiencyWhKm!!, 1e-10)
        assertEquals(100.0, rows.single().distance, 1e-10)
        online = false
        val restarted = repository().load(7) as ApiResult.Success
        assertEquals("mi", restarted.data.drives.single().lengthUnit)
        assertEquals(100.0, restarted.data.drives.single().efficiencyWhKm!!, 1e-10)
        assertEquals(100.0, TripAggregator.buildTrip(rows, emptyList())!!.avgEfficiency!!, 1e-10)
    }

    @Test fun malformedDistancesAndUntrustedImperialConsumptionAreNotPromoted() {
        assertNull(list(distance = 0.0).efficiencyWhKm)
        assertNull(list(distance = -1.0).efficiencyWhKm)
        val noEnergy = list().copy(energyConsumedNet = null, consumptionNet = 100.0)
        assertNull(noEnergy.efficiencyWhKm)
        assertNull(detail(unit = "unsupported").resolveDriveEnergy().estimate.efficiencyWhKm)
    }

    @Test fun namedKilometreRangePropertiesUseResponseUnitsWithoutChangingRawRange() {
        val drive = list().copy(rangeRated = DriveRange(200.0 / kmPerMile, 100.0 / kmPerMile))
        assertEquals(200.0, drive.startRatedRangeKm!!, 1e-10)
        assertEquals(100.0, drive.endRatedRangeKm!!, 1e-10)
        assertEquals(200.0 / kmPerMile, drive.rangeRated!!.startRange!!, 1e-10)
        val restored = drive.toSyncSummary(9)!!.toAnalysisDriveData()
        assertEquals(200.0, restored.startRatedRangeKm!!, 1e-10)
        assertNull(drive.copy(lengthUnit = "unsupported").startRatedRangeKm)
    }

    @Test fun detailChangingUnitsPreservesIndependentRawAndPresentationUnitEvidence() {
        val first = list().toSyncSummary(9)!!
        val current = detail(unit = "km", distance = 100.0)
        val stored = first.withResolvedDriveEnergy(current, current.resolveDriveEnergy())
        assertEquals("mi", stored.toRawAnalysisDriveData().lengthUnit)
        assertEquals(100.0 / kmPerMile, stored.toRawAnalysisDriveData().distance!!, 1e-10)
        val restored = stored.toAnalysisDriveData()
        assertEquals("km", restored.lengthUnit)
        assertEquals(100.0, restored.distance!!, 1e-10)
        assertEquals(100.0, restored.efficiencyWhKm!!, 1e-10)
        assertEquals(HistorySummaryEvidenceCodec.sourceJson(first.apiEvidence),
            HistorySummaryEvidenceCodec.sourceJson(stored.apiEvidence))
    }
}
