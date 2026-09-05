package com.matelink.data.local

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
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
}
