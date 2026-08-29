package com.matelink.debug

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.matelink.data.api.models.Units
import com.matelink.data.local.TirePosition
import com.matelink.ui.components.TelemetryMetricSpec
import com.matelink.ui.components.TelemetryMetricStrip
import com.matelink.ui.components.TelemetryPanel
import com.matelink.ui.components.TelemetrySectionHeader
import com.matelink.ui.components.VehicleHeroGraphic
import com.matelink.ui.screens.charges.CurrentChargeParametersCard
import com.matelink.ui.screens.dashboard.PowerDirection
import com.matelink.ui.screens.dashboard.ShiftState
import com.matelink.ui.screens.dashboard.VehicleOpeningAlert
import com.matelink.ui.screens.dashboard.drivingTelemetryFor
import com.matelink.ui.screens.dashboard.formatPressure
import com.matelink.ui.screens.dashboard.openVehicleOpenings
import com.matelink.ui.screens.dashboard.powerDirection
import com.matelink.ui.screens.dashboard.warningTires
import com.matelink.ui.theme.MateLinkTheme
import java.util.Locale

private data class StateReviewCopy(
    val title: String,
    val debugOnly: String,
    val display: String,
    val language: String,
    val theme: String,
    val fontScale: String,
    val scenarios: String,
    val speed: String,
    val regenerating: String,
    val consuming: String,
    val steady: String,
    val power: String,
    val gear: String,
    val tpmsWarning: String,
    val warning: String,
    val light: String,
    val dark: String,
    val normal: String,
    val large: String
)

private fun reviewCopy(chinese: Boolean) = if (chinese) {
    StateReviewCopy(
        title = "MateLink 状态验证",
        debugOnly = "仅 DEBUG · 独立测试包",
        display = "显示验证",
        language = "语言 / Language",
        theme = "主题 / Theme",
        fontScale = "字号 / Font scale",
        scenarios = "状态场景",
        speed = "速度",
        regenerating = "回收中",
        consuming = "耗电中",
        steady = "稳定",
        power = "功率",
        gear = "挡位",
        tpmsWarning = "胎压警告",
        warning = "警告",
        light = "浅色",
        dark = "深色",
        normal = "100%",
        large = "200%"
    )
} else {
    StateReviewCopy(
        title = "MateLink state verification",
        debugOnly = "DEBUG ONLY · independent test package",
        display = "Display checks",
        language = "Language / 语言",
        theme = "Theme / 主题",
        fontScale = "Font scale / 字号",
        scenarios = "State scenarios",
        speed = "Speed",
        regenerating = "Regenerating",
        consuming = "Consuming",
        steady = "Steady",
        power = "Power",
        gear = "Gear",
        tpmsWarning = "TPMS warning",
        warning = "WARNING",
        light = "Light",
        dark = "Dark",
        normal = "100%",
        large = "200%"
    )
}

