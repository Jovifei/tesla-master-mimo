package com.matelink.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JourVoltConsentDocumentsTest {
    @Test
    fun currentConsentRequiresBothCurrentVersionsAndAnAcceptanceTime() {
        assertTrue(
            JourVoltConsent(
                JourVoltConsentDocuments.TERMS_VERSION,
                JourVoltConsentDocuments.PRIVACY_VERSION,
                1L
            ).isCurrent
        )
        assertFalse(JourVoltConsent("old", JourVoltConsentDocuments.PRIVACY_VERSION, 1L).isCurrent)
        assertFalse(JourVoltConsent(JourVoltConsentDocuments.TERMS_VERSION, "old", 1L).isCurrent)
        assertFalse(
            JourVoltConsent(
                JourVoltConsentDocuments.TERMS_VERSION,
                JourVoltConsentDocuments.PRIVACY_VERSION,
                0L
            ).isCurrent
        )
    }
}
