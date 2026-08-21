package com.matelink.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppVisibilityTracker @Inject constructor() : Application.ActivityLifecycleCallbacks {
    private val startedActivities = AtomicInteger(0)

    @Volatile
    var isForeground: Boolean = false
        private set

    override fun onActivityStarted(activity: Activity) {
        if (startedActivities.incrementAndGet() == 1) {
            isForeground = true
        }
    }

    override fun onActivityStopped(activity: Activity) {
        val remaining = startedActivities.decrementAndGet()
        if (remaining <= 0) {
            startedActivities.set(0)
            isForeground = false
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
