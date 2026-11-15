package com.matelink.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic package database: no user account or device database is opened. */
@RunWith(AndroidJUnit4::class)
class BatteryTrendEvidenceMigrationTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), StatsDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test fun v23To24AddsOnlyEvidenceTableAndPreservesExistingUserData() {
        val name = "battery-trend-v23-to-v24"
        helper.createDatabase(name, 23).apply {
            execSQL("""
                INSERT INTO drives_summary (driveId,carId,startDate,endDate,durationMin,startAddress,endAddress,
                    distance,speedMax,speedAvg,powerMax,powerMin,startBatteryLevel,endBatteryLevel,energyCoverageSeconds,
                    energyCoverageRatio,apiEvidence,qualityState)
                VALUES (7,-71,'2026-01-01T00:00:00Z','2026-01-01T01:00:00Z',60,'A','B',10,50,30,0,0,80,70,0,0,'original-drive-evidence','observed')
            """.trimIndent())
            execSQL("""
                INSERT INTO charges_summary (chargeId,carId,startDate,endDate,durationMin,address,latitude,longitude,
                    energyAdded,startBatteryLevel,endBatteryLevel,odometer,apiEvidence,qualityState)
                VALUES (8,-71,'2026-01-02T00:00:00Z','2026-01-02T01:00:00Z',60,'',0,0,20,20,80,0,'original-charge-evidence','observed')
            """.trimIndent())
            execSQL("""
                INSERT INTO charge_cost_overrides (carId,chargeId,manualTotalAmount,sessionKey,currencyCode,version,deleted)
                VALUES (-71,8,27.38,'manual-session','CNY',4,0)
            """.trimIndent())
            execSQL("""
                INSERT INTO history_detail_receipts (receiptId,identityKey,localHistoryCarId,scopeFingerprint,kind,recordId,
                    sourceKey,sourceInstanceKey,sourceInstanceVerified,sourceIdKey,sourceIdVerified,startInstant,endInstant,
                    localRevision,state,responseComplete,providerTotalVerified,pointCount,encodedByteCount,codecVersion,receivedAtEpochMs)
                VALUES ('receipt','identity',-71,'scope','drive',7,'archive','source',1,'record',1,
                    '2026-01-01T00:00:00Z','2026-01-01T01:00:00Z',1,'complete',1,1,1,3,1,1)
            """.trimIndent())
            execSQL("INSERT INTO history_detail_chunks(receiptId,chunkIndex,firstPointIndex,pointCount,sha256,payload) VALUES('receipt',0,0,1,'hash',X'010203')")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 24, true, StatsDatabase.MIGRATION_23_24)
        try {
            db.query("SELECT apiEvidence FROM drives_summary WHERE carId=-71 AND driveId=7").use {
                assertTrue(it.moveToFirst()); assertEquals("original-drive-evidence", it.getString(0))
            }
            db.query("SELECT apiEvidence FROM charges_summary WHERE carId=-71 AND chargeId=8").use {
                assertTrue(it.moveToFirst()); assertEquals("original-charge-evidence", it.getString(0))
            }
            db.query("SELECT manualTotalAmount,currencyCode,version FROM charge_cost_overrides WHERE carId=-71 AND chargeId=8").use {
                assertTrue(it.moveToFirst()); assertEquals(27.38, it.getDouble(0), 0.0)
                assertEquals("CNY", it.getString(1)); assertEquals(4, it.getInt(2))
            }
            db.query("SELECT hex(payload) FROM history_detail_chunks WHERE receiptId='receipt'").use {
                assertTrue(it.moveToFirst()); assertEquals("010203", it.getString(0))
            }
            db.query("SELECT COUNT(*) FROM battery_trend_evidence").use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
        } finally { db.close() }
    }
}
