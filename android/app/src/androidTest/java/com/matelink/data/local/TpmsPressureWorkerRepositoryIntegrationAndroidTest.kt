package com.matelink.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.matelink.data.api.models.TpmsDetails
import com.matelink.data.repository.TpmsStateChangeClaimResult
import com.matelink.data.repository.TpmsStateRepository
import com.matelink.data.sync.processSuccessfulTpmsStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TpmsPressureWorkerRepositoryIntegrationAndroidTest {
    private val carId = 812_345
    private val details = TpmsDetails(
        pressureFl = 2.5,
        warningFl = true,
        warningFr = false,
        warningRl = false,
        warningRr = false
    )
    private val profile = TpmsAlertProfile(2.9, 2.6, 3.4, enabled = true)
    private lateinit var stateStore: TpmsStateDataStore
    private lateinit var repository: TpmsStateRepository

    @Before
    fun setUp() {
        stateStore = TpmsStateDataStore(InstrumentationRegistry.getInstrumentation().targetContext)
        repository = TpmsStateRepository(stateStore)
        runBlocking { stateStore.clearAllStates() }
    }

    @After
    fun tearDown() {
        runBlocking { stateStore.clearAllStates() }
    }

    @Test
    fun workerReleasesFailedTeslaClaimForRetryAndDefersCustomAlertForLiveClaim() = runBlocking {
        var teslaDeliveries = 0

        runWorkerPass(profile = null, notifyTesla = {
            teslaDeliveries++
            error("synthetic Tesla notification failure")
        })
        runWorkerPass(profile = null, notifyTesla = { teslaDeliveries++ })

        assertEquals(2, teslaDeliveries)
        assertEquals(
            TpmsStateChangeClaimResult.NoTransition,
            repository.claimStateChange(carId, details, now = 3_002L)
        )

        stateStore.clearAllStates()
        val liveClaim = repository.claimStateChange(carId, details, now = 4_000L)
        assertTrue(liveClaim is TpmsStateChangeClaimResult.Claimed)

        var deferred = false
        var deferredTeslaDeliveries = 0
        var customDeliveries = 0
        runWorkerPass(
            profile = profile,
            notifyTesla = { deferredTeslaDeliveries++ },
            captureDeferral = { deferred = it },
            notifyCustom = { customDeliveries++ }
        )

        assertTrue(deferred)
        assertEquals(0, deferredTeslaDeliveries)
        assertEquals(0, customDeliveries)
    }

    private suspend fun runWorkerPass(
        profile: TpmsAlertProfile?,
        notifyTesla: () -> Unit = {},
        captureDeferral: (Boolean) -> Unit = {},
        notifyCustom: () -> Unit = {}
    ) {
        processSuccessfulTpmsStatus(
            carId = carId,
            carName = "Integration car",
            tpmsDetails = details,
            observedAt = 4_001L,
            profile = profile,
            saveObservation = {},
            pruneOlderThan90Days = { _, _ -> },
            detectTeslaStateChange = { _, _ -> error("real repository claim path must be used") },
            updateTeslaState = { _, _ -> error("real repository claim path must be used") },
            resetCustomState = { _, _ -> },
            claimCustomAlerts = { _, _, _, defer, _ ->
                captureDeferral(defer)
                emptyList()
            },
            commitCustomAlert = { _, _ -> },
            releaseCustomAlert = { _, _ -> },
            claimTeslaStateChange = repository::claimStateChange,
            commitTeslaStateChange = repository::commitStateChange,
            releaseTeslaStateChange = repository::releaseStateChange,
            notifyTesla = { _, _, _ -> notifyTesla() },
            notifyCustom = { _, _, _ -> notifyCustom() }
        )
    }
}
