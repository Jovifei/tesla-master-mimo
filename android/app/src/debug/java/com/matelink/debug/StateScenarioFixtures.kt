package com.matelink.debug

import com.matelink.data.api.models.BatteryDetails
import com.matelink.data.api.models.CarStatus
import com.matelink.data.api.models.CarStatusDetails
import com.matelink.data.api.models.ChargingDetails
import com.matelink.data.api.models.DrivingDetails
import com.matelink.data.api.models.TpmsDetails
import java.util.Locale

data class StateScenario(
    val id: String,
    val title: String,
    val status: CarStatus,
    val model: String = "Model Y",
    val exteriorColor: String = "PPMR",
    val wheelType: String = "Gemini",
    val trimBadging: String = "Long Range"
)

data class StateScenarioSelector(
    val id: String,
    val title: String
)

enum class StateScenarioLanguage(val code: String, val label: String) {
    CHINESE("zh", "中文"),
    ENGLISH("en", "English")
}

enum class StateScenarioTheme(val dark: Boolean, val label: String) {
    LIGHT(false, "Light"),
    DARK(true, "Dark")
}

enum class StateScenarioFontScale(val factor: Float, val label: String) {
    NORMAL(1.0f, "100%"),
    LARGE(2.0f, "200%")
}

data class StateScenarioReviewState(
    val selectedScenarioId: String = "DRIVING_REGEN",
    val languageCode: String = StateScenarioLanguage.ENGLISH.code,
    val darkTheme: Boolean = false,
    val fontScale: Float = StateScenarioFontScale.NORMAL.factor
)

object StateScenarioReviewController {
    @JvmStatic
    fun selectLanguage(state: StateScenarioReviewState, languageCode: String): StateScenarioReviewState =
        state.takeIf { languageCode in StateScenarioFixtures.supportedLanguageCodes() }
            ?.copy(languageCode = languageCode)
            ?: state

    @JvmStatic
    fun selectTheme(state: StateScenarioReviewState, darkTheme: Boolean): StateScenarioReviewState =
        state.copy(darkTheme = darkTheme)

    @JvmStatic
    fun selectFontScale(state: StateScenarioReviewState, fontScale: Float): StateScenarioReviewState =
        state.takeIf { fontScale in StateScenarioFontScale.values().map { option -> option.factor } }
            ?.copy(fontScale = fontScale)
            ?: state

    @JvmStatic
    fun selectScenario(state: StateScenarioReviewState, scenarioId: String): StateScenarioReviewState =
        state.takeIf { scenarioId in StateScenarioFixtures.scenarioIds() }
            ?.copy(selectedScenarioId = scenarioId)
            ?: state
}

object StateScenarioReviewTags {
    @JvmStatic
    fun language(code: String): String = "matrix_language_$code"

    @JvmStatic
    fun theme(mode: String): String = "matrix_theme_$mode"

    @JvmStatic
    fun fontScale(percent: Int): String = "matrix_font_scale_$percent"

    @JvmStatic
    fun scenario(id: String): String = "scenario_$id"
}

object StateScenarioFixtures {
    private val scenarios = listOf(
        StateScenario(
            id = "DRIVING_REGEN",
            title = "Driving · regenerative braking",
            status = CarStatus(
                state = "driving",
                batteryDetails = BatteryDetails(batteryLevel = 71, ratedBatteryRange = 365.0),
                drivingDetails = DrivingDetails(speed = 68.4, power = -8400.0, shiftState = "D")
            )
        ),
        StateScenario(
            id = "OPENING_TPMS",
            title = "Openings and TPMS warning",
            status = CarStatus(
                state = "online",
                carStatus = CarStatusDetails(doorsOpen = true, windowsOpen = true, frunkOpen = true),
                tpmsDetails = TpmsDetails(
                    pressureFl = 2.1,
                    pressureFr = 2.9,
                    pressureRl = 2.8,
                    pressureRr = 2.0,
                    warningFl = true,
                    warningRr = true
                )
            )
        ),
        StateScenario(
            id = "AC_CHARGING",
            title = "Single-phase AC charging",
            status = CarStatus(
                state = "charging",
                chargingDetails = ChargingDetails(
                    pluggedIn = true,
                    chargingState = "charging",
                    chargePortDoorOpen = true,
                    chargerPhases = 1,
                    chargerVoltage = 231.5,
                    chargerActualCurrent = 15.7,
                    chargeCurrentRequest = 16.0,
                    chargeCurrentRequestMax = 16.0,
                    scheduledChargingStartTime = "2026-08-28T01:30:00Z"
                )
            )
        ),
        StateScenario(
            id = "DC_CHARGING",
            title = "DC charging",
            status = CarStatus(
                state = "charging",
                chargingDetails = ChargingDetails(
                    pluggedIn = true,
                    chargingState = "charging",
                    chargePortDoorOpen = true,
                    chargerPhases = 0,
                    chargerVoltage = 401.2,
                    chargerActualCurrent = 186.4,
                    chargeCurrentRequest = 190.0,
                    chargeCurrentRequestMax = 250.0
                )
            )
        ),
        StateScenario(
            id = "MISSING_FIELDS",
            title = "Observed fields unavailable",
            status = CarStatus(state = "online")
        )
    )

    @JvmStatic
    fun scenarioIds(): Set<String> = scenarios.mapTo(linkedSetOf()) { it.id }

    @JvmStatic
    fun supportedLanguageCodes(): List<String> = StateScenarioLanguage.values().map { it.code }

    @JvmStatic
    fun supportedThemeModes(): List<String> = StateScenarioTheme.values().map { it.name.lowercase(Locale.ROOT) }

    @JvmStatic
    fun supportedFontScales(): List<String> = StateScenarioFontScale.values().map { it.factor.toString() }

    @JvmStatic
    fun scenarioSelectors(): List<StateScenarioSelector> = scenarios.map {
        StateScenarioSelector(id = it.id, title = it.title)
    }

    fun scenario(id: String): StateScenario = scenarios.first { it.id == id }
}
