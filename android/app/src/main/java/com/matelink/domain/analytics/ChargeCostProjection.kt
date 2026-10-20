package com.matelink.domain.analytics

import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.ChargeDetail
import com.matelink.data.local.entity.ChargeCostOverride
import com.matelink.data.model.Currency
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale
import java.security.MessageDigest

/** An opaque key keeps two different sessions with the same numeric charge ID separate. */
fun chargeCostSessionKey(charge: ChargeData): String {
    val parts = listOf(
        charge.source.orEmpty().trim(), charge.sourceInstanceId.orEmpty().trim(),
        charge.sourceId.orEmpty().trim(),
        chargeCostInstant(charge.startDate)?.toString() ?: "unverified:${charge.startDate.orEmpty()}",
        chargeCostInstant(charge.endDate)?.toString() ?: "unverified:${charge.endDate.orEmpty()}"
    )
    val input = parts.joinToString("") { "${it.length}:$it" }
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return "session:$digest"
}

fun findChargeCostOverride(
    records: List<ChargeCostOverride>, localHistoryCarId: Int, charge: ChargeData,
    selectedCurrencyCode: String? = null
): ChargeCostOverride? {
    val key = chargeCostSessionKey(charge)
    val candidates = records.filter {
        it.carId == localHistoryCarId && it.chargeId == charge.chargeId &&
            (it.sessionKey == key || it.sessionKey.startsWith("$key:"))
    }
    val currency = selectedCurrencyCode?.trim()?.uppercase(Locale.ROOT)
    return if (currency == null) candidates.singleOrNull()
    else candidates.singleOrNull { it.currencyCode?.trim()?.uppercase(Locale.ROOT) == currency }
}

/** A saved amount in another currency is visible as evidence, never silently converted. */
fun findOtherCurrencyChargeCostOverride(
    records: List<ChargeCostOverride>, localHistoryCarId: Int, charge: ChargeData,
    selectedCurrencyCode: String
): ChargeCostOverride? {
    val key = chargeCostSessionKey(charge)
    val selected = selectedCurrencyCode.trim().uppercase(Locale.ROOT)
    return records.firstOrNull { record ->
        record.carId == localHistoryCarId && record.chargeId == charge.chargeId &&
            record.sessionKey.startsWith("$key:") && !record.deleted &&
            record.currencyCode != null &&
            record.currencyCode.trim().uppercase(Locale.ROOT) != selected &&
            projectEffectiveChargeCost(charge, localHistoryCarId, record, record.currencyCode).source == ChargeCostSource.MANUAL
    }
}

fun ChargeDetail.toCostChargeData(): ChargeData = ChargeData(
    chargeId = chargeId,
    startDate = startDate,
    endDate = endDate,
    cost = cost,
    chargeEnergyAdded = chargeEnergyAdded,
    energyContract = energyContract,
    source = source,
    sourceInstanceId = sourceInstanceId,
    sourceId = sourceId,
    currencyCode = currencyCode
)

/** A single source for charge cost qualification across history consumers. */
fun projectEffectiveChargeCost(
    charge: ChargeData,
    localHistoryCarId: Int,
    manual: ChargeCostOverride?,
    selectedCurrencyCode: String,
    freeSupercharging: Boolean = false,
    isDcCharge: Boolean = false,
    defaultPricePerKwh: Double? = null
): EffectiveChargeCost {
    val currency = selectedCurrencyCode.trim().uppercase(Locale.ROOT)
    if (Currency.entries.none { it.code == currency }) return unavailable("currency_unverified")
    if (manual != null && !manual.deleted) {
        val reason = manualQualificationReason(charge, localHistoryCarId, manual, currency)
        if (reason != null && reason != "instance_unverified") return unavailable(reason)
        return EffectiveChargeCost(
            cost = manual.manualTotalAmount,
            source = ChargeCostSource.MANUAL,
            currencyCode = currency,
            qualificationReason = reason,
            sourceInstanceVerified = reason == null
        )
    }
    if (freeSupercharging && isDcCharge) {
        return EffectiveChargeCost(0.0, ChargeCostSource.FREE, currency, "explicit_free")
    }
    val sourceCost = charge.cost?.takeIf { it.isFinite() && it > 0.0 }
    if (sourceCost != null) {
        if (charge.source.isNullOrBlank()) return unavailable("source_unverified")
        val sourceCurrency = normalizedCurrency(charge.currencyCode)
        if (sourceCurrency == currency) {
            return EffectiveChargeCost(sourceCost, ChargeCostSource.TESLAMATE, currency,
                if (charge.sourceInstanceId.isNullOrBlank()) "instance_unverified" else null,
                !charge.sourceInstanceId.isNullOrBlank())
        }
        if (sourceCurrency == null) return unavailable("currency_unverified")
        return unavailable("currency_mismatch")
    }
    val estimated = manualChargeAmount(defaultPricePerKwh, charge.batteryInputKwh)
        ?.takeIf(Double::isFinite)
    if (estimated != null) return EffectiveChargeCost(estimated, ChargeCostSource.ESTIMATE,
        currency, "estimate")
    return unavailable(if (charge.cost == 0.0) "provider_zero_unverified" else "price_unavailable")
}

private fun unavailable(reason: String): EffectiveChargeCost =
    EffectiveChargeCost(null, ChargeCostSource.UNAVAILABLE, qualificationReason = reason)

private fun normalizedCurrency(value: String?): String? {
    val code = value?.trim()?.uppercase(Locale.ROOT) ?: return null
    return code.takeIf { known -> Currency.entries.any { it.code == known } }
}

/** Offset or Z is required: local wall time alone cannot identify a session Instant. */
internal fun chargeCostInstant(value: String?): Instant? {
    if (value.isNullOrBlank()) return null
    return runCatching { Instant.parse(value) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
}

private fun manualQualificationReason(
    charge: ChargeData, localHistoryCarId: Int, manual: ChargeCostOverride, currency: String
): String? {
    if (manual.carId != localHistoryCarId || manual.chargeId != charge.chargeId)
        return "charge_identity_mismatch"
    if (!manual.manualTotalAmount.isFinite() || manual.manualTotalAmount < 0.0)
        return "amount_invalid"
    val recordedCurrency = normalizedCurrency(manual.currencyCode)
    val start = chargeCostInstant(manual.startInstant)
    val end = chargeCostInstant(manual.endInstant)
    if (recordedCurrency == null || manual.sourceKind.isNullOrBlank() || start == null || end == null)
        return "legacy_unverified"
    if (recordedCurrency != currency) return "currency_mismatch"
    if (charge.source.isNullOrBlank() || manual.sourceKind != charge.source) return "source_mismatch"
    if (start != chargeCostInstant(charge.startDate) || end != chargeCostInstant(charge.endDate))
        return "session_mismatch"
    val recordedSourceId = manual.sourceRecordId?.takeIf(String::isNotBlank)
    val chargeSourceId = charge.sourceId?.takeIf(String::isNotBlank)
    if (recordedSourceId != chargeSourceId) return "source_record_mismatch"
    val recordedInstance = manual.sourceInstanceId?.takeIf(String::isNotBlank)
    val chargeInstance = charge.sourceInstanceId?.takeIf(String::isNotBlank)
    if (recordedInstance != chargeInstance) return "source_instance_mismatch"
    return if (recordedInstance == null) "instance_unverified" else null
}
