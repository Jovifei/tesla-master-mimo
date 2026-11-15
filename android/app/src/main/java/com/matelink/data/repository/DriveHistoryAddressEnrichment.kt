package com.matelink.data.repository

import com.matelink.data.api.models.DriveData
import com.matelink.data.local.VehicleContext
import com.matelink.data.local.entity.DriveSummary
import com.matelink.domain.analytics.toRawAnalysisDriveData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Semaphore
import kotlin.coroutines.resume

internal data class DriveAddressKey(
    val identity: String, val receiptDigest: String, val start: Boolean, val latitude: Double, val longitude: Double
)
internal data class DriveAddressTarget(val drive: DriveData, val key: DriveAddressKey)

private const val LOOKUPS_PER_BATCH = 10
private const val LOOKUPS_PER_SNAPSHOT = 40
private const val BETWEEN_BATCHES_MS = 100L
private object CompletedAddressAttempt

// Permits remain held until the underlying blocking call actually returns.
private val driveAddressPermits = Semaphore(2)
private val driveAddressPermitLock = Any()
private val driveAddressReleases = MutableStateFlow(0L)
private val driveAddressScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private sealed interface DriveAddressLookup {
    data class Complete(val address: String?) : DriveAddressLookup
    data class Busy(val releaseVersion: Long) : DriveAddressLookup
}

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun boundedDriveAddressLookup(
    latitude: Double, longitude: Double, reverseGeocode: suspend (Double, Double) -> String?
): DriveAddressLookup = suspendCancellableCoroutine { continuation ->
    val busyVersion = synchronized(driveAddressPermitLock) {
        if (driveAddressPermits.tryAcquire()) null else driveAddressReleases.value
    }
    if (busyVersion != null) {
        if (continuation.isActive) continuation.resume(DriveAddressLookup.Busy(busyVersion))
    } else {
        val work = driveAddressScope.launch(start = CoroutineStart.ATOMIC) {
            var result: DriveAddressLookup? = null
            var cancelled: CancellationException? = null
            try {
                currentCoroutineContext().ensureActive()
                result = DriveAddressLookup.Complete(reverseGeocode(latitude, longitude))
            } catch (e: CancellationException) {
                cancelled = e
            } catch (_: Exception) {
                result = DriveAddressLookup.Complete(null)
            } finally {
                synchronized(driveAddressPermitLock) {
                    driveAddressPermits.release()
                    driveAddressReleases.value += 1L
                }
            }
            if (continuation.isActive) {
                if (cancelled != null) continuation.cancel(cancelled) else continuation.resume(requireNotNull(result))
            }
        }
        continuation.invokeOnCancellation { work.cancel() }
    }
}

internal fun resetCompletedDriveAddressAttempts(attempted: MutableMap<DriveAddressKey, Any>) {
    synchronized(attempted) { attempted.entries.removeAll { it.value === CompletedAddressAttempt } }
}

private fun releaseDriveAddressAttempt(
    attempted: MutableMap<DriveAddressKey, Any>, targets: List<DriveAddressTarget>, token: Any
) {
    synchronized(attempted) { targets.forEach { if (attempted[it.key] === token) attempted.remove(it.key) } }
}

private fun validDriveCoordinates(latitude: Double?, longitude: Double?): Boolean =
    latitude != null && longitude != null && latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0 && (latitude != 0.0 || longitude != 0.0)

internal fun driveAddressTargets(drives: List<DriveData>, rows: List<DriveSummary>, context: VehicleContext): List<DriveAddressTarget> {
    val byId = rows.associateBy { it.driveId }
    val targets = mutableListOf<DriveAddressTarget>()
    drives.forEach { drive ->
        val row = byId[drive.driveId] ?: return@forEach
        if (!drive.matchesHistoryIdentity(row.toRawAnalysisDriveData())) return@forEach
        val identity = row.verifiedDetailIdentity(context)?.identityKey ?: return@forEach
        val digest = driveSourceReceiptDigest(row) ?: return@forEach
        fun add(start: Boolean, latitude: Double?, longitude: Double?) {
            if (validDriveCoordinates(latitude, longitude))
                targets += DriveAddressTarget(drive, DriveAddressKey(identity, digest, start, latitude!!, longitude!!))
        }
        if (drive.startAddress.isNullOrBlank()) add(true, drive.startLatitude, drive.startLongitude)
        if (drive.endAddress.isNullOrBlank()) add(false, drive.endLatitude, drive.endLongitude)
    }
    return targets
}

internal suspend fun enrichDriveAddressesIncrementally(
    targets: List<DriveAddressTarget>,
    attempted: MutableMap<DriveAddressKey, Any>,
    ensureCurrent: suspend () -> Unit,
    reverseGeocode: suspend (Double, Double) -> String?,
    publish: suspend (List<DriveAddressTarget>, String) -> Unit
) {
    val pending = synchronized(attempted) { targets.filter { it.key !in attempted } }
        .groupBy { it.key.latitude to it.key.longitude }.entries.take(LOOKUPS_PER_SNAPSHOT)
    var retriedAfterRelease = false
    for ((index, batch) in pending.chunked(LOOKUPS_PER_BATCH).withIndex()) {
        ensureCurrent()
        if (index > 0) {
            delay(BETWEEN_BATCHES_MS)
            ensureCurrent()
        }
        for ((coordinates, matching) in batch) {
            while (true) {
                ensureCurrent()
                val token = Any()
                val claimed = synchronized(attempted) {
                    matching.filter { it.key !in attempted }.also { rows -> rows.forEach { attempted[it.key] = token } }
                }
                if (claimed.isEmpty()) break
                try {
                    val lookup = withTimeoutOrNull(5_000L) {
                        boundedDriveAddressLookup(coordinates.first, coordinates.second, reverseGeocode)
                    }
                    if (lookup == null) {
                        releaseDriveAddressAttempt(attempted, claimed, token)
                        return
                    }
                    if (lookup is DriveAddressLookup.Busy) {
                        releaseDriveAddressAttempt(attempted, claimed, token)
                        if (retriedAfterRelease) return
                        retriedAfterRelease = true
                        driveAddressReleases.first { it != lookup.releaseVersion }
                        ensureCurrent()
                        continue
                    }
                    ensureCurrent()
                    val address = (lookup as DriveAddressLookup.Complete).address
                    if (!address.isNullOrBlank()) publish(claimed, address)
                    synchronized(attempted) {
                        claimed.forEach { if (attempted[it.key] === token) attempted[it.key] = CompletedAddressAttempt }
                    }
                    break
                } catch (e: CancellationException) {
                    releaseDriveAddressAttempt(attempted, claimed, token)
                    throw e
                } catch (_: Exception) {
                    releaseDriveAddressAttempt(attempted, claimed, token)
                    return
                }
            }
        }
    }
}

internal suspend fun enrichDriveAddresses(
    drives: List<DriveData>, reverseGeocode: suspend (latitude: Double, longitude: Double) -> String?
): List<DriveData> = withContext(Dispatchers.IO) {
    drives.map { drive ->
        suspend fun resolve(existing: String?, latitude: Double?, longitude: Double?): String? {
            if (!existing.isNullOrBlank()) return existing
            if (!validDriveCoordinates(latitude, longitude)) return null
            return try { reverseGeocode(latitude!!, longitude!!) }
            catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
        }
        drive.copy(
            startAddress = resolve(drive.startAddress, drive.startLatitude, drive.startLongitude),
            endAddress = resolve(drive.endAddress, drive.endLatitude, drive.endLongitude)
        )
    }
}
