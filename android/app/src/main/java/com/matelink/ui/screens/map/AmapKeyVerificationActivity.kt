package com.matelink.ui.screens.map

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.matelink.R
import com.matelink.data.local.AmapSettingsStore
import com.matelink.ui.theme.MateLinkTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import javax.inject.Inject

/**
 * Loads a staged candidate Key in a short-lived dedicated process. The candidate
 * never enters a navigation argument or a log, and the active Key is untouched
 * until this activity reports a successful map load.
 */
@AndroidEntryPoint
class AmapKeyVerificationActivity : ComponentActivity() {
    @Inject lateinit var store: AmapSettingsStore

    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val candidateKey = store.currentPendingKey()
        if (candidateKey.isBlank()) {
            complete(Activity.RESULT_CANCELED)
            return
        }
        enableEdgeToEdge()
        setContent {
            MateLinkTheme {
                AmapKeyVerificationContent(
                    apiKey = candidateKey,
                    onCompleted = { verified ->
                        complete(if (verified) Activity.RESULT_OK else Activity.RESULT_CANCELED)
                    }
                )
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = complete(Activity.RESULT_CANCELED)

    private fun complete(resultCode: Int) {
        if (completed) return
        completed = true
        setResult(resultCode)
        finish()
        Handler(Looper.getMainLooper()).postDelayed(
            { Process.killProcess(Process.myPid()) },
            PROCESS_SHUTDOWN_DELAY_MS
        )
    }

    private companion object {
        // Let the Activity-result transaction return to the main process before
        // discarding this process and the SDK's candidate-key state.
        const val PROCESS_SHUTDOWN_DELAY_MS = 1_000L
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AmapKeyVerificationContent(
    apiKey: String,
    onCompleted: (Boolean) -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(apiKey) {
        onCompleted(verifyAmapAndroidKey(context.applicationContext, apiKey))
    }
    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text(stringResource(R.string.amap_verification_title)) },
                navigationIcon = { TextButton(onClick = { onCompleted(false) }) { Text(stringResource(R.string.amap_cancel)) } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator()
            Text(stringResource(R.string.amap_verification_pending), modifier = Modifier.padding(top = 16.dp))
        }
    }
}
