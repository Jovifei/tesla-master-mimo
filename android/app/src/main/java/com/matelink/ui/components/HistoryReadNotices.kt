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
    // Redundant cloud provenance note is hidden per design requirements
    if (historySyncWarning == null) return
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            stringResource(
                if (historySyncWarning == "history_energy_cache_failed") R.string.history_energy_cache_failed
                else R.string.history_sync_cached
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
    }
}
