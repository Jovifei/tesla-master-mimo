package com.matelink.data.sync

import com.matelink.data.api.models.TpmsDetails
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class TpmsWarningRecoveryEvidenceTest {
    private val now = Instant.parse("2026-10-11T04:00:00Z").toEpochMilli()
    private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(TpmsDetails::class.java)
    // Additive Go collector wire contract, parsed through the real Moshi adapter.
    private fun raw(extra: String) = """{
      "pressure_unit":"bar","source":"telemetry_mqtt","time_basis":"collector_received_at",
      "tpms_pressure_fl":0.0,"tpms_pressure_fr":2.5,"tpms_pressure_rl":2.5,"tpms_pressure_rr":2.5,
      "tpms_pressure_fl_observed_at":"2026-10-11T03:59:00Z",
      "tpms_pressure_fr_observed_at":"2026-10-11T03:59:01Z",
      "tpms_pressure_rl_observed_at":"2026-10-11T03:59:02Z",
      "tpms_pressure_rr_observed_at":"2026-10-11T03:59:03Z",
      "tpms_soft_warning_fl":true,"tpms_soft_warning_fr":false,"tpms_soft_warning_rl":false,"tpms_soft_warning_rr":false,
      $extra
    }"""
    private val qualified = """
      "warning_source":"telemetry_mqtt","warning_time_basis":"collector_received_at",
      "tpms_soft_warning_fl_observed_at":"2026-10-11T03:59:05Z",
      "tpms_soft_warning_fr_observed_at":"2026-10-11T03:59:05Z",
      "tpms_soft_warning_rl_observed_at":"2026-10-11T03:59:05Z",
      "tpms_soft_warning_rr_observed_at":"2026-10-11T03:59:05Z"
    """
    @Test fun fourDifferentPressureReceiptsMustNotEraseIndependentQualifiedWarnings() {
        // The Go test asserts its actual HTTP status output equals this same
        // resource, so wire/model/evidence-consumer drift fails across layers.
        val wire = requireNotNull(javaClass.classLoader?.getResourceAsStream("tpms_warning_collector_contract.json"))
            .bufferedReader().use { it.readText() }
        val parsed = adapter.fromJson(wire)!!
        val rows = verifiedTpmsSamples(-7, parsed, now)
        assertEquals(4, rows.size)
        assertEquals(0.0, rows.first().pressureFl!!, 0.0)
        for (row in rows) {
            val slice = parsed.onlyVerifiedWheels(listOf(row))
            assertNull("pressure consumer must not borrow the warning clock", slice.warningFl)
            assertNull(slice.warningFr)
        }
        val warnings = verifiedTpmsWarnings(parsed, now)
        assertEquals(1, warnings.size)
        assertEquals(Instant.parse("2026-10-11T03:59:05Z").toEpochMilli(), warnings.single().observedAt)
        assertEquals("independent FL soft warning must survive four asynchronous pressure receipts", true, warnings.single().details.warningFl)
        assertEquals(false, warnings.single().details.warningFr)
        assertNull(warnings.single().details.pressureFl)
    }
    @Test fun pressureCompletenessCannotPromoteWarningsWithMissingSourceOrTime() {
        for (extra in listOf("\"warning_source\":\"telemetry_mqtt\"", "\"warning_time_basis\":\"collector_received_at\"")) {
            val parsed = adapter.fromJson(raw(extra))!!
            val projected = parsed.onlyVerifiedWheels(verifiedTpmsSamples(-7, parsed, now))
            assertNull("pressure receipts promoted an unqualified warning", projected.warningFl)
            assertTrue("warning consumer promoted missing source/time", verifiedTpmsWarnings(parsed, now).isEmpty())
        }
    }

    @Test fun separateWarningTimesPreservePartialTrueFalseWithoutFillingUnknownWheels() {
        val parsed = adapter.fromJson(raw(qualified))!!.copy(
            warningObservedAtFr = "2026-10-11T03:59:06Z", warningObservedAtRl = null,
            warningObservedAtRr = null
        )
        val warnings = verifiedTpmsWarnings(parsed, now)
        assertEquals(2, warnings.size)
        assertEquals(true, warnings[0].details.warningFl)
        assertNull(warnings[0].details.warningFr)
        assertNull(warnings[0].details.warningRl)
        assertNull(warnings[1].details.warningFl)
        assertEquals(false, warnings[1].details.warningFr)
        assertEquals("2026-10-11T03:59:06Z", warnings[1].details.warningObservedAtFr)
        assertNull(warnings[1].details.warningRr)
    }

    @Test fun warningCollectorSourceTimeBasisAndWheelClocksFailClosed() {
        val parsed = adapter.fromJson(raw(qualified))!!
        val warningOnly = parsed.copy(warningFr=null, warningRl=null, warningRr=null)
        for (invalid in listOf(
            warningOnly.copy(warningSource=null), warningOnly.copy(warningSource="fleet_api"),
            warningOnly.copy(warningSource="local_import"),
            warningOnly.copy(warningTimeBasis="source_sample_time"),
            warningOnly.copy(warningTimeBasis=null),
            warningOnly.copy(warningObservedAtFl=null), warningOnly.copy(warningObservedAtFl="bad"),
            warningOnly.copy(warningObservedAtFl="1970-01-01T00:00:00Z"),
            warningOnly.copy(warningObservedAtFl="2026-10-11T04:00:00.001Z"),
            warningOnly.copy(warningObservedAtFl="2026-09-01T04:00:00Z")
        )) assertTrue("invalid warning was admitted: $invalid", verifiedTpmsWarnings(invalid, now).isEmpty())
        assertEquals(true, verifiedTpmsWarnings(warningOnly.copy(pressureSource=null,
            pressureFl=null, pressureFr=null, pressureRl=null, pressureRr=null), now).single().details.warningFl)
    }

    @Test fun moshiCannotConvertNumericZeroOrTextFalseIntoTeslaWarningEvidence() {
        for (invalid in listOf("0", "\"false\"")) {
            val wire = raw(qualified).replace("\"tpms_soft_warning_fl\":true", "\"tpms_soft_warning_fl\":$invalid")
            assertTrue("invalid wire boolean was coerced", runCatching { adapter.fromJson(wire) }.isFailure)
        }
        val missing = adapter.fromJson(raw(qualified).replace("\"tpms_soft_warning_fl\":true", "\"tpms_soft_warning_fl\":null"))!!
        assertNull(verifiedTpmsWarnings(missing, now).single().details.warningFl)
        assertNull(verifiedTpmsWarnings(missing, now).single().details.warningObservedAtFl)
    }
}
