package com.matelink.data.local

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class VehiclePhotoStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val preferences: SharedPreferences = context.getSharedPreferences(
        "vehicle_custom_photos",
        Context.MODE_PRIVATE
    )

    private val _photoUpdateSignal = MutableStateFlow(0L)
    val photoUpdateSignal: StateFlow<Long> = _photoUpdateSignal.asStateFlow()

    private val photosDir = File(context.filesDir, "vehicle_photos").apply {
        if (!exists()) mkdirs()
    }

    fun getCustomPhotoFile(carId: Int): File? {
        val path = preferences.getString("custom_photo_$carId", null) ?: return null
        val file = File(path)
        return if (file.exists() && file.length() > 0) file else null
    }

    fun saveCustomPhoto(carId: Int, inputStream: InputStream): File {
        val targetFile = File(photosDir, "car_${carId}_photo.jpg")
        FileOutputStream(targetFile).use { out ->
            inputStream.copyTo(out)
        }
        preferences.edit().putString("custom_photo_$carId", targetFile.absolutePath).apply()
        _photoUpdateSignal.value = System.currentTimeMillis()
        return targetFile
    }

    fun clearCustomPhoto(carId: Int) {
        val path = preferences.getString("custom_photo_$carId", null)
        if (path != null) {
            val file = File(path)
            if (file.exists()) file.delete()
        }
        preferences.edit().remove("custom_photo_$carId").apply()
        _photoUpdateSignal.value = System.currentTimeMillis()
    }
    /** Only a freshly verified account + stable vehicle may bind a NEW photo.
     * Original numeric-id photos remain on disk but never auto-attach to a UID.
     */
    private fun scopedPhotoKey(scope: HistoryReadScope, vehicle: VehicleContext): String? {
        if (vehicle.remoteApiCarId <= 0 || vehicle.connectionSource != scope.source ||
            vehicle.serverIdentity != scope.serverIdentity || vehicle.stableIdentity.isBlank() ||
            (scope.source == HistoryConnectionSource.CLOUD &&
                (scope.accountNamespace.isNullOrBlank() || vehicle.localHistoryCarId >= 0))) return null
        val input = listOf("vehicle-photo-v2", scope.source.name, scope.effectiveApiOrigin,
            scope.accountNamespace.orEmpty(), vehicle.stableIdentity).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun getScopedPhoto(scope: HistoryReadScope, vehicle: VehicleContext): File? {
        val key = scopedPhotoKey(scope, vehicle) ?: return null
        val target = File(photosDir, "scope_$key.jpg")
        return target.takeIf { it.exists() && it.isFile && it.length() > 0L &&
            preferences.getString("scoped_$key", null) == target.name }
    }

    /** A failed write cannot replace the older verified scoped photo. */
    fun saveScopedPhoto(scope: HistoryReadScope, vehicle: VehicleContext, input: InputStream): File {
        val key = requireNotNull(scopedPhotoKey(scope, vehicle)) { "photo_identity_missing" }
        val target = File(photosDir, "scope_$key.jpg")
        val staged = File(photosDir, "scope_$key.staged")
        val backup = File(photosDir, "scope_$key.rollback")
        check(!staged.exists() || staged.delete()) { "photo_staging_unavailable" }
        try {
            FileOutputStream(staged).use { output ->
                val buffer = ByteArray(8192)
                var size = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    check(size <= 8L * 1024 * 1024) { "photo_exceeds_local_limit" }
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
                check(size > 0) { "photo_empty" }
            }
            if (backup.exists()) check(backup.delete()) { "photo_old_backup_locked" }
            val hadOld = target.exists()
            if (hadOld) check(target.renameTo(backup)) { "photo_backup_failed" }
            try {
                check(staged.renameTo(target)) { "photo_atomic_replace_failed" }
                check(preferences.edit().putString("scoped_$key", target.name).commit()) {
                    "photo_pointer_persistence_failed"
                }
                if (hadOld) backup.delete()
            } catch (e: Exception) {
                target.delete()
                if (hadOld) backup.renameTo(target)
                throw e
            }
            _photoUpdateSignal.value = System.currentTimeMillis()
            return target
        } finally {
            staged.delete()
        }
    }

    fun clearScopedPhoto(scope: HistoryReadScope, vehicle: VehicleContext) {
        val key = scopedPhotoKey(scope, vehicle) ?: return
        val file = File(photosDir, "scope_$key.jpg")
        // Never delete an original unscoped photo or any other vehicle's file.
        check(preferences.edit().remove("scoped_$key").commit()) { "photo_clear_failed" }
        file.delete()
        _photoUpdateSignal.value = System.currentTimeMillis()
    }

}
