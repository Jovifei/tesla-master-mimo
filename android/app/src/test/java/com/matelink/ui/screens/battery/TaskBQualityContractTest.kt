package com.matelink.ui.screens.battery

import com.matelink.data.repository.ApiResult
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskBQualityContractTest {
    @Test
    fun batteryLoadCancelsAndGuardsPriorJobsAndRethrowsCancellation() {
        val source = File("src/main/java/com/matelink/ui/screens/battery/BatteryViewModel.kt").readText()

        assertTrue(source.contains("loadJob?.cancel()"))
        assertTrue(source.contains("loadGeneration"))
        assertTrue(source.contains("catch (e: CancellationException)"))
        assertTrue(source.contains("throw e"))
    }

    @Test
    fun readinessLoadCancelsAndGuardsPriorJobsAndRethrowsCancellation() {
        val source = File("src/main/java/com/matelink/ui/screens/readiness/DataReadinessViewModel.kt").readText()

        assertTrue(source.contains("loadJob?.cancel()"))
        assertTrue(source.contains("loadGeneration"))
        assertTrue(source.contains("catch (e: CancellationException)"))
        assertTrue(source.contains("throw e"))
    }

    @Test
    fun repositoryRethrowsCancellationBeforeMappingOtherExceptions() {
        val source = File("src/main/java/com/matelink/data/repository/TeslamateRepository.kt").readText()

        val executeWithFallback = source
            .substringAfter("private suspend fun <T> executeWithFallback")
            .substringBefore("suspend fun testConnection")
        val cancellationCatch = executeWithFallback.indexOf("catch (e: CancellationException)")
        val genericCatch = executeWithFallback.indexOf("catch (e: Exception)")
        assertTrue(cancellationCatch >= 0)
        assertTrue(genericCatch > cancellationCatch)
        assertTrue(executeWithFallback.substring(cancellationCatch, genericCatch).contains("throw e"))
    }

    @Test
    fun legacyReadinessCompatibilityRequiresAnExplicitSelfHostedGate() {
        val source = File("src/main/java/com/matelink/data/repository/TeslamateRepository.kt").readText()

        assertTrue(source.contains("allowLegacyCompatibility"))
        assertTrue(source.contains("ConnectionMode.SELF_HOSTED"))
    }

    @Test
    fun transportAndAuthFailuresNeverBecomeCollectingBatteryHealth() {
        val failures = listOf(
            ApiResult.Error("unauthorized", 401),
            ApiResult.Error("rate limited", 429),
            ApiResult.Error("server failure", 503),
            ApiResult.Error("Server is temporarily unreachable"),
            ApiResult.Error("Server returned unrecognised data")
        )

        failures.forEach { failure ->
            assertNotEquals(BatteryHealthAvailability.COLLECTING, classifyBatteryHealth(failure, null))
        }
    }

    @Test
    fun batteryReadinessIsStartedIndependentlyFromHealthAndStatusRendering() {
        val source = File("src/main/java/com/matelink/ui/screens/battery/BatteryViewModel.kt").readText()

        assertTrue(source.contains("async"))
        assertTrue(source.contains("getDataReadiness"))
        assertTrue(source.contains("await"))
    }
}
