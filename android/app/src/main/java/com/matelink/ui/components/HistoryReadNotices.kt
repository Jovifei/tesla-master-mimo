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
    if (historySyncWarning == null && !localArchiveLinkPending) return
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        if (localArchiveLinkPending) Text(stringResource(R.string.history_archive_link_pending),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (historySyncWarning != null) {
            val message = historyReadWarningMessageRes(historySyncWarning)
            Text(stringResource(message), style = MaterialTheme.typography.bodyMedium,
                color = if (historySyncWarning in setOf("history_detail_points_missing",
                        "history_detail_cache_corrupt",
                        "history_detail_waiting_for_summary", "history_energy_cache_failed"))
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
