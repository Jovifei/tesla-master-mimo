package com.matelink.ui.screens.dashboard

import com.matelink.data.api.models.CarData

/** A contradictory fresh /cars response means the pre-refresh status can no
 * longer be bound to the original UID, even though its numeric ID still matches.
 */
internal fun dashboardUidReboundOrMissing(current: CarData?, cars: List<CarData>): Boolean {
    if (current == null) return false
    val matches = cars.filter { it.carId == current.carId }
    return matches.size != 1 || matches.single().vehicleUid != current.vehicleUid
}

/** A metadata refresh can enrich the selected vehicle, never change its identity. */
internal fun refreshedDashboardCar(current: CarData?, cars: List<CarData>): CarData? {
    if (current == null) return null
    val candidate = cars.firstOrNull { it.carId == current.carId } ?: return current
    if (current.vehicleUid != candidate.vehicleUid) return current
    return current.copy(
        name = candidate.name?.takeIf(String::isNotBlank) ?: current.name,
        carDetails = candidate.carDetails?.takeIf { !it.model.isNullOrBlank() } ?: current.carDetails,
        carExterior = candidate.carExterior ?: current.carExterior
    )
}
