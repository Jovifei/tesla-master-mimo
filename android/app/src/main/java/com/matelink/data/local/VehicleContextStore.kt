package com.matelink.data.local

import android.content.Context
import android.content.SharedPreferences
import com.matelink.data.api.models.CarData
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists only opaque stable-identity hashes and generated local ids. Raw
 * account ids, server URLs and tokens never become preference values or keys.
 */
@Singleton
class VehicleContextStore internal constructor(private val preferences: SharedPreferences) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        context.getSharedPreferences("vehicle_history_identity", Context.MODE_PRIVATE)
    )

    @Synchronized
    fun getOrAllocate(
        stableIdentity: String,
        remoteApiCarId: Int,
        connectionSource: HistoryConnectionSource,
        serverIdentity: String
    ): VehicleContext {
        val key = identityKey(stableIdentity)
        val localId = preferences.getInt(key, Int.MIN_VALUE).let { existing ->
            if (existing != Int.MIN_VALUE) existing else allocate(key)
        }
        return VehicleContext(
            remoteApiCarId = remoteApiCarId,
            stableIdentity = stableIdentity,
            localHistoryCarId = localId,
            connectionSource = connectionSource,
            serverIdentity = serverIdentity
        )
    }

    @Synchronized
    fun findLocalHistoryCarId(stableIdentity: String): Int? = preferences.getInt(
        identityKey(stableIdentity),
        Int.MIN_VALUE
    ).takeUnless { it == Int.MIN_VALUE }

    @Synchronized
    fun rememberCloudRemoteMapping(accountNamespace: String, remoteApiCarId: Int, localHistoryCarId: Int) {
        val account = accountNamespace.trim()
        if (account.isEmpty() || remoteApiCarId < 0 || localHistoryCarId >= 0) return
        check(preferences.edit().putInt(cloudRemoteKey(account, remoteApiCarId), localHistoryCarId).commit()) {
            "unable to persist cloud vehicle history mapping"
        }
    }

    @Synchronized
    fun findCloudLocalHistoryCarId(accountNamespace: String, remoteApiCarId: Int): Int? {
        val account = accountNamespace.trim()
        if (account.isEmpty() || remoteApiCarId < 0) return null
        return preferences.getInt(cloudRemoteKey(account, remoteApiCarId), Int.MIN_VALUE)
            .takeUnless { it == Int.MIN_VALUE }
    }

    fun cloudRemoteOpaqueIdentity(accountNamespace: String, remoteApiCarId: Int): String =
        "cloud-cache:${sha256Hex("${accountNamespace.trim()}:$remoteApiCarId")}"

    /** Only written after an authenticated origin-specific vehicle identity has been resolved. */
    @Synchronized
    fun rememberOriginCloudRemoteMapping(
        accountNamespace: String, apiOrigin: String, remoteApiCarId: Int,
        localHistoryCarId: Int, verifiedStableIdentity: String
    ) {
        val account = accountNamespace.trim()
        if (account.isEmpty() || remoteApiCarId < 0 || localHistoryCarId >= 0) return
        val origin = requireSelfHostedServerIdentity(apiOrigin)
        val expectedPrefix = "cloud-origin:${sha256Hex("origin:$origin\u0000account:$account")}:vehicle:"
        check(verifiedStableIdentity.startsWith(expectedPrefix)) { "cloud vehicle identity does not match its account and origin" }
        val remoteKey = originCloudRemoteKey(account, origin, remoteApiCarId)
        val digest = sha256Hex(verifiedStableIdentity)
        check(preferences.edit().putInt(remoteKey, localHistoryCarId)
            .putString("$remoteKey:verified-identity", digest).commit()) {
            "unable to persist origin-scoped history mapping"
        }
    }

    @Synchronized
    fun findOriginCloudLocalHistoryCarId(accountNamespace: String, apiOrigin: String, remoteApiCarId: Int): Int? {
        val account = accountNamespace.trim()
        if (account.isEmpty() || remoteApiCarId < 0) return null
        val origin = requireSelfHostedServerIdentity(apiOrigin)
        return preferences.getInt(originCloudRemoteKey(account, origin, remoteApiCarId), Int.MIN_VALUE)
            .takeUnless { it == Int.MIN_VALUE }
    }

    /** A digest of the last UID-verified binding, never an unverified numeric-ID guess. */
    @Synchronized
    fun findVerifiedOriginCloudIdentityDigest(
        accountNamespace: String, apiOrigin: String, remoteApiCarId: Int, expectedLocalId: Int
    ): String? {
        val account = accountNamespace.trim()
        if (account.isEmpty() || remoteApiCarId <= 0 || expectedLocalId >= 0) return null
        val origin = requireSelfHostedServerIdentity(apiOrigin)
        val remoteKey = originCloudRemoteKey(account, origin, remoteApiCarId)
        if (preferences.getInt(remoteKey, Int.MIN_VALUE) != expectedLocalId) return null
        val digest = preferences.getString("$remoteKey:verified-identity", null)
            ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: return null
        return digest.takeIf { preferences.getInt("identity:$it", Int.MIN_VALUE) == expectedLocalId }
    }

    fun originCloudRemoteOpaqueIdentity(accountNamespace: String, apiOrigin: String, remoteApiCarId: Int): String =
        "cloud-origin-cache:${sha256Hex("${requireSelfHostedServerIdentity(apiOrigin)}\u0000${accountNamespace.trim()}\u0000$remoteApiCarId")}"

    private fun originCloudRemoteKey(account: String, origin: String, id: Int): String =
        "remote-origin:${sha256Hex("$origin\u0000$account\u0000$id")}"

    private fun allocate(identityKey: String): Int {
        val next = preferences.getInt(NEXT_ID_KEY, -1)
        require(next in (Int.MIN_VALUE + 1)..-1) { "local history id allocator exhausted" }
        val committed = preferences.edit()
            .putInt(identityKey, next)
            .putInt(NEXT_ID_KEY, next - 1)
            .commit()
        check(committed) { "unable to durably allocate local history id" }
        return next
    }

    fun resolveCar(
        car: CarData,
        accountNamespace: String?,
        connectionSource: HistoryConnectionSource,
        serverIdentity: String
    ): VehicleContext {
        val stableIdentity = when (connectionSource) {
            HistoryConnectionSource.CLOUD -> cloudVehicleStableIdentity(
                accountNamespace.orEmpty(),
                car.vehicleUid.orEmpty()
            )
            HistoryConnectionSource.SELF_HOSTED -> selfHostedVehicleStableIdentity(serverIdentity, car.carId)
        }
        val context = getOrAllocate(stableIdentity, car.carId, connectionSource, serverIdentity)
        if (connectionSource == HistoryConnectionSource.CLOUD && !accountNamespace.isNullOrBlank()) {
            rememberCloudRemoteMapping(accountNamespace, car.carId, context.localHistoryCarId)
        }
        return context
    }

    /** Dedicated verified-origin namespace for the unified history read path only. */
    fun resolveVerifiedHistoryCar(
        car: CarData,
        accountNamespace: String?,
        connectionSource: HistoryConnectionSource,
        serverIdentity: String,
        effectiveApiOrigin: String
    ): VehicleContext {
        val stableIdentity = when (connectionSource) {
            HistoryConnectionSource.CLOUD -> cloudOriginVehicleStableIdentity(
                accountNamespace.orEmpty(), effectiveApiOrigin, car.vehicleUid.orEmpty()
            )
            HistoryConnectionSource.SELF_HOSTED -> selfHostedVehicleStableIdentity(serverIdentity, car.carId)
        }
        val context = getOrAllocate(stableIdentity, car.carId, connectionSource, serverIdentity)
        if (connectionSource == HistoryConnectionSource.CLOUD && !accountNamespace.isNullOrBlank()) {
            rememberOriginCloudRemoteMapping(accountNamespace, effectiveApiOrigin, car.carId,
                context.localHistoryCarId, stableIdentity)
        }
        return context
    }

    private fun identityKey(stableIdentity: String): String = "identity:${sha256Hex(stableIdentity)}"

    private fun cloudRemoteKey(accountNamespace: String, remoteApiCarId: Int): String =
        "remote:${sha256Hex("cloud:${accountNamespace.trim()}:car:$remoteApiCarId")}"

    private companion object {
        const val NEXT_ID_KEY = "next_local_history_id"

        fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
