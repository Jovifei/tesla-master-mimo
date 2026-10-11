package com.matelink.ui.screens.tpms

import com.matelink.data.api.models.DriveData
import com.matelink.data.local.entity.DriveSummary
import com.matelink.data.local.entity.TpmsPressureSample
import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.analytics.TpmsTrendAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import com.matelink.data.local.HistoryIdentityUnavailableException
import org.junit.Assert.*
import org.junit.Test

/** Exercise the actual asynchronous consumer boundary, rather than analyzer-only filtering. */
class TpmsTrendIdentityRecoveryTest {
    @Test fun supersededRefreshCannotPublishAfterSuspendedFinalIdentityCheckResumes() = runBlocking {
        val gate = CompletableDeferred<Boolean>()
        var calls = 0
        val source = object : TpmsTrendHistorySource {
            override suspend fun load7DaySamples(carId: Int, now: Long) = emptyList<TpmsPressureSample>()
            override suspend fun load30DaySamples(carId: Int, now: Long) = emptyList<TpmsPressureSample>()
            override suspend fun loadDrives(carId: Int) = emptyList<DriveSummary>()
            override suspend fun loadSnapshot(carId: Int, window: TpmsTrendWindow, now: Long): TpmsTrendHistorySnapshot {
                calls++
                return if (calls == 1) TpmsTrendHistorySnapshot(emptyList(), emptyList()) {
                    withContext(NonCancellable) { gate.await() }
                } else TpmsTrendHistorySnapshot(emptyList(), emptyList())
            }
        }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val published = mutableListOf<TpmsTrendWindow>()
        try {
            val controller = TpmsTrendRefreshController(source, TpmsTrendAnalyzer(), scope,
                { window, _ -> published += window }, { error("No current failure expected") })
            controller.refresh(7, TpmsTrendWindow.SEVEN)
            controller.refresh(7, TpmsTrendWindow.THIRTY)
            gate.complete(true)
            assertEquals(listOf(TpmsTrendWindow.THIRTY), published)
        } finally { scope.cancel() }
    }

    @Test fun identityLossDuringSnapshotReadEndsCurrentLoadingAsUnavailable() {
        val source = object : TpmsTrendHistorySource {
            override suspend fun load7DaySamples(carId: Int, now: Long) = emptyList<TpmsPressureSample>()
            override suspend fun load30DaySamples(carId: Int, now: Long) = emptyList<TpmsPressureSample>()
            override suspend fun loadDrives(carId: Int) = emptyList<DriveSummary>()
            override suspend fun loadSnapshot(carId: Int, window: TpmsTrendWindow, now: Long): TpmsTrendHistorySnapshot =
                throw HistoryIdentityUnavailableException()
        }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        var failures = 0
        try {
            TpmsTrendRefreshController(source, TpmsTrendAnalyzer(), scope,
                { _, _ -> error("Unavailable identity must not publish") }, { failures++ })
                .refresh(7, TpmsTrendWindow.SEVEN)
            assertEquals(1, failures)
        } finally { scope.cancel() }
    }

    @Test fun scopeBecomingStaleImmediatelyBeforePublicationCannotPublishEvenWithSameRowIds() {
        val source = object : TpmsTrendHistorySource {
            override suspend fun load7DaySamples(carId: Int, now: Long) = emptyList<TpmsPressureSample>()
            override suspend fun load30DaySamples(carId: Int, now: Long) = emptyList<TpmsPressureSample>()
            override suspend fun loadDrives(carId: Int) = emptyList<DriveSummary>()
            override suspend fun loadSnapshot(carId: Int, window: TpmsTrendWindow, now: Long) =
                TpmsTrendHistorySnapshot(listOf(TpmsPressureSample(-7, now, pressureFl = 2.7)), emptyList()) { false }
        }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        var published = 0
        var failures = 0
        try {
            TpmsTrendRefreshController(source, TpmsTrendAnalyzer(), scope,
                { _, _ -> published++ }, { failures++ }).refresh(7, TpmsTrendWindow.SEVEN)
            assertEquals(0, published)
            assertEquals(1, failures)
        } finally { scope.cancel() }
    }

    @Test fun sameScopedSnapshotPublishesOnce() {
        val source = object : TpmsTrendHistorySource {
            override suspend fun load7DaySamples(carId: Int, now: Long) = listOf(TpmsPressureSample(-7, now, pressureFl = 2.7))
            override suspend fun load30DaySamples(carId: Int, now: Long) = load7DaySamples(carId, now)
            override suspend fun loadDrives(carId: Int) = emptyList<DriveSummary>()
        }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        var published = 0
        var failures = 0
        try {
            TpmsTrendRefreshController(source, TpmsTrendAnalyzer(), scope,
                { _, _ -> published++ }, { failures++ }).refresh(7, TpmsTrendWindow.SEVEN)
            assertEquals(1, published)
            assertEquals(0, failures)
        } finally { scope.cancel() }
    }

    @Test fun identitySwitchBetweenSampleAndDriveReadsCannotPublishMixedScopeTrend() {
        var driveReads = 0
        val source = object : TpmsTrendHistorySource {
            override suspend fun load7DaySamples(carId: Int, now: Long) = listOf(
                TpmsPressureSample(-7, now - 1000, pressureFl = 2.7)
            )
            override suspend fun load30DaySamples(carId: Int, now: Long) = load7DaySamples(carId, now)
            override suspend fun loadDrives(carId: Int): List<DriveSummary> {
                driveReads++
                // Same numeric remote car, but the account/origin changed while Room suspended.
                return listOf(DriveData(12, startDate = "2026-10-07T00:00:00Z",
                    endDate = "2026-10-07T01:00:00Z", source = "telemetry_mqtt",
                    qualityState = "observed").toSyncSummary(-8)!!)
            }
        }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        var published = 0
        var failures = 0
        try {
            TpmsTrendRefreshController(source, TpmsTrendAnalyzer(), scope,
                { _, _ -> published++ }, { failures++ })
                .refresh(7, TpmsTrendWindow.SEVEN, 1791338400000L)
            assertEquals(1, driveReads)
            assertEquals("A trend must not combine pressure and drive context from different identities", 0, published)
            assertEquals("The current request must terminate with an unavailable result", 1, failures)
        } finally { scope.cancel() }
    }
}
