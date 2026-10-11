package com.matelink.data.local

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.chargeEvents by preferencesDataStore("completed_charge_events")

/**
 * Durable independent system/app acknowledgements. v2 keys never reinterpret or
 * delete old unscoped JSON: an origin/account change establishes its own silent
 * full-sync baseline. No Room migration or history rewrite is involved.
 */
@Singleton
class CompletedChargeEventStore @Inject constructor(@ApplicationContext private val context: Context) {
    private fun key(carId: Int, kind: String, scopeKey: String) =
        stringPreferencesKey("completion_v2_${carId}_${kind}_$scopeKey")
    val changes get() = context.chargeEvents.data

    internal fun events(carId: Int, scopeKey: String, kind: String = "charge") = changes.map {
        CompletionJournalCodec.decode(it[key(carId, kind, scopeKey)], System.currentTimeMillis()).events
            .filter { e -> e.carId == carId && e.kind == kind && e.scopeKey == scopeKey }
    }

    internal suspend fun record(
        carId: Int, remoteCarId: Int, completed: List<CompletionReceipt>, scopeKey: String,
        kind: String = "charge"
    ) {
        context.chargeEvents.edit { prefs ->
            val key = key(carId, kind, scopeKey)
            prefs[key] = CompletionJournalCodec.record(prefs[key], carId, remoteCarId, kind,
                scopeKey, completed, System.currentTimeMillis())
        }
    }

    internal suspend fun consume(event: CompletedChargeEvent, system: Boolean) {
        val scopeKey = event.scopeKey ?: return
        context.chargeEvents.edit { prefs ->
            val key = key(event.carId, event.kind, scopeKey)
            val raw = prefs[key] ?: return@edit
            prefs[key] = CompletionJournalCodec.consume(raw, event, system, System.currentTimeMillis())
        }
    }
}
