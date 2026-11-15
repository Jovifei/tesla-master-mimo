package com.matelink.data.local.entity

import androidx.room.Entity

/** Authenticated, versioned evidence; the server's content hash is opaque to the client. */
@Entity(
    tableName = "battery_trend_evidence",
    primaryKeys = ["scopeFingerprint", "localHistoryCarId", "sourceInstanceId", "sourceVehicleId", "sourceRecordId"]
)
data class BatteryTrendEvidence(
    val scopeFingerprint: String,
    val localHistoryCarId: Int,
    val sourceInstanceId: String,
    val sourceVehicleId: String,
    val sourceRecordId: String,
    val payloadSha256: String,
    val observationJson: String
)
