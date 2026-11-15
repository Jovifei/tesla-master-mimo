package com.matelink.ui.screens.drives

import com.matelink.data.api.models.DriveData
import com.matelink.data.repository.DriveAddressKey
import com.matelink.data.repository.DriveAddressTarget
import org.junit.Assert.assertEquals
import org.junit.Test

class DriveHistoryAddressProjectionTest {
    private fun drive(id: Int) = DriveData(
        driveId = id,
        startDate = "2026-10-30T01:00:00Z",
        endDate = "2026-10-30T01:10:00Z",
        source = "teslamate_archive",
        sourceInstanceId = "synthetic-instance",
        sourceId = "record-$id",
        startLatitude = 30.0,
        startLongitude = 120.0,
        endLatitude = 30.0,
        endLongitude = 120.0
    )

    private fun target(drive: DriveData, start: Boolean) = DriveAddressTarget(
        drive,
        DriveAddressKey(
            "synthetic-identity-${drive.driveId}", "synthetic-receipt", start,
            if (start) drive.startLatitude!! else drive.endLatitude!!,
            if (start) drive.startLongitude!! else drive.endLongitude!!
        )
    )

    @Test fun sameNumericIdDoesNotCopyToAnotherSourceInstanceOrSession() {
        val original = drive(7)
        val otherSource = original.copy(source = "telemetry_mqtt")
        val otherInstance = original.copy(sourceInstanceId = "other-instance")
        val otherSession = original.copy(startDate = "2026-10-30T02:00:00Z")
        val conflictingRecord = original.copy(sourceRecordId = "different-record")
        val input = listOf(original, otherSource, otherInstance, otherSession, conflictingRecord)

        val result = applyDriveAddressResult(input, listOf(target(original, true), target(original, false)), "已解析")

        assertEquals(original.copy(startAddress = "已解析", endAddress = "已解析"), result[0])
        assertEquals(input.drop(1), result.drop(1))
    }

    @Test fun changedCoordinatesAndKnownAddressAreNotOverwritten() {
        val snapshot = drive(9)
        val current = snapshot.copy(startAddress = "已有地址", endLatitude = 31.0)

        val result = applyDriveAddressResult(listOf(current),
            listOf(target(snapshot, true), target(snapshot, false)), "迟到地址")

        assertEquals(listOf(current), result)
    }

    @Test fun manyTargetsResolveOnlyTheirOwnDriveAndEndpoint() {
        val input = (1..1000).map(::drive)
        val targets = input.map { target(it, start = it.driveId % 2 == 0) }.reversed()

        val result = applyDriveAddressResult(input, targets, "共同地点")

        assertEquals(input.map {
            if (it.driveId % 2 == 0) it.copy(startAddress = "共同地点")
            else it.copy(endAddress = "共同地点")
        }, result)
    }
}
