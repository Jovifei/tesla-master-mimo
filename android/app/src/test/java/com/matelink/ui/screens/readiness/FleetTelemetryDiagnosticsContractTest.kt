package com.matelink.ui.screens.readiness

import com.matelink.data.api.TeslamateApi
import com.matelink.data.api.models.*
import com.matelink.data.repository.ApiResult
import com.squareup.moshi.Moshi
import org.junit.Assert.*
import org.junit.Test
import retrofit2.http.GET
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Runtime lookup keeps the missing-client RED phase executable before the DTO exists. */
class FleetTelemetryDiagnosticsContractTest {
    @Test fun officialEndpointIsAGetOnTheExistingAuthenticatedApi() {
        val method = TeslamateApi::class.java.methods.singleOrNull { it.name == "getTelemetryDiagnostics" }
        assertNotNull("the authenticated API must expose the read-only diagnostic endpoint", method)
        assertEquals("api/v1/cars/{carId}/telemetry/diagnostics", method!!.getAnnotation(GET::class.java)?.value)
    }

    @Test fun missingAndPartialFieldsRemainUnknownRatherThanHealthyDefaults() {
        val model = parse("""{"data":{"checked_at":"2026-10-29T00:00:00Z","availability":"partial","requests":{"fleet_status":{"status":"error","http_status":403},"fleet_telemetry_config":{"status":"ok","http_status":200}},"vehicle":{"paired":true,"streaming_enabled":true},"configuration":{"synced":false}}}""")
        val projection = project(model)
        assertEquals("PARTIAL", getter(projection, "getAvailability").toString())
        assertNull("a failed status branch must not publish even a supplied true", getter(projection, "getPaired"))
        assertNull(getter(projection, "getStreamingEnabled"))
        assertEquals(false, getter(projection, "getConfigSynced"))
        assertNull(getter(projection, "getRecentErrorCount"))
    }

    @Test fun explicitFalseAndZeroAreRetainedFromSuccessfulBranches() {
        val model = parse("""{"data":{"availability":"available","requests":{"fleet_status":{"status":"ok","http_status":200},"fleet_telemetry_config":{"status":"ok","http_status":200},"fleet_telemetry_errors":{"status":"ok","http_status":200}},"vehicle":{"paired":false,"streaming_enabled":false},"configuration":{"synced":false,"matches_desired":{"hostname":false}},"recent_errors":{"counts":{}}}}""")
        val projection = project(model)
        assertEquals(false, getter(projection, "getPaired"))
        assertEquals(false, getter(projection, "getStreamingEnabled"))
        assertEquals(false, getter(projection, "getConfigSynced"))
        assertEquals(false, getter(projection, "getHostnameMatches"))
        assertEquals(0L, getter(projection, "getRecentErrorCount"))
    }

    @Test fun unknownAvailabilityOrRequestStatusCannotPromoteValues() {
        for (json in listOf(
            """{"data":{"availability":"future_state","requests":{"fleet_status":{"status":"ok","http_status":200}},"vehicle":{"paired":true}}}""",
            """{"data":{"availability":"available","requests":{"fleet_status":{"status":"future_status","http_status":200}},"vehicle":{"paired":true}}}"""
        )) assertNull(getter(project(parse(json)), "getPaired"))
    }

    @Test fun local404IsUnavailableAndDoesNotBecomeACompatibilitySuccess() {
        val projection = project(ApiResult.Error("raw upstream body must not escape", 404))
        assertEquals("UNAVAILABLE", getter(projection, "getAvailability").toString())
        assertEquals(404, getter(projection, "getHttpStatus"))
        assertNull(getter(projection, "getPaired"))
        assertNull(getter(projection, "getConfigSynced"))
        assertFalse(projection.toString().contains("raw upstream"))
    }

    @Test fun emptySuccessfulEnvelopeCannotClaimOfficialAvailability() {
        val projection = project(parse("""{"data":{"availability":"available","requests":{},"vehicle":{},"configuration":{},"recent_errors":{}}}"""))
        assertEquals("UNAVAILABLE", getter(projection, "getAvailability").toString())
        assertNull(getter(projection, "getPaired"))
        assertNull(getter(projection, "getConfigSynced"))
        assertNull(getter(projection, "getRecentErrorCount"))
    }

    @Test fun officialSetupEnrichesDetailsWithoutPromotingReceiptHistoryOrDelivery() {
        val data = FleetTelemetryDiagnostics(availability = "available", requests = FleetDiagnosticRequests(
            FleetDiagnosticRequest("ok", 200), FleetDiagnosticRequest("ok", 200), FleetDiagnosticRequest("ok", 200)),
            vehicle = FleetDiagnosticVehicle(paired = true, streamingEnabled = true),
            configuration = FleetDiagnosticConfiguration(synced = true))
        val projection = officialFleetDiagnosticsPresentation(ApiResult.Success(data))
        val facts = withOfficialFleetDiagnostics(unknownDiagnosticFacts(), projection)
        assertTrue(facts.all { it.result == DiagnosticResult.UNKNOWN })
        assertNotNull(facts.first().officialFleet)
        assertNull(facts.last().officialFleet)
        assertEquals("collecting", officialFleetSetupStatus(projection)?.status)
        val blocked = withDiagnosticPermissionBlocker(facts)
        assertEquals(projection, blocked.first().officialFleet)
    }