private fun scenarioTitle(id: String, chinese: Boolean): String = when (id) {
    "DRIVING_REGEN" -> if (chinese) "驾驶 · 能量回收" else "Driving · regenerative braking"
    "OPENING_TPMS" -> if (chinese) "车门车窗开启与胎压警告" else "Openings and TPMS warning"
    "AC_CHARGING" -> if (chinese) "单相交流充电" else "Single-phase AC charging"
    "DC_CHARGING" -> if (chinese) "直流充电" else "DC charging"
    "MISSING_FIELDS" -> if (chinese) "观测字段不可用" else "Observed fields unavailable"
    else -> id
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun StateScenarioReviewScreen(onLanguageSelected: (String) -> Unit = {}) {
    val systemLanguageCode = LocalConfiguration.current.locales[0]?.language
        ?.takeIf { it in StateScenarioFixtures.supportedLanguageCodes() }
        ?: StateScenarioLanguage.ENGLISH.code
    var selectedId by rememberSaveable { mutableStateOf(StateScenarioReviewState().selectedScenarioId) }
    var languageCode by rememberSaveable { mutableStateOf(systemLanguageCode) }
    var darkTheme by rememberSaveable { mutableStateOf(StateScenarioReviewState().darkTheme) }
    var fontScaleValue by rememberSaveable { mutableStateOf(StateScenarioReviewState().fontScale) }
    val matrixState = StateScenarioReviewState(selectedId, languageCode, darkTheme, fontScaleValue)
    val language = StateScenarioLanguage.values().firstOrNull { it.code == matrixState.languageCode }
        ?: StateScenarioLanguage.ENGLISH
    val theme = StateScenarioTheme.values().first { it.dark == matrixState.darkTheme }
    val fontScale = StateScenarioFontScale.values().first { it.factor == matrixState.fontScale }
    val copy = reviewCopy(language == StateScenarioLanguage.CHINESE)
    val scenario = StateScenarioFixtures.scenario(matrixState.selectedScenarioId)

    fun applyState(next: StateScenarioReviewState) {
        selectedId = next.selectedScenarioId
        languageCode = next.languageCode
        darkTheme = next.darkTheme
        fontScaleValue = next.fontScale
    }

    MateLinkTheme(darkTheme = theme.dark) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.factor)) {
            Scaffold(
                topBar = { TopAppBar(title = { Text(copy.title) }) }
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                        .semantics { testTagsAsResourceId = true },
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = copy.debugOnly,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    MatrixControls(
                        language = language,
                        theme = theme,
                        fontScale = fontScale,
                        copy = copy,
                        onLanguageSelected = { code ->
                            val next = StateScenarioReviewController.selectLanguage(matrixState, code)
                            applyState(next)
                            onLanguageSelected(next.languageCode)
                        },
                        onThemeSelected = { option ->
                            applyState(StateScenarioReviewController.selectTheme(matrixState, option.dark))
                        },
                        onFontScaleSelected = { option ->
                            applyState(StateScenarioReviewController.selectFontScale(matrixState, option.factor))
                        }
                    )
                    ScenarioSelector(
                        selectedId = matrixState.selectedScenarioId,
                        chinese = language == StateScenarioLanguage.CHINESE,
                        copy = copy,
                        onScenarioSelected = { id ->
                            applyState(StateScenarioReviewController.selectScenario(matrixState, id))
                        }
                    )
                    Text(
                        text = "${scenario.id} · ${scenarioTitle(scenario.id, language == StateScenarioLanguage.CHINESE)}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    ScenarioVehiclePanel(scenario, copy)
                    ScenarioChargePanel(scenario)
                }
            }
        }
    }
}

@Composable
private fun MatrixControls(
    language: StateScenarioLanguage,
    theme: StateScenarioTheme,
    fontScale: StateScenarioFontScale,
    copy: StateReviewCopy,
    onLanguageSelected: (String) -> Unit,
    onThemeSelected: (StateScenarioTheme) -> Unit,
    onFontScaleSelected: (StateScenarioFontScale) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(copy.display, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        MatrixOptionRow(copy.language) {
            StateScenarioLanguage.values().forEach { option ->
                FilterChip(
                    modifier = Modifier.testTag(StateScenarioReviewTags.language(option.code)),
                    selected = option == language,
                    onClick = { onLanguageSelected(option.code) },
                    label = { Text(option.label) }
                )
            }
        }
        MatrixOptionRow(copy.theme) {
            StateScenarioTheme.values().forEach { option ->
                FilterChip(
                    modifier = Modifier.testTag(
                        StateScenarioReviewTags.theme(option.name.lowercase(Locale.ROOT))
                    ),
                    selected = option == theme,
                    onClick = { onThemeSelected(option) },
                    label = { Text(if (option == StateScenarioTheme.LIGHT) copy.light else copy.dark) }
                )
            }
        }
        MatrixOptionRow(copy.fontScale) {
            StateScenarioFontScale.values().forEach { option ->
                FilterChip(
                    modifier = Modifier.testTag(
                        StateScenarioReviewTags.fontScale((option.factor * 100).toInt())
                    ),
                    selected = option == fontScale,
                    onClick = { onFontScaleSelected(option) },
                    label = { Text(if (option == StateScenarioFontScale.NORMAL) copy.normal else copy.large) }
                )
            }
        }
    }
}

@Composable
private fun MatrixOptionRow(label: String, options: @Composable RowScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = options
        )
    }
}

