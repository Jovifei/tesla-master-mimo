package com.matelink.domain.analytics

fun chargeTotalOverrideKey(carId: Int, chargeId: Int): String = "$carId:$chargeId"

fun validManualChargeTotal(value: Double?): Double? =
    value?.takeIf { it.isFinite() && it >= 0.0 }
