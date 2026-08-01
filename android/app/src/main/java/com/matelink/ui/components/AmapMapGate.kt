package com.matelink.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.matelink.R
import com.matelink.domain.map.AmapSetupState
import com.matelink.ui.screens.map.AmapEmbeddedMapViewModel
import kotlinx.coroutines.delay

@Composable
fun AmapMapGate(
    modifier: Modifier = Modifier,
    viewModel: AmapEmbeddedMapViewModel = hiltViewModel(),
    content: @Composable (
        apiKey: String,
        onLoading: () -> Unit,
        onLoaded: () -> Unit,
        onFailure: () -> Unit
    ) -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state.loading) {
        if (state.loading) {
            delay(15_000)
            if (viewModel.uiState.value.loading) viewModel.onMapFailure()
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when {
            state.failed -> AmapStatusMessage(R.string.amap_failed)
            state.setupState == AmapSetupState.UNCONFIGURED -> AmapStatusMessage(R.string.amap_not_configured)
            state.setupState == AmapSetupState.PRIVACY_NOT_AGREED -> AmapStatusMessage(R.string.amap_not_agreed)
            state.setupState == AmapSetupState.VERIFICATION_REQUIRED -> AmapStatusMessage(R.string.amap_not_verified)
            state.setupState == AmapSetupState.RESTART_REQUIRED -> AmapStatusMessage(R.string.amap_restart_required)
            state.setupState == AmapSetupState.READY_TO_PREVIEW -> {
                content(
                    state.apiKey,
                    viewModel::onMapLoading,
                    viewModel::onMapLoaded,
                    viewModel::onMapFailure
                )
                if (state.loading) CircularProgressIndicator()
            }
            else -> CircularProgressIndicator()
        }
    }
}

@Composable
fun AmapStatusMessage(@StringRes message: Int, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(message),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
