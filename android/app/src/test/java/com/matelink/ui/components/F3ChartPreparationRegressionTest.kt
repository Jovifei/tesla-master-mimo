package com.matelink.ui.components

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class F3ChartPreparationRegressionTest {
    @Test fun missingSamplesDoNotPoisonFiniteAxisBounds() {
        val data = prepareChartData(listOf(0f, Float.NaN, 20f), null, convertValue = { it })
        assertEquals(0f, data.minValue, 0f)
        assertEquals(20f, data.maxValue, 0f)
        assertTrue(data.range.isFinite())
    }

    @Test fun infiniteSamplesAreNotValidDualAxisEvidence() {
        val data = prepareDualChartData(listOf(0f, Float.POSITIVE_INFINITY, 20f))
        assertEquals(0f, data.minValue, 0f)
        assertEquals(20f, data.maxValue, 0f)
        assertTrue(data.range.isFinite())
    }

    @Test fun downsamplingRetainsTheOriginalSampleIndicesForTimeSelection() {
        val data = prepareChartData(List(500) { it.toFloat() }, null, convertValue = { it })
        assertEquals(data.displayPoints.map { it.toInt() }, data.originalIndices)
    }
}
