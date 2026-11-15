package com.matelink.data.repository

import com.matelink.data.api.models.DriveData
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
