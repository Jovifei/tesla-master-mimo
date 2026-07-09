package com.matelink.data.repository

sealed class ConnectionUrlValidation {
    data class Valid(val normalizedUrl: String) : ConnectionUrlValidation()
    data class Invalid(val message: String) : ConnectionUrlValidation()
}

sealed class ConnectionStepResult {
    data object Success : ConnectionStepResult()
    data class Warning(val message: String, val hint: String? = null) : ConnectionStepResult()
    data class Failure(val message: String, val hint: String? = null) : ConnectionStepResult()
}

data class ConnectionTestOutcome(
    val ping: ConnectionStepResult,
    val readiness: ConnectionStepResult? = null,
    val cars: ConnectionStepResult? = null,
    val carCount: Int = 0,
    val firstCarName: String? = null
) {
    val isSuccessful: Boolean
        get() = ping is ConnectionStepResult.Success &&
                readiness !is ConnectionStepResult.Failure &&
                cars is ConnectionStepResult.Success

    val readinessWarning: String?
        get() = (readiness as? ConnectionStepResult.Warning)?.message

    val summary: String
        get() = when {
            isSuccessful && carCount == 1 && !firstCarName.isNullOrBlank() ->
                "Connected to 1 car: $firstCarName"
            isSuccessful && carCount == 1 ->
                "Connected to 1 car"
            isSuccessful && carCount > 1 ->
                "Connected to $carCount cars"
            ping is ConnectionStepResult.Failure -> ping.message
            cars is ConnectionStepResult.Failure -> cars.message
            readiness is ConnectionStepResult.Failure -> readiness.message
            else -> "Connection test did not complete"
        }

    val failureHint: String?
        get() = listOf(ping, readiness, cars)
            .filterIsInstance<ConnectionStepResult.Failure>()
            .firstOrNull()
            ?.hint
}

fun validateConnectionUrl(input: String): ConnectionUrlValidation {
    val trimmed = input.trim().trimEnd('/')
    if (trimmed.isBlank()) {
        return ConnectionUrlValidation.Invalid("Server URL is required")
    }
    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
        return ConnectionUrlValidation.Invalid("URL must start with http:// or https://")
    }

    val withoutApiPath = trimmed
        .removeSuffix("/api/v1")
        .removeSuffix("/api")
        .trimEnd('/')

    return ConnectionUrlValidation.Valid(withoutApiPath)
}
