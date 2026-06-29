package com.matelink.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.matelink.data.model.Instance
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private val Context.instanceDataStore: DataStore<Preferences> by preferencesDataStore(name = "matelink_instances")

@Singleton
class InstanceDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureStore: SecureSettingsDataStore
) {
    private val instancesKey = stringPreferencesKey("instances_json")
    private val activeInstanceIdKey = stringPreferencesKey("active_instance_id")

    /** Flow of all configured instances. */
    val instances: Flow<List<Instance>> = context.instanceDataStore.data.map { prefs ->
        parseInstances(prefs[instancesKey] ?: "[]")
    }

    /** Flow of the currently active instance ID. */
    val activeInstanceId: Flow<String?> = context.instanceDataStore.data.map { prefs ->
        prefs[activeInstanceIdKey]
    }

    /** Flow of the currently active instance (null if none configured). */
    val activeInstance: Flow<Instance?> = context.instanceDataStore.data.map { prefs ->
        val all = parseInstances(prefs[instancesKey] ?: "[]")
        val activeId = prefs[activeInstanceIdKey]
        all.find { it.id == activeId } ?: all.firstOrNull()
    }

    /** Get the API token for a specific instance. */
    fun getTokenForInstance(instanceId: String): String {
        return secureStore.getInstanceToken(instanceId)
    }

    /** Save the API token for a specific instance. */
    suspend fun saveTokenForInstance(instanceId: String, token: String) {
        secureStore.setInstanceToken(instanceId, token)
    }

    /** Add a new instance. Returns the created instance. */
    suspend fun addInstance(instance: Instance, apiToken: String): Instance {
        context.instanceDataStore.edit { prefs ->
            val current = parseInstances(prefs[instancesKey] ?: "[]").toMutableList()
            current.add(instance)
            prefs[instancesKey] = instancesToJson(current)
            // Auto-select if first instance
            if (current.size == 1) {
                prefs[activeInstanceIdKey] = instance.id
            }
        }
        saveTokenForInstance(instance.id, apiToken)
        return instance
    }

    /** Update an existing instance. */
    suspend fun updateInstance(instance: Instance, apiToken: String? = null) {
        context.instanceDataStore.edit { prefs ->
            val current = parseInstances(prefs[instancesKey] ?: "[]").toMutableList()
            val index = current.indexOfFirst { it.id == instance.id }
            if (index >= 0) {
                current[index] = instance
                prefs[instancesKey] = instancesToJson(current)
            }
        }
        if (apiToken != null) {
            saveTokenForInstance(instance.id, apiToken)
        }
    }

    /** Remove an instance by ID. */
    suspend fun removeInstance(instanceId: String) {
        context.instanceDataStore.edit { prefs ->
            val current = parseInstances(prefs[instancesKey] ?: "[]").toMutableList()
            current.removeAll { it.id == instanceId }
            prefs[instancesKey] = instancesToJson(current)
            // If removed the active instance, switch to first available or clear
            if (prefs[activeInstanceIdKey] == instanceId) {
                val nextId = current.firstOrNull()?.id
                if (nextId != null) {
                    prefs[activeInstanceIdKey] = nextId
                } else {
                    prefs.remove(activeInstanceIdKey)
                }
            }
        }
        secureStore.removeInstanceToken(instanceId)
    }

    /** Switch the active instance. */
    suspend fun setActiveInstance(instanceId: String) {
        context.instanceDataStore.edit { prefs ->
            prefs[activeInstanceIdKey] = instanceId
        }
    }

    /** Get the active instance ID (single-shot read). */
    suspend fun getActiveInstanceId(): String? {
        return context.instanceDataStore.data.map { prefs ->
            prefs[activeInstanceIdKey]
        }.first()
    }

    // ---- JSON serialization ----

    private fun parseInstances(json: String): List<Instance> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                Instance(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    serverUrl = obj.getString("serverUrl"),
                    carId = obj.optInt("carId", 1)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun instancesToJson(instances: List<Instance>): String {
        val arr = JSONArray()
        for (inst in instances) {
            val obj = JSONObject()
            obj.put("id", inst.id)
            obj.put("name", inst.name)
            obj.put("serverUrl", inst.serverUrl)
            obj.put("carId", inst.carId)
            arr.put(obj)
        }
        return arr.toString()
    }
}
