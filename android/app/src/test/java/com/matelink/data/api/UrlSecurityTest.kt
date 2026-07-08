package com.matelink.data.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlSecurityTest {

    @Test fun https_isAlwaysSafe() {
        assertTrue(UrlSecurity.isSafe("https://teslamate.example.com"))
        assertTrue(UrlSecurity.isSafe("https://192.168.1.100:4000"))
        assertTrue(UrlSecurity.isSafe("https://10.0.0.5"))
    }

    @Test fun http_privateIp_isSafe() {
        assertTrue(UrlSecurity.isSafe("http://192.168.1.100:4000"))
        assertTrue(UrlSecurity.isSafe("http://10.0.0.5"))
        assertTrue(UrlSecurity.isSafe("http://172.16.0.1"))
        assertTrue(UrlSecurity.isSafe("http://172.31.255.255"))
    }

    @Test fun http_loopback_isSafe() {
        assertTrue(UrlSecurity.isSafe("http://localhost:4000"))
        assertTrue(UrlSecurity.isSafe("http://127.0.0.1:4000"))
    }

    @Test fun http_linkLocal_isSafe() {
        assertTrue(UrlSecurity.isSafe("http://169.254.1.1"))
    }

    @Test fun http_localDomain_isSafe() {
        assertTrue(UrlSecurity.isSafe("http://teslamate.local"))
    }

    @Test fun http_publicIp_isUnsafe() {
        assertFalse(UrlSecurity.isSafe("http://8.8.8.8"))
        assertFalse(UrlSecurity.isSafe("http://203.0.113.5:4000"))
    }

    @Test fun http_publicDomain_isUnsafe() {
        assertFalse(UrlSecurity.isSafe("http://teslamate.example.com"))
        assertFalse(UrlSecurity.isSafe("http://myserver.com"))
    }

    @Test fun blank_isUnsafe() {
        assertFalse(UrlSecurity.isSafe(""))
        assertFalse(UrlSecurity.isSafe("   "))
    }

    @Test fun malformed_isUnsafe() {
        assertFalse(UrlSecurity.isSafe("not a url"))
        assertFalse(UrlSecurity.isSafe("http://"))
    }
}
