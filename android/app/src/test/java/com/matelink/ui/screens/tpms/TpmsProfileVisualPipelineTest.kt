package com.matelink.ui.screens.tpms

import com.matelink.data.local.TpmsAlertProfile
import com.matelink.data.local.entity.DriveSummary
import com.matelink.data.local.entity.TpmsPressureSample
import com.matelink.domain.analytics.TpmsTrendAnalyzer
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TpmsProfileVisualPipelineTest {
    private val profile = TpmsAlertProfile(2.9, 2.6, 3.4, true)
    private fun source(read: suspend (Int) -> TpmsTrendHistorySnapshot) = object : TpmsTrendHistorySource {
        override suspend fun load7DaySamples(carId: Int, now: Long) = emptyList<TpmsPressureSample>()
        override suspend fun load30DaySamples(carId: Int, now: Long) = emptyList<TpmsPressureSample>()
        override suspend fun loadDrives(carId: Int) = emptyList<DriveSummary>()
        override suspend fun loadSnapshot(carId: Int, window: TpmsTrendWindow, now: Long) = read(carId)
    }
    @Test fun publishesProfileOnlyAfterTheSameSnapshotIdentityCheck() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val published = mutableListOf<TpmsAlertProfile?>()
        val order = mutableListOf<String>()
        try {
            val input = source { TpmsTrendHistorySnapshot(emptyList(), emptyList(), profile) { order += "identity"; true } }
            val controller = TpmsTrendRefreshController(input, TpmsTrendAnalyzer(), scope,
                { _, _ -> order += "history" }, { error("unexpected failure") },
                { published += it; order += "profile" })
            controller.refresh(7, TpmsTrendWindow.THIRTY)
            assertEquals(listOf(profile), published)
            assertEquals(listOf("identity", "profile", "history"), order)
        } finally { scope.cancel() }
    }
    @Test fun changedIdentityNeverPublishesItsThresholdsOrHistory() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        var failures = 0
        try {
            val input = source { TpmsTrendHistorySnapshot(emptyList(), emptyList(), profile) { false } }
            TpmsTrendRefreshController(input, TpmsTrendAnalyzer(), scope,
                { _, _ -> error("foreign history") }, { failures++ }, { error("foreign profile") })
                .refresh(7, TpmsTrendWindow.THIRTY)
            assertEquals(1, failures)
        } finally { scope.cancel() }
    }
    @Test fun lateProfileCannotReplaceAnotherCarsCurrentRule() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val gate = CompletableDeferred<Boolean>()
        val published = mutableListOf<TpmsAlertProfile?>()
        val newer = profile.copy(lowBar = 2.8)
        try {
            val input = source { car ->
                if (car == 1) TpmsTrendHistorySnapshot(emptyList(), emptyList(), profile) { withContext(NonCancellable) { gate.await() } }
                else TpmsTrendHistorySnapshot(emptyList(), emptyList(), newer)
            }
            val controller = TpmsTrendRefreshController(input, TpmsTrendAnalyzer(), scope, { _, _ -> }, { error("unexpected failure") }, { published += it })
            controller.refresh(1, TpmsTrendWindow.THIRTY)
            controller.refresh(2, TpmsTrendWindow.THIRTY)
            gate.complete(true)
            assertEquals(listOf(newer), published)
        } finally { scope.cancel() }
    }
    @Test fun reentrantRefreshStillOwnsTheNewJobAndCancelsItOnInvalidation() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        var cancelled = false
        var success = 0
        lateinit var controller: TpmsTrendRefreshController
        try {
            val input = source { car ->
                if (car == 2) try { awaitCancellation() } finally { cancelled = true }
                TpmsTrendHistorySnapshot(emptyList(), emptyList(), profile)
            }
            controller = TpmsTrendRefreshController(input, TpmsTrendAnalyzer(), scope,
                { _, _ -> success++; if (success == 1) controller.refresh(2, TpmsTrendWindow.THIRTY) },
                { error("unexpected failure") })
            controller.refresh(1, TpmsTrendWindow.THIRTY)
            controller.invalidate()
            assertTrue("The latest job was lost after synchronous completion", cancelled)
            assertEquals(1, success)
        } finally { scope.cancel() }
    }
}
