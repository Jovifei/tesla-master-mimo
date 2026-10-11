package com.matelink.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.matelink.data.local.entity.TpmsPressureSample
import com.matelink.data.local.entity.TPMS_VERIFIED_COLLECTOR
import com.matelink.data.repository.TpmsHistoryRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TpmsPressureSampleDaoAndroidTest {
    private lateinit var database: StatsDatabase
    private lateinit var dao: com.matelink.data.local.dao.TpmsPressureSampleDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            StatsDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.tpmsPressureSampleDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun compositeKeyUpsertKeepsCarsSeparateAndReplacesSameObservation() = runBlocking {
        dao.upsert(TpmsPressureSample(1, 100L, pressureFl = 2.5))
        dao.upsert(TpmsPressureSample(1, 100L, pressureFl = 2.7))
        dao.upsert(TpmsPressureSample(2, 100L, pressureFl = 3.0))

        assertEquals(2, dao.getInRange(1, 100L, 101L).size + dao.getInRange(2, 100L, 101L).size)
        assertEquals(2.7, dao.getInRange(1, 100L, 101L).single().pressureFl!!, 0.0)
    }

    @Test
    fun nullableAndObservedZeroValuesRoundTrip() = runBlocking {
        dao.upsert(TpmsPressureSample(1, 100L, pressureFl = 0.0, pressureFr = null))

        val saved = dao.getInRange(1, 100L, 101L).single()
        assertEquals(0.0, saved.pressureFl!!, 0.0)
        assertNull(saved.pressureFr)
    }

    @Test
    fun rangeIsInclusiveAtStartAndExclusiveAtEnd() = runBlocking {
        dao.upsertAll(
            listOf(
                TpmsPressureSample(1, 99L, pressureFl = 2.4),
                TpmsPressureSample(1, 100L, pressureFl = 2.5),
                TpmsPressureSample(1, 200L, pressureFl = 2.6),
                TpmsPressureSample(1, 201L, pressureFl = 2.7)
            )
        )

        assertEquals(listOf(100L), dao.getInRange(1, 100L, 200L).map { it.observedAt })
    }

    @Test
    fun pruneDeletesOnlyOlderRowsForRequestedCar() = runBlocking {
        dao.upsertAll(
            listOf(
                TpmsPressureSample(1, 99L, pressureFl = 2.4),
                TpmsPressureSample(1, 100L, pressureFl = 2.5),
                TpmsPressureSample(2, 99L, pressureFl = 3.0)
            )
        )

        assertEquals(1, dao.deleteOlderThan(1, 100L))
        assertEquals(listOf(100L), dao.getInRange(1, 0L, 200L).map { it.observedAt })
        assertEquals(listOf(99L), dao.getInRange(2, 0L, 200L).map { it.observedAt })
    }

    @Test
    fun v2CollectorReceiptAndPendingOldPollEvidenceRemainSeparate() = runBlocking {
        val repo = TpmsHistoryRepository(dao)
        val observedAt = 5_000L
        dao.upsert(TpmsPressureSample(1, observedAt, pressureFl = 5.0, provenance = "provider_observation"))
        repo.saveObservationForHistoryCarId(1, TpmsPressureSample(1, observedAt,
            pressureFr = 2.8, provenance = TPMS_VERIFIED_COLLECTOR))
        assertEquals("provider_observation", dao.getAt(1,observedAt)!!.provenance)
        assertEquals(5.0, dao.getAt(1,observedAt)!!.pressureFl!!, 0.0)
        assertEquals(1,dao.getInRange(1,observedAt,observedAt+1).size)
        assertEquals(0,repo.load30DaySamples(1,observedAt).size)
        val later = observedAt+10
        repo.saveObservationForHistoryCarId(1, TpmsPressureSample(1,later,
            pressureFl = 2.5, provenance = TPMS_VERIFIED_COLLECTOR))
        assertEquals(1,repo.load30DaySamples(1,later).size)
        val evidence = com.matelink.domain.analytics.TpmsPressureChange(
            TirePosition.FL, 2.8, 2.5, later)
        assertTrue(repo.verifiesChange(1,evidence))
        assertEquals(false,repo.verifiesChange(1,evidence.copy(observedAt=observedAt)))
    }

    @Test
    fun repositoryFiltersNonFiniteObservationsAndLoadsSevenAndThirtyDayWindows() = runBlocking {
        val repository = TpmsHistoryRepository(dao)
        val now = 30L * 24 * 60 * 60 * 1_000
        repository.saveObservation(TpmsPressureSample(1, now, pressureFl = 0.0, pressureFr = Double.NaN, provenance = TPMS_VERIFIED_COLLECTOR))
        repository.saveObservation(TpmsPressureSample(1, now - 29L * 24 * 60 * 60 * 1_000, pressureFl = 2.5, provenance = TPMS_VERIFIED_COLLECTOR))
        repository.saveObservation(TpmsPressureSample(1, now - 3L * 24 * 60 * 60 * 1_000, pressureFl = 8.0, provenance = "provider_observation"))

        assertEquals(1, repository.load7DaySamples(1, now).size)
        assertEquals(2, repository.load30DaySamples(1, now).size)
        assertNull(repository.load7DaySamples(1, now).single().pressureFr)
    }
}
