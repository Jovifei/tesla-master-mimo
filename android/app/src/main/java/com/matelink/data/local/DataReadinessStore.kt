package com.matelink.data.local

import android.content.Context
import android.content.SharedPreferences
import com.matelink.data.api.models.DataReadiness
import java.net.URI
import java.security.MessageDigest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

fun dataReadinessIdentityNamespace(userId: String?, carId: Int): String =
    sha256Hex(userId?.trim()?.takeIf { it.isNotEmpty() }?.let { "account:$it" } ?: "self-hosted-car:$carId")

fun normalizeSelfHostedServerIdentity(serverUrl: String): String {
    val trimmed = serverUrl.trim()
    return runCatching {
        val uri = URI(trimmed)
        buildString {
            append(uri.scheme?.lowercase() ?: "")
            append("://")
            append(uri.host?.lowercase() ?: uri.rawAuthority?.lowercase() ?: "")
            if (uri.port != -1) append(":${uri.port}")
            uri.path?.trimEnd('/')?.takeIf { it.isNotEmpty() }?.let { append(it) }
        }
    }.getOrElse { trimmed.trimEnd('/').lowercase() }
}

fun dataReadinessSeenKey(
    accountNamespace: String?,
    vehicleUid: String?,
    carId: Int,
    capabilityVersion: Int?,
    connectionMode: ConnectionMode,
    selfHostedServerIdentity: String?
): String {
    val vehicleIdentity = vehicleUid?.trim()?.takeIf { it.isNotEmpty() } ?: "car:$carId"
    val serverIdentity = if (connectionMode == ConnectionMode.SELF_HOSTED) {
        normalizeSelfHostedServerIdentity(selfHostedServerIdentity.orEmpty())
    } else {
        "cloud"
    }
    val material = listOf(
        "schema=2",
        "account=${accountNamespace?.trim().orEmpty()}",
        "vehicle=$vehicleIdentity",
        "car=$carId",
        "capability=${capabilityVersion ?: "unknown"}",
        "mode=${connectionMode.name}",
        "server=$serverIdentity"
    ).joinToString("\u0000")
    return "seen:v2:${sha256Hex(material)}"
}

private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

@Singleton
class DataReadinessStore @Inject constructor(
    @ApplicationContext context: Context,
    private val sessionStore: JourVoltSessionStore,
    private val connectionModeStore: ConnectionModeStore,
    private val settingsDataStore: SettingsDataStore
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("data_readiness_seen", Context.MODE_PRIVATE)

    suspend fun hasSeen(readiness: DataReadiness, carId: Int): Boolean =
        prefs.getBoolean(key(readiness, carId), false)

    suspend fun markSeen(readiness: DataReadiness, carId: Int) {
        prefs.edit().putBoolean(key(readiness, carId), true).apply()
    }

    private suspend fun key(readiness: DataReadiness, carId: Int): String {
        val settings = settingsDataStore.settings.first()
        val mode = connectionModeStore.mode.first() ?: ConnectionMode.SELF_HOSTED
        return dataReadinessSeenKey(
            accountNamespace = sessionStore.current()?.userId,
            vehicleUid = readiness.vehicleUid,
            carId = carId,
            capabilityVersion = readiness.capabilityVersion,
            connectionMode = mode,
            selfHostedServerIdentity = settings.serverUrl
        )
    }
}
