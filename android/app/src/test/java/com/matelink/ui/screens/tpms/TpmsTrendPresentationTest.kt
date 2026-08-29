package com.matelink.ui.screens.tpms

import com.matelink.data.local.TirePosition
import com.matelink.domain.analytics.TpmsPressurePoint
import com.matelink.domain.analytics.TpmsTrendEvidence
import com.matelink.domain.analytics.TpmsTrendFactor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import com.matelink.data.local.entity.DriveSummary
import com.matelink.data.local.entity.TpmsPressureSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TpmsTrendPresentationTest {
    @Test
    fun refreshUsesTheMatchingSevenAndThirtyDayRepositoryCalls() {
        val source = FakeTrendSource()
        val published = mutableListOf<TpmsTrendWindow>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val controller = controller(
            source = source,
            scope = scope,
            onSuccess = { window, _ -> published += window }
        )

        controller.refresh(7, TpmsTrendWindow.SEVEN)
        controller.refresh(7, TpmsTrendWindow.THIRTY)

        assertEquals(listOf(TpmsTrendWindow.SEVEN, TpmsTrendWindow.THIRTY), source.requestedWindows)
        assertEquals(listOf(TpmsTrendWindow.SEVEN, TpmsTrendWindow.THIRTY), published)
        scope.cancel()
    }

    @Test
    fun failedRefreshReportsFailureAndRetryPublishesFreshResult() {
        val source = FakeTrendSource(failNextRequest = true)
        val published = mutableListOf<TpmsTrendWindow>()
        var failures = 0
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val controller = controller(
            source = source,
            scope = scope,
            onSuccess = { window, _ -> published += window },
            onFailure = { failures++ }
        )

        controller.refresh(7, TpmsTrendWindow.SEVEN)
        controller.retry()

        assertEquals(1, failures)
        assertEquals(listOf(TpmsTrendWindow.SEVEN, TpmsTrendWindow.SEVEN), source.requestedWindows)
        assertEquals(listOf(TpmsTrendWindow.SEVEN), published)
        scope.cancel()
    }

    @Test
    fun staleRefreshCannotOverwriteTheLatestWindowResult() = runBlocking {
        val source = FakeTrendSource()
        val first = CompletableDeferred<List<TpmsPressureSample>>()
        val second = CompletableDeferred<List<TpmsPressureSample>>()
        source.sevenGate = first
        source.thirtyGate = second
        val published = mutableListOf<TpmsTrendWindow>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val controller = controller(
            source = source,
            scope = scope,
            onSuccess = { window, _ -> published += window }
        )

        controller.refresh(7, TpmsTrendWindow.SEVEN)
        controller.refresh(8, TpmsTrendWindow.THIRTY)
        second.complete(listOf(sample(2L, 2.8).copy(carId = 8)))
        first.complete(listOf(sample(1L, 2.7)))

        assertEquals(listOf(TpmsTrendWindow.THIRTY), published)
        scope.cancel()
    }

    @Test
    fun selectingSevenAndThirtyDayWindowsUpdatesPresentationState() {
        val initial = TpmsTrendUiState(selectedWindow = TpmsTrendWindow.SEVEN)

        assertEquals(TpmsTrendWindow.SEVEN, initial.selectWindow(TpmsTrendWindow.SEVEN).selectedWindow)
        assertEquals(TpmsTrendWindow.THIRTY, initial.selectWindow(TpmsTrendWindow.THIRTY).selectedWindow)
    }

    @Test
    fun nullableSamplesBecomeSegmentBreaksInsteadOfZeroValues() {
        val points = listOf(
            TpmsPressurePoint(1L, 2.8),
            TpmsPressurePoint(2L, null),
            TpmsPressurePoint(3L, 2.7),
            TpmsPressurePoint(4L, 2.6)
        )

        assertEquals(
            listOf(
                listOf(points[0]),
                listOf(points[2], points[3])
            ),
            nullableSegments(points)
        )
        assertFalse(nullableSegments(points).flatten().any { it.pressureBar == 0.0 })
    }

    @Test
    fun noValidSamplesProducesUnavailablePresentationState() {
        val state = TpmsTrendUiState(
            series = TirePosition.entries.associateWith {
                listOf(TpmsPressurePoint(1L, null))
            }
        )

        assertTrue(state.isUnavailable)
        assertNull(state.latestPressure(TirePosition.FL))
    }

    @Test
    fun customReminderLabelIsLocalizedAndExplicitlyNamed() {
        assertEquals(
            "App custom reminder",
            customReminderLabel("App custom reminder", "App 自定义提醒", isChinese = false)
        )
        assertEquals(
            "App 自定义提醒",
            customReminderLabel("App custom reminder", "App 自定义提醒", isChinese = true)
        )
    }

    @Test
    fun evidenceConclusionRendersLocalizedConclusionAndAnalyzerRecommendation() {
        val evidence = listOf(
            TpmsTrendEvidence(TpmsTrendFactor.PARKING, recommendation = "Perform a manual cold check.")
        )

        val conclusion = possibleFactorConclusion(evidence)
        val rendered = renderPossibleFactorConclusion(
            conclusion = conclusion,
            localized = TpmsTrendLocalizedText(
                ambient = "Possible ambient-temperature effect; cause not confirmed.",
                highway = "Possible highway warm-up effect; cause not confirmed.",
                parking = "Possible parking-related pressure change",
                parkingRecommendation = "Manual cold check recommended.",
                insufficient = "Possible factor unavailable: insufficient evidence."
            )
        )

        assertEquals(TpmsTrendFactor.PARKING, conclusion.factor)
        assertEquals(TpmsTrendRecommendation.MANUAL_COLD_CHECK, conclusion.recommendation)
        assertEquals("Possible parking-related pressure change", rendered.conclusion)
        assertEquals("Manual cold check recommended.", rendered.recommendation)
        assertFalse(rendered.conclusion.contains("certain", ignoreCase = true))
        assertFalse(rendered.conclusion.contains("leak", ignoreCase = true))
    }

    @Test
    fun conclusionRecommendationComesFromTheSameSelectedFactor() {
        val conclusion = possibleFactorConclusion(
            listOf(
                TpmsTrendEvidence(TpmsTrendFactor.AMBIENT),
                TpmsTrendEvidence(
                    TpmsTrendFactor.PARKING,
                    recommendation = "Perform a manual cold check."
                )
            )
        )

        assertEquals(TpmsTrendFactor.AMBIENT, conclusion.factor)
        assertNull(conclusion.recommendation)
    }

    @Test
    fun insufficientEvidenceRendersUnavailableReasonWithoutDiagnosis() {
        val rendered = renderPossibleFactorConclusion(
            conclusion = possibleFactorConclusion(
                listOf(TpmsTrendEvidence(TpmsTrendFactor.INSUFFICIENT_EVIDENCE))
            ),
            localized = TpmsTrendLocalizedText(
                ambient = "Possible ambient-temperature effect; cause not confirmed.",
                highway = "Possible highway warm-up effect; cause not confirmed.",
                parking = "Possible parking-related pressure change",
                parkingRecommendation = "Manual cold check recommended.",
                insufficient = "Possible factor unavailable: insufficient evidence."
            )
        )

        assertEquals("Possible factor unavailable: insufficient evidence.", rendered.conclusion)
        assertNull(rendered.recommendation)
        assertFalse(rendered.conclusion.contains("diagnosis", ignoreCase = true))
    }

    private fun controller(
        source: FakeTrendSource,
        scope: CoroutineScope,
        onSuccess: (TpmsTrendWindow, com.matelink.domain.analytics.TpmsTrendAnalysis) -> Unit,
        onFailure: () -> Unit = {}
    ) = TpmsTrendRefreshController(
        source = source,
        analyzer = com.matelink.domain.analytics.TpmsTrendAnalyzer(),
        scope = scope,
        onSuccess = onSuccess,
        onFailure = onFailure
    )

    private fun sample(observedAt: Long, pressure: Double) = TpmsPressureSample(
        carId = 7,
        observedAt = observedAt,
        pressureFl = pressure
    )

    private class FakeTrendSource(
        private var failNextRequest: Boolean = false
    ) : TpmsTrendHistorySource {
        val requestedWindows = mutableListOf<TpmsTrendWindow>()
        var sevenGate: CompletableDeferred<List<TpmsPressureSample>>? = null
        var thirtyGate: CompletableDeferred<List<TpmsPressureSample>>? = null

        override suspend fun load7DaySamples(
            carId: Int,
            now: Long
        ): List<TpmsPressureSample> {
            requestedWindows += TpmsTrendWindow.SEVEN
            if (failNextRequest) {
                failNextRequest = false
                error("synthetic load failure")
            }
            return sevenGate?.let { withContext(NonCancellable) { it.await() } }
                ?: listOf(TpmsPressureSample(7, 1L, pressureFl = 2.7))
        }

        override suspend fun load30DaySamples(
            carId: Int,
            now: Long
        ): List<TpmsPressureSample> {
            requestedWindows += TpmsTrendWindow.THIRTY
            return thirtyGate?.let { withContext(NonCancellable) { it.await() } }
                ?: listOf(TpmsPressureSample(7, 2L, pressureFl = 2.8))
        }

        override suspend fun loadDrives(carId: Int): List<DriveSummary> = emptyList()
    }
}
