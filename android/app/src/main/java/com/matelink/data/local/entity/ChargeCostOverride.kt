package com.matelink.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.ColumnInfo

/** A user-entered total cost for one charge, scoped to one vehicle. */
@Entity(
    tableName = "charge_cost_overrides",
    primaryKeys = ["carId", "chargeId", "sessionKey"],
    indices = [Index(value = ["carId"]), Index(value = ["carId", "chargeId"])]
)
data class ChargeCostOverride(
    val carId: Int,
    val chargeId: Int,
    val manualTotalAmount: Double,
    @ColumnInfo(defaultValue = "'legacy'") val sessionKey: String = "legacy",
    val currencyCode: String? = null,
    val sourceKind: String? = null,
    val sourceInstanceId: String? = null,
    val sourceRecordId: String? = null,
    val startInstant: String? = null,
    val endInstant: String? = null,
    @ColumnInfo(defaultValue = "0") val version: Long = 0L,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false
)
