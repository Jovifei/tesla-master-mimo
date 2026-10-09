package com.matelink.ui.components

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.hilt.navigation.compose.hiltViewModel
import com.matelink.R
import androidx.compose.ui.res.stringResource
import com.matelink.data.local.CompletedChargeEvent
import com.matelink.data.local.CompletedChargeEventStore
import com.matelink.data.local.VehicleContextRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import javax.inject.Inject

@HiltViewModel
class CompletedChargePromptViewModel @Inject constructor(private val store: CompletedChargeEventStore,
    private val vehicles: VehicleContextRepository,
    private val settings: com.matelink.data.local.SettingsDataStore,
    private val sessions: com.matelink.data.local.JourVoltSessionStore) : ViewModel() {
    suspend fun historyId(remoteId: Int): Int? = runCatching { vehicles.cachedContextForRemote(remoteId)?.localHistoryCarId }.getOrNull()
    fun pending(remoteId: Int) = kotlinx.coroutines.flow.combine(store.changes,settings.settings,sessions.session) { _,_,_ ->
        val id=historyId(remoteId) ?: return@combine null
        (store.events(id).first()+store.events(id,"drive").first()).firstOrNull { !it.appConsumed }
    }
    fun dismiss(e: CompletedChargeEvent,remoteId: Int,onOpen: (() -> Unit)? = null) { viewModelScope.launch {
        if (historyId(remoteId) != e.carId) return@launch
        store.consume(e.carId,e.chargeId,false,e.kind)
        if (historyId(remoteId) == e.carId) onOpen?.invoke()
    } }

}

@Composable
fun CompletedChargePrompt(remoteCarId: Int, identityKey: String, onDetail: (String,Int) -> Unit,
    model: CompletedChargePromptViewModel = hiltViewModel()) {
    var event by remember(remoteCarId,identityKey) { mutableStateOf<CompletedChargeEvent?>(null) }
    LaunchedEffect(remoteCarId,identityKey) {
        model.pending(remoteCarId).collect { event=it }
    }
    event?.let { e -> AlertDialog(
        onDismissRequest = { model.dismiss(e,remoteCarId) },
        title = { Text(stringResource(if(e.kind=="drive") R.string.trip_notification_title else R.string.completed_charge_title)) },
        text = { Text(stringResource(if(e.kind=="drive") R.string.completed_drive_body else R.string.completed_charge_body)) },
        confirmButton = { TextButton(onClick = { model.dismiss(e,remoteCarId) { onDetail(e.kind,e.chargeId) } }) { Text(stringResource(R.string.completed_charge_open)) } },
        dismissButton = { TextButton(onClick = { model.dismiss(e,remoteCarId) }) { Text(stringResource(android.R.string.cancel)) } }
    ) }
}
