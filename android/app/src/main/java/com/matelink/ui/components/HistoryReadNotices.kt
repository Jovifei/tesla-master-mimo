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
        if (historySyncWarning != null) Text(stringResource(R.string.history_sync_cached),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
}
