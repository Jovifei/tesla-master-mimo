package com.matelink.domain.analytics

import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DrivesResponse
import com.matelink.data.api.models.EnergyContract
import com.matelink.data.api.models.EnergyMetric
import com.matelink.data.api.models.netValueForWindow
import com.matelink.data.api.models.receivedDrives
import com.matelink.data.local.entity.DriveSummary
import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.model.UnitFormatter
import com.matelink.ui.screens.drives.historyEnergy
import com.matelink.ui.screens.drives.toQualifiedHistoryMetrics
import com.squareup.moshi.Moshi
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Locale

/** The same wire fixtures also exercise Mini's actual API/cache/page consumer. */
class MiniAppSharedParityTest {
    private val moshi = Moshi.Builder().build()
    private val any = moshi.adapter(Any::class.java)
    private val response = moshi.adapter(DrivesResponse::class.java)

    @Suppress("UNCHECKED_CAST")
    private fun row(value: Any?) = value as Map<String, Any?>

    private fun parsed(value: Any?): DriveData = requireNotNull(response.fromJson(any.toJson(value)))
        .data!!.receivedDrives().single().withQualifiedEnergy()

    private fun persist(drive: DriveData, model: String?, prior: DriveSummary? = null): DriveSummary {
        val detail = drive.asCachedDetail()
        return (prior ?: drive.toSyncSummary(7)!!).withResolvedDriveEnergy(detail,
            detail.resolveDriveEnergy(model = model))
    }

    private fun assertNumber(name: String, expected: Any?, actual: Double?) {
        if (expected == null) assertNull(name, actual)
        else assertEquals(name, (expected as Number).toDouble(), actual ?: Double.NaN, 1e-9)
    }

    @Test fun persistedDefaultCapacityProofCannotRequalifyAnUnknownModel() {
        val start = "2026-10-14T00:00:00Z"
        val end = "2026-10-14T00:10:00Z"
        val metric = EnergyMetric(valueKwh = 1.2, method = "soc_capacity_estimate", measurementPoint = "battery_soc",
            source = "telemetry_mqtt", quality = "estimated", startDate = start, endDate = end,
            coverageKind = "endpoints", coverageRatio = 1.0, capacityKwh = 60.0, capacityBasis = "default_assumption",
            calculationVersion = 1, startSoc = 80, endSoc = 78)
        assertNull(EnergyContract(netEnergy = metric).netValueForWindow(start, end))
        assertEquals(1.2, EnergyContract(netEnergy = metric.copy(capacityBasis = "provided_capacity"))
            .netValueForWindow(start, end)!!, 1e-12)
    }

    @Test fun sharedWireScenariosRebuildQualifiedHistoryAndActualCardPresentation() {
        val fixture = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .map { File(it, "fixtures/miniprogram-app-parity-v1.json") }.first { it.isFile }
        val root = row(any.fromJson(fixture.readText()))
        assertEquals(1.0, root["version"])
        val cases = root["cases"] as List<*>
        assertEquals(5, cases.size)
        val oldLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            cases.forEach { value ->
                val case = row(value)
                val name = case["name"] as String
                val expected = row(case["expected"])
                val car = row(case["car"])
                val model = car["model"] as String?
                val previous = case["previousResponse"]?.let { persist(parsed(it), model) }
                if (previous != null) assertNotNull(previous.toQualifiedHistoryMetrics().energyKwh)
                val received = parsed(case["response"])
                val saved = persist(received, model, previous)
                // A new summary object must rebuild its projection from persisted JSON evidence.
                val reopened = saved.copy(apiEvidence = saved.apiEvidence!!.toByteArray().toString(Charsets.UTF_8))
                val canonical = reopened.toAnalysisDriveData()
                val card = canonical.historyEnergy(reopened.toQualifiedHistoryMetrics())
                assertNumber("$name distance", expected["distanceKm"], canonical.distanceKm)
                assertNumber("$name energy", expected["energyKwh"], card.energyKwh)
                assertNumber("$name average", expected["averageKwh100km"], card.efficiencyWhKm?.div(10))
                val quality = if (card.energyKwh == null) "unknown" else if (card.isEstimated) "estimated" else "reported"
                assertEquals("$name quality", expected["quality"], quality)
                card.efficiencyWhKm?.let { efficiency ->
                    assertEquals("$name complete display unit",
                        String.format(Locale.US, "%.1f kWh/100km", (expected["averageKwh100km"] as Number).toDouble()),
                        UnitFormatter.formatEfficiency(efficiency, null))
                }
                assertEquals("$name source", received.source, canonical.source)
                assertEquals("$name window", received.startDate, canonical.startDate)
            }
        } finally { Locale.setDefault(oldLocale) }
    }
}
