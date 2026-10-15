package com.matelink.ui.screens.readiness

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.matelink.R

@Composable
fun ReadinessDiagnosticCards(facts: List<DiagnosticFact>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.diagnostic_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.diagnostic_scope_note), style = MaterialTheme.typography.bodySmall)
        facts.forEach { fact -> DiagnosticCard(fact) }
    }
}

@Composable
private fun DiagnosticCard(fact: DiagnosticFact) {
    var expanded by remember(fact) { mutableStateOf(false) }
    val title = when (fact.step) {
        DiagnosticStep.AUTHORIZATION -> R.string.diagnostic_authorization
        DiagnosticStep.VEHICLE -> R.string.diagnostic_vehicle
        DiagnosticStep.RECEIVE -> R.string.diagnostic_receive
        DiagnosticStep.QUALIFICATION -> R.string.diagnostic_qualification
        DiagnosticStep.HISTORY -> R.string.diagnostic_history
        DiagnosticStep.DISPLAY -> R.string.diagnostic_display
    }
    val icon = when (fact.step) {
        DiagnosticStep.AUTHORIZATION -> Icons.Filled.VerifiedUser
        DiagnosticStep.VEHICLE -> Icons.Filled.DirectionsCar
        DiagnosticStep.RECEIVE -> Icons.Filled.CloudDownload
        DiagnosticStep.QUALIFICATION -> Icons.Filled.FactCheck
        DiagnosticStep.HISTORY -> Icons.Filled.History
        DiagnosticStep.DISPLAY -> Icons.Filled.Notifications
    }
    Card(modifier = Modifier.fillMaxWidth().testTag("diagnostic_${fact.step}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            Text(stringResource(when (fact.result) {
                DiagnosticResult.UNKNOWN -> R.string.diagnostic_unknown
                DiagnosticResult.PASS -> when {
                    fact.step == DiagnosticStep.DISPLAY -> R.string.diagnostic_handled
                    fact.step == DiagnosticStep.RECEIVE && fact.olderReceipt -> R.string.diagnostic_older_receipt
                    fact.step == DiagnosticStep.RECEIVE -> R.string.diagnostic_recorded_receipt
                    fact.step == DiagnosticStep.QUALIFICATION -> R.string.diagnostic_stored_qualification
                    else -> R.string.diagnostic_observed
                }
                DiagnosticResult.WAITING -> when (fact.step) {
                    DiagnosticStep.AUTHORIZATION -> R.string.telemetry_setup_permission_required
                    DiagnosticStep.RECEIVE -> R.string.data_readiness_status_waiting_vehicle
                    else -> R.string.diagnostic_empty
                }
            }), style = MaterialTheme.typography.labelLarge, color = if (fact.olderReceipt)
                MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.data_readiness_source, stringResource(when (fact.source) {
                "scoped_response" -> R.string.diagnostic_source_response
                "verified_identity" -> R.string.diagnostic_source_identity
                "telemetry_mqtt" -> R.string.telemetry_setup_title
                "room_verified", "room_qualified" -> R.string.diagnostic_source_room
                "room_projection" -> R.string.diagnostic_source_projection
                "notification_journal" -> R.string.diagnostic_source_journal
                else -> R.string.visual_status_unknown
            })), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.data_readiness_last_observed,
                fact.observedAt ?: stringResource(R.string.visual_status_unknown)), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (expanded) R.string.diagnostic_hide else R.string.diagnostic_details))
            }
            if (expanded) Text(diagnosticDetail(fact), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun diagnosticDetail(fact: DiagnosticFact): String {
    val parts = fact.detail.split(':')
    return when (parts.first()) {
        "scoped_authorization" -> stringResource(R.string.diagnostic_authorization_detail)
        "scoped_identity" -> stringResource(R.string.diagnostic_vehicle_detail)
        "cached_identity" -> stringResource(R.string.diagnostic_cached_vehicle_detail)
        "collector_receipt", "source_observation", "waiting_vehicle" -> stringResource(R.string.diagnostic_receive_detail)
        "qualified" -> stringResource(R.string.diagnostic_projection_detail,
            parts.getOrNull(1)?.toIntOrNull() ?: 0, parts.getOrNull(2)?.toIntOrNull() ?: 0, parts.getOrNull(3)?.toIntOrNull() ?: 0)
        "history" -> stringResource(R.string.diagnostic_history_detail,
            parts.getOrNull(1)?.toIntOrNull() ?: 0, parts.getOrNull(2)?.toIntOrNull() ?: 0, parts.getOrNull(3)?.toIntOrNull() ?: 0)
        "permission_required" -> stringResource(R.string.diagnostic_permission_detail)
        "delivery" -> stringResource(R.string.diagnostic_delivery_detail,
            stringResource(when (parts.getOrNull(1)) {
                "true" -> R.string.diagnostic_enabled
                "false" -> R.string.diagnostic_disabled
                else -> R.string.visual_status_unknown
            }), parts.getOrNull(2)?.toIntOrNull() ?: 0, parts.getOrNull(3)?.toIntOrNull() ?: 0, parts.getOrNull(4)?.toIntOrNull() ?: 0)
        else -> stringResource(R.string.diagnostic_unknown_detail)
    }
}
