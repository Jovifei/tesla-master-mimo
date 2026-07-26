package com.matelink.domain.map

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

data class InstalledAppIdentity(val packageName: String, val sha1: String?, val buildType: String)

object InstalledAppSignature {
    fun read(context: Context, isDebug: Boolean): InstalledAppIdentity {
        val packageName = context.packageName
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        }
        val packageInfo = context.packageManager.getPackageInfo(packageName, flags)
        val bytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION") packageInfo.signatures?.firstOrNull()?.toByteArray()
        }
        return InstalledAppIdentity(packageName, bytes?.let(::formatSha1), if (isDebug) "Debug" else "Release")
    }

    fun formatSha1(certificateBytes: ByteArray): String = MessageDigest.getInstance("SHA-1")
        .digest(certificateBytes)
        .joinToString(":") { "%02X".format(it) }
}
