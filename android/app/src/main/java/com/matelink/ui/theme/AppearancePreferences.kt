package com.matelink.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.matelink.R

/** Contains no account, vehicle, history, or credentials; changing it never invalidates data. */
class AppearancePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("matelink_appearance_v1", Context.MODE_PRIVATE)
    fun palette(): AppearancePalette = AppearancePalette.fromStorage(runCatching { prefs.getString("palette", null) }.getOrNull())
    fun dayStackAxis(): DayStackAxis = DayStackAxis.fromStorage(runCatching { prefs.getString("day_stack_axis", null) }.getOrNull())
    fun setDayStackAxis(value: DayStackAxis) { prefs.edit().putString("day_stack_axis", value.storageKey).apply() }
    fun reducedMotion(): Boolean = runCatching { prefs.getBoolean("reduced_motion", false) }.getOrDefault(false)
    fun setPalette(value: AppearancePalette) { prefs.edit().putString("palette", value.storageKey).apply() }
    fun setReducedMotion(value: Boolean) { prefs.edit().putBoolean("reduced_motion", value).apply() }
    fun listen(listener: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.registerOnSharedPreferenceChangeListener(listener)
    fun unlisten(listener: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.unregisterOnSharedPreferenceChangeListener(listener)
}

data class AppearanceState(
    val palette: AppearancePalette = AppearancePalette.NEUTRAL,
    val reducedMotion: Boolean = false,
    val setPalette: (AppearancePalette) -> Unit = {},
    val setReducedMotion: (Boolean) -> Unit = {},
    val dayStackAxis: DayStackAxis = DayStackAxis.HORIZONTAL,
    val setDayStackAxis: (DayStackAxis) -> Unit = {}
)
val LocalAppearance = staticCompositionLocalOf { AppearanceState() }

@Composable
internal fun rememberAppearancePreferences(): AppearanceState {
    val context = LocalContext.current
    val store = remember(context.applicationContext) { AppearancePreferences(context) }
    var palette by remember(store) { mutableStateOf(store.palette()) }
    var reduced by remember(store) { mutableStateOf(store.reducedMotion()) }
    var axis by remember(store) { mutableStateOf(store.dayStackAxis()) }
    DisposableEffect(store) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            palette = store.palette(); reduced = store.reducedMotion(); axis = store.dayStackAxis()
        }
        store.listen(listener)
        onDispose { store.unlisten(listener) }
    }
    return AppearanceState(palette, reduced, { palette = it; store.setPalette(it) }, { reduced = it; store.setReducedMotion(it) }, axis, { axis = it; store.setDayStackAxis(it) })
}

@Composable
fun AppearancePreferencePanel() {
    val state = LocalAppearance.current
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ui_appearance_title), style = MaterialTheme.typography.titleSmall)
            Column(Modifier.selectableGroup()) {
                AppearancePalette.entries.forEach { value ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("appearance_${value.storageKey}")
                        .selectable(selected = state.palette == value, role = Role.RadioButton, onClick = { state.setPalette(value) }),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = state.palette == value, onClick = null)
                        Text(stringResource(if (value == AppearancePalette.NEUTRAL) R.string.ui_palette_neutral else R.string.ui_palette_cream))
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(stringResource(R.string.ui_day_axis_title), style = MaterialTheme.typography.titleSmall)
            Row(Modifier.selectableGroup().fillMaxWidth()) {
                DayStackAxis.entries.forEach { axis ->
                    Row(Modifier.weight(1f).heightIn(min = 48.dp).testTag("day_axis_${axis.storageKey}")
                        .selectable(state.dayStackAxis == axis, role = Role.RadioButton, onClick = { state.setDayStackAxis(axis) }),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = state.dayStackAxis == axis, onClick = null)
                        Text(stringResource(if (axis == DayStackAxis.HORIZONTAL) R.string.ui_day_horizontal else R.string.ui_day_vertical))
                    }
                }
            }
            Text(stringResource(R.string.ui_day_axis_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ui_reduce_motion), Modifier.weight(1f))
                Switch(checked = state.reducedMotion, onCheckedChange = state.setReducedMotion, modifier = Modifier.testTag("appearance_reduced_motion"))
            }
        }
    }
}
