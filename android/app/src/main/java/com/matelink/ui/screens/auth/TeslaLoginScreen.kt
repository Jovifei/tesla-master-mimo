package com.matelink.ui.screens.auth

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.matelink.BuildConfig
import com.matelink.R
import com.matelink.ui.common.PublicInfoLinks
import com.matelink.ui.components.launchExternalIntentSafely

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeslaLoginScreen(
    viewModel: TeslaLoginViewModel = hiltViewModel(),
    onLoginSuccess: () -> Unit,
    onOpenSelfHosted: () -> Unit,
    onNavigateBack: (() -> Unit)? = null
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

    Scaffold(
        topBar = {
            onNavigateBack?.let { onBack ->
                TopAppBar(
                    title = { Text(stringResource(R.string.tesla_login_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.tesla_login_back)
                            )
                        }
                    }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            LoginHeaderPanel()

            LoginPanel {
                Text(
                    text = stringResource(R.string.tesla_login_documents_section),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(R.string.tesla_login_documents_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ConsentRow(
                    checked = termsAccepted,
                    label = stringResource(R.string.tesla_login_terms_consent),
                    onCheckedChange = { termsAccepted = it }
                )
                DocumentButton(
                    label = stringResource(R.string.tesla_login_view_terms),
                    enabled = termsUrl != null,
                    onClick = {
                        termsUrl?.let { url ->
                            context.launchExternalIntentSafely(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                    }
                )
                ConsentRow(
                    checked = privacyAccepted,
                    label = stringResource(R.string.tesla_login_privacy_consent),
                    onCheckedChange = { privacyAccepted = it }
                )
                DocumentButton(
                    label = stringResource(R.string.tesla_login_view_privacy),
                    enabled = privacyUrl != null,
                    onClick = {
                        privacyUrl?.let { url ->
                            context.launchExternalIntentSafely(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                    }
                )
            }

            LoginPanel(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Cloud,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.tesla_login_cloud_section),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Text(
                    text = stringResource(R.string.tesla_login_cloud_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = { viewModel.startTeslaLogin(termsAccepted, privacyAccepted) },
                    enabled = termsAccepted && privacyAccepted && legalDocumentsConfigured &&
                        BuildConfig.JOURVOLT_CLOUD_LOGIN && uiState !is TeslaLoginUiState.Loading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Lock, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.tesla_login_button))
                }
                if (!legalDocumentsConfigured) {
                    Text(
                        text = stringResource(R.string.tesla_login_documents_unavailable),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            LoginPanel(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.SettingsEthernet,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.tesla_login_self_hosted),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Text(
                    text = stringResource(R.string.tesla_login_self_hosted_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = onOpenSelfHosted,
                    enabled = uiState !is TeslaLoginUiState.Loading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.tesla_login_self_hosted_open))
                }
            }

            when (val state = uiState) {
                TeslaLoginUiState.Idle -> Unit
                TeslaLoginUiState.Loading -> LoginStatusPanel(
                    icon = { CircularProgressIndicator(modifier = Modifier.size(22.dp)) },
                    text = stringResource(R.string.tesla_login_loading)
                )
                is TeslaLoginUiState.Error -> LoginStatusPanel(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    text = stringResource(R.string.tesla_login_error, state.message)
                )
            }
            DebugMockLoginEntry(onLoginSuccess = onLoginSuccess)
        }
    }
}

@Composable
private fun LoginHeaderPanel() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall
                )
            }
            Text(
                text = stringResource(R.string.tesla_login_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun LoginPanel(
    containerColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

@Composable
private fun ConsentRow(
    checked: Boolean,
    label: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = onCheckedChange
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        Text(text = label, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun DocumentButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Filled.OpenInBrowser, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun LoginStatusPanel(
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
    icon: @Composable () -> Unit,
    text: String
) {
    LoginPanel(containerColor = containerColor) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon()
            Spacer(Modifier.width(10.dp))
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
