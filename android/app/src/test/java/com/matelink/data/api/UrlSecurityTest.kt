package com.matelink.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlSecurityTest {

    @Test fun https_isSafe() {
        assertEquals(UrlSecurity.Verdict.Https, UrlSecurity.classify("https://api.example.com"))
        assertEquals(UrlSecurity.Verdict.Https, UrlSecurity.classify("https://192.168.1.100:4000"))
    }

    @Test fun http_privateAndLoopbackAddresses_areAllowedWithWarning() {
        listOf("http://192.168.1.100:4000", "http://10.0.0.5", "http://172.16.0.1", "http://localhost:4000", "http://127.0.0.1:4000", "http://[fd00::1]:8080")
            .forEach { assertEquals(UrlSecurity.Verdict.LocalHttp, UrlSecurity.classify(it)) }
    }

    @Test fun http_publicEndpoints_areBlockedBeforeRequest() {
        listOf("http://8.8.8.8", "http://203.0.113.5:4000", "http://teslamate.example.com")
            .forEach { assertEquals(UrlSecurity.Verdict.Unsafe, UrlSecurity.classify(it)) }
    }

    @Test fun malformedAddress_isUnsafe() {
        assertEquals(UrlSecurity.Verdict.Unsafe, UrlSecurity.classify(""))
        assertEquals(UrlSecurity.Verdict.Unsafe, UrlSecurity.classify("http://"))
        assertFalse(UrlSecurity.isSafe("not a url"))
        assertTrue(UrlSecurity.isSafe("https://api.example.com"))
    }
}
