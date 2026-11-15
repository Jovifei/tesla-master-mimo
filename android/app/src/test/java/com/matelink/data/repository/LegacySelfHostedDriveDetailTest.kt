package com.matelink.data.repository

import android.app.Application
import androidx.room.Room
import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveDetail
import com.matelink.data.api.models.DrivePosition
import com.matelink.data.local.HistoryConnectionSource
import com.matelink.data.local.StatsDatabase
import com.matelink.data.local.VehicleContext
import com.matelink.data.local.cloudOriginVehicleStableIdentity
import com.matelink.data.local.selfHostedVehicleStableIdentity
import com.matelink.data.local.entity.HistoryDetailReceiptState
import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.analytics.toRawAnalysisDriveData
import com.matelink.ui.screens.drives.projectDriveCurves
import com.squareup.moshi.Moshi
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class LegacySelfHostedDriveDetailTest {
    @get:Rule val temp = TemporaryFolder()
    private val start = "2026-10-30T12:41:30.123456Z"
    private val end = Instant.parse(start).plusMillis(1722L * 250).toString()
    private val server = "https://adapter.example"
    private val context = VehicleContext(7, selfHostedVehicleStableIdentity(server, 7),
        -71, HistoryConnectionSource.SELF_HOSTED, server)
    private val moshi = Moshi.Builder().build()

    private fun raw() = moshi.adapter(DriveData::class.java).fromJson(
        """{"drive_id":41,"start_date":"$start","end_date":"$end"}"""
    )!!.copy(lengthUnit = "km")

    private fun detail(): DriveDetail {
        val original = DriveDetail(41, startDate = start, endDate = end,
            positions = (0..1722).map { index ->
                DrivePosition(date = Instant.parse(start).plusMillis(index * 250L).toString(),
                    speed = if (index == 0) 0.0 else 40.0,
                    power = if (index == 1722) -3.0 else index % 12 * 1.0,
                    batteryLevel = 80 - index / 600, elevation = 10 + index % 5)
            })
        val adapter = moshi.adapter(DriveDetail::class.java)
        return adapter.fromJson(adapter.toJson(original))!!.copy(lengthUnit = "km")
    }

    private fun open() = Room.databaseBuilder(RuntimeEnvironment.getApplication(), StatsDatabase::class.java,
        File(temp.root, "legacy-detail.db").absolutePath).allowMainThreadQueries().build()

    @Test fun oldSelfHostedWireReceiptsPersist1723PointsAcrossOfflineReopen() = runBlocking {
        var db = open()
        try {
            val row = raw().toSyncSummary(context.localHistoryCarId)!!
            db.driveSummaryDao().upsert(row)
            val identity = db.driveSummaryDao().get(context.localHistoryCarId, 41)!!.verifiedDetailIdentity(context)
            assertNotNull("genuine self-hosted list receipt without source must identify its exact detail", identity)
            val original = detail()
            val persisted = persistVerifiedDriveDetail(db, HistoryDetailRepository(db.historyDetailDao()), context, original) {}
            assertNotNull(persisted)
            val receipt = persisted!!.receipt!!
            assertEquals(1723, receipt.pointCount)
            assertEquals(HistoryDetailReceiptState.RESPONSE_COMPLETE_TOTAL_UNVERIFIED, receipt.state)
            assertFalse(receipt.providerTotalVerified)
            assertFalse(receipt.sourceInstanceVerified)
            assertFalse(receipt.sourceIdVerified)
            assertEquals("", receipt.sourceKey)
            assertEquals("", receipt.sourceInstanceKey)
            assertEquals("", receipt.sourceIdKey)
            assertNull(persisted.summary.toRawAnalysisDriveData().source)
            val sameReceipt = persisted.summary.verifiedDetailIdentity(context)!!
            assertEquals(identity!!.identityKey, sameReceipt.identityKey)
            db.close()
            db = open()
            val offlineSummary = db.driveSummaryDao().get(context.localHistoryCarId, 41)!!
            val offlineIdentity = offlineSummary.verifiedDetailIdentity(context)!!
            assertEquals(HistoryDetailIdentity.Mode.LEGACY_SELF_HOSTED, offlineIdentity.mode)
            assertEquals(sameReceipt.mode, offlineIdentity.mode)
            assertEquals(sameReceipt.identityKey, offlineIdentity.identityKey)
            val reopened = HistoryDetailRepository(db.historyDetailDao()).readDrive(offlineIdentity)
            assertEquals(original, reopened.detail)
            assertNull(reopened.detail!!.source)
            assertNull(reopened.detail.sourceInstanceId)
            assertNull(reopened.detail.sourceId)
            assertNull(reopened.detail.sourceRecordId)
            val curves = projectDriveCurves(reopened.detail)
            assertTrue(curves.hasChartData)
            assertEquals(1723, curves.speed.validCount)
            assertEquals(1723, curves.power.validCount)
            assertEquals(0f, curves.speed.values.first(), 0f)
            assertEquals(-3f, curves.power.values.last(), 0f)
        } finally { db.close() }
    }

    @Test fun rawEvidenceMustDecodeAndExactlyMatchRowIdAndInstants() {
        val row = raw().toSyncSummary(context.localHistoryCarId)!!
        val invalid = listOf(row.copy(apiEvidence = null), row.copy(apiEvidence = ""),
            row.copy(apiEvidence = "{broken"), row.copy(driveId = 42),
            row.copy(startDate = Instant.parse(start).plusNanos(1).toString()),
            row.copy(endDate = Instant.parse(end).minusNanos(1).toString()),
            row.copy(apiEvidence = row.apiEvidence!!.replace(end, start)),
            row.copy(carId = -72))
        invalid.forEach { assertNull(it.verifiedDetailIdentity(context)) }
    }

    @Test fun anyDetailSourceMetadataIncludingExplicitBlankOrConflictIsRejected() = runBlocking {
        val db = open()
        try {
            val row = raw().toSyncSummary(context.localHistoryCarId)!!
            db.driveSummaryDao().upsert(row)
            val identity = row.verifiedDetailIdentity(context)
            assertNotNull(identity)
            val original = detail()
            val invalid = listOf(original.copy(source = "named"), original.copy(source = ""),
                original.copy(sourceInstanceId = "instance"), original.copy(sourceInstanceId = " "),
                original.copy(sourceId = "record"), original.copy(sourceId = ""),
                original.copy(sourceRecordId = "record"), original.copy(sourceRecordId = ""),
                original.copy(sourceId = "old", sourceRecordId = "other"), original.copy(driveId = 42),
                original.copy(startDate = Instant.parse(start).plusNanos(1).toString()),
                original.copy(endDate = Instant.parse(end).minusNanos(1).toString()))
            invalid.forEach {
                assertFalse(identity!!.matches(it.driveId, it.source, it.sourceInstanceId, it.startDate, it.endDate, it.sourceId, it.sourceRecordId))
                assertNull(persistVerifiedDriveDetail(db, HistoryDetailRepository(db.historyDetailDao()), context, it) {})
                assertEquals(row.apiEvidence, db.driveSummaryDao().get(context.localHistoryCarId, 41)!!.apiEvidence)
            }
            assertNull(HistoryDetailRepository(db.historyDetailDao()).readDrive(identity!!).detail)
        } finally { db.close() }
    }

    @Test fun singleSidedListSourceAndAliasesDoNotBecomeUnknownLegacyIdentity() {
        val base = raw()
        val invalid = listOf(base.copy(source = ""), base.copy(sourceInstanceId = "instance"),
            base.copy(sourceId = "record"), base.copy(sourceRecordId = "record"),
            base.copy(sourceId = "old", sourceRecordId = "other"))
        invalid.forEach { assertNull(it.toSyncSummary(context.localHistoryCarId)!!.verifiedDetailIdentity(context)) }
        val named = base.copy(source = "named").toSyncSummary(context.localHistoryCarId)!!.verifiedDetailIdentity(context)!!
        assertFalse(named.matches(41, null, null, start, end))
    }

    @Test fun cloudChargeUnverifiedContextAndPublicCreateStayStrict() {
        val row = raw().toSyncSummary(context.localHistoryCarId)!!
        val invalid = listOf(context.copy(connectionSource = HistoryConnectionSource.CLOUD),
            context.copy(stableIdentity = ""), context.copy(serverIdentity = ""),
            context.copy(remoteApiCarId = 8), context.copy(serverIdentity = "https://other.example"),
            context.copy(stableIdentity = cloudOriginVehicleStableIdentity("another-account", server, "vehicle-a")),
            context.copy(localHistoryCarId = 0), context.copy(localHistoryCarId = Int.MIN_VALUE))
        invalid.forEach { assertNull(row.copy(carId = it.localHistoryCarId).verifiedDetailIdentity(it)) }
        assertThrows(IllegalArgumentException::class.java) {
            HistoryDetailIdentity.create(context, HistoryDetailIdentity.DRIVE, 41, null, null, start, end)
        }
        assertNull(ChargeData(41, startDate = start, endDate = end).toSyncSummary(context.localHistoryCarId)!!.verifiedDetailIdentity(context))
    }

    @Test fun offlineCacheNeverCrossesServerVehicleOrLocalNamespace() = runBlocking {
        val db = open()
        try {
            val row = raw().toSyncSummary(context.localHistoryCarId)!!
            val identity = row.verifiedDetailIdentity(context)
            assertNotNull(identity)
            assertTrue(HistoryDetailRepository(db.historyDetailDao()).saveDrive(identity!!, detail()) is HistoryDetailWriteResult.Stored)
            val anotherServer = "https://other.example"
            val others = listOf(context.copy(serverIdentity = anotherServer,
                stableIdentity = selfHostedVehicleStableIdentity(anotherServer, 7)),
                context.copy(remoteApiCarId = 8, stableIdentity = selfHostedVehicleStableIdentity(server, 8)),
                context.copy(localHistoryCarId = -72))
            others.forEach {
                val different = row.copy(carId = it.localHistoryCarId).verifiedDetailIdentity(it)!!
                assertNotEquals(identity.identityKey, different.identityKey)
                assertNull(HistoryDetailRepository(db.historyDetailDao()).readDrive(different).detail)
            }
        } finally { db.close() }
    }

    @Test fun namedSourceKeysStayByteCompatibleAndLegacyHasSeparateDomain() {
        val named = HistoryDetailIdentity.create(context, HistoryDetailIdentity.DRIVE, 41,
            " named ", " instance ", start, end, sourceId = "record")
        fun digest(vararg parts: String) = sha256Framed(parts.first().toByteArray(), parts.drop(1).map { it.toByteArray() })
        val scope = digest(context.stableIdentity, context.connectionSource.name, context.serverIdentity)
        val prefix = arrayOf(context.localHistoryCarId.toString(), scope, HistoryDetailIdentity.DRIVE, "41")
        assertEquals(digest(*prefix, "named", "instance", "record", Instant.parse(start).toString(), Instant.parse(end).toString()), named.identityKey)
        val legacy = raw().toSyncSummary(context.localHistoryCarId)!!.verifiedDetailIdentity(context)
        assertNotNull(legacy)
        assertNotEquals(digest(*prefix, "", "", "", Instant.parse(start).toString(), Instant.parse(end).toString()), legacy!!.identityKey)
        assertNotEquals(named.identityKey, legacy.identityKey)
    }
}
