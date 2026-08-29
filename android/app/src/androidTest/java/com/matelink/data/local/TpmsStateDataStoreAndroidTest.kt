package com.matelink.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.matelink.data.api.models.TpmsDetails
import com.matelink.data.repository.TpmsStateChangeClaimResult
import com.matelink.data.repository.TpmsStateRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TpmsStateDataStoreAndroidTest {
    private lateinit var store: TpmsStateDataStore

    @Before
    fun setUp() {
        store = TpmsStateDataStore(InstrumentationRegistry.getInstrumentation().targetContext)
        runBlocking { store.clearAllStates() }
    }

    @After
    fun tearDown() {
        runBlocking { store.clearAllStates() }
    }

    @Test
    fun leaseTakeoverCreatesNewTokenAndStaleOwnerCannotCommitOrRelease() = runBlocking {
        val first = (store.claimStateChange(1, warningState(), now = 1_000L) as
            TpmsStateDataStore.TpmsStateDataStoreClaimResult.Claimed).claim
        val second = (store.claimStateChange(1, warningState(), now = 301_001L) as
            TpmsStateDataStore.TpmsStateDataStoreClaimResult.Claimed).claim

        assertNotEquals(first.token, second.token)
        store.commitStateChange(1, first, now = 301_002L)
        store.releaseStateChange(1, first)
        assertEquals(TpmsState(), store.getState(1))

        store.commitStateChange(1, second, now = 301_003L)
        assertEquals(warningState().copy(lastCheckedAt = 301_003L), store.getState(1))
    }

    @Test
    fun repositoryDistinguishesNoTransitionClaimedAndInFlight() = runBlocking {
        val repository = TpmsStateRepository(store)
        val details = completeWarningDetails()

        val claimed = repository.claimStateChange(1, details, now = 2_000L)
        val inFlight = repository.claimStateChange(1, details, now = 2_001L)

        assertTrue(claimed is TpmsStateChangeClaimResult.Claimed)
        assertEquals(TpmsStateChangeClaimResult.InFlight, inFlight)

        val claimedValue = (claimed as TpmsStateChangeClaimResult.Claimed).claim
        repository.commitStateChange(1, claimedValue)
        assertEquals(TpmsStateChangeClaimResult.NoTransition, repository.claimStateChange(1, details, now = 2_002L))
    }

    @Test
    fun repositoryReleaseAfterNotificationFailureAllowsRetryWithNewToken() = runBlocking {
        val repository = TpmsStateRepository(store)
        val details = completeWarningDetails()
        val first = (repository.claimStateChange(1, details, now = 3_000L) as
            TpmsStateChangeClaimResult.Claimed).claim

        repository.releaseStateChange(1, first)
        val retry = (repository.claimStateChange(1, details, now = 3_001L) as
            TpmsStateChangeClaimResult.Claimed).claim

        assertNotEquals(first.token, retry.token)
    }

    private fun warningState() = TpmsState(warningFl = true)

    private fun completeWarningDetails() = TpmsDetails(
        warningFl = true,
        warningFr = false,
        warningRl = false,
        warningRr = false
    )
}
