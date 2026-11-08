package com.matelink.ui.screens.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Reflection keeps the RED suite compilable before the new production boundary exists. */
class ArchiveEnrollmentLinkTest {
    private val handle = "a1".repeat(32)
    private val base = "https://auth.teslalink.joviluma.com/archive/enroll"

    private fun parse(raw: String?): Any? {
        val type = try { Class.forName("com.matelink.ui.screens.archive.ArchiveEnrollmentLink") }
        catch (_: ClassNotFoundException) { throw AssertionError("Archive enrollment strict link parser is missing") }
        return type.getMethod("parse", String::class.java).invoke(type.getField("INSTANCE").get(null), raw)
    }

    @Test fun exactHttpsLinkReturnsOnlyPublicHandle() { assertEquals(handle, parse("$base?handle=$handle")) }

    @Test fun foreignOriginsUserinfoPortsAndFragmentsAreRejected() {
        listOf(
            "http://auth.teslalink.joviluma.com/archive/enroll?handle=$handle",
            "https://evil.example/archive/enroll?handle=$handle",
            "https://auth.teslalink.joviluma.com.evil.example/archive/enroll?handle=$handle",
            "https://evil@auth.teslalink.joviluma.com/archive/enroll?handle=$handle",
            "https://auth.teslalink.joviluma.com:443/archive/enroll?handle=$handle",
            "$base?handle=$handle#", "$base?handle=$handle#credential"
        ).forEach { assertNull(it, parse(it)) }
    }

    @Test fun DuplicateUnknownAndEncodedQueriesAreRejected() {
        listOf("handle=$handle&handle=$handle", "handle=$handle&device_secret=secret",
            "handle=$handle&ticket=oauth", "handle=$handle&approve=true", "handle=$handle&",
            "handle=$handle;credential=secret", "%68andle=$handle", "handle=%61${handle.drop(1)}")
            .forEach { assertNull(it, parse("$base?$it")) }
    }

    @Test fun MalformedPathsAndNonCanonicalHandlesAreRejected() {
        listOf(null, "", "$base/../enroll?handle=$handle", "$base/?handle=$handle",
            "https://auth.teslalink.joviluma.com/oauth/callback?ticket=$handle",
            "$base?handle=", "$base?handle=${handle.uppercase()}", "$base?handle=${"a".repeat(63)}",
            "$base?handle=${"a".repeat(65)}", "$base?handle= $handle", "$base?handle=$handle\n")
            .forEach { assertNull(it, parse(it)) }
    }
}
