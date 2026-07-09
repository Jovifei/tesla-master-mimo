package com.matelink.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.matelink.data.local.InstanceDataStore
import com.matelink.data.local.SettingsDataStore
import com.matelink.data.local.StatsDatabase
import com.matelink.data.model.Instance
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.ConnectionTestOutcome
import com.matelink.data.repository.TeslamateRepository
import com.matelink.data.sync.DataSyncWorker
import com.matelink.widget.CarWidgetUpdateWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class InstanceEditorState(
    val id: String? = null, // null = new instance
    val name: String = "",
    val serverUrl: String = "",
    val apiToken: String = "",
    val carId: Int = 1,
    val isTestingConnection: Boolean = false,
    val testResult: ServerTestResult? = null,
    val allowUntestedSave: Boolean = false
)

data class InstanceUiState(
    val instances: List<Instance> = emptyList(),
    val activeInstanceId: String? = null,
    val editorState: InstanceEditorState? = null, // null = no editor open
    val isLoading: Boolean = true
)

@HiltViewModel
class InstanceViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val instanceDataStore: InstanceDataStore,
    private val settingsDataStore: SettingsDataStore,
    private val statsDatabase: StatsDatabase,
    private val teslamateRepository: TeslamateRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(InstanceUiState())
    val uiState: StateFlow<InstanceUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                instanceDataStore.instances,
                instanceDataStore.activeInstanceId
            ) { instances, activeId ->
                InstanceUiState(
                    instances = instances,
                    activeInstanceId = activeId,
                    isLoading = false
                )
            }.collect { merged ->
                // Preserve editorState — DataStore changes must not close an open editor
                _uiState.value = merged.copy(editorState = _uiState.value.editorState)
            }
        }
    }

    /** Switch the active instance. Cancels running sync, clears cached data, and triggers resync. */
    fun switchInstance(instanceId: String) {
        viewModelScope.launch {
            // Cancel any running sync to prevent it from writing stale data
            // after we clear the tables
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(DataSyncWorker.WORK_NAME)

            // Wait for in-flight sync to actually stop (up to 2s)
            withContext(Dispatchers.IO) {
                repeat(20) {
                    val workInfo = workManager.getWorkInfosForUniqueWork(DataSyncWorker.WORK_NAME)
                        .get()
                        .firstOrNull()
                    if (workInfo == null || workInfo.state.isFinished) return@withContext
                    kotlinx.coroutines.delay(100)
                }
            }

            // Set active instance first, then clear tables — prevents stale reads
            // during the window between clear and active switch
            instanceDataStore.setActiveInstance(instanceId)

            withContext(Dispatchers.IO) {
                statsDatabase.clearAllTables()
            }

            // Update legacy settings so ApiClient picks up the change
            val instance = _uiState.value.instances.find { it.id == instanceId }
            if (instance != null) {
                withContext(Dispatchers.IO) {
                    val token = instanceDataStore.getTokenForInstance(instanceId)
                    settingsDataStore.saveServerUrl(instance.serverUrl)
                    settingsDataStore.saveApiToken(token)
                }
            }

            // Trigger sync for the new instance
            triggerSync()
        }
    }

    /** Open the editor for a new instance. */
    fun openAddEditor() {
        _uiState.value = _uiState.value.copy(
            editorState = InstanceEditorState()
        )
    }

    /** Open the editor for an existing instance. */
    fun openEditEditor(instance: Instance) {
        viewModelScope.launch {
            val token = withContext(Dispatchers.IO) {
                instanceDataStore.getTokenForInstance(instance.id)
            }
            _uiState.value = _uiState.value.copy(
                editorState = InstanceEditorState(
                    id = instance.id,
                    name = instance.name,
                    serverUrl = instance.serverUrl,
                    apiToken = token,
                    carId = instance.carId
                )
            )
        }
    }

    /** Close the editor without saving. */
    fun closeEditor() {
        _uiState.value = _uiState.value.copy(editorState = null)
    }

    /** Update the editor name field. */
    fun updateEditorName(name: String) {
        _uiState.value = _uiState.value.copy(
            editorState = _uiState.value.editorState?.copy(name = name)
        )
    }

    /** Update the editor server URL field. */
    fun updateEditorServerUrl(url: String) {
        _uiState.value = _uiState.value.copy(
            editorState = _uiState.value.editorState?.copy(
                serverUrl = url,
                testResult = null,
                allowUntestedSave = false
            )
        )
    }

    /** Update the editor API token field. */
    fun updateEditorToken(token: String) {
        _uiState.value = _uiState.value.copy(
            editorState = _uiState.value.editorState?.copy(
                apiToken = token,
                testResult = null,
                allowUntestedSave = false
            )
        )
    }

    /** Update the editor car ID field. */
    fun updateEditorCarId(carId: Int) {
        _uiState.value = _uiState.value.copy(
            editorState = _uiState.value.editorState?.copy(carId = carId)
        )
    }

    fun testEditorConnection() {
        val editor = _uiState.value.editorState ?: return
        if (editor.serverUrl.isBlank()) {
            _uiState.value = _uiState.value.copy(
                editorState = editor.copy(
                    testResult = ServerTestResult.Failure("Server URL is required")
                )
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                editorState = editor.copy(isTestingConnection = true, testResult = null)
            )

            val result = when (val probe = teslamateRepository.testConnection(
                serverUrl = editor.serverUrl,
                apiToken = editor.apiToken
            )) {
                is ApiResult.Success -> probe.data.toServerTestResult()
                is ApiResult.Error -> ServerTestResult.Failure(probe.message, probe.details)
            }

            _uiState.value = _uiState.value.copy(
                editorState = _uiState.value.editorState?.copy(
                    isTestingConnection = false,
                    testResult = result,
                    allowUntestedSave = false
                )
            )
        }
    }

    /** Save the current editor state (add or update). */
    fun saveEditor() {
        val editor = _uiState.value.editorState ?: return
        if (editor.serverUrl.isBlank()) return
        val hasSuccessfulTest = editor.testResult is ServerTestResult.Success
        if (!hasSuccessfulTest && !editor.allowUntestedSave) {
            _uiState.value = _uiState.value.copy(
                editorState = editor.copy(
                    allowUntestedSave = true,
                    testResult = ServerTestResult.Failure(
                        message = "Test this instance before saving, or tap Save again to skip.",
                        hint = "Skipping may leave this instance unable to sync."
                    )
                )
            )
            return
        }
        viewModelScope.launch {
            if (editor.id == null) {
                // Add new instance
                val instance = Instance(
                    name = editor.name.ifBlank { "Instance" },
                    serverUrl = editor.serverUrl.trimEnd('/'),
                    carId = editor.carId
                )
                instanceDataStore.addInstance(instance, editor.apiToken)
            } else {
                // Update existing instance
                val instance = Instance(
                    id = editor.id,
                    name = editor.name.ifBlank { "Instance" },
                    serverUrl = editor.serverUrl.trimEnd('/'),
                    carId = editor.carId
                )
                instanceDataStore.updateInstance(instance, editor.apiToken)
                // If this is the active instance, update legacy settings
                if (editor.id == _uiState.value.activeInstanceId) {
                    settingsDataStore.saveServerUrl(instance.serverUrl)
                    settingsDataStore.saveApiToken(editor.apiToken)
                }
            }
            _uiState.value = _uiState.value.copy(editorState = null)
        }
    }

    private fun ConnectionTestOutcome.toServerTestResult(): ServerTestResult {
        return if (isSuccessful) {
            ServerTestResult.Success(
                carCount = carCount,
                firstCarName = firstCarName,
                warning = readinessWarning
            )
        } else {
            ServerTestResult.Failure(summary, failureHint)
        }
    }

    /** Delete an instance. If it was the active instance, switch to the next available. */
    fun deleteInstance(instanceId: String) {
        viewModelScope.launch {
            val wasActive = _uiState.value.activeInstanceId == instanceId
            instanceDataStore.removeInstance(instanceId)
            if (wasActive) {
                // Cancel any running sync before clearing data
                val workManager = WorkManager.getInstance(context)
                workManager.cancelUniqueWork(DataSyncWorker.WORK_NAME)
                withContext(Dispatchers.IO) {
                    repeat(20) {
                        val workInfo = workManager.getWorkInfosForUniqueWork(DataSyncWorker.WORK_NAME)
                            .get()
                            .firstOrNull()
                        if (workInfo == null || workInfo.state.isFinished) return@withContext
                        kotlinx.coroutines.delay(100)
                    }
                }

                // Update legacy settings for the new active instance
                val newActiveId = instanceDataStore.getActiveInstanceId()
                if (!newActiveId.isNullOrEmpty()) {
                    withContext(Dispatchers.IO) {
                        statsDatabase.clearAllTables()
                    }
                    // Read fresh instance list from DataStore (not stale _uiState)
                    val freshInstances = instanceDataStore.instances.first()
                    val instance = freshInstances.find { it.id == newActiveId }
                    if (instance != null) {
                        withContext(Dispatchers.IO) {
                            val token = instanceDataStore.getTokenForInstance(newActiveId)
                            settingsDataStore.saveServerUrl(instance.serverUrl)
                            settingsDataStore.saveApiToken(token)
                        }
                    }
                    triggerSync()
                }
            }
        }
    }

    private fun triggerSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = OneTimeWorkRequestBuilder<DataSyncWorker>()
            .setConstraints(constraints)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(DataSyncWorker.TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            DataSyncWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            syncRequest
        )

        // Trigger immediate widget update to reflect new instance data
        CarWidgetUpdateWorker.scheduleImmediateUpdate(context)
    }
}
