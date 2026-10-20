package com.matelink.data.repository

import android.app.Application
import androidx.room.Room
import com.matelink.data.api.models.*
import com.matelink.data.local.*
import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.analytics.*
import com.squareup.moshi.Moshi
import androidx.lifecycle.viewModelScope
import com.matelink.ui.screens.drives.DriveDetailViewModel
import com.matelink.data.api.OpenMeteoApi
import com.matelink.domain.TripRepository
import com.matelink.domain.TripDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Go historySessionMap's source_record_id is an actual wire alias, not an inferred numeric ID. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class HistorySourceRecordWireAliasTest {
    @get:Rule val temp = TemporaryFolder()
    private val moshi = Moshi.Builder().build()
    private val start = "2026-10-10T00:00:00Z"
    private val end = "2026-10-10T00:30:00Z"
    private val context = VehicleContext(7, cloudOriginVehicleStableIdentity("synthetic", "https://synthetic.test", "car"),
        -71, HistoryConnectionSource.CLOUD, "cloud")
    private fun wire(idKey: String, aliases: String) = """{"$idKey":17,"start_date":"$start","end_date":"$end","source":"telemetry_mqtt","source_instance_id":"archive-A",$aliases}"""
    private fun charge(aliases: String) = moshi.adapter(ChargeData::class.java).fromJson(wire("charge_id", aliases))!!
    private fun detail(aliases: String) = moshi.adapter(ChargeDetail::class.java).fromJson(wire("charge_id", aliases))!!
    private fun open() = Room.databaseBuilder(RuntimeEnvironment.getApplication(), StatsDatabase::class.java,
        File(temp.root, "wire-alias.db").absolutePath).allowMainThreadQueries().build()

    @Test fun moshiPreservesGoRecordAliasOnAllFourListAndDetailModels() {
        val models = listOf(
            charge("\"source_record_id\":\"record-A\""), detail("\"source_record_id\":\"record-A\""),
            moshi.adapter(DriveData::class.java).fromJson(wire("drive_id", "\"source_record_id\":\"record-A\""))!!,
            moshi.adapter(DriveDetail::class.java).fromJson(wire("drive_id", "\"source_record_id\":\"record-A\""))!!)
        for (model in models) {
            val originalAlias = model.javaClass.methods.firstOrNull { it.name == "getSourceRecordId" }?.invoke(model)
            assertEquals("Raw Go alias must survive ${model.javaClass.simpleName}", "record-A", originalAlias)
        }
    }

    @Test fun realDaoRejectsConflictingAliasesInsteadOfTreatingThemAsSameOrUnknown() = runBlocking {
        val db = open()
        try {
            val saved = charge("\"source_id\":\"record-A\"")
            db.chargeSummaryDao().upsert(saved.toSyncSummary(-71)!!)
            val ambiguous = detail("\"source_id\":\"record-A\",\"source_record_id\":\"record-B\"")
            assertFalse(db.chargeSummaryDao().mergeDetail(-71, ambiguous) {})
        } finally { db.close() }
    }

    @Test fun feeSessionIdentitySeparatesRealGoAliasesAndConflictsCannotUseAnEdit() {
        val a = charge("\"source_record_id\":\"record-A\"")
        val b = charge("\"source_record_id\":\"record-B\"")
        assertNotEquals(chargeCostSessionKey(a), chargeCostSessionKey(b))
        val legacySpelling = charge("\"source_id\":\"record-A\"")
        assertEquals(chargeCostSessionKey(a), chargeCostSessionKey(legacySpelling))
        val conflict = charge("\"source_id\":\"record-A\",\"source_record_id\":\"record-B\"")
        assertNull(projectEffectiveChargeCost(conflict.copy(cost = 10.0, currencyCode = "CNY"),
            -71, null, "CNY").cost)
    }

    @Test fun actualRoomPointReceiptUsesCanonicalAliasAndReopensWithoutLosingRawIdentity() = runBlocking {
        var db = open()
        try {
            val saved = charge("\"source_record_id\":\"record-A\"")
            db.chargeSummaryDao().upsert(saved.toSyncSummary(-71)!!)
            val key = db.chargeSummaryDao().get(-71, 17)!!.verifiedDetailIdentity(context)!!
            assertEquals("record-A", key.sourceIdKey)
            val incoming = detail("\"source_id\":\"record-A\"").copy(chargeEnergyAdded = 0.0,
                chargePoints = listOf(ChargePoint(date = start, chargeEnergyAdded = 0.0)))
            val repo = HistoryDetailRepository(db.historyDetailDao())
            assertNotNull(persistVerifiedChargeDetail(db, repo, context, incoming) {})
            db.close(); db = open()
            val recoveredKey = db.chargeSummaryDao().get(-71, 17)!!.verifiedDetailIdentity(context)!!
            assertEquals(key.identityKey, recoveredKey.identityKey)
            val recovered = HistoryDetailRepository(db.historyDetailDao()).readCharge(recoveredKey)
            assertEquals("record-A", recovered.receipt!!.sourceIdKey)
            assertTrue(recovered.receipt!!.sourceIdVerified)
            assertEquals(0.0, recovered.detail!!.chargePoints!!.single().chargeEnergyAdded!!, 0.0)
            val bad = detail("\"source_id\":\"record-A\",\"source_record_id\":\"record-B\"")
                .copy(chargePoints = incoming.chargePoints)
            assertTrue(HistoryDetailRepository(db.historyDetailDao()).saveCharge(key, bad) is HistoryDetailWriteResult.Rejected)
        } finally { db.close() }
    }
    @Test fun canonicalAliasConflictIsDistinctFromLegacyUnknownAndBothRawFieldsRoundTrip() {
        val legacy = charge("\"source_id\":null")
        assertNull(legacy.sourceRecordIdentity.value)
        assertFalse(legacy.sourceRecordIdentity.conflicted)
        val same = charge("\"source_id\":\"record-A\",\"source_record_id\":\"record-A\"")
        assertEquals("record-A", same.sourceRecordIdentity.value)
        assertFalse(same.sourceRecordIdentity.conflicted)
        val conflict = charge("\"source_id\":\"record-A\",\"source_record_id\":\"record-B\"")
        assertTrue(conflict.sourceRecordIdentity.conflicted)
        val reopened = moshi.adapter(ChargeData::class.java).fromJson(moshi.adapter(ChargeData::class.java).toJson(conflict))!!
        assertEquals("record-A", reopened.sourceId)
        assertEquals("record-B", reopened.sourceRecordId)
        assertTrue(reopened.sourceRecordIdentity.conflicted)
        assertThrows(IllegalArgumentException::class.java) { chargeCostSessionKey(conflict) }
    }

    @Test fun typedManualZeroUsesCanonicalWireReceiptAcrossRoomReopenAndRejectsConflict() = runBlocking {
        val fixture = com.matelink.data.sync.HistoryResourceFixture(File(temp.root, "alias-manual.db"))
        try {
            val a = charge("\"source_record_id\":\"record-A\"")
            var store = ChargeCostOverrideStore(fixture.db.chargeCostOverrideDao(), fixture.settings)
            assertTrue(store.saveBound(-71, a, 0.0, "CNY", 0L))
            fixture.reopen()
            store = ChargeCostOverrideStore(fixture.db.chargeCostOverrideDao(), fixture.settings)
            val records = store.getAllRecords()
            assertEquals("record-A", records.single().sourceRecordId)
            val equivalent = charge("\"source_id\":\"record-A\"")
            val manual = findChargeCostOverride(records, -71, equivalent, "CNY")
            assertEquals(0.0, projectEffectiveChargeCost(equivalent, -71, manual, "CNY").cost!!, 0.0)
            assertNull(findChargeCostOverride(records, -71, charge("\"source_record_id\":\"record-B\""), "CNY"))
            val conflict = charge("\"source_id\":\"record-A\",\"source_record_id\":\"record-B\"")
            assertFalse(store.saveBound(-71, conflict, 99.0, "CNY", 0L))
            assertEquals(records, store.getAllRecords())
        } finally { fixture.close() }
    }

    @Test fun drivePointCacheMatchesEitherWireAliasAndRejectsAmbiguousHeaderAfterReopen() = runBlocking {
        var db = open()
        try {
            val saved = moshi.adapter(DriveData::class.java).fromJson(wire("drive_id", "\"source_record_id\":\"record-A\""))!!
                .copy(lengthUnit = "km")
            db.driveSummaryDao().upsert(saved.toSyncSummary(-71)!!)
            val identity = db.driveSummaryDao().get(-71, 17)!!.verifiedDetailIdentity(context)!!
            assertEquals("record-A", identity.sourceIdKey)
            val incoming = moshi.adapter(DriveDetail::class.java).fromJson(wire("drive_id", "\"source_id\":\"record-A\""))!!
                .copy(lengthUnit = "km", positions = listOf(DrivePosition(date = start, power = 0.0)))
            assertNotNull(persistVerifiedDriveDetail(db, HistoryDetailRepository(db.historyDetailDao()), context, incoming) {})
            db.close(); db = open()
            val restored = db.driveSummaryDao().get(-71, 17)!!.toAnalysisDriveData().asCachedDetail()
            assertEquals("record-A", restored.sourceRecordIdentity.value)
            val recovered = HistoryDetailRepository(db.historyDetailDao()).readDrive(identity)
            assertTrue(recovered.receipt!!.sourceIdVerified)
            assertEquals(0.0, recovered.detail!!.positions!!.single().power!!, 0.0)
            val conflict = incoming.copy(sourceRecordId = "record-B")
            assertNull(persistVerifiedDriveDetail(db, HistoryDetailRepository(db.historyDetailDao()), context, conflict) {})
            assertNotNull(HistoryDetailRepository(db.historyDetailDao()).readDrive(identity).detail)
        } finally { db.close() }
    }

    @Test fun whitespaceAliasesShareCanonicalIdentityWithoutChangingRawSpellings() {
        val decoded = charge("\"source_id\":\" record-A \",\"source_record_id\":\"record-A\"")
        assertFalse(decoded.sourceRecordIdentity.conflicted)
        assertEquals("record-A", decoded.sourceRecordIdentity.value)
        assertEquals(" record-A ", decoded.sourceId)
        assertEquals(chargeCostSessionKey(charge("\"source_id\":\"record-A\"")), chargeCostSessionKey(decoded))
    }

    @Test fun oldTypedManualReceiptWithWhitespaceKeepsItsQualifiedZeroAfterCanonicalAliasAndRoomReopen() = runBlocking {
        var db = open()
        try {
            val current = charge("\"source_record_id\":\"record-A\"")
            val old = com.matelink.data.local.entity.ChargeCostOverride(
                carId = -71, chargeId = 17, manualTotalAmount = 0.0,
                sessionKey = chargeCostSessionKey(current) + ":CNY", currencyCode = "CNY",
                sourceKind = current.source, sourceInstanceId = current.sourceInstanceId,
                sourceRecordId = " record-A ", startInstant = start, endInstant = end)
            assertTrue(db.chargeCostOverrideDao().saveIfVersion(old, 0L))
            db.close(); db = open()
            val records = db.chargeCostOverrideDao().getAll()
            val manual = findChargeCostOverride(records, -71, current, "CNY")
            assertNotNull(manual)
            val projected = projectEffectiveChargeCost(current, -71, manual, "CNY")
            assertEquals("Canonical spelling must retain the old qualified manual zero", 0.0, projected.cost)
            assertEquals(ChargeCostSource.MANUAL, projected.source)
            assertEquals(" record-A ", db.chargeCostOverrideDao().getAll().single().sourceRecordId)
            val other = charge("\"source_record_id\":\"record-B\"")
            assertNull(findChargeCostOverride(records, -71, other, "CNY"))
            assertNull(projectEffectiveChargeCost(other, -71, manual, "CNY").cost)
        } finally { db.close() }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun actualOfflineDriveVmRetainsSummaryIdentityWithoutPointsAndUsesCompletePointHeaderWhenPresent() = runBlocking {
        val fixture = com.matelink.data.sync.HistoryResourceFixture(File(temp.root, "alias-drive-vm.db"))
        Dispatchers.setMain(UnconfinedTestDispatcher())
        var vm: DriveDetailViewModel? = null
        try {
            fixture.initialize()
            val vehicle = fixture.vehicleContexts.cachedContextForRemote(1)!!
            val summary = moshi.adapter(DriveData::class.java).fromJson(wire("drive_id", "\"source_record_id\":\"record-A\""))!!
            fixture.db.driveSummaryDao().upsert(summary.toSyncSummary(vehicle.localHistoryCarId)!!)
            fixture.status = { 503 }
            val noWeather = java.lang.reflect.Proxy.newProxyInstance(OpenMeteoApi::class.java.classLoader,
                arrayOf(OpenMeteoApi::class.java)) { _, _, _ -> error("No weather for distance-less synthetic receipt") } as OpenMeteoApi
            val viewModel = DriveDetailViewModel(fixture.apiRepo, fixture.db.driveSummaryDao(),
                UnifiedHistoryRepository(fixture.apiRepo, fixture.vehicleContexts, fixture.db.driveSummaryDao(), fixture.db.chargeSummaryDao()),
                WeatherRepository(noWeather), TripRepository(fixture.db.driveSummaryDao(), fixture.db.chargeSummaryDao(),
                    fixture.db.aggregateDao(), fixture.db.savedTripDao(), TripDetector()),
                fixture.geocodingRepository, fixture.db, HistoryDetailRepository(fixture.db.historyDetailDao()))
            vm = viewModel
            viewModel.loadDriveDetail(1, 17)
            val withoutPoints = withTimeout(10_000) { viewModel.uiState.first { !it.isLoading } }
            assertEquals("archive-A", withoutPoints.driveDetail!!.sourceInstanceId)
            assertNull(withoutPoints.driveDetail!!.sourceId)
            assertEquals("record-A", withoutPoints.driveDetail!!.sourceRecordId)
            assertNull(withoutPoints.driveDetail!!.positions)

            // The point header uses the other spelling. Keep that pair whole, rather than coalescing aliases.
            val identity = fixture.db.driveSummaryDao().get(vehicle.localHistoryCarId, 17)!!.verifiedDetailIdentity(vehicle)!!
            val pointDetail = summary.asCachedDetail().copy(sourceId = "record-A", sourceRecordId = null,
                positions = listOf(DrivePosition(date = start, power = 0.0)))
            assertTrue(HistoryDetailRepository(fixture.db.historyDetailDao()).saveDrive(identity, pointDetail) is HistoryDetailWriteResult.Stored)
            viewModel.loadDriveDetail(1, 17)
            val withPoints = withTimeout(10_000) { viewModel.uiState.first { !it.isLoading } }
            assertEquals("record-A", withPoints.driveDetail!!.sourceId)
            assertNull(withPoints.driveDetail!!.sourceRecordId)
            assertEquals(1, withPoints.driveDetail!!.positions!!.size)
        } finally { vm?.viewModelScope?.cancel(); fixture.close(); Dispatchers.resetMain() }
    }

}
