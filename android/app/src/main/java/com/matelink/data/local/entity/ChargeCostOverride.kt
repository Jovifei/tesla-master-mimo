package com.matelink.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/** A user-entered total cost for one charge, scoped to one vehicle. */
@Entity(
    tableName = "charge_cost_overrides",
    primaryKeys = ["carId", "chargeId"],
    indices = [Index(value = ["carId"])]
)
data class ChargeCostOverride(
    val carId: Int,
    val chargeId: Int,
    val manualTotalAmount: Double
)
