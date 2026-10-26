package com.matelink.ui.screens.trips

import android.app.Application
import androidx.room.Room
import com.matelink.data.local.StatsDatabase
import com.matelink.data.local.entity.ChargeSummary
import com.matelink.data.local.entity.DriveSummary
import com.matelink.data.local.entity.SavedTrip
import com.matelink.data.local.entity.SavedTripLeg
import com.matelink.domain.LegRef
import com.matelink.domain.TripDetector
import com.matelink.domain.TripRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class TripSavedRecordProtectionTest {
    private val car = -701
    private fun drive() = DriveSummary(
        driveId = 1, carId = car, startDate = "2026-10-14T10:00:00Z",
        endDate = "2026-10-14T12:00:00Z", durationMin = 120,
        startAddress = "A", endAddress = "B", distance = 160.0,
        speedMax = 100, speedAvg = 80, powerMax = 30, powerMin = -10,
        startBatteryLevel = 80, endBatteryLevel = 50, outsideTempAvg = null,
        insideTempAvg = null, energyConsumed = null, efficiency = null)

    private fun charge(id: Int) = ChargeSummary(
        chargeId = id, carId = car, startDate = "2026-10-14T12:10:00Z",
        endDate = "2026-10-14T12:40:00Z", durationMin = 30, address = "B",
        latitude = 1.0, longitude = 2.0, energyAdded = 20.0, energyUsed = null,
        cost = null, startBatteryLevel = 50, endBatteryLevel = 80,
        outsideTempAvg = null, odometer = 100.0)

    private fun repository(db: StatsDatabase) = TripRepository(
        db.driveSummaryDao(), db.chargeSummaryDao(), db.aggregateDao(),
        db.savedTripDao(), TripDetector())

    @Test fun browsingTripsPreservesOwnerNamesAndDifferentChargeMembership() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
            StatsDatabase::class.java).allowMainThreadQueries().build()
        try {
            db.driveSummaryDao().upsert(drive())
            db.chargeSummaryDao().upsertAll(listOf(charge(9), charge(10)))
            repeat(2) { index ->
                db.savedTripDao().insertTripWithLegs(SavedTrip(carId = car,
                    name = "owner-$index", source = SavedTrip.SOURCE_USER_EDITED,
                    createdAt = 1, updatedAt = 1)) { id ->
                    listOf(SavedTripLeg(id, 0, SavedTripLeg.TYPE_DRIVE, 1),
                        SavedTripLeg(id, 1, SavedTripLeg.TYPE_CHARGE, 9 + index))
                }
            }
            val before = db.savedTripDao().getAllWithLegs(car)
            assertEquals(2, repository(db).getTrips(car).size)
            assertEquals(before, db.savedTripDao().getAllWithLegs(car))
            assertEquals(setOf("owner-0", "owner-1"), before.map { it.trip.name }.toSet())
        } finally { db.close() }
    }

    @Test fun creationWithAnUnresolvableDriveDoesNotPersistAnOrphanTrip() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
            StatsDatabase::class.java).allowMainThreadQueries().build()
        try {
            val result = repository(db).createTrip(car,
                listOf(LegRef(SavedTripLeg.TYPE_DRIVE, 404)), "keep draft")
            assertNull(result)
            assertEquals(emptyList<Any>(), db.savedTripDao().getAllWithLegs(car))
        } finally { db.close() }
    }

    @Test fun selectingADifferentNamedTripNeverBorrowsTheFirstRowsEditIdentity() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
            StatsDatabase::class.java).allowMainThreadQueries().build()
        try {
            db.driveSummaryDao().upsert(drive())
            val ids = (0..1).map { index ->
                db.savedTripDao().insertTripWithLegs(SavedTrip(carId = car,
                    name = "owner-$index", source = SavedTrip.SOURCE_USER_EDITED,
                    createdAt = 1, updatedAt = 1)) { id ->
                    listOf(SavedTripLeg(id, 0, SavedTripLeg.TYPE_DRIVE, 1))
                }
            }
            val repository = repository(db)
            val selected = repository.getSavedTripsReadOnly(car).single { it.name == "owner-1" }
            assertEquals(ids[1], repository.findSavedTripId(car, selected))
        } finally { db.close() }
    }

    @Test fun creationRejectsALegClaimedSinceTheDraftWasOpened() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
            StatsDatabase::class.java).allowMainThreadQueries().build()
        try {
            db.driveSummaryDao().upsert(drive())
            db.savedTripDao().insertTripWithLegs(SavedTrip(carId = car,
                name = "existing owner", source = SavedTrip.SOURCE_USER_EDITED,
                createdAt = 1, updatedAt = 1)) { id ->
                listOf(SavedTripLeg(id, 0, SavedTripLeg.TYPE_DRIVE, 1))
            }
            assertNull(repository(db).createTrip(car,
                listOf(LegRef(SavedTripLeg.TYPE_DRIVE, 1)), "late draft"))
            assertEquals(1, db.savedTripDao().getAllWithLegs(car).size)
        } finally { db.close() }
    }
}
