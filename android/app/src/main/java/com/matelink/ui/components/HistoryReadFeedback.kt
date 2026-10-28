package com.matelink.ui.components

import androidx.annotation.StringRes
import com.matelink.R

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
