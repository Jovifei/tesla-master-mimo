package com.matelink.data.sync

import com.matelink.data.local.ConnectionMode
import com.matelink.data.api.models.CarStatus

internal enum class NotificationReadPlan { REMOTE_SELF_HOSTED, SAVED_CLOUD, UNCONFIGURED }

internal fun notificationReadPlan(mode: ConnectionMode?, serverConfigured: Boolean): NotificationReadPlan =
    when {
        mode == ConnectionMode.TESLA_CLOUD -> NotificationReadPlan.SAVED_CLOUD
        serverConfigured -> NotificationReadPlan.REMOTE_SELF_HOSTED
        else -> NotificationReadPlan.UNCONFIGURED
    }

internal fun isNewSentryObservation(previousDisplay: String?, status: CarStatus): Boolean =
    status.sentryMode == true && status.centerDisplayState?.toIntOrNull() == 7 &&
        previousDisplay?.toIntOrNull() != 7
