package com.matelink.ui.screens.settings

import com.matelink.data.local.HistoryReadScope
import com.matelink.data.local.SettingsDataStore
import com.matelink.data.local.TpmsAlertProfile
import com.matelink.data.local.VehicleContext
import com.matelink.data.local.VehicleContextRepository

internal data class TpmsProfileBinding(val scope: HistoryReadScope, val vehicle: VehicleContext)

internal suspend fun resolveTpmsProfileBinding(remoteId: Int, contexts: VehicleContextRepository): TpmsProfileBinding {
    val scope = contexts.captureReadScope()
    val context = contexts.resolveRemote(remoteId)
    check(contexts.isHistoryContextCurrent(scope, context)) { "history_identity_unavailable" }
    return TpmsProfileBinding(scope, context)
}

internal fun tpmsProfileStorageKey(binding: TpmsProfileBinding): Int = binding.vehicle.localHistoryCarId

internal suspend fun saveBoundTpmsProfile(settings: SettingsDataStore, contexts: VehicleContextRepository,
    binding: TpmsProfileBinding, profile: TpmsAlertProfile) {
    check(contexts.isHistoryContextCurrent(binding.scope, binding.vehicle)) { "history_identity_unavailable" }
    settings.saveTpmsAlertProfile(tpmsProfileStorageKey(binding), profile)
    check(contexts.isHistoryContextCurrent(binding.scope, binding.vehicle)) { "history_identity_unavailable" }
}
