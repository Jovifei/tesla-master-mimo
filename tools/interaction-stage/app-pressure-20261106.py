#!/usr/bin/env python3
"""One-shot, exact-anchor source patch for the owned complete-interaction branch.
No provider, production, credentials, migrations, or user files are accessed.
"""
from pathlib import Path
import hashlib
import subprocess

root = Path(__file__).resolve().parents[2]
def replace(path, pairs):
    p = root / path
    text = p.read_text()
    for old, new in pairs:
        assert text.count(old) == 1, (path, old[:100], text.count(old))
        text = text.replace(old, new)
    p.write_text(text)

def create(path, text):
    p = root / path
    assert not p.exists(), path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text)

assert subprocess.check_output(['git','branch','--show-current'], cwd=root, text=True).strip() == 'codex/app-interaction-complete-20261106'
for path, expected in {
    'android/app/src/main/java/com/matelink/ui/screens/tpms/TpmsTrendViewModel.kt':'48a8627edc4962114f3826e21d1f5677fb3b20ee',
    'android/app/src/main/java/com/matelink/ui/screens/tpms/TpmsTrendScreen.kt':'a924d6b08c52edbf8cda0eecadbff80d9ea63cd7',
}.items():
    data=(root/path).read_bytes()
    assert hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()==expected,path

