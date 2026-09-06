package com.matelink.domain.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryQualityTest {
    @Test
    fun driveWithApiEvidenceIsObserved() {
        val quality = classifyDrive(energySource = "api", apiEvidence = "{\"drive_id\":1}")

        assertEquals(HistoryQualityState.OBSERVED, quality.state)
        assertEquals("api_evidence", quality.reason)
    }

    @Test
    fun driveCalculatedFromPowerSamplesIsDerived() {
        val quality = classifyDrive(energySource = "power_samples", apiEvidence = "{\"drive_id\":1}")

        assertEquals(HistoryQualityState.DERIVED, quality.state)
        assertEquals("power_samples", quality.reason)
    }

    @Test
    fun missingDriveEvidenceIsIncomplete() {
        val quality = classifyDrive(energySource = "api", apiEvidence = null)

        assertEquals(HistoryQualityState.INCOMPLETE, quality.state)
        assertEquals("missing_api_evidence", quality.reason)
    }

    @Test
    fun syntheticDriveSourcesAreQuarantined() {
        listOf(
            "snapshot_session",
            "snapshot_consolidated",
            "snapshot_estimate",
            "physical_model",
            "snapshot_charge",
            "inferred_soc_jump"
        ).forEach { source ->
            val quality = classifyDrive(source, "{\"drive_id\":1}")

            assertEquals(source, HistoryQualityState.QUARANTINED, quality.state)
            assertEquals(source, "synthetic_provenance:$source", quality.reason)
        }
    }

    @Test
    fun syntheticChargeEvidenceIsQuarantined() {
        val quality = classifyCharge(
            apiEvidence = "{\"source\":\"snapshot_charge\",\"charge_id\":1}",
            latitude = 30.0,
            longitude = 120.0,
            energyAdded = 10.0
        )

        assertEquals(HistoryQualityState.QUARANTINED, quality.state)
        assertEquals("synthetic_provenance:snapshot_charge", quality.reason)
    }

    @Test
    fun chargeNeedsEvidenceAndARecordedMeasurement() {
        assertEquals(
            HistoryQualityState.OBSERVED,
            classifyCharge("{\"charge_id\":1}", 30.0, 120.0, null).state
        )
        assertEquals(
            HistoryQualityState.OBSERVED,
            classifyCharge("{\"charge_id\":1}", null, null, 0.0).state
        )
        assertEquals(
            HistoryQualityState.INCOMPLETE,
            classifyCharge("{\"charge_id\":1}", null, null, null).state
        )
        assertEquals(
            HistoryQualityState.INCOMPLETE,
            classifyCharge(null, 30.0, 120.0, 10.0).state
        )
    }

    @Test
    fun onlyObservedAndDerivedRowsAreAnalysisEligible() {
        assertTrue(isAnalysisEligible("observed"))
        assertTrue(isAnalysisEligible("derived"))
        assertFalse(isAnalysisEligible("incomplete"))
        assertFalse(isAnalysisEligible("quarantined"))
        assertFalse(isAnalysisEligible("unknown"))
    }

    @Test
    fun legacyRemoteHistoryWithoutAQualityFieldRemainsEligible() {
        assertTrue(isAnalysisEligible("incomplete", "remote_quality_unavailable"))
        assertFalse(isAnalysisEligible("incomplete", "local_import_unverified"))
    }
}
