package com.matelink.ui.screens.map

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.matelink.BuildConfig
import com.matelink.R
import com.matelink.domain.map.InstalledAppSignature

internal enum class AmapSetupWizardStep(
    val titleRes: Int,
    val bodyRes: Int
) {
    IDENTITY(R.string.amap_setup_step_1_title, R.string.amap_setup_step_1_body),
    PRIVACY(R.string.amap_setup_step_2_title, R.string.amap_setup_step_2_body),
    KEY(R.string.amap_setup_step_3_title, R.string.amap_setup_step_3_body);

    val stepNumber: Int get() = ordinal + 1
    val progressFraction: Float get() = stepNumber / entries.size.toFloat()

    fun previous(): AmapSetupWizardStep? = entries.getOrNull(ordinal - 1)

    fun next(): AmapSetupWizardStep? = entries.getOrNull(ordinal + 1)
}

@Composable
private fun AmapSetupIllustration() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Language,
                contentDescription = stringResource(R.string.amap_setup_platform_illustration),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Icon(
                imageVector = Icons.Filled.ContentCopy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Icon(
                imageVector = Icons.Filled.VpnKey,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

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
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.amap_setup_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.amap_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
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
            onCancelKeyEdit = viewModel::cancelEditingKey,
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
    onCancelKeyEdit: () -> Unit,
    onPrivacyAgreedChange: (Boolean) -> Unit,
    onNavigateToPreview: () -> Unit
) {
    var showKeyDialog by remember { mutableStateOf(false) }
    var step by rememberSaveable { mutableStateOf(AmapSetupWizardStep.IDENTITY) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(
                        R.string.amap_setup_step_progress,
                        step.stepNumber,
                        AmapSetupWizardStep.entries.size
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                LinearProgressIndicator(
                    progress = { step.progressFraction },
                    modifier = Modifier.fillMaxWidth()
                )
                AmapSetupIllustration()
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(step.titleRes),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(step.bodyRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                when (step) {
                    AmapSetupWizardStep.IDENTITY -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.amap_setup_warning),
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }

                    AmapSetupWizardStep.PRIVACY -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(stringResource(R.string.amap_identity_package, identity.packageName))
                                Text(stringResource(R.string.amap_identity_build, identity.buildType))
                                Text(stringResource(R.string.amap_identity_sha1, identity.sha1 ?: stringResource(R.string.amap_sha1_unavailable)))
                                OutlinedButton(onClick = onCopyPackage, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.amap_copy_package))
                                }
                                OutlinedButton(
                                    onClick = onCopySha1,
                                    enabled = identity.sha1 != null,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.amap_copy_sha1))
                                }
                            }
                        }
                    }

                    AmapSetupWizardStep.KEY -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(stringResource(R.string.amap_privacy_notice), style = MaterialTheme.typography.bodySmall)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .toggleable(
                                            value = uiState.privacyAgreed,
                                            role = Role.Checkbox,
                                            onValueChange = onPrivacyAgreedChange
                                        ),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(checked = uiState.privacyAgreed, onCheckedChange = null)
                                    Text(stringResource(R.string.amap_privacy_agree), modifier = Modifier.padding(top = 12.dp))
                                }
                            }
                        }
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                when {
                                    uiState.hasVerifiedKey && !uiState.isEditingKey -> {
                                        Text(stringResource(R.string.amap_key_verified), style = MaterialTheme.typography.bodySmall)
                                        if (uiState.verificationFailed) {
                                            Text(stringResource(R.string.amap_change_verification_failed), color = MaterialTheme.colorScheme.error)
                                        }
                                        OutlinedButton(onClick = { onChangeKey(); showKeyDialog = true }, modifier = Modifier.fillMaxWidth()) {
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
                                        OutlinedButton(onClick = { onChangeKey(); showKeyDialog = true }, modifier = Modifier.fillMaxWidth()) {
                                            Text(stringResource(R.string.amap_change_key))
                                        }
                                    }
                                    else -> {
                                        Text(stringResource(R.string.amap_key_not_saved), style = MaterialTheme.typography.bodySmall)
                                        OutlinedButton(onClick = { showKeyDialog = true }, modifier = Modifier.fillMaxWidth()) {
                                            Text(stringResource(R.string.amap_enter_key))
                                        }
                                        Text(stringResource(R.string.amap_key_storage_notice), style = MaterialTheme.typography.bodySmall)
                                        if (uiState.keyError) Text(stringResource(R.string.amap_key_invalid), color = MaterialTheme.colorScheme.error)
                                        if (uiState.verificationFailed) Text(stringResource(R.string.amap_verification_failed), color = MaterialTheme.colorScheme.error)
                                    }
                                }
                                if (uiState.restartRequired) Text(stringResource(R.string.amap_restart_required), color = MaterialTheme.colorScheme.error)
                                OutlinedButton(
                                    onClick = onNavigateToPreview,
                                    enabled = uiState.hasVerifiedKey && uiState.privacyAgreed && !uiState.restartRequired,
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text(stringResource(R.string.amap_preview)) }
                            }
                        }
                    }
                }
            }
        }

        val previous = step.previous()
        val next = step.next()
        when {
            previous != null && next != null -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(onClick = { step = previous }, modifier = Modifier.weight(1f)) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.amap_setup_previous)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.amap_setup_previous))
                }
                Button(onClick = { step = next }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.amap_setup_next))
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = stringResource(R.string.amap_setup_next)
                    )
                }
            }
            next != null -> Button(onClick = { step = next }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.amap_setup_next))
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = stringResource(R.string.amap_setup_next)
                )
            }
            previous != null -> OutlinedButton(onClick = { step = previous }, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.amap_setup_previous)
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.amap_setup_previous))
            }
        }
    }

    if (showKeyDialog) {
        AlertDialog(
            onDismissRequest = {
                showKeyDialog = false
                onCancelKeyEdit()
            },
            title = { Text(stringResource(R.string.amap_key_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.amap_key_dialog_hint), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = uiState.keyInput,
                        onValueChange = onKeyChange,
                        label = { Text(stringResource(R.string.amap_key_label)) },
                        visualTransformation = PasswordVisualTransformation(),
                        isError = uiState.keyError,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    if (uiState.keyError) Text(stringResource(R.string.amap_key_invalid), color = MaterialTheme.colorScheme.error)
                    if (!uiState.privacyAgreed) {
                        Text(stringResource(R.string.amap_verification_requires_privacy), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showKeyDialog = false
                        onVerifyDraftKey()
                    },
                    enabled = uiState.keyInput.isNotBlank() && uiState.privacyAgreed
                ) { Text(stringResource(R.string.amap_verify_and_save)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showKeyDialog = false
                        onCancelKeyEdit()
                    }
                ) { Text(stringResource(R.string.amap_cancel)) }
            }
        )
    }
}
