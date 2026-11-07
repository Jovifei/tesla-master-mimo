package com.matelink.ui.screens.trips

import android.app.Application
import androidx.room.Room
import com.matelink.data.local.StatsDatabase
import com.matelink.data.local.entity.ChargeDetailAggregate
import com.matelink.data.local.entity.ChargeSummary
import com.matelink.data.local.entity.DriveSummary
import com.matelink.data.local.entity.SavedTrip
import com.matelink.data.local.entity.SavedTripLeg
import com.matelink.domain.TripDetector
import com.matelink.domain.TripRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class TripDetailReadOnlyFallbackTest {
    private fun drive(id: Int, start: String, end: String) = DriveSummary(
        driveId = id, carId = 701, startDate = start, endDate = end, durationMin = 120,
        startAddress = "A", endAddress = "B", distance = 160.0, speedMax = 100, speedAvg = 80,
        powerMax = 30, powerMin = -10, startBatteryLevel = 80, endBatteryLevel = 50,
        outsideTempAvg = null, insideTempAvg = null, energyConsumed = null, efficiency = null)

    private val charge = ChargeSummary(
        chargeId = 9, carId = 701, startDate = "2026-10-14T12:10:00Z",
        endDate = "2026-10-14T12:40:00Z", durationMin = 30,
        address = "B", latitude = 1.0, longitude = 2.0, energyAdded = 20.0,
        energyUsed = null, cost = null, startBatteryLevel = 50, endBatteryLevel = 80,
        outsideTempAvg = null, odometer = 100.0)

    private suspend fun seed(db: StatsDatabase) {
        db.driveSummaryDao().upsertAll(listOf(
            drive(1, "2026-10-14T10:00:00Z", "2026-10-14T12:00:00Z"),
            drive(2, "2026-10-14T12:50:00Z", "2026-10-14T15:00:00Z")))
        db.chargeSummaryDao().upsert(charge)
        db.aggregateDao().upsertChargeAggregate(ChargeDetailAggregate(
            chargeId = 9, carId = 701, schemaVersion = 1, computedAt = 1L,
            isFastCharger = true, fastChargerBrand = null, connectorType = null,
            maxChargerPower = null, maxChargerVoltage = null, maxChargerCurrent = null,
            chargerPhases = 0, maxOutsideTemp = null, minOutsideTemp = null,
            chargePointCount = 1))
    }

    private fun repository(db: StatsDatabase) = TripRepository(
        db.driveSummaryDao(), db.chargeSummaryDao(), db.aggregateDao(), db.savedTripDao(),
        TripDetector())

    @Test fun cacheMissReadsNoSavedTripWithoutAutoPersistingAnEligibleNewOne() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), StatsDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            seed(db)
            assertEquals(1, TripDetector().detectTrips(db.driveSummaryDao().getAllChronological(701),
                db.aggregateDao().getDcChargeSummaries(701)).size)
            val visible = repository(db).getSavedTripsReadOnly(701)
            assertEquals(1, visible.size)
            assertEquals(listOf(1, 2), visible.single().drives.map { it.driveId })
            assertTrue(db.savedTripDao().getAllWithLegs(701).isEmpty())
        } finally { db.close() }
    }

    @Test fun cacheMissRehydratesSavedRowsWithoutDeletingDuplicateOwnerTrips() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), StatsDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            seed(db)
            repeat(2) { index ->
                db.savedTripDao().insertTripWithLegs(SavedTrip(carId = 701,
                    name = "owner-$index", source = SavedTrip.SOURCE_USER_EDITED,
                    createdAt = 1L, updatedAt = 1L)) { tripId ->
                    listOf(SavedTripLeg(tripId, 0, SavedTripLeg.TYPE_DRIVE, 1),
                        SavedTripLeg(tripId, 1, SavedTripLeg.TYPE_CHARGE, 9),
                        SavedTripLeg(tripId, 2, SavedTripLeg.TYPE_DRIVE, 2))
                }
            }
            val trips = repository(db).getSavedTripsReadOnly(701)
            assertEquals(2, trips.size)
            assertEquals(setOf("owner-0", "owner-1"), trips.map { it.name }.toSet())
            assertEquals(2, db.savedTripDao().getAllWithLegs(701).size)
        } finally { db.close() }
    }
}
