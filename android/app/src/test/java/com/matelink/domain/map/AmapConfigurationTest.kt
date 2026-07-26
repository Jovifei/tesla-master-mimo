package com.matelink.domain.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmapConfigurationTest {
    @Test fun key_isTrimmed() = assertEquals("key-value", AmapConfiguration.sanitizeKey("  key-value  "))
    @Test fun blankKey_isRejected() = assertNull(AmapConfiguration.sanitizeKey("   "))
    @Test fun newlineKey_isRejected() = assertNull(AmapConfiguration.sanitizeKey("key\nvalue"))
    @Test fun controlCharacterKey_isRejected() = assertNull(AmapConfiguration.sanitizeKey("key\u0000value"))
    @Test fun saveMutationTrimsKeyWithoutExposingItInErrors() = assertEquals("key-value", AmapConfiguration.prepareKeySave(" key-value ", "", false)?.key)
    @Test fun clearMutationRemovesSavedKeyAndRestartRequirement() = assertEquals(AmapKeyMutation("", false), AmapConfiguration.clearKeyMutation())
    @Test fun privacyDefaultsToNotAgreed() = assertEquals(AmapSetupState.PRIVACY_NOT_AGREED, amapSetupState(true, false))
    @Test fun privacyRevocationReturnsToNotAgreed() = assertEquals(AmapSetupState.PRIVACY_NOT_AGREED, amapSetupState(true, false))
    @Test fun missingKeyWinsOverPrivacy() = assertEquals(AmapSetupState.UNCONFIGURED, amapSetupState(false, true))
    @Test fun changedKeyRequiresRestart() = assertEquals(AmapSetupState.RESTART_REQUIRED, amapSetupState(true, true, true))
    @Test fun changedKeySetsRestartMutationAfterMapInitialization() = assertTrue(AmapConfiguration.prepareKeySave("next", "previous", true)?.restartRequired == true)
    @Test fun readyMapHasKeyAndConsent() = assertEquals(AmapSetupState.READY_TO_PREVIEW, amapSetupState(true, true))
    @Test fun debugAndReleaseLabelsRemainDistinct() = assertFalse(InstalledAppIdentity("com.matelink", null, "Debug").buildType == InstalledAppIdentity("com.matelink", null, "Release").buildType)
    @Test fun validCoordinate_isAccepted() = assertTrue(AmapConfiguration.isUsableCoordinate(1.0, 1.0))
    @Test fun zeroZero_isRejected() = assertFalse(AmapConfiguration.isUsableCoordinate(0.0, 0.0))
    @Test fun invalidCoordinate_isRejected() = assertFalse(AmapConfiguration.isUsableCoordinate(91.0, 120.0))
    @Test fun sha1UsesColonSeparatedUppercase() = assertEquals("DA:39:A3:EE:5E:6B:4B:0D:32:55:BF:EF:95:60:18:90:AF:D8:07:09", InstalledAppSignature.formatSha1(byteArrayOf()))
}
