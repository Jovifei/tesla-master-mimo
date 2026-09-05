package com.matelink.ui.screens.readiness

import com.matelink.data.api.models.DataReadinessItem
import com.matelink.R
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

enum class ReadinessItemStatus {
    AVAILABLE,
    COLLECTING,
    WAITING_VEHICLE,
    UNSUPPORTED,
    UNKNOWN
}

fun readinessItemStatus(status: String): ReadinessItemStatus = when (status.trim().lowercase()) {
    "available" -> ReadinessItemStatus.AVAILABLE
    "collecting" -> ReadinessItemStatus.COLLECTING
    "waiting_vehicle" -> ReadinessItemStatus.WAITING_VEHICLE
    "unsupported" -> ReadinessItemStatus.UNSUPPORTED
    else -> ReadinessItemStatus.UNKNOWN
}

fun readinessItemStatus(item: DataReadinessItem): ReadinessItemStatus = readinessItemStatus(item.status)

fun readinessSourceLabelRes(source: String): Int = when (source.trim().lowercase()) {
    "fleet_api" -> R.string.data_readiness_source_fleet_api
    "mock_fixture" -> R.string.data_readiness_source_mock
    "legacy_compatibility" -> R.string.data_readiness_source_legacy
    "local_history" -> R.string.data_readiness_source_local_history
    else -> R.string.data_readiness_source_unavailable
}

@Composable
fun readinessDashboardValue(item: DataReadinessItem?): String {
    if (item == null) return stringResource(R.string.data_readiness_status_waiting_vehicle)
    val statusText = when (readinessItemStatus(item)) {
        ReadinessItemStatus.AVAILABLE -> stringResource(R.string.data_readiness_status_available)
        ReadinessItemStatus.COLLECTING -> stringResource(R.string.data_readiness_status_collecting)
        ReadinessItemStatus.WAITING_VEHICLE -> stringResource(R.string.data_readiness_status_waiting_vehicle)
        ReadinessItemStatus.UNSUPPORTED -> stringResource(R.string.data_readiness_status_unsupported)
        ReadinessItemStatus.UNKNOWN -> stringResource(R.string.data_readiness_status_unavailable)
    }
    return item.lastObservedAt?.takeIf(String::isNotBlank)?.let {
        "$statusText · ${stringResource(R.string.data_readiness_last_observed, it)}"
    } ?: statusText
}
