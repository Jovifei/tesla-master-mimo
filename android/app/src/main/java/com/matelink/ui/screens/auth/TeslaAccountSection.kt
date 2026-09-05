package com.matelink.ui.screens.auth

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import com.matelink.BuildConfig
import com.matelink.R
import com.matelink.ui.components.launchExternalIntentSafely

@Composable
fun TeslaAccountSection(
    viewModel: TeslaLoginViewModel,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val authenticated by viewModel.isAuthenticated.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    var actionInProgress by rememberSaveable { mutableStateOf(false) }
    var deleteFailed by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirmation by rememberSaveable { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(stringResource(R.string.tesla_account_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(
                    if (authenticated && BuildConfig.JOURVOLT_MOCK_LOGIN) R.string.tesla_account_test_session
                    else if (authenticated) R.string.tesla_account_connected
                    else R.string.tesla_account_not_connected
                ),
                style = MaterialTheme.typography.bodyMedium
            )
            if (authenticated) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            actionInProgress = true
                            viewModel.logout {
                                actionInProgress = false
                                onLogout()
                            }
                        },
                        enabled = !actionInProgress,
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.tesla_account_logout)) }
                    TextButton(
                        onClick = { showDeleteConfirmation = true },
                        enabled = !actionInProgress
                    ) { Text(stringResource(R.string.tesla_account_delete)) }
                }
                    TextButton(
                        onClick = {
                            actionInProgress = true
                            onLogin()
                        },
                        enabled = !actionInProgress
                    ) {
                    Text(stringResource(R.string.tesla_account_reauthorize))
                }
            } else {
                Button(onClick = onLogin, enabled = !actionInProgress) {
                    Text(stringResource(R.string.tesla_account_reauthorize))
                }
            }
            if (actionInProgress) CircularProgressIndicator(modifier = Modifier.padding(top = 4.dp))
            when (val state = uiState) {
                is TeslaLoginUiState.Error -> Text(
                    stringResource(R.string.tesla_login_error, state.message),
                    color = MaterialTheme.colorScheme.error
                )
                else -> Unit
            }
            if (deleteFailed) {
                Text(
                    stringResource(R.string.tesla_account_delete_failed),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(stringResource(R.string.tesla_account_delete_title)) },
            text = { Text(stringResource(R.string.tesla_account_delete_message)) },
            confirmButton = {
                Button(onClick = {
                    showDeleteConfirmation = false
                    actionInProgress = true
                    deleteFailed = false
                    viewModel.deleteAccount(
                        onSuccess = { revokeUrl ->
                            actionInProgress = false
                            revokeUrl
                                ?.takeIf(::isTrustedTeslaConsentRevokeUrl)
                                ?.let { context.launchExternalIntentSafely(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
                            onDelete()
                        },
                        onFailure = {
                            actionInProgress = false
                            deleteFailed = true
                        }
                    )
                }) { Text(stringResource(R.string.tesla_account_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(stringResource(R.string.tesla_account_delete_cancel))
                }
            }
        )
    }
}
