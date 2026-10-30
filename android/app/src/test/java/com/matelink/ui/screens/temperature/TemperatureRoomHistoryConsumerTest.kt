package com.matelink.ui.screens.temperature

import android.app.Application
import androidx.room.Room
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DrivesResponse
import com.matelink.data.api.models.receivedDrives
import com.squareup.moshi.Moshi
import com.matelink.data.local.StatsDatabase
import com.matelink.data.sync.toSyncSummary
import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking

/** Actual Room raw receipts -> qualified drive-average temperature consumer, including offline reopen. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE, application=Application::class, sdk=[28])
class TemperatureRoomHistoryConsumerTest {
    @get:Rule val temp = TemporaryFolder()
    private val now = Instant.parse("2026-10-24T05:00:00Z")
    private fun open() = Room.databaseBuilder(RuntimeEnvironment.getApplication(),
        StatsDatabase::class.java, File(temp.root, "temperature-receipts.db").absolutePath)
        .allowMainThreadQueries().build()

    @Test fun fahrenheitReceiptKeepsItsOwnUnitAcrossRoomReopenAndProjectsCanonicalC() = runBlocking {
        val receipt = Moshi.Builder().build().adapter(DrivesResponse::class.java).fromJson("""{
            "data":{"units":{"unit_of_length":"km","unit_of_temperature":"F"},"drives":[{
                "drive_id":71,"source":"teslamate_archive","quality_state":"observed",
                "start_date":"2026-10-23T01:00:00Z","end_date":"2026-10-23T01:20:00Z",
                "inside_temp_avg":86.0,"outside_temp_avg":68.0}]}}
        """)!!.data!!.receivedDrives().single()
        assertEquals(86.0, receipt.insideTempAvg!!, 0.0)
        assertEquals("F", receipt.temperatureUnit)
        var db = open()
        try {
            db.driveSummaryDao().upsert(receipt.toSyncSummary(-71)!!)
            db.close()
            db = open()
            val point = observedTemperaturePoints(db.driveSummaryDao().getAllChronological(-71),
                TemperatureTrendWindow.SEVEN, now).single()
            assertEquals(30.0, point.insideTemp!!, 1e-9)
            assertEquals(20.0, point.outsideTemp!!, 1e-9)
        } finally { db.close() }
    }

    @Test fun explicitUnknownTemperatureMetadataCannotBorrowLegacySourceCelsius() {
        val raw = DriveData(72, startDate="2026-10-23T01:00:00Z", endDate="2026-10-23T01:20:00Z",
            source="teslamate_archive", outsideTempAvg=68.0, temperatureUnit="")
        val points = observedTemperaturePoints(listOf(raw.toSyncSummary(-71)!!), TemperatureTrendWindow.SEVEN, now)
        assertTrue(points.isEmpty())
    }

    @Test fun savedArchiveAverageRetainsSourceTimeZeroAndNegativeAcrossReopen() = runBlocking {
        var db = open()
        try {
            val real = DriveData(7,
                startDate="2026-10-23T01:00:00Z", endDate="2026-10-23T01:20:00Z",
                source="teslamate_archive", qualityState=null, qualityReason=null,
                insideTempAvg=0.0, outsideTempAvg=-3.5)
            val invalid = real.copy(driveId=8, source="local_import", insideTempAvg=99.0)
            db.driveSummaryDao().upsertAll(listOf(real, invalid).map { it.toSyncSummary(-71)!! })
            val point = observedTemperaturePoints(db.driveSummaryDao().getAllChronological(-71),
                TemperatureTrendWindow.SEVEN, now).single()
            assertEquals(0.0, point.insideTemp!!, 0.0)
            assertEquals(-3.5, point.outsideTemp!!, 0.0)
            assertEquals("teslamate_archive", point.source)
            assertEquals("drive_start_trip_average", point.timeBasis)
            assertEquals(Instant.parse(real.startDate).toEpochMilli(), point.timestampMs)
            db.close()
            db = open()
            val restored = observedTemperaturePoints(db.driveSummaryDao().getAllChronological(-71),
                TemperatureTrendWindow.SEVEN, now)
            assertEquals(listOf(point), restored)
        } finally { db.close() }
    }

    @Test fun otherAccountAndOutOfWindowRowsNeverBorrowHistory() = runBlocking {
        val db = open()
        try {
            val inside = DriveData(20,
                startDate="2026-10-23T01:00:00Z", endDate="2026-10-23T01:20:00Z",
                source="teslamate_archive", insideTempAvg=24.0)
            db.driveSummaryDao().upsert(inside.toSyncSummary(-71)!!)
            assertTrue(observedTemperaturePoints(db.driveSummaryDao().getAllChronological(-72),
                TemperatureTrendWindow.SEVEN, now).isEmpty())
            assertTrue(observedTemperaturePoints(db.driveSummaryDao().getAllChronological(-71),
                TemperatureTrendWindow.SEVEN, Instant.parse("2026-11-24T05:00:00Z")).isEmpty())
        } finally { db.close() }
    }
}
