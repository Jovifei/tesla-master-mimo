package com.matelink.ui.screens.map

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
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.amap_setup_title)) }, navigationIcon = { TextButton(onClick = onNavigateBack) { Text("Back") } }) }) { padding ->
        AmapSetupGuideContent(
            modifier = Modifier.padding(padding),
            identity = identity,
            uiState = uiState,
            onCopyPackage = { clipboard.setText(AnnotatedString(identity.packageName)) },
            onCopySha1 = { identity.sha1?.let { clipboard.setText(AnnotatedString(it)) } },
            onKeyChange = viewModel::updateKey,
            onSaveKey = viewModel::saveKey,
            onClearKey = viewModel::clearKey,
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
    onSaveKey: () -> Unit,
    onClearKey: () -> Unit,
    onPrivacyAgreedChange: (Boolean) -> Unit,
    onNavigateToPreview: () -> Unit
) {
    Column(
        modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.amap_setup_steps), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.amap_setup_warning), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        Text("Package: ${identity.packageName}")
        Text("Build: ${identity.buildType}")
        Text("SHA1: ${identity.sha1 ?: "Unavailable"}")
        OutlinedButton(onClick = onCopyPackage) { Text("Copy package name") }
        OutlinedButton(onClick = onCopySha1, enabled = identity.sha1 != null) { Text("Copy SHA1") }
        OutlinedTextField(
            value = uiState.keyInput,
            onValueChange = onKeyChange,
            label = { Text(stringResource(R.string.amap_key_label)) },
            visualTransformation = PasswordVisualTransformation(),
            isError = uiState.keyError,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Text("The key is encrypted on this device and is never shown after saving.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onSaveKey, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.amap_save_key)) }
        if (uiState.hasKey) OutlinedButton(onClick = onClearKey, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.amap_clear_key)) }
        Text(stringResource(R.string.amap_privacy_notice), style = MaterialTheme.typography.bodySmall)
        androidx.compose.foundation.layout.Row {
            Checkbox(checked = uiState.privacyAgreed, onCheckedChange = onPrivacyAgreedChange)
            Text(stringResource(R.string.amap_privacy_agree), modifier = Modifier.padding(top = 12.dp))
        }
        if (uiState.restartRequired) Text(stringResource(R.string.amap_restart_required), color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(4.dp))
        OutlinedButton(
            onClick = onNavigateToPreview,
            enabled = uiState.hasKey && uiState.privacyAgreed && !uiState.restartRequired,
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.amap_preview)) }
    }
}
