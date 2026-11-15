package com.matelink.ui.screens.readiness

import com.matelink.data.api.models.DataReadiness
import kotlinx.coroutines.flow.flowOf

internal object TestReadinessEvidenceSource : ReadinessEvidenceSource {
    override val scopeChanges = flowOf(Unit)
    override suspend fun capture(carId: Int) = object : ReadinessEvidenceSession {
        override val connectionSource = com.matelink.data.local.HistoryConnectionSource.SELF_HOSTED
        override suspend fun isCurrent() = true
        override fun verifies(readiness: DataReadiness?, car: com.matelink.data.api.models.CarData?) = false
        override suspend fun localEvidence(): ReadinessLocalEvidence? = null
    }
}
