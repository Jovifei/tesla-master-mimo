package com.matelink.ui.screens.readiness

import com.matelink.data.api.models.FleetDiagnosticRequest
import com.matelink.data.api.models.FleetTelemetryDiagnostics
import com.matelink.data.api.models.TelemetryPairingStatus
import com.matelink.data.repository.ApiResult

enum class FleetDiagnosticAvailability { AVAILABLE, PARTIAL, UNAVAILABLE, UNKNOWN }
enum class FleetDiagnosticRequestState { OK, ERROR, NOT_ATTEMPTED, UNKNOWN }

data class FleetDiagnosticRead(
    val state: FleetDiagnosticRequestState = FleetDiagnosticRequestState.UNKNOWN,
    val httpStatus: Int? = null
)

/** Only presentation-safe scalar facts; upstream error text and credentials have no path here. */
data class OfficialFleetDiagnosticPresentation(
    val availability: FleetDiagnosticAvailability = FleetDiagnosticAvailability.UNKNOWN,
    val httpStatus: Int? = null,
    val statusRead: FleetDiagnosticRead = FleetDiagnosticRead(),
    val configRead: FleetDiagnosticRead = FleetDiagnosticRead(),
    val errorsRead: FleetDiagnosticRead = FleetDiagnosticRead(),
    val paired: Boolean? = null,
    val streamingEnabled: Boolean? = null,
    val configSynced: Boolean? = null,
    val hostnameMatches: Boolean? = null,
    val portMatches: Boolean? = null,
    val fieldsMatch: Boolean? = null,
    val caMatches: Boolean? = null,
    val recentErrorCount: Long? = null,
    val permissionRequired: Boolean = false
) {
    val serviceTargetMatches: Boolean? get() = when {
        hostnameMatches == false || portMatches == false -> false
        hostnameMatches == true && portMatches == true -> true
        else -> null
    }
}

fun officialFleetDiagnosticsPresentation(
    result: ApiResult<FleetTelemetryDiagnostics>
): OfficialFleetDiagnosticPresentation {
    if (result is ApiResult.Error) return OfficialFleetDiagnosticPresentation(
        availability = FleetDiagnosticAvailability.UNAVAILABLE, httpStatus = result.code)
    val data = (result as ApiResult.Success).data
    val requests = data.requests
    fun read(request: FleetDiagnosticRequest?): FleetDiagnosticRead {
        val code = request?.httpStatus?.takeIf { it in 100..599 }
        val state = when {
            request?.status == "ok" && code != null && code in 200..299 -> FleetDiagnosticRequestState.OK
            request?.status == "error" -> FleetDiagnosticRequestState.ERROR
            request?.status == "not_attempted" -> FleetDiagnosticRequestState.NOT_ATTEMPTED
            else -> FleetDiagnosticRequestState.UNKNOWN
        }
        return FleetDiagnosticRead(state, code)
    }
    val status = read(requests?.fleetStatus)
    val config = read(requests?.fleetTelemetryConfig)
    val errors = read(requests?.fleetTelemetryErrors)
    val allReads = listOf(status, config, errors)
    val knownAvailability = data.availability in setOf("available", "partial", "unavailable")
    val availableBranch = data.availability in setOf("available", "partial")
    val availability = when {
        !knownAvailability -> FleetDiagnosticAvailability.UNKNOWN
        !availableBranch || allReads.none { it.state == FleetDiagnosticRequestState.OK } -> FleetDiagnosticAvailability.UNAVAILABLE
        data.availability == "available" && allReads.all { it.state == FleetDiagnosticRequestState.OK } -> FleetDiagnosticAvailability.AVAILABLE
        else -> FleetDiagnosticAvailability.PARTIAL
    }
    val vehicle = data.vehicle.takeIf { availableBranch && status.state == FleetDiagnosticRequestState.OK }
    val configuration = data.configuration.takeIf { availableBranch && config.state == FleetDiagnosticRequestState.OK }
    val recentErrors = data.recentErrors.takeIf { availableBranch && errors.state == FleetDiagnosticRequestState.OK }
    val categories = setOf("connection", "tls", "configuration", "rate_limited", "authentication", "unknown")
    val counts = recentErrors?.counts?.takeIf { counts ->
        counts.keys.all(categories::contains) && counts.values.all { it >= 0 }
    }
    return OfficialFleetDiagnosticPresentation(
        availability = availability,
        statusRead = status, configRead = config, errorsRead = errors,
        paired = vehicle?.paired, streamingEnabled = vehicle?.streamingEnabled,
        configSynced = configuration?.synced,
        hostnameMatches = configuration?.matchesDesired?.hostname, portMatches = configuration?.matchesDesired?.port,
        fieldsMatch = configuration?.matchesDesired?.fields, caMatches = configuration?.matchesDesired?.ca,
        recentErrorCount = counts?.values?.sumOf { it.toLong() },
        permissionRequired = listOf(requests?.fleetStatus, requests?.fleetTelemetryConfig, requests?.fleetTelemetryErrors)
            .any { it?.status == "error" && it.errorClass == "permission_required" }
    )
}

/** Official setup facts enrich details; they never prove a receipt, history, or delivery. */
fun withOfficialFleetDiagnostics(
    facts: List<DiagnosticFact>, official: OfficialFleetDiagnosticPresentation
): List<DiagnosticFact> = facts.map { fact ->
    if (fact.step in setOf(DiagnosticStep.AUTHORIZATION, DiagnosticStep.VEHICLE, DiagnosticStep.RECEIVE))
        fact.copy(officialFleet = official) else fact
}

/** Reuses the existing manual setup action without invoking pairing discovery. */
fun officialFleetSetupStatus(official: OfficialFleetDiagnosticPresentation): TelemetryPairingStatus? = when {
    official.permissionRequired -> TelemetryPairingStatus("permission_required")
    official.paired == false -> TelemetryPairingStatus("pairing_required", configSynced = official.configSynced)
    official.configSynced == true -> TelemetryPairingStatus("collecting", configSynced = true)
    official.paired == true || official.configSynced == false -> TelemetryPairingStatus("waiting_vehicle", configSynced = official.configSynced)
    else -> null
}
