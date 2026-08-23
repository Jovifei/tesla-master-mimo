package com.matelink.ui.screens.auth

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.matelink.BuildConfig
import com.matelink.R
import com.matelink.ui.common.PublicInfoLinks
import com.matelink.ui.components.launchExternalIntentSafely

@Composable
fun TeslaLoginScreen(
    viewModel: TeslaLoginViewModel = hiltViewModel(),
    onLoginSuccess: () -> Unit,
    onOpenSelfHosted: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val isAuthenticated by viewModel.isAuthenticated.collectAsState()
    val hasCurrentConsent by viewModel.hasCurrentConsent.collectAsState()
    val context = LocalContext.current
    val termsUrl = PublicInfoLinks.url(
        BuildConfig.MATELINK_PUBLIC_INFO_BASE_URL,
        PublicInfoLinks.Page.TERMS
    )
    val privacyUrl = PublicInfoLinks.url(
        BuildConfig.MATELINK_PUBLIC_INFO_BASE_URL,
        PublicInfoLinks.Page.PRIVACY
    )
    val legalDocumentsConfigured = termsUrl != null && privacyUrl != null
    var termsAccepted by rememberSaveable { mutableStateOf(false) }
    var privacyAccepted by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(isAuthenticated) {
        if (isAuthenticated) onLoginSuccess()
    }
    LaunchedEffect(hasCurrentConsent) {
        if (hasCurrentConsent) {
            termsAccepted = true
            privacyAccepted = true
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
            Text(
                stringResource(R.string.tesla_login_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 20.dp)
            )
            Text(
                stringResource(R.string.tesla_login_description),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 10.dp)
            )
            Row(
                modifier = Modifier.padding(top = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = termsAccepted, onCheckedChange = { termsAccepted = it })
                Text(stringResource(R.string.tesla_login_terms_consent))
            }
            TextButton(
                onClick = {
                    termsUrl?.let { url ->
                        context.launchExternalIntentSafely(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                },
                enabled = termsUrl != null
            ) {
                Text(stringResource(R.string.tesla_login_view_terms))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = privacyAccepted, onCheckedChange = { privacyAccepted = it })
                Text(stringResource(R.string.tesla_login_privacy_consent))
            }
            TextButton(
                onClick = {
                    privacyUrl?.let { url ->
                        context.launchExternalIntentSafely(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                },
                enabled = privacyUrl != null
            ) {
                Text(stringResource(R.string.tesla_login_view_privacy))
            }
            Button(
                onClick = { viewModel.startTeslaLogin(termsAccepted, privacyAccepted) },
                enabled = termsAccepted && privacyAccepted && legalDocumentsConfigured &&
                    BuildConfig.JOURVOLT_CLOUD_LOGIN &&
                    uiState !is TeslaLoginUiState.Loading,
                modifier = Modifier.padding(top = 12.dp)
            ) {
                Text(stringResource(R.string.tesla_login_button))
            }
            if (!legalDocumentsConfigured) {
                Text(
                    stringResource(R.string.tesla_login_documents_unavailable),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            TextButton(
                onClick = onOpenSelfHosted,
                enabled = uiState !is TeslaLoginUiState.Loading,
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Text(stringResource(R.string.tesla_login_self_hosted))
            }
            DebugMockLoginEntry(onLoginSuccess = onLoginSuccess)
            when (val state = uiState) {
                TeslaLoginUiState.Idle -> Unit
                TeslaLoginUiState.Loading -> Text(
                    stringResource(R.string.tesla_login_loading),
                    modifier = Modifier.padding(top = 12.dp)
                )
                is TeslaLoginUiState.Error -> Text(
                    stringResource(R.string.tesla_login_error, state.message),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
    }
}
