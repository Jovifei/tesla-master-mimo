package com.matelink.data.repository

import android.app.Application
import androidx.room.Room
import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.DriveData
import com.matelink.data.local.StatsDatabase
import com.matelink.data.sync.toSyncSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking
import java.io.File

/** Existing Room DAOs, not a stand-in reducer: A -> B -> A may not destroy any raw receipt. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,application=Application::class,sdk=[28])
class HistorySourceConflictArchiveRoomTest {
    @get:Rule val temp = TemporaryFolder()
    private val aStart = "2026-10-20T10:00:00Z"
    private val aEnd = "2026-10-20T10:39:00Z"

    private fun open() = Room.databaseBuilder(RuntimeEnvironment.getApplication(),
        StatsDatabase::class.java, File(temp.root, "conflict.db").absolutePath)
        .allowMainThreadQueries().build()

    private fun charge(instance: String) = ChargeData(9, startDate=aStart,endDate=aEnd,
        chargeEnergyAdded=if(instance=="A") 0.0 else 24.02,
        source="teslamate_archive",sourceInstanceId=instance,sourceRecordId="charge-$instance",
        qualityState="observed")

    private fun drive(instance: String) = DriveData(8, startDate=aStart,endDate=aEnd,
        energyConsumedNet=if(instance=="A") -0.5 else 1.2,
        source="teslamate_archive", sourceInstanceId=instance,sourceRecordId="drive-$instance",
        qualityState="observed",lengthUnit="km")

    @Test fun quarantinedRawReceiptIsNotSilentlyOverwrittenByIdenticalNumericId() = runBlocking {
        var db = open()
        try {
            val car = -71
            val oldCharge = charge("A").copy(qualityState = "quarantined",
                qualityReason = "synthetic_provenance", chargeEnergyAdded = null)
            val oldDrive = drive("A").copy(qualityState = "quarantined",
                qualityReason = "synthetic_provenance", energyConsumedNet = null)
            db.chargeSummaryDao().upsert(oldCharge.toSyncSummary(car)!!)
            db.driveSummaryDao().upsert(oldDrive.toSyncSummary(car)!!)
            val beforeCharge = db.chargeSummaryDao().getAnyForPersistence(car, 9)!!.apiEvidence
            val beforeDrive = db.driveSummaryDao().getAnyForPersistence(car, 8)!!.apiEvidence
            assertTrue(beforeCharge != null && beforeDrive != null)
            db.chargeSummaryDao().upsertPreservingEvidence(listOf(charge("B").toSyncSummary(car)!!))
            db.driveSummaryDao().upsertPreservingEvidence(listOf(drive("B").toSyncSummary(car)!!))
            db.close(); db = open()
            db.openHelper.readableDatabase.query(
                "SELECT kind,headerJson FROM history_detail_receipts WHERE state='archived_source_conflict'"
            ).use { rows ->
                val actual = mutableMapOf<String,String>()
                while (rows.moveToNext()) actual[rows.getString(0)] = rows.getString(1)
                assertEquals(beforeCharge,actual["archived_charge_source"])
                assertEquals(beforeDrive,actual["archived_drive_source"])
            }
            assertTrue(db.chargeSummaryDao().get(car, 9) != null)
            assertTrue(db.driveSummaryDao().get(car, 8) != null)
        } finally { db.close() }
    }

    @Test fun sourceConflictArchivesChargeAndDriveBeforeOverwritingThenReopensBoth() = runBlocking {
        var db = open()
        try {
            val car=-71
            val firstCharge=charge("A").toSyncSummary(car)!!
            val firstDrive=drive("A").toSyncSummary(car)!!
            db.chargeSummaryDao().upsertPreservingEvidence(listOf(firstCharge))
            db.driveSummaryDao().upsertPreservingEvidence(listOf(firstDrive))
            val rawChargeA=db.chargeSummaryDao().get(car,9)!!.apiEvidence!!
            val rawDriveA=db.driveSummaryDao().get(car,8)!!.apiEvidence!!
            db.chargeSummaryDao().upsertPreservingEvidence(listOf(charge("B").toSyncSummary(car)!!))
            db.driveSummaryDao().upsertPreservingEvidence(listOf(drive("B").toSyncSummary(car)!!))
            val rawChargeB=db.chargeSummaryDao().get(car,9)!!.apiEvidence!!
            val rawDriveB=db.driveSummaryDao().get(car,8)!!.apiEvidence!!
            assertFalse(rawChargeA==rawChargeB)
            assertFalse(rawDriveA==rawDriveB)
            db.chargeSummaryDao().upsertPreservingEvidence(listOf(firstCharge))
            db.driveSummaryDao().upsertPreservingEvidence(listOf(firstDrive))
            db.close(); db=open()
            val rows=mutableMapOf<String,Set<String>>()
            db.openHelper.writableDatabase.query(
                "SELECT kind,headerJson,localHistoryCarId FROM history_detail_receipts WHERE kind LIKE 'archived_%'"
            ).use { cursor ->
                val byKind=mutableMapOf<String,MutableSet<String>>()
                while(cursor.moveToNext()) {
                    assertEquals(car,cursor.getInt(2))
                    byKind.getOrPut(cursor.getString(0)) { mutableSetOf() }.add(cursor.getString(1))
                }
                for ((kind,value) in byKind) rows[kind]=value.toSet()
            }
            assertEquals(setOf(rawChargeA,rawChargeB),rows["archived_charge_source"])
            assertEquals(setOf(rawDriveA,rawDriveB),rows["archived_drive_source"])
            assertEquals(rawChargeA,db.chargeSummaryDao().get(car,9)!!.apiEvidence)
            assertEquals(rawDriveA,db.driveSummaryDao().get(car,8)!!.apiEvidence)
            assertTrue(db.chargeSummaryDao().getAllForCar(-72).isEmpty())
            assertTrue(db.driveSummaryDao().getAllChronological(-72).isEmpty())
        } finally { db.close() }
    }
}
