package com.matelink.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Regression against trading privacy or identity security for a successful history request. */
class CloudTlsPolicyRegressionTest {
    @Test fun productionNetworkStillUsesSystemTrustWithoutUserCertificateInjection() {
        val release = File("src/release/res/xml/network_security_config.xml").readText()
        assertTrue(release.contains("<certificates src=\"system\""))
        assertFalse(release.contains("<certificates src=\"user\""))
        assertFalse(release.contains("debug-overrides"))
        val network = File("src/main/java/com/matelink/di/NetworkModule.kt").readText()
        assertTrue(network.contains("if (cloudMode && urlVerdict != UrlSecurity.Verdict.Https && !allowsDebugLocalHttp)"))
        assertTrue(network.contains("JourVolt cloud API must use HTTPS"))
        assertFalse(network.contains(".hostnameVerifier("))
        assertFalse(network.contains(".sslSocketFactory("))
    }
}
