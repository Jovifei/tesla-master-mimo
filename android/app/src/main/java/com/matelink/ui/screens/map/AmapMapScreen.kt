package com.matelink.ui.screens.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.matelink.R
import com.matelink.domain.map.AmapSetupState
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AmapMapScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSetup: () -> Unit,
    viewModel: AmapMapViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    LaunchedEffect(uiState.loading) {
        if (uiState.loading) { delay(15_000); if (uiState.loading) viewModel.onMapFailure() }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("AMap preview") }, navigationIcon = { TextButton(onClick = onNavigateBack) { Text("Back") } }) }) { padding ->
        when {
            uiState.setupState == AmapSetupState.UNCONFIGURED -> AmapMessage(padding, R.string.amap_not_configured, onNavigateToSetup)
            uiState.setupState == AmapSetupState.PRIVACY_NOT_AGREED -> AmapMessage(padding, R.string.amap_not_agreed, onNavigateToSetup)
            uiState.setupState == AmapSetupState.RESTART_REQUIRED -> AmapMessage(padding, R.string.amap_restart_required, onNavigateToSetup)
            uiState.failed -> AmapMessage(padding, R.string.amap_failed, onNavigateToSetup)
            else -> Box(Modifier.fillMaxSize().padding(padding)) {
                AmapMapView(uiState.key, uiState.latitude, uiState.longitude, viewModel::onMapLoading, viewModel::onMapLoaded, viewModel::onMapFailure)
                if (uiState.loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
                if (uiState.latitude == null || uiState.longitude == null) Text(stringResource(R.string.amap_no_position), Modifier.align(Alignment.BottomCenter).padding(16.dp), color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun AmapMessage(padding: androidx.compose.foundation.layout.PaddingValues, message: Int, action: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(message), style = MaterialTheme.typography.bodyLarge)
        Button(onClick = action, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.amap_go_setup)) }
    }
}
