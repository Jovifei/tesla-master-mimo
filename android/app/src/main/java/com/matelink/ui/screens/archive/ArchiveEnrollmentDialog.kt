package com.matelink.ui.screens.archive

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.matelink.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** An independent dialog host. Link delivery can only load a preview, never make a decision. */
@Composable
fun ArchiveEnrollmentDialogHost(
    intent: Intent?,
    viewModel: ArchiveEnrollmentViewModel = hiltViewModel()
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by viewModel.uiState.collectAsState()
    DisposableEffect(lifecycle, intent) {
        val observer = ArchiveEnrollmentLifecycleObserver(intent, viewModel)
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) observer.deliverOnce()
        onDispose { lifecycle.removeObserver(observer); viewModel.dismiss() }
    }
    LaunchedEffect(state.phase) {
        if (state.phase == ArchiveEnrollmentPhase.READY || state.phase == ArchiveEnrollmentPhase.SUBMITTING) {
            while (isActive) {
                viewModel.verifyCurrentContext()
                viewModel.checkExpiry()
                delay(1_000)
            }
        }
    }
    if (state.phase == ArchiveEnrollmentPhase.HIDDEN) return
    val ready = state.phase == ArchiveEnrollmentPhase.READY
    val busy = state.phase == ArchiveEnrollmentPhase.LOADING || state.phase == ArchiveEnrollmentPhase.SUBMITTING
    AlertDialog(
        onDismissRequest = viewModel::dismiss,
        title = { Text(stringResource(R.string.archive_enrollment_title)) },
        text = {
            Column {
                when (state.phase) {
                    ArchiveEnrollmentPhase.LOADING -> Text(stringResource(R.string.archive_enrollment_loading))
                    ArchiveEnrollmentPhase.APPROVED -> Text(stringResource(R.string.archive_enrollment_approved))
                    ArchiveEnrollmentPhase.REJECTED -> Text(stringResource(R.string.archive_enrollment_rejected))
                    ArchiveEnrollmentPhase.ERROR -> Text(stringResource(when (state.error) {
                        ArchiveEnrollmentError.IDENTITY_UNAVAILABLE -> R.string.archive_enrollment_identity_changed
                        ArchiveEnrollmentError.EXPIRED -> R.string.archive_enrollment_expired
                        ArchiveEnrollmentError.FORBIDDEN -> R.string.archive_enrollment_forbidden
                        ArchiveEnrollmentError.INVALID_REQUEST -> R.string.archive_enrollment_invalid
                        else -> R.string.archive_enrollment_unavailable
                    }))
                    else -> state.preview?.let { preview ->
                        Text(stringResource(R.string.archive_enrollment_compare))
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.archive_enrollment_vehicle, preview.vehicleName))
                        Text(stringResource(R.string.archive_enrollment_source, preview.sourceLabel))
                        Text(stringResource(R.string.archive_enrollment_fingerprint, preview.scopeFingerprint),
                            style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(12.dp))
                        Text(preview.verificationCode, style = MaterialTheme.typography.headlineMedium,
                            fontFamily = FontFamily.Monospace)
                        val expires = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
                            .withZone(ZoneId.systemDefault()).format(Instant.parse(preview.expiresAt))
                        Text(stringResource(R.string.archive_enrollment_expires, expires),
                            style = MaterialTheme.typography.bodySmall)
                        if (busy) Text(stringResource(R.string.archive_enrollment_submitting))
                    }
                }
            }
        },
        confirmButton = {
            if (ready || busy) {
                TextButton(enabled = ready, onClick = viewModel::approve) {
                    Text(stringResource(R.string.archive_enrollment_approve))
                }
            } else {
                TextButton(onClick = viewModel::dismiss) { Text(stringResource(R.string.archive_enrollment_close)) }
            }
        },
        dismissButton = {
            if (ready) TextButton(onClick = viewModel::reject) {
                Text(stringResource(R.string.archive_enrollment_reject))
            }
            else if (busy) TextButton(onClick = viewModel::dismiss) {
                Text(stringResource(R.string.archive_enrollment_cancel))
            }
        }
    )
}
