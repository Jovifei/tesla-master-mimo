package com.matelink.data.local

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.*
import dagger.hilt.android.qualifiers.ApplicationContext
import com.matelink.domain.analytics.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
private val Context.changeRules by preferencesDataStore("tpms_change_rules")
@Singleton
class TpmsChangeRuleStore @Inject constructor(@ApplicationContext private val context: Context) {
    fun observe(carId: Int) = context.changeRules.data.map { p -> TpmsChangeRule(
        p[booleanPreferencesKey("enabled_$carId")] ?: true,
        p[doublePreferencesKey("delta_$carId")] ?: 0.3,
        p[intPreferencesKey("hours_$carId")] ?: 24) }
    suspend fun save(carId: Int, rule: TpmsChangeRule) { require(rule.valid); context.changeRules.edit {
        it[booleanPreferencesKey("enabled_$carId")]=rule.enabled
        it[doublePreferencesKey("delta_$carId")]=rule.deltaBar
        it[intPreferencesKey("hours_$carId")]=rule.windowHours
        TirePosition.entries.forEach { wheel -> it.remove(stringPreferencesKey("pending_${carId}_$wheel")) }
    } }
    suspend fun pending(carId: Int): List<TpmsPressureChange> {
        val p=context.changeRules.data.first()
        return TirePosition.entries.mapNotNull { wheel ->
            val raw=p[stringPreferencesKey("pending_${carId}_$wheel")]?.split(',') ?: return@mapNotNull null
            runCatching { TpmsPressureChange(wheel,raw[0].toDouble(),raw[1].toDouble(),raw[2].toLong()) }.getOrNull()
        }
    }
    suspend fun record(carId: Int, changes: List<TpmsPressureChange>) { context.changeRules.edit { p -> changes.forEach { c ->
        p[stringPreferencesKey("pending_${carId}_${c.wheel}")]="${c.before},${c.after},${c.observedAt}"
    } } }
    suspend fun acknowledge(carId: Int, change: TpmsPressureChange) { context.changeRules.edit { p ->
        val key=stringPreferencesKey("pending_${carId}_${change.wheel}")
        if(p[key]=="${change.before},${change.after},${change.observedAt}") p.remove(key)
    } }
}
