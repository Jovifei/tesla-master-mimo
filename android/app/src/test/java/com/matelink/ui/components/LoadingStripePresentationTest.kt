package com.matelink.ui.components

import org.junit.Assert.*
import org.junit.Test

class LoadingStripePresentationTest {
    @Test fun everyStripePulsesInOrder() {
        for (index in 0..3) {
            assertEquals(1f, loadingStripeAlpha(index.toFloat(), index), 0.0001f)
            assertEquals(0.3f, loadingStripeAlpha(index.toFloat(), (index + 2) % 4), 0.0001f)
        }
    }
    @Test fun cycleBoundaryIsContinuousAndFinite() {
        assertEquals(loadingStripeAlpha(0f, 0), loadingStripeAlpha(4f, 0), 0.0001f)
        assertEquals(loadingStripeAlpha(3.999f, 0), loadingStripeAlpha(0.001f, 0), 0.0001f)
        assertEquals(0.3f, loadingStripeAlpha(Float.NaN, 0), 0f)
    }
    @Test fun preservesBrandRoadAndTransparentBackground() {
        assertNull(loadingStripeIndex(0xFF0C2644.toInt(), 450, 80, 515, 273))
        assertNull(loadingStripeIndex(0x0000D3FA, 450, 80, 515, 273))
        assertNull(loadingStripeIndex(0xFFFFFFFF.toInt(), 100, 100, 515, 273))
    }
    @Test fun identifiesOriginalFourStripesAtAnySize() {
        listOf(457 to 88, 451 to 143, 410 to 188, 341 to 216).forEachIndexed { i, (x,y) ->
            assertEquals(i, loadingStripeIndex(0xFF0F7486.toInt(), x, y, 515, 273))
            assertEquals(i, loadingStripeIndex(0xFFFFFFFF.toInt(), x*2, y*2, 1030, 546))
        }
    }
}
