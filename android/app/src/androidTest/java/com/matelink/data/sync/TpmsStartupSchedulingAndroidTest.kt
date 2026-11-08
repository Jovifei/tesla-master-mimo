package com.matelink.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.matelink.MateLinkApplication
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TpmsStartupSchedulingAndroidTest {
    @Test fun applicationStartupKeepsOneTpmsWorker() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(context.applicationContext is MateLinkApplication)
        val workManager = WorkManager.getInstance(context)
        fun activeWork(): List<WorkInfo> = workManager
            .getWorkInfosForUniqueWork(TpmsPressureWorker.WORK_NAME)
            .get(10, TimeUnit.SECONDS)
            .filterNot { it.state.isFinished }

        val startup = activeWork()
        assertEquals("Application startup must schedule TPMS without waiting for a reboot", 1, startup.size)
        assertTrue(startup.single().tags.contains(TpmsPressureWorker.TAG))

        // A second scheduling request must not create another polling stream.
        TpmsPressureWorker.schedulePeriodicWork(context)
        assertEquals(1, activeWork().size)
    }
}
