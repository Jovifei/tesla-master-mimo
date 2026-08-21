package com.matelink

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.matelink.ui.navigation.MateLinkNavHost
import com.matelink.ui.screens.drivereport.DriveReportPromptHost
import com.matelink.ui.theme.MateLinkTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var currentIntent by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        currentIntent = intent
        setContent {
            MateLinkTheme {
                DriveReportPromptHost {
                    MateLinkNavHost(intent = currentIntent)
                }
            }
        }
    }

    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)
        currentIntent = newIntent
    }
}
