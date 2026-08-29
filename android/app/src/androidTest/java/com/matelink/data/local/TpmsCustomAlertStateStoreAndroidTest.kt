package com.matelink.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TpmsCustomAlertStateStoreAndroidTest {
    private lateinit var store: TpmsCustomAlertStateStore

    @Before
    fun setUp() {
        store = TpmsCustomAlertStateStore(InstrumentationRegistry.getInstrumentation().targetContext)
        runBlocking { store.resetForProfile(11, "test-profile-${System.nanoTime()}") }
    }

    @After
    fun tearDown() {
        runBlocking { store.resetForProfile(11, "cleanup-${System.nanoTime()}") }
    }

    @Test
    fun finiteStateAndClaimTokenRoundTripThroughDataStore() = runBlocking {
        store.saveStates(
            11,
            mapOf(
                TirePosition.FL to TpmsCustomWheelState(
                    state = TpmsCustomPressureState.LOW,
                    pending = TpmsCustomPendingAlert(2.5, 2.6),
                    claim = TpmsCustomPendingAlert(2.5, 2.6),
                    claimToken = "owner-1",
                    claimStartedAt = 10L
                )
            )
        )

        val saved = store.getStates(11).getValue(TirePosition.FL)
        val pending = requireNotNull(saved.pending)
        assertEquals(2.5, pending.observedPressureBar, 0.0)
        assertEquals(2.6, pending.thresholdBar, 0.0)
        assertEquals("owner-1", saved.claimToken)
        assertEquals(10L, saved.claimStartedAt)
    }

    @Test
    fun leaseTakeoverCreatesNewTokenAndStaleOwnerCannotCommitOrRelease() = runBlocking {
        val observation = lowObservation()
        val first = store.claimAlerts(11, "profile", mapOf(TirePosition.FL to observation), defer = false, now = 1_000L).single()
        val second = store.claimAlerts(11, "profile", mapOf(TirePosition.FL to observation), defer = false, now = 301_001L).single()

        assertNotEquals(first.token, second.token)
        store.commitClaim(11, first)
        store.releaseClaim(11, first)
        val afterStaleOwner = store.getStates(11).getValue(TirePosition.FL)
        assertNotNull(afterStaleOwner.claim)
        assertEquals(second.token, afterStaleOwner.claimToken)

        store.commitClaim(11, second)
        val committed = store.getStates(11).getValue(TirePosition.FL)
        assertNull(committed.pending)
        assertNull(committed.claim)
        assertTrue(committed.claimToken == null)
    }

    @Test
    fun releaseAfterNotificationFailureAllowsRetryWithNewToken() = runBlocking {
        val first = store.claimAlerts(11, "retry-profile", mapOf(TirePosition.FL to lowObservation()), defer = false, now = 4_000L).single()

        store.releaseClaim(11, first)
        val retry = store.claimAlerts(11, "retry-profile", mapOf(TirePosition.FL to lowObservation()), defer = false, now = 4_001L).single()

        assertNotEquals(first.token, retry.token)
    }

    private fun lowObservation() = TpmsCustomWheelObservation(
        observed = true,
        state = TpmsCustomPressureState.LOW,
        observedPressureBar = 2.5,
        thresholdBar = 2.6
    )
}