replace('android/app/src/main/java/com/matelink/ui/screens/tpms/TpmsTrendViewModel.kt', [
('import com.matelink.data.local.TirePosition', 'import com.matelink.data.local.SettingsDataStore\nimport com.matelink.data.local.TpmsAlertProfile\nimport com.matelink.data.local.TirePosition'),
('import kotlinx.coroutines.CoroutineScope', 'import kotlinx.coroutines.CoroutineStart\nimport kotlinx.coroutines.flow.distinctUntilChanged\nimport kotlinx.coroutines.CoroutineScope'),
('val samples: List<TpmsPressureSample>, val drives: List<DriveSummary>,\n    val stillCurrent:', 'val samples: List<TpmsPressureSample>, val drives: List<DriveSummary>,\n    val profile: TpmsAlertProfile? = null,\n    val stillCurrent:'),
('private val repository: com.matelink.data.repository.TeslamateRepository\n) : TpmsTrendHistorySource', 'private val repository: com.matelink.data.repository.TeslamateRepository,\n    private val settings: SettingsDataStore\n) : TpmsTrendHistorySource'),
('val drives = driveSummaryDao.getAllChronological(context.localHistoryCarId)\n        verify()\n        return TpmsTrendHistorySnapshot(samples, drives) {', 'val drives = driveSummaryDao.getAllChronological(context.localHistoryCarId)\n        verify()\n        // A setting from another account/source/car cannot colour this history.\n        val profile = try { settings.getTpmsAlertProfile(context.localHistoryCarId) }\n            catch (cancelled: CancellationException) { throw cancelled }\n            catch (_: Exception) { null }\n        verify()\n        return TpmsTrendHistorySnapshot(samples, drives, profile) {'),
('private val onFailure: () -> Unit\n)', 'private val onFailure: () -> Unit,\n    private val onProfile: (TpmsAlertProfile?) -> Unit = {}\n)'),
('refreshJob = scope.launch {', 'val job = scope.launch(start = CoroutineStart.LAZY) {'),
('if (identityCurrent) onSuccess(request.window, analysis) else onFailure()', 'if (identityCurrent) {\n                        onProfile(snapshot.profile)\n                        if (token == requestToken) onSuccess(request.window, analysis)\n                    } else onFailure()'),
('if (token == requestToken) onFailure()\n            }\n        }\n    }', 'if (token == requestToken) onFailure()\n            }\n        }\n        // Install ownership before immediate/reentrant publication can start the next request.\n        refreshJob = job\n        job.start()\n    }'),
('val error: TpmsTrendLoadError? = null\n)', 'val error: TpmsTrendLoadError? = null,\n    val alertProfile: TpmsAlertProfile? = null\n)'),
('private val repository: com.matelink.data.repository.TeslamateRepository\n) : ViewModel()', 'private val repository: com.matelink.data.repository.TeslamateRepository,\n    private val settings: SettingsDataStore\n) : ViewModel()'),
('private var loadedCarId: Int? = null', 'private var loadedCarId: Int? = null\n    private var qualifiedProfile: TpmsAlertProfile? = null'),
('RepositoryTpmsTrendHistorySource(historyRepository, driveSummaryDao, vehicleContextRepository, repository)', 'RepositoryTpmsTrendHistorySource(historyRepository, driveSummaryDao, vehicleContextRepository, repository, settings)'),
('_uiState.value = analysis.toUiState(window)', '_uiState.value = analysis.toUiState(window).copy(alertProfile = qualifiedProfile)'),
('onFailure = {\n            _uiState.value = _uiState.value.copy(\n                isLoading = false,\n                error = TpmsTrendLoadError.LOAD_FAILED\n            )\n        }', 'onFailure = {\n            qualifiedProfile = null\n            _uiState.value = _uiState.value.copy(\n                isLoading = false,\n                error = TpmsTrendLoadError.LOAD_FAILED,\n                alertProfile = null\n            )\n        },\n        onProfile = { qualifiedProfile = it }'),
('refreshController.invalidate()\n                // Clear', 'refreshController.invalidate()\n                qualifiedProfile = null\n                // Clear'),
('    fun load(carId: Int) {', '    init {\n        viewModelScope.launch {\n            settings.tpmsAlertProfiles.distinctUntilChanged().collect {\n                loadedCarId?.let { carId ->\n                    qualifiedProfile = null\n                    _uiState.value = _uiState.value.copy(alertProfile = null)\n                    refreshController.refresh(carId, _uiState.value.selectedWindow)\n                }\n            }\n        }\n    }\n\n    fun load(carId: Int) {'),
('        loadedCarId = carId\n        _uiState.value = _uiState.value.copy(isLoading = true, error = null)', '        val changedCar = loadedCarId != carId\n        loadedCarId = carId\n        qualifiedProfile = null\n        _uiState.value = if (changedCar) TpmsTrendUiState(selectedWindow = _uiState.value.selectedWindow)\n            else _uiState.value.copy(isLoading = true, error = null, alertProfile = null)'),
])
replace('android/app/src/main/java/com/matelink/ui/screens/tpms/TpmsTrendScreen.kt', [
('TpmsVehicleVisual(readings, focusedWheel, onSelect = { focusedWheel = it })', 'TpmsVehicleVisual(readings, focusedWheel, onSelect = { focusedWheel = it }, profile = state.alertProfile)'),
])
replace('android/app/src/main/java/com/matelink/ui/components/InteractionVisuals.kt', [
('import com.matelink.data.local.TirePosition', 'import com.matelink.data.local.TpmsAlertProfile\nimport com.matelink.data.local.TirePosition'),
('onSelect: (TirePosition?) -> Unit, modifier: Modifier = Modifier) {', 'onSelect: (TirePosition?) -> Unit, modifier: Modifier = Modifier, profile: TpmsAlertProfile? = null) {'),
('val stateLabel = stringResource(when (reading.state) {', 'val band = visualPressureBand(reading, profile)\n                            val stateLabel = stringResource(when {\n                                band == VisualPressureBand.LOW -> R.string.interaction_evidence_pressure_low\n                                band == VisualPressureBand.HIGH -> R.string.interaction_evidence_pressure_high\n                                band == VisualPressureBand.IN_RANGE -> R.string.interaction_evidence_pressure_range\n                                else -> when (reading.state) {'),
('else -> R.string.interaction_unknown\n                            })', 'else -> R.string.interaction_unknown\n                                }\n                            })'),
('val accent = if (reading.state == VisualWheelState.WARNING) MaterialTheme.colorScheme.error', 'val accent = if (band == VisualPressureBand.LOW || band == VisualPressureBand.HIGH ||\n                                reading.state == VisualWheelState.WARNING) MaterialTheme.colorScheme.error'),
('        val observedAt = if (selected == null)', '        if (profile?.enabled == true && profile.isValid) Text(stringResource(R.string.interaction_evidence_pressure_source),\n            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)\n        val observedAt = if (selected == null)'),
])
replace('android/app/src/main/java/com/matelink/ui/components/InteractionEvidencePresentation.kt', [
('import com.matelink.domain.model.VisualWheelReading', 'import com.matelink.data.api.models.TpmsDetails\nimport com.matelink.data.local.TirePosition\nimport com.matelink.data.local.TpmsAlertProfile\nimport com.matelink.data.local.TpmsCustomPressureState\nimport com.matelink.domain.analytics.TpmsCustomAlertEvaluator\nimport com.matelink.domain.model.VisualWheelReading'),
])
with (root/'android/app/src/main/java/com/matelink/ui/components/InteractionEvidencePresentation.kt').open('a') as out:
    out.write('''
internal enum class VisualPressureBand { UNASSESSED, IN_RANGE, LOW, HIGH }

/** Reuses the existing configured comparator; never assigns a factory/default threshold. */
internal fun visualPressureBand(input: VisualWheelReading?, profile: TpmsAlertProfile?): VisualPressureBand {
    val reading = qualifiedVisualReading(input)
    if (profile?.enabled != true || !profile.isValid ||
        reading.state !in setOf(VisualWheelState.RECORDED, VisualWheelState.WARNING)) return VisualPressureBand.UNASSESSED
    val observation = TpmsCustomAlertEvaluator().observe(profile, TpmsDetails(pressureFl = reading.pressureBar))[TirePosition.FL]
    if (observation?.observed != true) return VisualPressureBand.UNASSESSED
    return when (observation.state) {
        TpmsCustomPressureState.LOW -> VisualPressureBand.LOW
        TpmsCustomPressureState.HIGH -> VisualPressureBand.HIGH
        null -> VisualPressureBand.IN_RANGE
    }
}
''')
for locale, strings in {
    'values': ('Below saved lower limit','Above saved upper limit','Within saved range','Compared with your saved thresholds; not a Tesla warning'),
    'values-zh': ('低于设置下限','高于设置上限','设置范围内','按你保存的阈值比较，不代表车辆原厂告警'),
}.items():
    additions=''.join(f'    <string name="interaction_evidence_pressure_{key}">{text}</string>\n' for key,text in zip(('low','high','range','source'),strings))
    replace(f'android/app/src/main/res/{locale}/interaction_evidence.xml',[('</resources>',additions+'</resources>')])
