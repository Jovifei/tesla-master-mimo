package com.matelink.data.repository

import android.app.Application
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveOdometerDetails
import com.matelink.data.sync.HistoryResourceFixture
import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.analytics.AnalysisHistoryRepository
import com.matelink.domain.analytics.HistoryFreshness
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Retrofit/Moshi -> repository -> scoped Room consumers; HTTP is intercepted by the fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ExternalReviewHistoryShapeTest {
    @get:Rule val temp = TemporaryFolder()

    private suspend fun withResponse(body: String, block: suspend (HistoryResourceFixture) -> Unit) {
        val f = HistoryResourceFixture(File(temp.root, "history-shape-${UUID.randomUUID()}.db"))
        try {
            SettingsRepository(f.context).setMockMode(false)
            f.initialize()
            f.detailResponse = { path ->
                body.takeIf { path.endsWith("/drives") || path.endsWith("/charges") }
            }
            block(f)
        } finally { f.close() }
    }

    private fun assertInvalid(result: ApiResult<*>) {
        assertTrue("A structurally absent list must be an error, never an authoritative empty result", result is ApiResult.Error)
        assertEquals(ApiErrorKind.INVALID_RESPONSE, (result as ApiResult.Error).kind)
    }

    @Test fun drivesRejectMissingDataEnvelope() = runBlocking(Dispatchers.IO) {
        withResponse("{}") { assertInvalid(it.apiRepo.getDrives(1)) }
    }

    @Test fun drivesRejectNullDataEnvelope() = runBlocking(Dispatchers.IO) {
        withResponse("""{"data":null}""") { assertInvalid(it.apiRepo.getDrives(1)) }
    }

    @Test fun drivesRejectMissingList() = runBlocking(Dispatchers.IO) {
        withResponse("""{"data":{}}""") { assertInvalid(it.apiRepo.getDrives(1)) }
    }

    @Test fun drivesRejectNullList() = runBlocking(Dispatchers.IO) {
        withResponse("""{"data":{"drives":null}}""") { assertInvalid(it.apiRepo.getDrives(1)) }
    }

    @Test fun chargesRejectMissingDataEnvelope() = runBlocking(Dispatchers.IO) {
        withResponse("{}") { assertInvalid(it.apiRepo.getCharges(1)) }
    }

    @Test fun chargesRejectNullDataEnvelope() = runBlocking(Dispatchers.IO) {
        withResponse("""{"data":null}""") { assertInvalid(it.apiRepo.getCharges(1)) }
    }

    @Test fun chargesRejectMissingList() = runBlocking(Dispatchers.IO) {
        withResponse("""{"data":{}}""") { assertInvalid(it.apiRepo.getCharges(1)) }
    }

    @Test fun chargesRejectNullList() = runBlocking(Dispatchers.IO) {
        withResponse("""{"data":{"charges":null}}""") { assertInvalid(it.apiRepo.getCharges(1)) }
    }

    @Test fun explicitlyEmptyListsRemainSuccessful() = runBlocking(Dispatchers.IO) {
        withResponse("""{"data":{"drives":[],"charges":[]}}""") { f ->
            val drives = f.apiRepo.getDrives(1)
            val charges = f.apiRepo.getCharges(1)
            assertTrue(drives is ApiResult.Success)
            assertTrue(charges is ApiResult.Success)
            assertTrue((drives as ApiResult.Success).data.isEmpty())
            assertTrue((charges as ApiResult.Success).data.isEmpty())
        }
    }

    @Test fun malformedSuccessKeepsRoomHistoryButCannotMarkItsAnalysisFresh() = runBlocking(Dispatchers.IO) {
        withResponse("{}") { f ->
            val local = f.localId(1)
            val row = requireNotNull(DriveData(7,
                startDate = "2026-10-14T00:00:00Z", endDate = "2026-10-14T00:01:00Z",
                odometerDetails = DriveOdometerDetails(distance = 1.0), lengthUnit = "km",
                source = "teslamate_archive", qualityState = "observed").toSyncSummary(local))
            f.db.driveSummaryDao().upsert(row)
            val history = UnifiedHistoryRepository(f.apiRepo, f.vehicleContexts,
                f.db.driveSummaryDao(), f.db.chargeSummaryDao())
            val result = AnalysisHistoryRepository(history, f.vehicleContexts).load(1)
            assertTrue("The verified Room observation must remain readable", result is ApiResult.Success)
            val snapshot = (result as ApiResult.Success).data
            assertEquals(listOf(7), snapshot.drives.map { it.driveId })
            assertEquals(HistoryFreshness.STALE, snapshot.freshness)
            assertNotNull(snapshot.staleReason)
            assertEquals(row, f.db.driveSummaryDao().get(local, 7))
        }
    }
}
