package com.matelink.data.local

import com.matelink.data.api.models.CarData

interface VehicleContextResolver {
    suspend fun resolve(car: CarData): VehicleContext
    suspend fun recordExplicitUpgradeOrigin(car: CarData): Boolean
}
