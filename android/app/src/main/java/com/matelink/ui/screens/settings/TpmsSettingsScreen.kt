package com.matelink.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.matelink.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TpmsSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    TpmsSettingsContent(
        uiState = uiState,
        onTargetChange = viewModel::updateTpmsTargetBar,
        onLowChange = viewModel::updateTpmsLowBar,
        onHighChange = viewModel::updateTpmsHighBar,
        onSave = viewModel::saveTpmsAlertProfile,
        onNavigateBack = onNavigateBack
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TpmsSettingsContent(
    uiState: SettingsUiState,
    onTargetChange: (String) -> Unit,
    onLowChange: (String) -> Unit,
    onHighChange: (String) -> Unit,
    onSave: () -> Unit,
    onNavigateBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tpms_profile_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TpmsAlertProfileCard(
                uiState = uiState,
                onTargetChange = onTargetChange,
                onLowChange = onLowChange,
                onHighChange = onHighChange,
                onSave = onSave
            )
        }
    }
}

@Composable
internal fun TpmsAlertProfileCard(
    uiState: SettingsUiState,
    onTargetChange: (String) -> Unit,
    onLowChange: (String) -> Unit,
    onHighChange: (String) -> Unit,
    onSave: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.tpms_profile_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.tpms_profile_source_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.tpms_profile_model_y_suggestion),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = uiState.tpmsTargetBar,
                onValueChange = onTargetChange,
                label = { Text(stringResource(R.string.tpms_profile_target_label)) },
                suffix = { Text(stringResource(R.string.tpms_pressure_unit)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().testTag("tpmsTargetInput")
            )
            OutlinedTextField(
                value = uiState.tpmsLowBar,
                onValueChange = onLowChange,
                label = { Text(stringResource(R.string.tpms_profile_low_label)) },
                suffix = { Text(stringResource(R.string.tpms_pressure_unit)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().testTag("tpmsLowInput")
            )
            OutlinedTextField(
                value = uiState.tpmsHighBar,
                onValueChange = onHighChange,
                label = { Text(stringResource(R.string.tpms_profile_high_label)) },
                suffix = { Text(stringResource(R.string.tpms_pressure_unit)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().testTag("tpmsHighInput")
            )
            Text(
                text = stringResource(R.string.tpms_custom_reminder_label),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = if (uiState.tpmsProfileEnabled) {
                    stringResource(R.string.tpms_profile_enabled, uiState.tpmsCarId)
                } else {
                    stringResource(R.string.tpms_profile_not_enabled)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth().testTag("saveTpmsProfileButton")
            ) {
                Text(stringResource(R.string.tpms_profile_save))
            }
        }
    }
}
