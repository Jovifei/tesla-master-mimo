package com.matelink.data.repository

import com.matelink.data.api.UrlSecurity

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
    val isSuccessful get() = ping is ConnectionStepResult.Success && readiness !is ConnectionStepResult.Failure && cars is ConnectionStepResult.Success
    val readinessWarning get() = (readiness as? ConnectionStepResult.Warning)?.message
    val summary get() = when {
        isSuccessful && carCount == 1 && !firstCarName.isNullOrBlank() -> "Connected to 1 car: $firstCarName"
        isSuccessful && carCount == 1 -> "Connected to 1 car"
        isSuccessful && carCount > 1 -> "Connected to $carCount cars"
        ping is ConnectionStepResult.Failure -> ping.message
        cars is ConnectionStepResult.Failure -> cars.message
        readiness is ConnectionStepResult.Failure -> readiness.message
        else -> "Connection test did not complete"
    }
    val failureHint get() = listOf(ping, readiness, cars).filterIsInstance<ConnectionStepResult.Failure>().firstOrNull()?.hint
}

fun validateConnectionUrl(input: String): ConnectionUrlValidation = when (val result = UrlSecurity.normalizeAndValidate(input)) {
    is UrlSecurity.Validation.Valid -> ConnectionUrlValidation.Valid(result.normalizedUrl)
    is UrlSecurity.Validation.Invalid -> ConnectionUrlValidation.Invalid(result.message)
}
