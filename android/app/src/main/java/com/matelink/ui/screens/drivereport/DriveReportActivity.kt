package com.matelink.ui.screens.drivereport

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.matelink.R
import com.matelink.data.report.DriveReportDeliveryRepository
import com.matelink.ui.theme.MateLinkTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@OptIn(ExperimentalMaterial3Api::class)
@AndroidEntryPoint
class DriveReportActivity : ComponentActivity() {
    @Inject
    lateinit var deliveryRepository: DriveReportDeliveryRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val carId = intent.getIntExtra(EXTRA_CAR_ID, -1)
        val driveId = intent.getIntExtra(EXTRA_DRIVE_ID, -1)
        if (carId <= 0 || driveId <= 0) {
            finish()
            return
        }

        enableEdgeToEdge()
        setContent {
            MateLinkTheme {
                LaunchedEffect(carId, driveId) {
                    deliveryRepository.markOpened(carId, driveId)
                }
                Scaffold(
                    topBar = {
                        TopAppBar(title = { Text(stringResource(R.string.drive_report_title)) })
                    }
                ) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = stringResource(R.string.drive_report_loading),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_CAR_ID = "drive_report_car_id"
        private const val EXTRA_DRIVE_ID = "drive_report_drive_id"

        fun createIntent(context: Context, carId: Int, driveId: Int): Intent =
            Intent(context, DriveReportActivity::class.java).apply {
                putExtra(EXTRA_CAR_ID, carId)
                putExtra(EXTRA_DRIVE_ID, driveId)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
    }
}
