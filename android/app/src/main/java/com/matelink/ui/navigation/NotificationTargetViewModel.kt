package com.matelink.ui.navigation

import androidx.lifecycle.ViewModel
import com.matelink.data.local.VehicleContextRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** A notification's storage identity must belong to the currently selected account and origin. */
@HiltViewModel
class NotificationTargetViewModel @Inject constructor(
    private val contexts: VehicleContextRepository
) : ViewModel() {
    internal suspend fun isCurrentTarget(remoteCarId: Int, localHistoryCarId: Int?): Boolean {
        if (remoteCarId <= 0 || localHistoryCarId == null || localHistoryCarId >= 0) return false
        return try {
            val scope = contexts.captureReadScope()
            val current = contexts.cachedContextForRemote(remoteCarId, scope) ?: return false
            current.localHistoryCarId == localHistoryCarId && contexts.isHistoryContextCurrentNow(scope, current)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { false }
    }
}
