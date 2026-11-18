package com.matelink.data.local

import android.app.Application
import androidx.room.Room
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class SocHistoryStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private fun open() = Room.databaseBuilder(
        RuntimeEnvironment.getApplication(), StatsDatabase::class.java,
        File(temp.root, "soc-history.db").absolutePath
    ).allowMainThreadQueries().build()

    @After fun noop() = Unit

    @Test fun hourBucketKeepsNewestObservationAndPrunesThirtyDayRetention() = runBlocking {
        val db = open()
        try {
            val store = SocHistoryStore(db.socSampleDao())
            val base = 1_800_000_000_000L
            assertTrue(store.record(-2, 60, base, "status"))
            assertTrue(store.record(-2, 61, base + 10 * 60_000L, "drive_end"))
            val sameHour = db.socSampleDao().observeSince(-2, base - 1).first()
            assertEquals(1, sameHour.size)
            assertEquals(61, sameHour.single().socPercent)

            assertTrue(store.record(-2, 62, base + 2 * 3_600_000L, "status"))
            assertEquals(2, db.socSampleDao().observeSince(-2, base - 1).first().size)

            // A newer observation beyond retention removes samples older than 30 days.
            assertTrue(store.record(-2, 63, base + 40L * 86_400_000L, "status"))
            val retained = db.socSampleDao().observeSince(-2, 0).first()
            assertEquals(1, retained.size)
            assertEquals(63, retained.single().socPercent)
        } finally {
            db.close()
        }
    }
}