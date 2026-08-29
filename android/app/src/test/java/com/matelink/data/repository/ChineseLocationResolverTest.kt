package com.matelink.data.repository

import com.matelink.data.local.AmapSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class ChineseLocationResolverTest {
    @Test
    fun verifiedKeyAndPrivacyRouteLocationRecognitionToAmap() {
        assertEquals(
            ChineseLocationAvailability.READY,
            chineseLocationAvailability(
                AmapSettings(hasKey = true, privacyAgreed = true, mapLoaded = true)
            )
        )
    }

    @Test
    fun missingKeyExplainsWhyLocationRecognitionCannotStart() {
        assertEquals(
            ChineseLocationAvailability.KEY_NOT_CONFIGURED,
            chineseLocationAvailability(AmapSettings())
        )
    }
}
