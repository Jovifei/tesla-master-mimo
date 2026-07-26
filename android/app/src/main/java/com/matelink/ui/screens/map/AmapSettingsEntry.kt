package com.matelink.ui.screens.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.matelink.R
import com.matelink.domain.map.AmapSetupState

@Composable
fun AmapSettingsEntry(
    onNavigateToSetup: () -> Unit,
    viewModel: AmapSetupViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    AmapSettingsEntryContent(state, onNavigateToSetup)
}

@Composable
internal fun AmapSettingsEntryContent(state: AmapSetupUiState, onNavigateToSetup: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onNavigateToSetup),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.amap_settings_title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    stringResource(amapStatusText(state)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

private fun amapStatusText(state: AmapSetupUiState): Int = when (state.state) {
    AmapSetupState.UNCONFIGURED -> R.string.amap_status_unconfigured
    AmapSetupState.PRIVACY_NOT_AGREED -> R.string.amap_status_privacy_not_agreed
    AmapSetupState.RESTART_REQUIRED -> R.string.amap_status_restart_required
    AmapSetupState.VERIFICATION_REQUIRED -> R.string.amap_status_verification_required
    else -> if (state.mapLoaded) R.string.amap_status_loaded else R.string.amap_status_ready
}
