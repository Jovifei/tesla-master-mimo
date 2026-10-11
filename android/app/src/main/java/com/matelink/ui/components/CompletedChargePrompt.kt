package com.matelink.ui.components

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.hilt.navigation.compose.hiltViewModel
import com.matelink.R
import androidx.compose.ui.res.stringResource
import com.matelink.data.local.*
import com.matelink.data.local.dao.DriveSummaryDao
import com.matelink.data.local.dao.ChargeSummaryDao
import com.matelink.data.sync.canPublishCompletion
import com.matelink.data.sync.matchesDrive
import com.matelink.data.sync.matchesCharge
import com.matelink.data.sync.resolvePendingCompletion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import javax.inject.Inject

@HiltViewModel
class CompletedChargePromptViewModel @Inject constructor(
    private val store: CompletedChargeEventStore,
    private val vehicles: VehicleContextRepository,
    private val settings: SettingsDataStore,
    private val sessions: JourVoltSessionStore,
    private val modes: ConnectionModeStore,
    private val drives: DriveSummaryDao,
    private val charges: ChargeSummaryDao
) : ViewModel() {
    private suspend fun currentScope(): HistoryReadScope? = try {
        vehicles.captureReadScope()
    } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    private suspend fun qualified(event: CompletedChargeEvent, remoteId: Int, scope: HistoryReadScope): Boolean {
        val context = vehicles.cachedContextForRemote(remoteId, scope) ?: return false
        val localId = context.localHistoryCarId
        val key = completionScopeKey(scope, localId, remoteId)
        return when (event.kind) {
            "drive" -> resolvePendingCompletion(event, localId, remoteId, key,
                { vehicles.isHistoryContextCurrent(scope, context) }, { drives.get(localId, event.chargeId) },
                { it.canPublishCompletion(scope.source) && event.matchesDrive(it) }) != null
            "charge" -> resolvePendingCompletion(event, localId, remoteId, key,
                { vehicles.isHistoryContextCurrent(scope, context) }, { charges.get(localId, event.chargeId) },
                { it.canPublishCompletion(scope.source) && event.matchesCharge(it) }) != null
            else -> false
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun pending(remoteId: Int) = combine(store.changes, settings.settings, sessions.session, modes.mode) {
        _, _, _, _ -> Unit
    }.mapLatest {
        try {
            val scope = currentScope() ?: return@mapLatest null
            val localId = vehicles.cachedContextForRemote(remoteId, scope)?.localHistoryCarId ?: return@mapLatest null
            val key = completionScopeKey(scope, localId, remoteId)
            val events = store.events(localId, key).first() + store.events(localId, key, "drive").first()
            events.firstOrNull { !it.appConsumed && qualified(it, remoteId, scope) }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }

    fun dismiss(event: CompletedChargeEvent, remoteId: Int, onOpen: (() -> Unit)? = null) {
        viewModelScope.launch {
            try {
                val scope = currentScope() ?: return@launch
                if (!qualified(event, remoteId, scope)) return@launch
                store.consume(event, false)
                if (qualified(event, remoteId, scope)) onOpen?.invoke()
            } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Retain the journal on failure. */ }
        }
    }
}

@Composable
fun CompletedChargePrompt(remoteCarId: Int, identityKey: String, onDetail: (String, Int) -> Unit,
    model: CompletedChargePromptViewModel = hiltViewModel()) {
    var event by remember(remoteCarId, identityKey) { mutableStateOf<CompletedChargeEvent?>(null) }
    LaunchedEffect(remoteCarId, identityKey) {
        model.pending(remoteCarId).collect { event = it }
    }
    event?.let { e -> AlertDialog(
        onDismissRequest = { model.dismiss(e, remoteCarId) },
        title = { Text(stringResource(if (e.kind == "drive") R.string.trip_notification_title else R.string.completed_charge_title)) },
        text = { Text(stringResource(if (e.kind == "drive") R.string.completed_drive_body else R.string.completed_charge_body)) },
        confirmButton = { TextButton(onClick = { model.dismiss(e, remoteCarId) { onDetail(e.kind, e.chargeId) } }) { Text(stringResource(R.string.completed_charge_open)) } },
        dismissButton = { TextButton(onClick = { model.dismiss(e, remoteCarId) }) { Text(stringResource(android.R.string.cancel)) } }
    ) }
}
