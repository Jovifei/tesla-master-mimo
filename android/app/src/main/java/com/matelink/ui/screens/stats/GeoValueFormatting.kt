package com.matelink.ui.screens.stats

import com.matelink.data.api.models.Units
import com.matelink.domain.model.UnitFormatter

internal fun geoDistanceText(kilometers: Double?, units: Units?): String =
    kilometers?.takeIf { it.isFinite() && it >= 0.0 }?.let { UnitFormatter.formatDistanceKm(it, units, 0) } ?: "--"

internal fun geoEnergyText(kwh: Double?): String = kwh?.takeIf { it.isFinite() && it >= 0.0 }?.let {
    if (it > 999) "%.1f MWh".format(it / 1000) else "%.0f kWh".format(it)
} ?: "--"
