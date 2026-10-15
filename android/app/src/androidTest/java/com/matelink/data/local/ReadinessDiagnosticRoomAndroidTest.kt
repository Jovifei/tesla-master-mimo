package com.matelink.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.matelink.data.local.entity.*
import com.matelink.data.sync.deliverPendingCompletions
import com.matelink.data.sync.resolvePendingCompletion
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReadinessDiagnosticRoomAndroidTest {
    @Test fun qualifiedScalarEvidenceSurvivesCloseReopenAndRejectsOtherVehiclesAndLegacyRows() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "readiness-evidence-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, StatsDatabase::class.java, name).build()
        var db = open()
        try {
            db.tpmsPressureSampleDao().upsertAll(listOf(
                TpmsPressureSample(1, 10L, pressureFl = 2.8, provenance = TPMS_VERIFIED_COLLECTOR),
                TpmsPressureSample(1, 20L, pressureFl = 3.0),
                TpmsPressureSample(2, 30L, pressureFl = 3.0, provenance = TPMS_VERIFIED_COLLECTOR),
                TpmsPressureSample(1, 200L, pressureFl = 3.0, provenance = TPMS_VERIFIED_COLLECTOR)))
            val drive = DriveSummary(1, 1, "2026-10-15T00:00:00Z", "2026-10-15T00:10:00Z", 10,
                "", "", 10.0, 60, 30, 20, -5, 80, 78, null, null, 1.0, 100.0,
                energySource = "api", apiEvidence = "{}", qualityState = "observed")
            db.driveSummaryDao().upsertAll(listOf(drive, drive.copy(driveId = 2, qualityState = "quarantined"),
                drive.copy(driveId = 3, energyConsumed = null), drive.copy(driveId = 4, carId = 2),
                drive.copy(driveId = 5, energyConsumed = 0.0), drive.copy(driveId = 6, energyConsumed = -0.5),
                drive.copy(driveId = 7, energySource = "unavailable")))
            db.driveSummaryDao().upsert(drive.copy(energyConsumed = 1.2)) // Same-ID correction does not duplicate the count.
            val charge = ChargeSummary(1, 1, "2026-10-15T00:00:00Z", "2026-10-15T00:10:00Z", 10,
                "", 0.0, 0.0, 3.0, null, null, 70, 74, null, 100.0,
                apiEvidence = "{}", qualityState = "observed")
            db.chargeSummaryDao().upsertAll(listOf(charge, charge.copy(chargeId = 2, qualityState = "incomplete"),
                charge.copy(chargeId = 3, carId = 2), charge.copy(chargeId = 4, energyAdded = 0.0),
                charge.copy(chargeId = 5, energyAdded = -1.0)))
            db.close()
            db = open()
            assertEquals(10L, db.tpmsPressureSampleDao().latestDiagnosticReceipt(1, 100L))
            assertNull(db.tpmsPressureSampleDao().latestDiagnosticReceipt(3, 100L))
            assertEquals(3, db.driveSummaryDao().countDiagnosticEnergy(1))
            assertEquals(2, db.chargeSummaryDao().countDiagnosticEnergy(1))
            assertEquals(5, db.driveSummaryDao().countDiagnosticHistory(1))
            assertEquals(4, db.chargeSummaryDao().countDiagnosticHistory(1))
            assertEquals(0, db.driveSummaryDao().countDiagnosticEnergy(3))

            // Durable real codec round trip, then the actual system consumer with a Room read.
            val event = CompletedChargeEvent(1, 7, 1, drive.endDate, kind = "drive", source = "api",
                startDate = drive.startDate, scopeKey = "scope-a")
            val file = java.io.File(context.cacheDir, "readiness-journal-${UUID.randomUUID()}.json")
            try {
                file.writeText(CompletionJournalCodec.encode(CompletionState(0, 0L, listOf(event))))
                val reloaded = CompletionJournalCodec.decode(file.readText(), Long.MAX_VALUE).events
                var acknowledgements = 0
                var permission = false
                var scopeCurrent = true
                suspend fun deliver() = deliverPendingCompletions(reloaded, { scopeCurrent }, { pending ->
                    resolvePendingCompletion(pending, 1, 7, "scope-a", { scopeCurrent },
                        { db.driveSummaryDao().get(1, pending.chargeId) }, { it.qualityState == "observed" })
                }, { _, _ -> permission }, { acknowledgements++ })
                deliver()
                assertEquals(0, acknowledgements)
                permission = true
                deliver()
                assertEquals(1, acknowledgements)
                scopeCurrent = false
                deliver()
                assertEquals(1, acknowledgements)
                assertNull(resolvePendingCompletion(event, 1, 8, "scope-b", { true },
                    { db.driveSummaryDao().get(1, 1) }, { true }))
            } finally { file.delete() }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
