package com.matelink.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.matelink.R

@Composable
internal fun HistoryReadNotices(localArchiveLinkPending: Boolean, historySyncWarning: String?) {
    if (!localArchiveLinkPending && historySyncWarning == null) return
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        if (localArchiveLinkPending) Text(stringResource(R.string.history_archive_link_pending),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (historySyncWarning != null) {
            val message = when (historySyncWarning) {
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
            Text(stringResource(message), style = MaterialTheme.typography.bodyMedium,
                color = if (historySyncWarning in setOf("history_detail_points_missing",
                        "history_detail_cache_corrupt",
                        "history_detail_waiting_for_summary", "history_energy_cache_failed"))
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
