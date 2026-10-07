package com.matelink.data.local

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private val Context.chargeEvents by preferencesDataStore("completed_charge_events")
/** One durable event; Android delivery and foreground acknowledgement are independent. */
@Singleton
class CompletedChargeEventStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val mutex = Mutex()
    private fun key(carId: Int, kind: String) = stringPreferencesKey("vehicle_${carId}_$kind")
    val changes get() = context.chargeEvents.data
    fun events(carId: Int, kind: String = "charge") = changes.map { decode(it[key(carId,kind)]).events }
    suspend fun record(carId: Int, remoteCarId: Int, completed: List<Pair<Int, String>>, kind: String = "charge") = mutex.withLock {
        context.chargeEvents.edit { prefs ->
            val previous=decode(prefs[key(carId,kind)])
            prefs[key(carId,kind)] = encode(advanceCompletionState(previous,carId,remoteCarId,kind,completed))
        }
    }
    suspend fun consume(carId: Int, id: Int, system: Boolean, kind: String = "charge") = mutex.withLock {
        context.chargeEvents.edit { prefs ->
            val previous=decode(prefs[key(carId,kind)])
            prefs[key(carId,kind)] = encode(previous.copy(events=consumeCompletionEvent(previous.events,id,system)))
        }
    }
    private fun decode(raw: String?): CompletionState {
        if(raw==null) return CompletionState(null,java.time.Instant.now().toEpochMilli(),emptyList())
        val obj=JSONObject(raw)
        val rows=obj.getJSONArray("events")
        val events=(0 until rows.length()).map { i -> rows.getJSONObject(i).let {
            CompletedChargeEvent(it.getInt("car"),it.getInt("remote"),it.getInt("id"),it.getString("end"),
                it.optBoolean("system"),it.optBoolean("app"),it.optString("kind","charge"))
        } }
        val seen=obj.getJSONArray("seen")
        return CompletionState(obj.getInt("baseline"),obj.getLong("cutoff"),events,(0 until seen.length()).map { seen.getInt(it) }.toSet())
    }
    private fun encode(state: CompletionState): String = JSONObject().apply {
        put("baseline",state.baseline ?: 0); put("cutoff",state.cutoff);put("seen",JSONArray(state.seen.sorted()))
        put("events",JSONArray().apply { state.events.forEach { e -> put(JSONObject().apply {
            put("car",e.carId);put("remote",e.remoteCarId);put("id",e.chargeId);put("end",e.endDate)
            put("kind",e.kind);put("system",e.systemConsumed);put("app",e.appConsumed)
        }) } })
    }.toString()
}
