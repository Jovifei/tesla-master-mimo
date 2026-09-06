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
class VehicleContextStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val preferences: SharedPreferences = context.getSharedPreferences(
        "vehicle_history_identity",
        Context.MODE_PRIVATE
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
