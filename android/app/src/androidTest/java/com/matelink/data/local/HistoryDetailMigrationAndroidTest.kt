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

/** Isolated package DBs: no owner account or on-device MateLink database is opened. */
@RunWith(AndroidJUnit4::class)
class HistoryDetailMigrationAndroidTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StatsDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test fun v22To23AddsEmptyDetailTablesWithoutChangingExistingChargeOrCost() {
        val name = "detail-v22-to-v23"
        helper.createDatabase(name, 22).apply {
            execSQL("""
                INSERT INTO charges_summary (
                    chargeId, carId, startDate, endDate, durationMin, address, latitude, longitude,
                    energyAdded, startBatteryLevel, endBatteryLevel, odometer
                ) VALUES (64, -71, '2026-10-20T10:00:00Z', '2026-10-20T10:39:00Z',
                    39, '', 0, 0, 24.02, 56, 97, 0)
            """.trimIndent())
            execSQL("""
                INSERT INTO charge_cost_overrides (
                    carId, chargeId, manualTotalAmount, sessionKey, currencyCode,
                    sourceKind, sourceInstanceId, startInstant, endInstant, version
                ) VALUES (-71, 64, 27.38, 'scoped-a', 'CNY', 'teslamate_archive',
                    'archive-A', '2026-10-20T10:00:00Z', '2026-10-20T10:39:00Z', 4)
            """.trimIndent())
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 23, true, StatsDatabase.MIGRATION_22_23)
        try {
            db.query("SELECT energyAdded FROM charges_summary WHERE carId=-71 AND chargeId=64").use {
                assertTrue(it.moveToFirst()); assertEquals(24.02, it.getDouble(0), 0.0)
            }
            db.query("SELECT manualTotalAmount,currencyCode,sourceInstanceId,version FROM charge_cost_overrides WHERE carId=-71 AND chargeId=64").use {
                assertTrue(it.moveToFirst())
                assertEquals(27.38, it.getDouble(0), 0.0)
                assertEquals("CNY", it.getString(1))
                assertEquals("archive-A", it.getString(2))
                assertEquals(4, it.getInt(3))
            }
            db.query("SELECT COUNT(*) FROM history_detail_receipts").use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM history_detail_chunks").use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
        } finally { db.close() }
    }

    @Test fun v21To23KeepsLegacyManualAmountUnqualifiedAndCreatesNoPoints() {
        val name = "detail-v21-to-v23"
        helper.createDatabase(name, 21).apply {
            execSQL("INSERT INTO charge_cost_overrides(carId,chargeId,manualTotalAmount) VALUES(-72,65,12.34)")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 23, true,
            StatsDatabase.MIGRATION_21_22, StatsDatabase.MIGRATION_22_23)
        try {
            db.query("SELECT manualTotalAmount,sessionKey,currencyCode FROM charge_cost_overrides WHERE carId=-72 AND chargeId=65").use {
                assertTrue(it.moveToFirst())
                assertEquals(12.34, it.getDouble(0), 0.0)
                assertEquals("legacy", it.getString(1))
                assertTrue(it.isNull(2))
            }
            db.query("SELECT COUNT(*) FROM history_detail_receipts").use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
        } finally { db.close() }
    }
}
