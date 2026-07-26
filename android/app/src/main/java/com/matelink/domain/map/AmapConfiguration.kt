package com.matelink.domain.map

/** Pure, secret-safe configuration rules for the user-provided Android SDK key. */
object AmapConfiguration {
    fun sanitizeKey(raw: String): String? {
        val key = raw.trim()
        return key.takeIf { it.isNotEmpty() && key.none { it.isISOControl() || it == '\n' || it == '\r' } }
    }

    fun isUsableCoordinate(latitude: Double?, longitude: Double?): Boolean =
        latitude != null && longitude != null &&
            latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
            !(latitude == 0.0 && longitude == 0.0)

    fun prepareKeySave(rawKey: String, previousKey: String, sdkWasInitialized: Boolean): AmapKeyMutation? {
        val key = sanitizeKey(rawKey) ?: return null
        return AmapKeyMutation(key = key, restartRequired = key != previousKey && sdkWasInitialized)
    }

    fun clearKeyMutation(): AmapKeyMutation = AmapKeyMutation(key = "", restartRequired = false)
}

data class AmapKeyMutation(val key: String, val restartRequired: Boolean)

enum class AmapSetupState {
    UNCONFIGURED, PRIVACY_NOT_AGREED, READY_TO_PREVIEW, RESTART_REQUIRED, LOADING, LOADED, FAILED
}

fun amapSetupState(hasKey: Boolean, privacyAgreed: Boolean, restartRequired: Boolean = false): AmapSetupState = when {
    !hasKey -> AmapSetupState.UNCONFIGURED
    !privacyAgreed -> AmapSetupState.PRIVACY_NOT_AGREED
    restartRequired -> AmapSetupState.RESTART_REQUIRED
    else -> AmapSetupState.READY_TO_PREVIEW
}