    @Test fun negativeCountsAndUnrecognizedCategoriesCannotMasqueradeAsZeroErrors() {
        for (counts in listOf(mapOf("tls" to -1), mapOf("future_category" to 2))) {
            val data = FleetTelemetryDiagnostics(availability = "partial", requests = FleetDiagnosticRequests(
                fleetTelemetryErrors = FleetDiagnosticRequest("ok", 200)), recentErrors = FleetDiagnosticRecentErrors(counts))
            assertNull(officialFleetDiagnosticsPresentation(ApiResult.Success(data)).recentErrorCount)
        }
    }

    @Test fun detailResourcesFormatWithTheirActualStringAndIntegerArgumentsInBothLocales() {
        val formats = mapOf(
            "diagnostic_fleet_summary" to Array<Any>(4) { "fixture" },
            "diagnostic_fleet_vehicle" to Array<Any>(2) { "fixture" },
            "diagnostic_fleet_config" to Array<Any>(5) { "fixture" })
        for (locale in listOf("values", "values-zh")) {
            val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(File("src/main/res/$locale/strings.xml")).getElementsByTagName("string")
            for ((name, arguments) in formats) {
                val node = (0 until strings.length).map(strings::item)
                    .single { it.attributes.getNamedItem("name").nodeValue == name }
                assertFalse(String.format(java.util.Locale.ROOT, node.textContent, *arguments).contains("%"))
            }
        }
    }

    @Test fun existingVirtualKeyButtonDelegatesToTheScopedClickAction() {
        val screen = File("src/main/java/com/matelink/ui/screens/readiness/DataReadinessScreen.kt").readText()
        val handler = screen.substringAfter("onOpenVirtualKey = {").substringBefore("onConfigure =")
        assertTrue(handler.contains("viewModel.openExternalPairing"))
        assertFalse(handler.contains("uiState.pairing?.virtualKeyUrl"))
    }

    @Test fun providerStringsNeverReachThePresentationModel() {
        val rawHost = "LRW-FIXTURE-VIN.AUTH-FIXTURE.example.test"
        val rawVersion = "AUTH_FIXTURE_TOKEN"
        val rawBuild = "aabbccddeeff00112233445566778899aabbccdd"
        val data = FleetTelemetryDiagnostics(checkedAt = "2026-10-29T00:00:00Z", buildSha = rawBuild,
            availability = "available", requests = FleetDiagnosticRequests(
                FleetDiagnosticRequest("ok", 200), FleetDiagnosticRequest("ok", 200), FleetDiagnosticRequest("ok", 200)),
            vehicle = FleetDiagnosticVehicle(paired = true, firmwareVersion = rawVersion, fleetTelemetryVersion = rawVersion),
            configuration = FleetDiagnosticConfiguration(hostname = rawHost,
                desired = FleetDiagnosticDesiredConfiguration(hostname = rawHost),
                matchesDesired = FleetDiagnosticConfigurationMatches(hostname = true, port = true, fields = false, ca = null)))
        val projection = officialFleetDiagnosticsPresentation(ApiResult.Success(data))
        for (raw in listOf(rawHost, rawVersion, rawBuild)) assertFalse(projection.toString().contains(raw))
        assertEquals(true, projection.hostnameMatches)
        assertEquals(true, projection.portMatches)
        assertEquals(false, projection.fieldsMatch)
        assertNull(projection.caMatches)
        assertEquals(true, projection.serviceTargetMatches)
        assertNull(projection.copy(portMatches = null).serviceTargetMatches)
        assertEquals(false, projection.copy(hostnameMatches = false, portMatches = null).serviceTargetMatches)
    }

    private fun parse(json: String): ApiResult<Any> {
        val type = try { Class.forName("com.matelink.data.api.models.FleetTelemetryDiagnosticsResponse") }
        catch (_: ClassNotFoundException) { fail("missing Fleet diagnostic DTO"); error("unreachable") }
        val response = Moshi.Builder().build().adapter<Any>(type).fromJson(json)!!
        return ApiResult.Success(getter(response, "getData")!!)
    }

    private fun project(result: ApiResult<*>): Any {
        val type = try { Class.forName("com.matelink.ui.screens.readiness.FleetTelemetryDiagnosticsPresentationKt") }
        catch (_: ClassNotFoundException) { fail("missing safe Fleet diagnostic mapper"); error("unreachable") }
        return type.methods.single { it.name == "officialFleetDiagnosticsPresentation" }.invoke(null, result)!!
    }

    private fun getter(target: Any, name: String): Any? = target.javaClass.getMethod(name).invoke(target)
}
