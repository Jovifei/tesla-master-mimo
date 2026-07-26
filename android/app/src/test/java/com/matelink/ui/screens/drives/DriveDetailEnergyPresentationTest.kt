package com.matelink.ui.screens.drives

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DriveDetailEnergyPresentationTest {

    @Test
    fun apiEnergyKeepsPersistedValueAndZeroCoverage() {
        val presentation = presentDriveDetailEnergy(12.5, 150.0, "api", 0L, 0.0)

        assertEquals(12.5, presentation.energyKwh ?: error("energy missing"), 0.0001)
        assertEquals(DriveDetailEnergySource.API, presentation.source)
        assertEquals(0L, presentation.coverageSeconds)
        assertEquals(0.0, presentation.coverageRatio ?: error("coverage missing"), 0.0001)
    }

    @Test
    fun powerSamplesKeepPartialCoverage() {
        val presentation = presentDriveDetailEnergy(8.0, 160.0, "power_samples", 180L, 0.45)

        assertEquals(DriveDetailEnergySource.POWER_SAMPLES, presentation.source)
        assertEquals(180L, presentation.coverageSeconds)
        assertEquals(0.45, presentation.coverageRatio ?: error("coverage missing"), 0.0001)
    }

    @Test
    fun powerSamplesKeepFullCoverage() {
        val presentation = presentDriveDetailEnergy(8.0, 160.0, "power_samples", 360L, 1.0)

        assertEquals(1.0, presentation.coverageRatio ?: error("coverage missing"), 0.0001)
    }

    @Test
    fun missingSourceMakesZeroEnergyUnavailable() {
        val presentation = presentDriveDetailEnergy(0.0, 0.0, null, 0L, 0.0)

        assertNull(presentation.energyKwh)
        assertNull(presentation.source)
        assertNull(presentation.coverageRatio)
    }

    @Test
    fun missingSourceMakesPresentEnergyUnavailable() {
        val presentation = presentDriveDetailEnergy(8.0, 160.0, null, 180L, 0.5)

        assertNull(presentation.energyKwh)
        assertNull(presentation.source)
    }

    @Test
    fun invalidEnergyIsUnavailable() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0).forEach { energyKwh ->
            val presentation = presentDriveDetailEnergy(energyKwh, 160.0, "api", 0L, 0.0)

            assertNull(presentation.energyKwh)
            assertNull(presentation.source)
        }
    }

    @Test
    fun sourcedZeroEnergyStaysAvailable() {
        val presentation = presentDriveDetailEnergy(0.0, 0.0, "api", 0L, 0.0)

        assertEquals(0.0, presentation.energyKwh ?: error("energy missing"), 0.0001)
        assertEquals(DriveDetailEnergySource.API, presentation.source)
    }

    @Test
    fun invalidCoverageDoesNotBecomeCompleteCoverage() {
        listOf(-0.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY).forEach { coverageRatio ->
            val presentation = presentDriveDetailEnergy(8.0, 160.0, "power_samples", 180L, coverageRatio)

            assertNull(presentation.coverageRatio)
        }
    }

    @Test
    fun presentationIsDeterministic() {
        assertEquals(
            presentDriveDetailEnergy(8.0, 160.0, "power_samples", 180L, 0.45),
            presentDriveDetailEnergy(8.0, 160.0, "power_samples", 180L, 0.45)
        )
    }
}
