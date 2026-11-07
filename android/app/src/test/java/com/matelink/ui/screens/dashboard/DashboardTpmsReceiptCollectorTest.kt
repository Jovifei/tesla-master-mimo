package com.matelink.ui.screens.dashboard

import android.app.Application
import com.matelink.data.api.models.CarData
import com.matelink.data.api.models.CarStatus
import com.matelink.data.api.models.TpmsDetails
import com.matelink.data.local.TpmsChangeRuleStore
import com.matelink.data.repository.TpmsHistoryRepository
import com.matelink.data.sync.HistoryMemoryPreferences
import com.matelink.data.sync.HistoryResourceFixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class, sdk=[28])
class DashboardTpmsReceiptCollectorTest {
    @get:Rule val temp=TemporaryFolder()
    private val now=Instant.parse("2026-10-21T02:00:00Z").toEpochMilli()
    private val details=TpmsDetails(pressureFl=2.4,pressureUnit="bar",pressureSource="telemetry_mqtt",
        pressureTimeBasis="collector_received_at",pressureObservedAtFl=Instant.ofEpochMilli(now-1000).toString())

    @Test fun pressureOnlyRawResponseAdmitsOnceAtOriginalTimeWithoutAnyAdditionalHttp() = runBlocking(Dispatchers.IO) {
        val f=HistoryResourceFixture(File(temp.root,"dashboard-tpms.db"))
        try {
            f.initialize(cloud=true)
            val scope=f.vehicleContexts.captureReadScope()
            val history=TpmsHistoryRepository(f.db.tpmsPressureSampleDao(),f.vehicleContexts)
            val rules=TpmsChangeRuleStore(HistoryMemoryPreferences())
            val collector=DashboardTpmsReceiptCollector(f.vehicleContexts,history,rules,
                Clock.fixed(Instant.ofEpochMilli(now),ZoneOffset.UTC))
            val raw=CarStatus(tpmsDetails=details)
            val car=CarData(1,vehicleUid="synthetic-vehicle-1")
            f.calls.clear()
            assertTrue(collector.collect(scope,car,raw) { true })
            collector.collect(scope,car,raw) { true }
            val saved=f.db.tpmsPressureSampleDao().getVerifiedCollectorInRange(f.localId(1),now-10_000,now+1)
            assertEquals(1,saved.size)
            assertEquals(now-1000,saved.single().observedAt)
            assertEquals(2.4,requireNotNull(saved.single().pressureFl),0.0)
            assertNull(saved.single().pressureFr)
            assertTrue(f.calls.isEmpty())
        } finally { f.close() }
    }

    @Test fun unknownMappingWrongSourceUnitTimeAndStaleAccountCannotAdmit() = runBlocking(Dispatchers.IO) {
        val f=HistoryResourceFixture(File(temp.root,"dashboard-tpms-negative.db"))
        try {
            f.initialize(cloud=true)
            val scope=f.vehicleContexts.captureReadScope()
            val collector=DashboardTpmsReceiptCollector(f.vehicleContexts,
                TpmsHistoryRepository(f.db.tpmsPressureSampleDao(),f.vehicleContexts),
                TpmsChangeRuleStore(HistoryMemoryPreferences()),Clock.fixed(Instant.ofEpochMilli(now),ZoneOffset.UTC))
            val car=CarData(1,vehicleUid="synthetic-vehicle-1")
            val invalid=listOf(details.copy(pressureUnit="psi"),details.copy(pressureSource="fleet_api"),
                details.copy(pressureTimeBasis="phone_poll_time"),details.copy(pressureObservedAtFl=Instant.ofEpochMilli(now+1).toString()),
                details.copy(pressureObservedAtFl="bad"),details.copy(pressureObservedAtFl=Instant.ofEpochMilli(now-31L*86_400_000).toString()))
            f.calls.clear()
            invalid.forEach { assertFalse(collector.collect(scope,car,CarStatus(tpmsDetails=it)) { true }) }
            assertFalse(collector.collect(scope,CarData(999,vehicleUid="unverified"),CarStatus(tpmsDetails=details)) { true })
            assertFalse(collector.collect(scope,car.copy(vehicleUid="different-vehicle"),CarStatus(tpmsDetails=details)) { true })
            assertFalse(collector.collect(scope,car,CarStatus(tpmsDetails=details)) { false })
            assertFalse(collector.collect(scope.copy(effectiveApiOrigin="https://other.invalid"),car,CarStatus(tpmsDetails=details)) { true })
            f.sessions.save("other-synthetic-access","other-synthetic-refresh",3600,"other-account")
            assertFalse(collector.collect(scope,car,CarStatus(tpmsDetails=details)) { true })
            f.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM tpms_pressure_samples").use { count ->
                assertTrue(count.moveToFirst())
                assertEquals(0,count.getInt(0))
            }
            assertNull(f.vehicleContexts.cachedContextForRemote(999))
            assertTrue(f.calls.isEmpty())
        } finally { f.close() }
    }

    @Test fun partialPressureChangeAndIndependentWarningTimesRemainDurableAndDeduplicated() = runBlocking(Dispatchers.IO) {
        val f=HistoryResourceFixture(File(temp.root,"dashboard-tpms-journal.db"))
        try {
            f.initialize(cloud=true)
            val scope=f.vehicleContexts.captureReadScope()
            val id=f.localId(1)
            val rules=TpmsChangeRuleStore(HistoryMemoryPreferences())
            val collector=DashboardTpmsReceiptCollector(f.vehicleContexts,
                TpmsHistoryRepository(f.db.tpmsPressureSampleDao(),f.vehicleContexts),rules,
                Clock.fixed(Instant.ofEpochMilli(now),ZoneOffset.UTC))
            val car=CarData(1,vehicleUid="synthetic-vehicle-1")
            val warningAt=Instant.ofEpochMilli(now-3000).toString()
            val initial=details.copy(pressureFl=2.8,pressureObservedAtFl=Instant.ofEpochMilli(now-2000).toString(),
                warningFl=true,warningSource="telemetry_mqtt",warningTimeBasis="collector_received_at",warningObservedAtFl=warningAt)
            assertTrue(collector.collect(scope,car,CarStatus(tpmsDetails=initial)) { true })
            val changed=initial.copy(pressureFl=2.4,pressureObservedAtFl=Instant.ofEpochMilli(now-1000).toString(),
                pressureFr=2.7,pressureObservedAtFr=Instant.ofEpochMilli(now-500).toString())
            repeat(2) { collector.collect(scope,car,CarStatus(tpmsDetails=changed)) { true } }
            val rows=f.db.tpmsPressureSampleDao().getVerifiedCollectorInRange(id,now-10_000,now+1)
            assertEquals(listOf(now-2000,now-1000,now-500),rows.map { it.observedAt })
            assertNull(rows.last().pressureFl)
            assertEquals(1,rules.pending(id).size)
            assertEquals(now-1000,rules.pending(id).single().observedAt)
            assertEquals(warningAt,rules.verifiedWarningSnapshot(id,now)?.warningObservedAtFl)
            assertTrue(rules.verifiedWarningSnapshot(id,now)?.warningFl==true)
            val unverifiedFalse=TpmsDetails(warningFl=false,warningSource="telemetry_mqtt",warningTimeBasis="collector_received_at")
            assertFalse(collector.collect(scope,car,CarStatus(tpmsDetails=unverifiedFalse)) { true })
            assertTrue(rules.verifiedWarningSnapshot(id,now)?.warningFl==true)
            assertTrue(f.calls.isEmpty())
        } finally { f.close() }
    }
}
