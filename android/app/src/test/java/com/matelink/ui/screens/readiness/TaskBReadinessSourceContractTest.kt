package com.matelink.ui.screens.readiness

import java.io.File
import com.matelink.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskBReadinessSourceContractTest {
    @Test
    fun knownAndUnknownSourcesResolveToLocalizedResourceKeys() {
        assertEquals(R.string.data_readiness_source_fleet_api, readinessSourceLabelRes("fleet_api"))
        assertEquals(R.string.data_readiness_source_mock, readinessSourceLabelRes("mock_fixture"))
        assertEquals(R.string.data_readiness_source_legacy, readinessSourceLabelRes("legacy_compatibility"))
        assertEquals(R.string.data_readiness_source_unavailable, readinessSourceLabelRes("future_source"))
    }

    @Test
    fun readinessSourceUsesLocalizedLabelsAndNeverRendersRawSourceCodes() {
        val source = File("src/main/java/com/matelink/ui/screens/readiness/DataReadinessScreen.kt").readText()
        val presentation = File("src/main/java/com/matelink/ui/screens/readiness/ReadinessPresentation.kt").readText()

        assertTrue(source.contains("readinessSourceLabelRes"))
        assertTrue(presentation.contains("data_readiness_source_fleet_api"))
        assertFalse(source.contains("data_readiness_source, it"))
    }
}
