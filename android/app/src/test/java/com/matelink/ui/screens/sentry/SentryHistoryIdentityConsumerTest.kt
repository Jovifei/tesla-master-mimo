package com.matelink.ui.screens.sentry

import com.matelink.data.local.HistoryIdentityUnavailableException
import com.matelink.data.local.SentryState
import com.matelink.data.local.dao.HourlyCount
import com.matelink.data.local.entity.SentryAlertLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SentryHistoryIdentityConsumerTest {
    @Test fun pageReadsWriterLocalNamespaceRatherThanRemoteNumericCarId() = runTest {
        val source = FakeSource()
        source.activeLocalId = -42
        source.alertsFor(-42).value = listOf(SentryAlertLog(1, -42, 1_700_000_000_000, 10))
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val controller = SentryHistoryController(source, scope)
            controller.setCarId(7)
            advanceUntilIdle()

            assertEquals(listOf(-42), source.stateReads)
            assertEquals(listOf(-42), source.alertReads)
            assertEquals(listOf(-42), source.heatmapReads)
            assertEquals(1L, controller.uiState.value.currentSessionAlerts.single().id)
            assertFalse(controller.uiState.value.identityUnavailable)
        } finally { scope.cancel() }
    }

    @Test fun scopeChangeCancelsOldFlowAndLateHeatmapBeforePublishingNewCar() = runTest {
        val source = FakeSource()
        source.activeLocalId = -42
        source.oldHeatmapGate = CompletableDeferred()
        source.alertsFor(-42).value = listOf(SentryAlertLog(1, -42, 1_700_000_000_000, 10))
        source.alertsFor(-43).value = listOf(SentryAlertLog(2, -43, 1_700_000_000_000, 10))
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val controller = SentryHistoryController(source, scope)
            controller.setCarId(7)
            advanceUntilIdle()
            assertTrue(controller.uiState.value.isLoading)

            source.activeLocalId = -43
            source.scopeChanges.emit(Unit)
            advanceUntilIdle()
            assertEquals(2L, controller.uiState.value.currentSessionAlerts.single().id)
            assertEquals(1, controller.uiState.value.heatmapCounts.sum())

            source.oldHeatmapGate!!.complete(Unit)
            advanceUntilIdle()
            assertEquals(2L, controller.uiState.value.currentSessionAlerts.single().id)
            assertEquals(1, controller.uiState.value.heatmapCounts.sum())
            assertFalse(controller.uiState.value.identityUnavailable)
        } finally { scope.cancel() }
    }

    @Test fun unverifiedIdentityClearsOldRowsInsteadOfClaimingNoAlerts() = runTest {
        val source = FakeSource()
        source.activeLocalId = -42
        source.alertsFor(-42).value = listOf(SentryAlertLog(1, -42, 1_700_000_000_000, 10))
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val controller = SentryHistoryController(source, scope)
            controller.setCarId(7)
            advanceUntilIdle()
            assertEquals(1, controller.uiState.value.currentSessionAlerts.size)

            source.failResolution = true
            source.scopeChanges.emit(Unit)
            advanceUntilIdle()

            assertTrue(controller.uiState.value.identityUnavailable)
            assertFalse(controller.uiState.value.isLoading)
            assertTrue(controller.uiState.value.currentSessionAlerts.isEmpty())
            assertTrue(controller.uiState.value.pastAlertsByDay.isEmpty())
            assertEquals(0, controller.uiState.value.heatmapCounts.sum())
        } finally { scope.cancel() }
    }

    @Test fun switchingRemoteCarClearsOldRowsBeforeNewNamespacePublishes() = runTest {
        val source = FakeSource()
        source.alertsFor(-42).value = listOf(SentryAlertLog(1, -42, 1_700_000_000_000, 10))
        source.alertsFor(-43).value = listOf(SentryAlertLog(2, -43, 1_700_000_000_000, 10))
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val controller = SentryHistoryController(source, scope)
            controller.setCarId(7)
            advanceUntilIdle()
            assertEquals(1L, controller.uiState.value.currentSessionAlerts.single().id)

            source.activeLocalId = -43
            controller.setCarId(8)
            assertTrue(controller.uiState.value.isLoading)
            assertTrue(controller.uiState.value.currentSessionAlerts.isEmpty())
            advanceUntilIdle()
            assertEquals(2L, controller.uiState.value.currentSessionAlerts.single().id)
            assertEquals(listOf(-42, -43), source.alertReads)
        } finally { scope.cancel() }
    }

    @Test fun wrongNamespaceRowCannotBePublishedEvenIfReadSourceReturnsIt() = runTest {
        val source = FakeSource()
        source.alertsFor(-42).value = listOf(SentryAlertLog(1, 7, 1_700_000_000_000, 10))
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val controller = SentryHistoryController(source, scope)
            controller.setCarId(7)
            advanceUntilIdle()
            assertTrue(controller.uiState.value.identityUnavailable)
            assertTrue(controller.uiState.value.currentSessionAlerts.isEmpty())
            assertEquals(0, controller.uiState.value.heatmapCounts.sum())
        } finally { scope.cancel() }
    }

    private class FakeSource : SentryHistorySource {
        override val scopeChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var activeLocalId = -42
        var failResolution = false
        var oldHeatmapGate: CompletableDeferred<Unit>? = null
        val stateReads = mutableListOf<Int>()
        val alertReads = mutableListOf<Int>()
        val heatmapReads = mutableListOf<Int>()
        private val alerts = mutableMapOf<Int, MutableStateFlow<List<SentryAlertLog>>>()

        fun alertsFor(localId: Int) = alerts.getOrPut(localId) { MutableStateFlow(emptyList()) }

        override suspend fun resolve(remoteCarId: Int): SentryHistoryIdentity {
            if (failResolution) throw HistoryIdentityUnavailableException()
            val localId = activeLocalId
            return SentryHistoryIdentity(localId) { !failResolution && activeLocalId == localId }
        }

        override suspend fun readState(localId: Int): SentryState {
            stateReads += localId
            return SentryState(sentryActive = true, sessionStartedAt = 10)
        }

        override fun observeAlerts(localId: Int): Flow<List<SentryAlertLog>> {
            alertReads += localId
            return alertsFor(localId)
        }

        override suspend fun countByHour(localId: Int, sinceMillis: Long): List<HourlyCount> {
            heatmapReads += localId
            if (localId == -42) oldHeatmapGate?.let { withContext(NonCancellable) { it.await() } }
            return listOf(HourlyCount(sinceMillis / 3_600_000, if (localId == -42) 99 else 1))
        }
    }
}
