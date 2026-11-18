package com.matelink.data.repository

import com.matelink.data.api.models.DriveData
import com.matelink.data.local.VehicleContext
import com.matelink.data.local.entity.DriveSummary
import com.matelink.domain.analytics.HistorySummaryEvidenceCodec
import com.matelink.domain.analytics.sameChargeSourceInstance
import com.matelink.util.parseIsoInstant
import java.security.MessageDigest

internal fun DriveData.matchesHistoryIdentity(previous: DriveData): Boolean =
    driveId == previous.driveId && source == previous.source &&
        sameChargeSourceInstance(sourceInstanceId, sourceRecordIdentity, previous.sourceInstanceId, previous.sourceRecordIdentity) &&
        parseIsoInstant(startDate) != null && parseIsoInstant(endDate) != null &&
        parseIsoInstant(startDate) == parseIsoInstant(previous.startDate) &&
        parseIsoInstant(endDate) == parseIsoInstant(previous.endDate)

internal fun driveSourceReceiptDigest(row: DriveSummary): String? =
    HistorySummaryEvidenceCodec.sourceEvidence(row.apiEvidence)?.let { source ->
        MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

/**
 * Display-only address enrichment is allowed even when the provider never supplied a
 * source (TeslaMate self-hosted rows). It never feeds statistics or energy evidence;
 * this key only scopes the in-memory label to the current car, account and time window.
 */
internal fun displayAddressIdentity(row: DriveSummary, context: VehicleContext): String {
    val scope = listOf(context.localHistoryCarId, context.connectionSource.name,
        context.serverIdentity, context.stableIdentity, row.driveId, row.startDate, row.endDate)
        .joinToString("|")
    return "display:" + MessageDigest.getInstance("SHA-256")
        .digest(scope.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/** Same receipt guard for display labels; legacy rows may only carry the archived JSON. */
internal fun driveAddressReceiptDigest(row: DriveSummary): String? =
    driveSourceReceiptDigest(row) ?: row.apiEvidence?.let {
        MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
