package com.matelink.ui.screens.settings

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.Column
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.hilt.navigation.compose.hiltViewModel
import com.matelink.data.local.TpmsChangeRuleStore
import com.matelink.domain.analytics.TpmsChangeRule
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
@HiltViewModel
class TpmsChangeSettingsViewModel @Inject constructor(private val store: TpmsChangeRuleStore,
    private val vehicles: com.matelink.data.local.VehicleContextRepository,
    private val settings: com.matelink.data.local.SettingsDataStore,
    private val sessions: com.matelink.data.local.JourVoltSessionStore): ViewModel() {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun rule(remoteId:Int)=kotlinx.coroutines.flow.combine(settings.settings,sessions.session) { _,_ ->
        runCatching { vehicles.cachedContextForRemote(remoteId)?.localHistoryCarId }.getOrNull()
    }.flatMapLatest { id -> if(id==null) kotlinx.coroutines.flow.flowOf(null) else store.observe(id).map { id to it } }
    fun save(id:Int,rule:TpmsChangeRule){ viewModelScope.launch { store.save(id,rule) } }
}
@Composable
fun TpmsChangeSettingsCard(carId:Int, model:TpmsChangeSettingsViewModel=hiltViewModel()) {
    val scoped by remember(carId){model.rule(carId)}.collectAsState(null)
    val (localId,rule)=scoped ?: return

    var delta by remember(localId,rule.deltaBar){mutableStateOf(rule.deltaBar.toString())}
    var hours by remember(localId,rule.windowHours){mutableStateOf(rule.windowHours.toString())}
    var enabled by remember(localId,rule.enabled){mutableStateOf(rule.enabled)}
    Card { Column {
        Text(androidx.compose.ui.res.stringResource(com.matelink.R.string.tpms_change_title))
        Text(androidx.compose.ui.res.stringResource(com.matelink.R.string.tpms_change_help))
        Switch(checked=enabled,onCheckedChange={enabled=it})
        OutlinedTextField(delta,{delta=it},label={Text(androidx.compose.ui.res.stringResource(com.matelink.R.string.tpms_change_delta))})
        OutlinedTextField(hours,{hours=it},label={Text(androidx.compose.ui.res.stringResource(com.matelink.R.string.tpms_change_hours))})
        val candidate=TpmsChangeRule(enabled,delta.toDoubleOrNull() ?: Double.NaN,hours.toIntOrNull() ?: 0)
        Button(onClick={model.save(localId,candidate)},enabled=candidate.valid){Text(androidx.compose.ui.res.stringResource(com.matelink.R.string.save))}
    } }
}
