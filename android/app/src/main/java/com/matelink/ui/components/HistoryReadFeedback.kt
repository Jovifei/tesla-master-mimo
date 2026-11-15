package com.matelink.ui.components

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import com.matelink.R

@StringRes
internal fun historyReadErrorMessageRes(error: String): Int = when (error) {
    "history_identity_unavailable", "current_charge_identity_invalid" -> R.string.history_identity_unavailable_message
    "Server not configured" -> R.string.server_not_configured_message
    "history_sync_timeout" -> R.string.settings_connection_timeout
    else -> R.string.error_loading_data
}

/** Retain failure feedback when no content exists; use the page's existing snackbar host. */
internal suspend fun SnackbarHostState.showHistoryReadFailure(
    context: Context,
    error: String,
    hasContent: Boolean,
    onRetry: () -> Unit
): Boolean {
    val retry = showSnackbar(
        message = context.getString(historyReadErrorMessageRes(error)),
        actionLabel = context.getString(R.string.retry),
        duration = if (hasContent) SnackbarDuration.Long else SnackbarDuration.Indefinite
    ) == SnackbarResult.ActionPerformed
    if (retry) onRetry()
    return retry
}

@StringRes
internal fun historyReadWarningMessageRes(warning: String): Int = when (warning) {
    "history_identity_unavailable" -> R.string.history_identity_unavailable_message
    "history_partial" -> R.string.history_sync_partial
    "history_sync_timeout" -> R.string.history_sync_timeout
    "history_energy_cache_failed" -> R.string.history_energy_cache_failed
    "history_detail_waiting_for_summary" -> R.string.history_detail_waiting_for_summary
    "history_detail_points_missing" -> R.string.history_detail_points_missing
    "history_detail_cache_corrupt" -> R.string.history_detail_cache_corrupt
    "history_detail_source_unverified" -> R.string.history_detail_source_unverified
    "history_detail_total_unverified" -> R.string.history_detail_total_unverified
    "history_detail_cached_source_unverified" -> R.string.history_detail_cached_source_unverified
    "history_detail_cached_total_unverified" -> R.string.history_detail_cached_total_unverified
    else -> R.string.history_sync_cached
}
