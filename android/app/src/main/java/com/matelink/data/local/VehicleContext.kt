package com.matelink.data.local

import java.security.MessageDigest

/** Identifies where a vehicle's remote data comes from. */
enum class HistoryConnectionSource {
    CLOUD,
    SELF_HOSTED
}

class HistoryIdentityUnavailableException : IllegalStateException(
    "Vehicle history identity is unavailable"
)

interface HistoryCarIdResolver {
    suspend fun requireLocalHistoryCarId(remoteApiCarId: Int): Int
}

object LegacyHistoryCarIdResolver : HistoryCarIdResolver {
    override suspend fun requireLocalHistoryCarId(remoteApiCarId: Int): Int = remoteApiCarId
}

/**
 * Separates the provider's car id from the local Room history namespace.
 * [localHistoryCarId] is intentionally allocated independently of a positive
 * remote id so a newly seen vehicle cannot inherit an old archive implicitly.
 */
data class VehicleContext(
    val remoteApiCarId: Int,
    val stableIdentity: String,
    val localHistoryCarId: Int,
    val connectionSource: HistoryConnectionSource,
    val serverIdentity: String
)

fun cloudVehicleStableIdentity(accountNamespace: String, vehicleUid: String): String {
    val account = accountNamespace.trim()
    val uid = vehicleUid.trim()
    require(account.isNotEmpty()) { "cloud account namespace is required" }
    require(uid.isNotEmpty()) { "cloud vehicle uid is required" }
    return "cloud:${sha256Hex("account:$account")}:vehicle:$uid"
}

fun selfHostedVehicleStableIdentity(serverUrl: String, remoteApiCarId: Int): String {
    require(remoteApiCarId >= 0) { "remote car id must be non-negative" }
    return "self-hosted:${requireSelfHostedServerIdentity(serverUrl)}:car:$remoteApiCarId"
}

/** A local history namespace must never be derived from an absent server. */
fun requireSelfHostedServerIdentity(serverUrl: String): String {
    val normalized = normalizeSelfHostedServerIdentity(serverUrl)
    if (normalized.isBlank() || normalized == "://") {
        throw HistoryIdentityUnavailableException()
    }
    return normalized
}

private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
