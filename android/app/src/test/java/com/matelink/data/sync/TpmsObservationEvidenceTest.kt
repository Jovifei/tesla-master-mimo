package com.matelink.data.sync

import com.matelink.data.api.models.TpmsDetails
import com.matelink.data.local.TirePosition
import com.matelink.data.local.entity.TpmsPressureSample
import com.matelink.data.local.entity.TPMS_VERIFIED_COLLECTOR
import com.matelink.data.repository.mergeTpmsSampleAtSameInstant
import com.matelink.data.repository.novelTpmsSampleAtSameInstant
import com.matelink.domain.analytics.TpmsChangeRule
import com.matelink.domain.analytics.pressureChangesAcrossWheels
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class TpmsObservationEvidenceTest {
    private val now=Instant.parse("2026-10-11T04:00:00Z").toEpochMilli()
    private val first="2026-10-11T03:59:00.417Z"
    private val second="2026-10-11T03:59:01.417Z"
    private fun evidence()=TpmsDetails(
        pressureFl=2.5,pressureFr=2.7,pressureRl=0.0,pressureUnit="bar",
        pressureSource="telemetry_mqtt",pressureTimeBasis="collector_received_at",
        pressureObservedAtFl=first,pressureObservedAtFr=second,pressureObservedAtRl=first
    )
    @Test fun moshiContractUsesTwoActualCollectorInstantsAndPreservesZero() {
        val adapter=Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(TpmsDetails::class.java)
        val parsed=adapter.fromJson(adapter.toJson(evidence()))!!
        val rows=verifiedTpmsSamples(-7,parsed,now)
        assertEquals(2,rows.size)
        assertEquals(Instant.parse(first).toEpochMilli(),rows[0].observedAt)
        assertEquals(2.5,rows[0].pressureFl!!,0.0)
        assertEquals(0.0,rows[0].pressureRl!!,0.0)
        assertNull(rows[0].pressureFr)
        assertEquals(Instant.parse(second).toEpochMilli(),rows[1].observedAt)
        assertEquals(2.7,rows[1].pressureFr!!,0.0)
        assertNull(rows[1].pressureFl)
        assertEquals(TPMS_VERIFIED_COLLECTOR,rows[0].provenance)
        assertNull(parsed.onlyVerifiedWheels(rows).warningFl)
        assertNull(parsed.onlyVerifiedWheels(rows).pressureRr)
    }
    @Test fun perWheelAlertEvaluationNeverAssignsAnEarlierWheelToTheLaterWheelTime() {
        val raw=evidence()
        val rows=verifiedTpmsSamples(-7,raw,now)
        val firstSlice=raw.onlyVerifiedWheels(listOf(rows[0]))
        val secondSlice=raw.onlyVerifiedWheels(listOf(rows[1]))
        assertEquals(2.5,firstSlice.pressureFl!!,0.0)
        assertEquals(0.0,firstSlice.pressureRl!!,0.0)
        assertNull(firstSlice.pressureFr)
        assertNull(secondSlice.pressureFl)
        assertNull(secondSlice.pressureRl)
        assertEquals(2.7,secondSlice.pressureFr!!,0.0)
        assertNull(firstSlice.warningFl)
        assertNull(secondSlice.warningFl)
        assertEquals(listOf(Instant.parse(first).toEpochMilli(), Instant.parse(second).toEpochMilli()),
            rows.map { it.observedAt })
    }
    @Test fun oldAPIArchiveBadUnitAndUntrustedFutureTimeCannotInventTrend() {
        assertTrue(verifiedTpmsSamples(-7,TpmsDetails(pressureFl=2.5),now).isEmpty())
        assertTrue(verifiedTpmsSamples(-7,evidence().copy(pressureSource="local_import"),now).isEmpty())
        assertTrue(verifiedTpmsSamples(-7,evidence().copy(pressureTimeBasis="source_sample_time"),now).isEmpty())
        assertTrue(verifiedTpmsSamples(-7,evidence().copy(pressureUnit="psi"),now).isEmpty())
        assertTrue(verifiedTpmsSamples(-7,evidence().copy(pressureObservedAtFl="bad",
            pressureObservedAtFr=null,pressureObservedAtRl=null),now).isEmpty())
        assertTrue(verifiedTpmsSamples(-7,evidence().copy(pressureObservedAtFl="2026-10-11T04:05:00Z",
            pressureObservedAtFr=null,pressureObservedAtRl=null),now).isEmpty())
    }
    @Test fun sameInstantMergesPartialWheelsWithoutPromotingLegacyOrOverwritingZero() {
        val first=TpmsPressureSample(-7,1000,pressureFl=0.0,provenance=TPMS_VERIFIED_COLLECTOR)
        val extra=TpmsPressureSample(-7,1000,pressureFr=2.8,provenance=TPMS_VERIFIED_COLLECTOR)
        val merged=mergeTpmsSampleAtSameInstant(first,extra)
        assertEquals(0.0,merged.pressureFl!!,0.0)
        assertEquals(2.8,merged.pressureFr!!,0.0)
        assertEquals(merged,mergeTpmsSampleAtSameInstant(merged,extra))
        val legacy=TpmsPressureSample(-7,1000,pressureFl=9.0,provenance="legacy_unverified")
        assertEquals(legacy,mergeTpmsSampleAtSameInstant(legacy,first))
        val oldPoll=first.copy(provenance="provider_observation")
        assertEquals(oldPoll,mergeTpmsSampleAtSameInstant(oldPoll,first))
        assertEquals(first,mergeTpmsSampleAtSameInstant(first,first.copy(carId=-8)))
    }

    @Test fun newCollectorFieldIsAdmittedOnlyOncePerTimestampAndWheel() {
        val one=TpmsPressureSample(-7,1000,pressureFl=2.5,provenance=TPMS_VERIFIED_COLLECTOR)
        val repeat=one.copy(pressureFl=2.5)
        val conflict=one.copy(pressureFl=2.7)
        val secondWheel=one.copy(pressureFl=null,pressureFr=2.8)
        assertEquals(one,novelTpmsSampleAtSameInstant(null,one))
        assertNull(novelTpmsSampleAtSameInstant(one,repeat))
        assertNull(novelTpmsSampleAtSameInstant(one,conflict))
        assertEquals(secondWheel,novelTpmsSampleAtSameInstant(one,secondWheel))
        val merged=mergeTpmsSampleAtSameInstant(one,secondWheel)
        assertEquals(2.5,merged.pressureFl!!,0.0)
        assertEquals(2.8,merged.pressureFr!!,0.0)
        assertNull(novelTpmsSampleAtSameInstant(merged,secondWheel))
        assertNull(novelTpmsSampleAtSameInstant(
            one.copy(provenance="provider_observation"),one))
        assertNull(novelTpmsSampleAtSameInstant(
            one.copy(carId=-8),one))
    }

    @Test fun partialWheelChangeUsesItsOwnLatestVerifiedPrevious() {
        val history=listOf(
            TpmsPressureSample(-7,1000,pressureFl=2.8,provenance=TPMS_VERIFIED_COLLECTOR),
            TpmsPressureSample(-7,2000,pressureFr=3.0,provenance=TPMS_VERIFIED_COLLECTOR),
            TpmsPressureSample(-8,3000,pressureFl=4.0,provenance=TPMS_VERIFIED_COLLECTOR),
            TpmsPressureSample(-7,3200,pressureFl=1.0,provenance="legacy_unverified"),
            TpmsPressureSample(-7,3300,pressureFl=9.0,provenance="provider_observation")
        )
        val current=TpmsPressureSample(-7,4000,pressureFl=2.4,provenance=TPMS_VERIFIED_COLLECTOR)
        val changes=pressureChangesAcrossWheels(history,current,TpmsChangeRule(deltaBar=0.3))
        assertEquals(1,changes.size)
        assertEquals(TirePosition.FL,changes.single().wheel)
        assertEquals(2.8,changes.single().before,0.0)
        assertEquals(2.4,changes.single().after,0.0)
        assertTrue(pressureChangesAcrossWheels(history,current.copy(observedAt=1000),TpmsChangeRule()).isEmpty())
    }
}
