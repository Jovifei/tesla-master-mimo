package com.matelink.ui.screens.drives

import com.matelink.data.api.models.DriveData
import com.matelink.data.repository.DriveAddressTarget
import com.matelink.data.repository.matchesHistoryIdentity
import com.matelink.util.preferSpecificEndpointAddress

internal fun applyDriveAddressResult(
    drives: List<DriveData>,
    matching: List<DriveAddressTarget>, address: String
): List<DriveData> {
    val targetsByDriveId = matching.groupBy { it.drive.driveId }
    return drives.map { drive ->
        var updated = drive
        for (target in targetsByDriveId[drive.driveId].orEmpty()) {
            if (!drive.matchesHistoryIdentity(target.drive)) continue
            if (target.key.start &&
                updated.startLatitude == target.key.latitude && updated.startLongitude == target.key.longitude)
                updated = updated.copy(startAddress = preferSpecificEndpointAddress(updated.startAddress, address))
            if (!target.key.start &&
                updated.endLatitude == target.key.latitude && updated.endLongitude == target.key.longitude)
                updated = updated.copy(endAddress = preferSpecificEndpointAddress(updated.endAddress, address))
        }
        updated
    }
}
