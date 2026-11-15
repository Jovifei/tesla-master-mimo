package com.matelink.ui.screens.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import com.matelink.data.api.models.ArchiveEnrollmentDecision
import com.matelink.data.api.models.ArchiveEnrollmentPreview
import com.squareup.moshi.Moshi

class ArchiveEnrollmentBoundaryTest {
    private fun source(relative: String): String = File("src/main/$relative").readText()
    private fun type(name: String): Class<*> = try { Class.forName(name) }
        catch (_: ClassNotFoundException) { throw AssertionError("Missing archive owner-only DTO: $name") }

    @Test fun ownerDtosCannotCarryHelperProofOrCredentials() {
        val preview = type("com.matelink.data.api.models.ArchiveEnrollmentPreview")
        assertEquals(setOf("handle", "status", "verificationCode", "expiresAt", "vehicleId", "vehicleName",
            "sourceLabel", "scopeFingerprint", "bindingState"),
            preview.declaredFields.filter { !it.isSynthetic && !Modifier.isStatic(it.modifiers) }.map { it.name }.toSet())
        val decision = type("com.matelink.data.api.models.ArchiveEnrollmentDecision")
        assertEquals(setOf("approve", "verificationCode"), decision.declaredFields
            .filter { !it.isSynthetic && !Modifier.isStatic(it.modifiers) }.map { it.name }.toSet())
    }

    @Test fun manifestDeclaresSeparateExactArchiveAppLink() {
        val manifest = source("AndroidManifest.xml")
        assertTrue(manifest.contains("android:path=\"/archive/enroll\""))
        assertTrue(manifest.contains("android:host=\"auth.teslalink.joviluma.com\""))
        assertFalse(manifest.contains("android:pathPrefix=\"/archive"))
    }

    @Test fun navGraphHostsIndependentDialog() {
        val nav = source("java/com/matelink/ui/navigation/NavGraph.kt")
        assertTrue(nav.contains("ArchiveEnrollmentDialogHost("))
    }

    @Test fun enrollmentRepositoryUsesAuthenticatedCloudAndOnlyCachedVehicleProof() {
        val file = File("src/main/java/com/matelink/data/repository/ArchiveEnrollmentRepository.kt")
        assertTrue("Archive owner repository is missing", file.exists())
        val repository = file.readText()
        assertTrue(repository.contains("requiredCloudMode = true"))
        assertTrue(repository.contains("cachedContextForRemote"))
        assertTrue(repository.contains("cachedVerifiedStatusIdentityDigest"))
        listOf("getCars(", "discoverHistoryCar(", "getHistoryContext(", "device_secret", "credential",
            "configureTelemetry(", "getDataReadiness(").forEach { assertFalse(it, repository.contains(it)) }
    }

    @Test fun generatedJsonAdaptersKeepTheSixDigitCodeAndIntegerVehicleId() {
        val moshi = Moshi.Builder().build()
        val json = """{"handle":"${"ab".repeat(32)}","status":"pending","verification_code":"001234","expires_at":"2026-10-29T04:10:00Z","vehicle_id":7,"vehicle_name":"Current vehicle","source_label":"TeslaMate","scope_fingerprint":"0123456789abcdef","binding_state":"expired"}"""
        val preview = requireNotNull(moshi.adapter(ArchiveEnrollmentPreview::class.java).fromJson(json))
        assertEquals(7, preview.vehicleId)
        assertEquals("001234", preview.verificationCode)
        val body = moshi.adapter(ArchiveEnrollmentDecision::class.java).toJson(ArchiveEnrollmentDecision(true, "001234"))
        val decoded = requireNotNull(moshi.adapter(Map::class.java).fromJson(body))
        assertEquals(mapOf("approve" to true, "verification_code" to "001234"), decoded)
    }

    @Test fun dialogCopyHasMatchingEnglishChineseKeysAndValidStringFormats() {
        fun strings(locale: String): Map<String, String> {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/$locale/strings.xml"))
            val entries = doc.getElementsByTagName("string")
            return (0 until entries.length).map { entries.item(it) }.associate {
                it.attributes.getNamedItem("name").nodeValue to it.textContent
            }.filterKeys { it.startsWith("archive_enrollment_") }
        }
        val en = strings("values"); val zh = strings("values-zh")
        assertEquals(en.keys, zh.keys)
        assertEquals(19, en.size)
        listOf(en, zh).forEach { entries ->
            listOf("vehicle", "source", "fingerprint", "expires").forEach {
                assertTrue(String.format(Locale.ROOT, entries.getValue("archive_enrollment_$it"), "VALUE").contains("VALUE"))
            }
        }
    }
}