replace('tools/interaction-stage/verify-complete-preservation.py', [
("allowed_app = {", "allowed_app = {\n    'android/app/src/main/java/com/matelink/ui/screens/tpms/TpmsTrendViewModel.kt',\n    'android/app/src/main/java/com/matelink/ui/screens/tpms/TpmsTrendScreen.kt',"),
])
create('android/app/src/test/java/com/matelink/ui/components/ConfiguredPressureVisualTest.kt', '''package com.matelink.ui.components

import com.matelink.data.local.TpmsAlertProfile
import com.matelink.domain.model.VisualWheelReading
import com.matelink.domain.model.VisualWheelState
import org.junit.Assert.*
import org.junit.Test

class ConfiguredPressureVisualTest {
    private val profile = TpmsAlertProfile(2.9, 2.6, 3.4, true)
    private fun reading(pressure: Double) = VisualWheelReading(pressure, 1800000000000L, VisualWheelState.RECORDED)
    @Test fun usesOnlySavedLimitsAndPreservesZeroAsAQualifiedLowValue() {
        assertEquals(VisualPressureBand.LOW, visualPressureBand(reading(0.0), profile))
        assertEquals(VisualPressureBand.LOW, visualPressureBand(reading(2.59), profile))
        assertEquals(VisualPressureBand.IN_RANGE, visualPressureBand(reading(2.6), profile))
        assertEquals(VisualPressureBand.IN_RANGE, visualPressureBand(reading(3.4), profile))
        assertEquals(VisualPressureBand.HIGH, visualPressureBand(reading(3.41), profile))
    }
    @Test fun absentDisabledAndInvalidSettingsNeverMeanNormal() {
        for (setting in listOf(null, profile.copy(enabled = false), profile.copy(lowBar = 4.0), profile.copy(highBar = Double.NaN))) {
            assertEquals(VisualPressureBand.UNASSESSED, visualPressureBand(reading(2.9), setting))
        }
    }
    @Test fun staleUnknownMalformedAndMissingTimesNeverBecomeCurrentWarnings() {
        for (row in listOf(null, reading(2.0).copy(state = VisualWheelState.STALE),
            reading(2.0).copy(state = VisualWheelState.UNKNOWN), reading(Double.NaN),
            reading(2.0).copy(observedAt = null))) {
            assertEquals(VisualPressureBand.UNASSESSED, visualPressureBand(row, profile))
        }
    }
    @Test fun ruleChangesReclassifyWithoutMutatingTheOriginalObservation() {
        val row = reading(2.7)
        assertEquals(VisualPressureBand.IN_RANGE, visualPressureBand(row, profile))
        assertEquals(VisualPressureBand.LOW, visualPressureBand(row, profile.copy(lowBar = 2.8)))
        assertEquals(reading(2.7), row)
    }
}
''')
create('android/app/src/test/java/com/matelink/ui/screens/tpms/TpmsProfileVisualPipelineTest.kt', '''package com.matelink.ui.screens.tpms

import com.matelink.data.local.TpmsAlertProfile
import com.matelink.data.local.entity.DriveSummary
import com.matelink.data.local.entity.TpmsPressureSample
import com.matelink.domain.analytics.TpmsTrendAnalyzer
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TpmsProfileVisualPipelineTest {
    private val profile = TpmsAlertProfile(2.9, 2.6, 3.4, true)
    private fun source(read: suspend (Int) -> TpmsTrendHistorySnapshot) = object : TpmsTrendHistorySource {
        override suspend fun load7DaySamples(carId: Int, now: Long) = emptyList<TpmsPressureSample>()
        override suspend fun load30DaySamples(carId: Int, now: Long) = emptyList<TpmsPressureSample>()
        override suspend fun loadDrives(carId: Int) = emptyList<DriveSummary>()
        override suspend fun loadSnapshot(carId: Int, window: TpmsTrendWindow, now: Long) = read(carId)
    }
    @Test fun publishesProfileOnlyAfterTheSameSnapshotIdentityCheck() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val published = mutableListOf<TpmsAlertProfile?>()
        val order = mutableListOf<String>()
        try {
            val input = source { TpmsTrendHistorySnapshot(emptyList(), emptyList(), profile) { order += "identity"; true } }
            val controller = TpmsTrendRefreshController(input, TpmsTrendAnalyzer(), scope,
                { _, _ -> order += "history" }, { error("unexpected failure") },
                { published += it; order += "profile" })
            controller.refresh(7, TpmsTrendWindow.THIRTY)
            assertEquals(listOf(profile), published)
            assertEquals(listOf("identity", "profile", "history"), order)
        } finally { scope.cancel() }
    }
    @Test fun changedIdentityNeverPublishesItsThresholdsOrHistory() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        var failures = 0
        try {
            val input = source { TpmsTrendHistorySnapshot(emptyList(), emptyList(), profile) { false } }
            TpmsTrendRefreshController(input, TpmsTrendAnalyzer(), scope,
                { _, _ -> error("foreign history") }, { failures++ }, { error("foreign profile") })
                .refresh(7, TpmsTrendWindow.THIRTY)
            assertEquals(1, failures)
        } finally { scope.cancel() }
    }
    @Test fun lateProfileCannotReplaceAnotherCarsCurrentRule() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val gate = CompletableDeferred<Boolean>()
        val published = mutableListOf<TpmsAlertProfile?>()
        val newer = profile.copy(lowBar = 2.8)
        try {
            val input = source { car ->
                if (car == 1) TpmsTrendHistorySnapshot(emptyList(), emptyList(), profile) { withContext(NonCancellable) { gate.await() } }
                else TpmsTrendHistorySnapshot(emptyList(), emptyList(), newer)
            }
            val controller = TpmsTrendRefreshController(input, TpmsTrendAnalyzer(), scope, { _, _ -> }, { error("unexpected failure") }, { published += it })
            controller.refresh(1, TpmsTrendWindow.THIRTY)
            controller.refresh(2, TpmsTrendWindow.THIRTY)
            gate.complete(true)
            assertEquals(listOf(newer), published)
        } finally { scope.cancel() }
    }
    @Test fun reentrantRefreshStillOwnsTheNewJobAndCancelsItOnInvalidation() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        var cancelled = false
        var success = 0
        lateinit var controller: TpmsTrendRefreshController
        try {
            val input = source { car ->
                if (car == 2) try { awaitCancellation() } finally { cancelled = true }
                TpmsTrendHistorySnapshot(emptyList(), emptyList(), profile)
            }
            controller = TpmsTrendRefreshController(input, TpmsTrendAnalyzer(), scope,
                { _, _ -> success++; if (success == 1) controller.refresh(2, TpmsTrendWindow.THIRTY) },
                { error("unexpected failure") })
            controller.refresh(1, TpmsTrendWindow.THIRTY)
            controller.invalidate()
            assertTrue("The latest job was lost after synchronous completion", cancelled)
            assertEquals(1, success)
        } finally { scope.cancel() }
    }
}
''')
replace('android/app/src/androidTest/java/com/matelink/InteractionEvidenceAndroidTest.kt', [
('    @Test fun openingsExpose', '''    @Test fun configuredLimitsAreWiredToTheActualFourWheelConsumer() {
        val setting = mutableStateOf<com.matelink.data.local.TpmsAlertProfile?>(com.matelink.data.local.TpmsAlertProfile(2.9, 2.6, 3.4, true))
        val readings = mapOf(TirePosition.FL to VisualWheelReading(0.0, 1800000000000L, VisualWheelState.RECORDED),
            TirePosition.FR to VisualWheelReading(3.5, 1800000000000L, VisualWheelState.RECORDED),
            TirePosition.RL to VisualWheelReading(2.9, 1800000000000L, VisualWheelState.RECORDED))
        compose.setContent { MateLinkTheme { TpmsVehicleVisual(readings, null, {}, profile = setting.value) } }
        compose.onNodeWithTag("interaction-wheel-FL").assertTextContains(label(R.string.interaction_evidence_pressure_low), substring = true)
        compose.onNodeWithTag("interaction-wheel-FR").assertTextContains(label(R.string.interaction_evidence_pressure_high), substring = true)
        compose.onNodeWithTag("interaction-wheel-RL").assertTextContains(label(R.string.interaction_evidence_pressure_range), substring = true)
        compose.runOnIdle { setting.value = null }
        compose.onNodeWithText(label(R.string.interaction_evidence_pressure_low)).assertDoesNotExist()
        compose.onNodeWithTag("interaction-wheel-FL").assertTextContains(label(R.string.interaction_observed), substring = true)
    }

    @Test fun openingsExpose'''),
])
print('Applied exact-anchor scoped settings -> qualified history -> actual visual consumer and regression patch')
