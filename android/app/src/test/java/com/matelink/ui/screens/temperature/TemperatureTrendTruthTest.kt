package com.matelink.ui.screens.temperature

import com.matelink.data.api.models.DriveData
import com.matelink.data.local.entity.DriveSummary
import com.matelink.data.repository.toLocalSummary
import com.matelink.domain.analytics.HistorySummaryEvidenceCodec
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TemperatureTrendTruthTest {
    private val now = Instant.parse("2026-10-21T12:00:00Z")

    @Test fun legacyRemoteDriveKeepsItsOwnTemperatureDespiteMissingEnergyQuality() {
        val raw = DriveData(
            driveId = 7, startDate = "2026-10-20T10:00:00Z", endDate = "2026-10-20T10:20:00Z",
            source = "teslamate_archive", insideTempAvg = 0.0, outsideTempAvg = -3.5
        )
        val stored = raw.toLocalSummary(1)!!
        assertEquals("incomplete", stored.qualityState)
        assertEquals("remote_quality_unavailable", stored.qualityReason)

        // A stale Room projection cannot override the exact archived response.
        val point = observedTemperaturePoints(listOf(stored.copy(insideTempAvg = 99.0)), TemperatureTrendWindow.SEVEN, now).single()
        assertEquals(0.0, point.insideTemp!!, 0.0)
        assertEquals(-3.5, point.outsideTemp!!, 0.0)
    }

    @Test fun missingOrUntrustedLegacyTemperatureReceiptCannotEnterTrend() {
        val raw = DriveData(
            driveId = 8, startDate = "2026-10-20T10:00:00Z", endDate = "2026-10-20T10:20:00Z",
            source = "teslamate_archive", insideTempAvg = 22.0
        )
        val stored = raw.toLocalSummary(1)!!
        val candidates = listOf(
            stored.copy(apiEvidence = null),
            raw.copy(source = "local_import").toLocalSummary(1)!!,
            raw.copy(source = null).toLocalSummary(1)!!,
            raw.copy(qualityState = "quarantined").toLocalSummary(1)!!,
            stored.copy(apiEvidence = HistorySummaryEvidenceCodec.encode(raw.copy(qualityReason = "synthetic_provenance"))),
            stored.copy(apiEvidence = HistorySummaryEvidenceCodec.encode(raw.copy(sourceId = "a", sourceRecordId = "b"))),
            stored.copy(apiEvidence = HistorySummaryEvidenceCodec.encode(raw.copy(driveId = 9))),
            stored.copy(apiEvidence = HistorySummaryEvidenceCodec.encode(raw.copy(endDate = "2026-10-20T10:21:00Z"))),
            raw.copy(endDate = "2026-10-22T10:20:00Z").toLocalSummary(1)!!,
            stored.copy(startDate = "not-a-time")
        )
        assertTrue(observedTemperaturePoints(candidates, TemperatureTrendWindow.SEVEN, now).isEmpty())
    }

    @Test fun historyUsesOnlyQualifiedTimestampedFiniteDriveAverages() {
        val points = observedTemperaturePoints(listOf(
            drive(1, "2026-10-20T10:00:00Z", inside = 0.0, outside = -3.5),
            drive(2, "2026-10-19T10:00:00Z", inside = Double.NaN, outside = 16.0),
            drive(3, "2026-10-18T10:00:00Z", inside = 20.0, quality = "incomplete"),
            drive(4, "2026-10-01T10:00:00Z", inside = 22.0),
            drive(5, "not-a-time", inside = 30.0),
            drive(6, "2026-10-22T10:00:00Z", inside = 40.0)
        ), TemperatureTrendWindow.SEVEN, now)
        assertEquals(2, points.size)
        assertEquals(16.0, points[0].outsideTemp!!, 0.0)
        assertNull(points[0].insideTemp)
        assertEquals(0.0, points[1].insideTemp!!, 0.0)
        assertEquals(-3.5, points[1].outsideTemp!!, 0.0)
    }

    @Test fun currentApiSnapshotNeverBecomesHistoryOrHistoricalExtremum() = runTest {
        var state = TemperatureTrendUiState()
        val source = Source { _, _ -> TemperatureTrendSnapshot(
            points = emptyList(), currentInsideTemp = 37.0, currentOutsideTemp = 19.0,
            currentStatusAvailable = true
        ) }
        val controller = TemperatureTrendRefreshController(source, this, { now }) { state = it }
        controller.refresh(1, TemperatureTrendWindow.SEVEN)
        runCurrent()
        assertFalse(state.isLoading)
        assertTrue(state.isUnavailable)
        assertTrue(state.currentStatusAvailable)
        assertEquals(37.0, state.currentInsideTemp!!, 0.0)
        assertNull(state.maxInsideTemp)
        assertNull(state.minOutsideTemp)
    }

    @Test fun offlineHistoryRemainsVisibleWithoutClaimingCurrentApiStatus() = runTest {
        var state = TemperatureTrendUiState()
        val source = Source { _, _ -> TemperatureTrendSnapshot(
            points = listOf(TemperaturePoint(now.minusSeconds(3600).toEpochMilli(), 18.0, 20.0)),
            currentStatusAvailable = false
        ) }
        val controller = TemperatureTrendRefreshController(source, this, { now }) { state = it }
        controller.refresh(1, TemperatureTrendWindow.THIRTY)
        runCurrent()
        assertFalse(state.isUnavailable)
        assertFalse(state.currentStatusAvailable)
        assertEquals(18.0, state.maxInsideTemp!!, 0.0)
        assertNull(state.currentInsideTemp)
    }

    @Test fun oldCarUncancellableResponseCannotReplaceNewCar() = runTest {
        val gate = CompletableDeferred<Unit>()
        var state = TemperatureTrendUiState()
        val source = Source { carId, _ ->
            if (carId == 1) withContext(NonCancellable) { gate.await() }
            TemperatureTrendSnapshot(listOf(TemperaturePoint(now.toEpochMilli(), carId.toDouble(), null)))
        }
        val controller = TemperatureTrendRefreshController(source, this, { now }) { state = it }
        controller.refresh(1, TemperatureTrendWindow.SEVEN)
        runCurrent()
        controller.refresh(2, TemperatureTrendWindow.SEVEN)
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(2.0, state.points.single().insideTemp!!, 0.0)
    }

    @Test fun staleScopeAndReadFailureClearAllPreviouslyVisibleValues() = runTest {
        var state = TemperatureTrendUiState()
        var fail = false
        var stale = false
        val source = Source { _, _ ->
            if (fail) error("Room failed")
            TemperatureTrendSnapshot(
                listOf(TemperaturePoint(now.toEpochMilli(), 23.0, null)),
                stillCurrent = { !stale }
            )
        }
        val controller = TemperatureTrendRefreshController(source, this, { now }) { state = it }
        controller.refresh(1, TemperatureTrendWindow.SEVEN)
        runCurrent()
        assertEquals(23.0, state.points.single().insideTemp!!, 0.0)
        fail = true
        controller.refresh(1, TemperatureTrendWindow.SEVEN)
        runCurrent()
        assertTrue(state.points.isEmpty())
        assertNull(state.currentInsideTemp)
        assertTrue(state.error != null)
        fail = false
        stale = true
        controller.refresh(1, TemperatureTrendWindow.SEVEN)
        runCurrent()
        assertTrue(state.points.isEmpty())
        assertTrue(state.error != null)
    }

    private fun drive(
        id: Int, start: String, inside: Double? = null, outside: Double? = null,
        quality: String = "observed"
    ) = DriveSummary(
        driveId = id, carId = 1, startDate = start, endDate = start, durationMin = 1,
        startAddress = "", endAddress = "", distance = 1.0, speedMax = 0, speedAvg = 0,
        powerMax = 0, powerMin = 0, startBatteryLevel = 50, endBatteryLevel = 50,
        outsideTempAvg = outside, insideTempAvg = inside, energyConsumed = null,
        efficiency = null, qualityState = quality
    )

    private class Source(
        private val response: suspend (Int, TemperatureTrendWindow) -> TemperatureTrendSnapshot
    ) : TemperatureTrendSource {
        override suspend fun load(carId: Int, window: TemperatureTrendWindow, now: Instant) =
            response(carId, window)
    }
}
