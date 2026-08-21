package com.matelink.ui.screens.drivereport

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.matelink.R

@Composable
fun DriveReportPromptHost(
    viewModel: DriveReportPromptViewModel = hiltViewModel(),
    content: @Composable () -> Unit
) {
    val prompt by viewModel.prompt.collectAsState()
    val context = LocalContext.current

    content()

    prompt?.let { batch ->
        AlertDialog(
            onDismissRequest = { viewModel.hideBatchForSession(batch) },
            title = { Text(stringResource(R.string.drive_report_prompt_title)) },
            text = {
                Text(
                    if (batch.count == 1) {
                        stringResource(R.string.drive_report_prompt_message)
                    } else {
                        stringResource(R.string.drive_report_prompt_summary_message, batch.count)
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.hideBatchForSession(batch)
                        context.startActivity(
                            DriveReportActivity.createIntent(
                                context,
                                batch.latest.carId,
                                batch.latest.driveId
                            )
                        )
                    }
                ) {
                    Text(stringResource(R.string.drive_report_prompt_view))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.hideBatchForSession(batch) }) {
                    Text(stringResource(R.string.drive_report_prompt_later))
                }
            }
        )
    }
}
