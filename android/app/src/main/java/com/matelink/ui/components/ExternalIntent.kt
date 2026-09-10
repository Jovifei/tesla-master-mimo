package com.matelink.ui.components

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import com.matelink.R

fun Context.launchBrowserChooser(url: String) {
    val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addCategory(Intent.CATEGORY_BROWSABLE)
    // MATCH_ALL avoids OEM filtering to just the broken/default browser.
    val browsers = packageManager.queryIntentActivities(
        webIntent, PackageManager.MATCH_ALL or PackageManager.GET_RESOLVED_FILTER
    )
        .filter { it.filter?.countDataAuthorities() == 0 && it.activityInfo.exported && it.activityInfo.enabled }
        .distinctBy { it.activityInfo.packageName }
    if (browsers.isEmpty()) {
        Toast.makeText(this, getString(R.string.external_action_unavailable), Toast.LENGTH_SHORT).show()
        return
    }
    AlertDialog.Builder(this)
        .setTitle(R.string.tesla_login_choose_browser)
        .setItems(browsers.map { it.loadLabel(packageManager) }.toTypedArray()) { _, index ->
            val activity = browsers[index].activityInfo
            launchExternalIntentSafely(
                Intent(webIntent).setComponent(ComponentName(activity.packageName, activity.name))
            )
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

fun Context.launchExternalIntentSafely(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, getString(R.string.external_action_unavailable), Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        Toast.makeText(this, getString(R.string.external_action_unavailable), Toast.LENGTH_SHORT).show()
    }
}
