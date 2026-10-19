package com.matelink.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.matelink.R

/** Source explanation lives in the toolbar so recent history stays above the fold. */
@Composable
fun HistorySourceNotice() {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Default.Info, contentDescription = stringResource(R.string.history_source_show),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.history_source_show)) },
        text = { Text(stringResource(R.string.history_archive_link_pending)) },
        confirmButton = {
            TextButton(onClick = { open = false }) { Text(stringResource(android.R.string.ok)) }
        }
    )
}
