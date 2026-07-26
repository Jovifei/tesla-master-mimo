package com.matelink.ui.screens.map

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.matelink.BuildConfig
import com.matelink.R
import com.matelink.domain.map.InstalledAppSignature

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AmapSetupGuideScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPreview: () -> Unit,
    viewModel: AmapSetupViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val identity = InstalledAppSignature.read(context, BuildConfig.DEBUG)
    val verificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.acceptVerifiedKey() else viewModel.rejectPendingKey()
    }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.amap_setup_title)) }, navigationIcon = { TextButton(onClick = onNavigateBack) { Text(stringResource(R.string.amap_back)) } }) }) { padding ->
        AmapSetupGuideContent(
            modifier = Modifier.padding(padding),
            identity = identity,
            uiState = uiState,
            onCopyPackage = { clipboard.setText(AnnotatedString(identity.packageName)) },
            onCopySha1 = { identity.sha1?.let { clipboard.setText(AnnotatedString(it)) } },
            onKeyChange = viewModel::updateKey,
            onVerifyDraftKey = {
                viewModel.stageDraftKey {
                    verificationLauncher.launch(Intent(context, AmapKeyVerificationActivity::class.java))
                }
            },
            onVerifySavedKey = {
                viewModel.stageSavedKey {
                    verificationLauncher.launch(Intent(context, AmapKeyVerificationActivity::class.java))
                }
            },
            onChangeKey = viewModel::startEditingKey,
            onPrivacyAgreedChange = viewModel::setPrivacyAgreed,
            onNavigateToPreview = onNavigateToPreview
        )
    }
}

@Composable
internal fun AmapSetupGuideContent(
    modifier: Modifier = Modifier,
    identity: com.matelink.domain.map.InstalledAppIdentity,
    uiState: AmapSetupUiState,
    onCopyPackage: () -> Unit,
    onCopySha1: () -> Unit,
    onKeyChange: (String) -> Unit,
    onVerifyDraftKey: () -> Unit,
    onVerifySavedKey: () -> Unit,
    onChangeKey: () -> Unit,
    onPrivacyAgreedChange: (Boolean) -> Unit,
    onNavigateToPreview: () -> Unit
) {
    Column(
        modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.amap_setup_steps), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.amap_setup_warning), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.amap_identity_package, identity.packageName))
        Text(stringResource(R.string.amap_identity_build, identity.buildType))
        Text(stringResource(R.string.amap_identity_sha1, identity.sha1 ?: stringResource(R.string.amap_sha1_unavailable)))
        OutlinedButton(onClick = onCopyPackage) { Text(stringResource(R.string.amap_copy_package)) }
        OutlinedButton(onClick = onCopySha1, enabled = identity.sha1 != null) { Text(stringResource(R.string.amap_copy_sha1)) }
        Text(stringResource(R.string.amap_privacy_notice), style = MaterialTheme.typography.bodySmall)
        androidx.compose.foundation.layout.Row {
            Checkbox(checked = uiState.privacyAgreed, onCheckedChange = onPrivacyAgreedChange)
            Text(stringResource(R.string.amap_privacy_agree), modifier = Modifier.padding(top = 12.dp))
        }
        when {
            uiState.hasVerifiedKey && !uiState.isEditingKey -> {
                Text(stringResource(R.string.amap_key_verified), style = MaterialTheme.typography.bodySmall)
                if (uiState.verificationFailed) {
                    Text(stringResource(R.string.amap_change_verification_failed), color = MaterialTheme.colorScheme.error)
                }
                OutlinedButton(onClick = onChangeKey, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.amap_change_key))
                }
            }
            uiState.hasKey && !uiState.isEditingKey -> {
                Text(stringResource(R.string.amap_key_saved_unverified), style = MaterialTheme.typography.bodySmall)
                if (uiState.verificationFailed) {
                    Text(stringResource(R.string.amap_verification_failed), color = MaterialTheme.colorScheme.error)
                }
                OutlinedButton(
                    onClick = onVerifySavedKey,
                    enabled = uiState.privacyAgreed,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.amap_verify_saved_key)) }
                OutlinedButton(onClick = onChangeKey, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.amap_change_key))
                }
            }
            else -> {
                OutlinedTextField(
                    value = uiState.keyInput,
                    onValueChange = onKeyChange,
                    label = { Text(stringResource(R.string.amap_key_label)) },
                    visualTransformation = PasswordVisualTransformation(),
                    isError = uiState.keyError,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Text(stringResource(R.string.amap_key_storage_notice), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.amap_key_not_saved), style = MaterialTheme.typography.bodySmall)
                if (uiState.keyError) Text(stringResource(R.string.amap_key_invalid), color = MaterialTheme.colorScheme.error)
                if (uiState.verificationFailed) Text(stringResource(R.string.amap_verification_failed), color = MaterialTheme.colorScheme.error)
                OutlinedButton(
                    onClick = onVerifyDraftKey,
                    enabled = uiState.keyInput.isNotBlank() && uiState.privacyAgreed,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.amap_verify_and_save)) }
                if (uiState.keyInput.isNotBlank() && !uiState.privacyAgreed) {
                    Text(stringResource(R.string.amap_verification_requires_privacy), color = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (uiState.restartRequired) Text(stringResource(R.string.amap_restart_required), color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(4.dp))
        OutlinedButton(
            onClick = onNavigateToPreview,
            enabled = uiState.hasVerifiedKey && uiState.privacyAgreed && !uiState.restartRequired,
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.amap_preview)) }
    }
}
