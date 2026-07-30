package com.matelink.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.matelink.R

fun Context.launchExternalIntentSafely(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, getString(R.string.external_action_unavailable), Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        Toast.makeText(this, getString(R.string.external_action_unavailable), Toast.LENGTH_SHORT).show()
    }
}
