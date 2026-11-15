package com.matelink.ui.screens.archive

import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

/** A restored activity receives its previous Intent again, not a new user action. */
internal fun archiveIntentForActivityCreate(intent: Intent?, restoring: Boolean): Intent? =
    if (restoring && intent?.action == Intent.ACTION_VIEW &&
        ArchiveEnrollmentLink.parse(intent.dataString) != null) null else intent

/** Delivers a link only while resumed. Pausing invalidates even a not-yet-delivered link. */
internal class ArchiveEnrollmentLifecycleObserver(
    private val intent: Intent?,
    private val viewModel: ArchiveEnrollmentViewModel
) : LifecycleEventObserver {
    fun deliverOnce() {
        val candidate = intent ?: return
        if (!viewModel.claimIntent(candidate)) return
        if (candidate.action == Intent.ACTION_VIEW) {
            ArchiveEnrollmentLink.parse(candidate.dataString)?.let(viewModel::open)
        }
    }

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_RESUME -> deliverOnce()
            Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                intent?.let(viewModel::claimIntent)
                viewModel.onBackground()
            }
            else -> Unit
        }
    }
}
