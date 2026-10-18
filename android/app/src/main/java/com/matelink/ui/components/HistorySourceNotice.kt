package com.matelink.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.matelink.R

/** Keep source explanations reachable without displacing the history controls. */
@Composable
fun HistorySourceNotice() {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(Modifier.clickable { expanded = !expanded }.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Default.Info, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(if (expanded) R.string.history_source_hide else R.string.history_source_show),
                style = MaterialTheme.typography.labelMedium)
        }
        if (expanded) Text(stringResource(R.string.history_archive_link_pending),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp))
    }
}
