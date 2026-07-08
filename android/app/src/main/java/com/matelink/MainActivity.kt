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
import com.matelink.ui.theme.MateLinkTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // Tracks the latest intent so a new deep-link arriving while the activity
    // is already running (onNewIntent) propagates into the Compose tree and
    // re-fires NavGraph's LaunchedEffect(intent). Held as Compose state so a
    // new instance triggers recomposition without recreating the activity.
    private var currentIntent by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        currentIntent = intent
        setContent {
            MateLinkTheme {
                MateLinkNavHost(intent = currentIntent)
            }
        }
    }

    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)
        currentIntent = newIntent
    }
}
