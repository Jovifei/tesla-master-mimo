package com.matelink.domain.analytics

import com.matelink.data.api.models.ChargeData
import com.matelink.data.local.entity.ChargeCostOverride
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeCostProjectionContractTest {
    private fun charge(
        sourceInstanceId: String? = null,
        sourceId: String? = null,
        start: String = "2026-10-20T10:00:00Z",
        end: String = "2026-10-20T11:00:00Z",
        cost: Double? = null,
        currencyCode: String? = null
    ) = ChargeData(
        chargeId = 7, startDate = start, endDate = end, source = "teslamate_archive",
        sourceInstanceId = sourceInstanceId, sourceId = sourceId, cost = cost,
        currencyCode = currencyCode
    )

    private fun manual(
        amount: Double = 12.34,
        currencyCode: String? = "CNY",
        sourceInstanceId: String? = null,
        start: String? = "2026-10-20T18:00:00+08:00",
        end: String? = "2026-10-20T19:00:00+08:00",
        version: Long = 1L
    ) = ChargeCostOverride(
        carId = -3, chargeId = 7, manualTotalAmount = amount,
        currencyCode = currencyCode, sourceKind = "teslamate_archive",
        sourceInstanceId = sourceInstanceId, startInstant = start, endInstant = end,
        version = version
    )

    @Test fun sameLocalSessionWithoutUpstreamInstanceKeepsUserAmountAndMarksEvidenceLimit() {
        val result = projectEffectiveChargeCost(charge(), -3, manual(), "CNY")
        assertEquals(12.34, result.cost!!, 0.001)
        assertEquals(ChargeCostSource.MANUAL, result.source)
        assertEquals("instance_unverified", result.qualificationReason)
        assertFalse(result.sourceInstanceVerified)
    }

    @Test fun manualZeroIsKnownAndAChangedCurrencyCannotRelabelIt() {
        val zero = projectEffectiveChargeCost(charge(), -3, manual(amount = 0.0), "CNY")
        assertEquals(0.0, zero.cost!!, 0.0)
        val changed = projectEffectiveChargeCost(charge(), -3, manual(amount = 0.0), "USD")
        assertNull(changed.cost)
        assertEquals("currency_mismatch", changed.qualificationReason)
    }

    @Test fun oldAmountWithoutCurrencyOrWindowRemainsPending() {
        val old = ChargeCostOverride(-3, 7, 20.0)
        val result = projectEffectiveChargeCost(charge(), -3, old, "CNY")
        assertNull(result.cost)
        assertEquals("legacy_unverified", result.qualificationReason)
    }

    @Test fun reusedNumericIdDoesNotInheritDifferentSessionOrSourceInstance() {
        val changedWindow = projectEffectiveChargeCost(
            charge(start = "2026-10-21T10:00:00Z"), -3, manual(), "CNY")
        assertNull(changedWindow.cost)
        assertEquals("session_mismatch", changedWindow.qualificationReason)

        val changedInstance = projectEffectiveChargeCost(
            charge(sourceInstanceId = "instance-b"), -3,
            manual(sourceInstanceId = "instance-a"), "CNY")
        assertNull(changedInstance.cost)
        assertEquals("source_instance_mismatch", changedInstance.qualificationReason)
    }

    @Test fun verifiedInstanceAndRecordIdQualifyOnlyExactMatch() {
        val bound = manual(sourceInstanceId = "instance-a").copy(sourceRecordId = "charge-7")
        val valid = projectEffectiveChargeCost(
            charge(sourceInstanceId = "instance-a", sourceId = "charge-7"), -3, bound, "CNY")
        assertEquals(12.34, valid.cost!!, 0.001)
        assertTrue(valid.sourceInstanceVerified)
        val differentId = projectEffectiveChargeCost(
            charge(sourceInstanceId = "instance-a", sourceId = "charge-8"), -3, bound, "CNY")
        assertNull(differentId.cost)
    }

    @Test fun providerZeroAndMissingCurrencyDoNotBecomeComparableBills() {
        val zero = projectEffectiveChargeCost(charge(cost = 0.0, currencyCode = "CNY"), -3, null, "CNY")
        assertNull(zero.cost)
        val unknownCurrency = projectEffectiveChargeCost(charge(cost = 8.0), -3, null, "CNY")
        assertNull(unknownCurrency.cost)
        val known = projectEffectiveChargeCost(charge(cost = 8.0, currencyCode = "CNY"), -3, null, "CNY")
        assertEquals(8.0, known.cost!!, 0.0)
        assertEquals(ChargeCostSource.TESLAMATE, known.source)
        val missingSource = projectEffectiveChargeCost(
            charge(cost = 8.0, currencyCode = "CNY").copy(source = null), -3, null, "CNY")
        assertNull(missingSource.cost)
        assertEquals("source_unverified", missingSource.qualificationReason)
        val otherCurrency = projectEffectiveChargeCost(
            charge(cost = 8.0, currencyCode = "USD"), -3, null, "CNY")
        assertNull(otherCurrency.cost)
        assertEquals("currency_mismatch", otherCurrency.qualificationReason)
    }

    @Test fun twoSameNumericIdSessionsRetainSeparateManualReceipts() {
        val firstCharge = charge(start = "2026-10-20T10:00:00Z")
        val secondCharge = charge(start = "2026-10-21T10:00:00Z",
            end = "2026-10-21T11:00:00Z")
        val first = manual().copy(sessionKey = chargeCostSessionKey(firstCharge))
        val second = manual(amount = 45.0, start = secondCharge.startDate,
            end = secondCharge.endDate)
            .copy(sessionKey = chargeCostSessionKey(secondCharge))
        assertEquals(2, listOf(first, second).map { it.sessionKey }.distinct().size)
        assertEquals(first, findChargeCostOverride(listOf(first, second), -3, firstCharge))
        assertEquals(second, findChargeCostOverride(listOf(first, second), -3, secondCharge))
    }

    @Test fun sameSessionKeepsSeparateUserReceiptsForDifferentEditCurrencies() {
        val session = charge()
        val base = chargeCostSessionKey(session)
        val cny = manual(amount = 20.0).copy(sessionKey = "$base:CNY")
        val usd = manual(amount = 5.0, currencyCode = "USD").copy(sessionKey = "$base:USD")
        val records = listOf(cny, usd)
        assertNull(findChargeCostOverride(records, -3, session))
        assertEquals(20.0, projectEffectiveChargeCost(session, -3,
            findChargeCostOverride(records, -3, session, "CNY"), "CNY").cost!!, 0.0)
        assertEquals(5.0, projectEffectiveChargeCost(session, -3,
            findChargeCostOverride(records, -3, session, "USD"), "USD").cost!!, 0.0)
        assertEquals(cny, findOtherCurrencyChargeCostOverride(records, -3, session, "USD"))
    }

    @Test fun sessionKeyUsesInstantAndDoesNotBorrowAcrossNewInstance() {
        val original = charge()
        val sameInstant = charge(start = "2026-10-20T18:00:00+08:00",
            end = "2026-10-20T19:00:00+08:00")
        assertEquals(chargeCostSessionKey(original), chargeCostSessionKey(sameInstant))
        val record = manual().copy(sessionKey = chargeCostSessionKey(original) + ":CNY")
        assertEquals(record, findChargeCostOverride(listOf(record), -3, sameInstant, "CNY"))
        assertNull(findChargeCostOverride(listOf(record), -3,
            charge(sourceInstanceId = "new-instance"), "CNY"))
    }
}