@Composable
private fun ScenarioSelector(
    selectedId: String,
    chinese: Boolean,
    copy: StateReviewCopy,
    onScenarioSelected: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(copy.scenarios, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        StateScenarioFixtures.scenarioSelectors().forEach { selector ->
            val title = if (chinese) scenarioTitle(selector.id, true) else selector.title
            FilterChip(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(StateScenarioReviewTags.scenario(selector.id)),
                selected = selector.id == selectedId,
                onClick = { onScenarioSelected(selector.id) },
                label = { Text("${selector.id} · $title") }
            )
        }
    }
}

@Composable
private fun ScenarioVehiclePanel(scenario: StateScenario, copy: StateReviewCopy) {
    val status = scenario.status
    val driving = drivingTelemetryFor(status)
    val openings = openVehicleOpenings(status)
    val warningTires = warningTires(status.tpmsDetails)
    TelemetryPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            VehicleHeroGraphic(
                model = scenario.model,
                exteriorColor = scenario.exteriorColor,
                wheelType = scenario.wheelType,
                trimBadging = scenario.trimBadging
            )
            driving?.let { telemetry ->
                val metrics = buildList {
                    telemetry.speed?.let {
                        add(TelemetryMetricSpec(Icons.Default.Speed, copy.speed, "%.0f km/h".format(Locale.ROOT, it), MaterialTheme.colorScheme.primary))
                    }
                    telemetry.power?.let {
                        val label = when (powerDirection(it)) {
                            PowerDirection.REGENERATING -> copy.regenerating
                            PowerDirection.CONSUMING -> copy.consuming
                            PowerDirection.STEADY -> copy.steady
                            null -> copy.power
                        }
                        add(TelemetryMetricSpec(Icons.Default.Bolt, label, "%.0f W".format(Locale.ROOT, kotlin.math.abs(it)), MaterialTheme.colorScheme.primary))
                    }
                    telemetry.shiftState?.let {
                        val gear = when (it) {
                            ShiftState.DRIVE -> "D"
                            ShiftState.REVERSE -> "R"
                            ShiftState.NEUTRAL -> "N"
                            ShiftState.PARK -> "P"
                        }
                        add(TelemetryMetricSpec(Icons.Default.Speed, copy.gear, gear, MaterialTheme.colorScheme.primary))
                    }
                }
                if (metrics.isNotEmpty()) TelemetryMetricStrip(metrics)
            }
            if (openings.isNotEmpty()) VehicleOpeningAlert(openings)
            if (warningTires.isNotEmpty()) ScenarioTpmsPanel(scenario, warningTires, copy)
        }
    }
}

@Composable
private fun ScenarioTpmsPanel(scenario: StateScenario, warningTires: Set<TirePosition>, copy: StateReviewCopy) {
    val tires = listOf(
        TirePosition.FL to scenario.status.tpmsDetails?.pressureFl,
        TirePosition.FR to scenario.status.tpmsDetails?.pressureFr,
        TirePosition.RL to scenario.status.tpmsDetails?.pressureRl,
        TirePosition.RR to scenario.status.tpmsDetails?.pressureRr
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TelemetrySectionHeader(Icons.Default.Warning, copy.tpmsWarning, accent = Color(0xFFFF9800))
        tires.forEach { (position, pressure) ->
            pressure?.let {
                Text(
                    text = "$position ${formatPressure(it, Units())}" + if (position in warningTires) "  ${copy.warning}" else "",
                    color = if (position in warningTires) Color(0xFFFF9800) else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun ScenarioChargePanel(scenario: StateScenario) {
    val charge = scenario.status.chargingDetails ?: return
    CurrentChargeParametersCard(
        chargePortDoorOpen = charge.chargePortDoorOpen,
        chargerPhases = charge.chargerPhases,
        chargerVoltage = charge.chargerVoltage,
        chargerActualCurrent = charge.chargerActualCurrent,
        chargeCurrentRequest = charge.chargeCurrentRequest,
        chargeCurrentRequestMax = charge.chargeCurrentRequestMax,
        scheduledChargingStartTime = charge.scheduledChargingStartTime
    )
}
