package com.matelink.data.sync

import android.app.Application
import androidx.work.*
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.utils.futures.SettableFuture
import com.matelink.data.local.*
import com.matelink.data.repository.SettingsRepository
import com.matelink.data.repository.TpmsHistoryRepository
import com.matelink.data.repository.TpmsStateRepository
import com.matelink.notification.TpmsTrendNotificationManager
import com.matelink.notification.NotificationDeliveryUnavailableException
import com.matelink.data.api.models.TpmsDetails
import com.matelink.data.local.entity.TPMS_VERIFIED_COLLECTOR
import com.matelink.data.local.entity.TpmsPressureSample
import com.matelink.domain.analytics.TpmsChangeRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor

/** Actual worker + real scoped resolver/Room; all HTTP is intercepted by the existing fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class, sdk=[28])
class TpmsCloudSafetyTest {
    @get:Rule val temp=TemporaryFolder()
    private val managers=mutableListOf<WorkManagerImpl>()

    @After fun releaseManagers() {
        managers.forEach { it.cancelAllWork().result.get(); it.workDatabase.close() }
        WorkManagerImpl.setDelegate(null)
    }

    private fun worker(f:HistoryResourceFixture, custom:TpmsCustomAlertStateStore,
        rules:TpmsChangeRuleStore=TpmsChangeRuleStore(HistoryMemoryPreferences())):TpmsPressureWorker {
        val manager=Class.forName("androidx.work.impl.WorkManagerImplExtKt")
            .getMethod("createWorkManager",android.content.Context::class.java,Configuration::class.java)
            .invoke(null,f.context,Configuration.Builder().build()) as WorkManagerImpl
        WorkManagerImpl.setDelegate(manager)
        managers+=manager
        val done={ SettableFuture.create<Void>().apply { set(null) } }
        val parameters=WorkerParameters(UUID.randomUUID(),Data.EMPTY,listOf(TpmsPressureWorker.TAG),
            WorkerParameters.RuntimeExtras(),0,0,Executor { it.run() },manager.workTaskExecutor,
            WorkerFactory.getDefaultWorkerFactory(),ProgressUpdater { _,_,_->done() },ForegroundUpdater { _,_,_->done() })
        return TpmsPressureWorker(f.context,parameters,f.apiRepo,
            TpmsStateRepository(TpmsStateDataStore(HistoryMemoryPreferences()),f.vehicleContexts),f.settings,
            TpmsHistoryRepository(f.db.tpmsPressureSampleDao(),f.vehicleContexts),custom,
            TpmsTrendNotificationManager(f.context),f.vehicleContexts,rules,
            SettingsRepository(f.context))
    }

    @Test fun actualCloudWorkerNeverInvokesProviderFacingHttpEvenWithoutSelfHostedUrl() = runBlocking(Dispatchers.IO) {
        val f=HistoryResourceFixture(File(temp.root,"cloud-worker.db"))
        try {
            f.initialize(cloud=true)
            f.settings.saveServerUrl("")
            SettingsRepository(f.context).setCurrentCarId(1)
            val custom=TpmsCustomAlertStateStore(HistoryMemoryPreferences())
            f.calls.clear()
            assertTrue(worker(f,custom).doWork() is ListenableWorker.Result.Success)
            assertEquals("cloud background recovery must not fetch cars, context, status or snapshot",emptyList<String>(),f.calls.toList())
        } finally { f.close() }
    }

    @Test fun backgroundRecoveryDoesNotDeleteOlderVerifiedPressureHistory() = runBlocking(Dispatchers.IO) {
        val f = HistoryResourceFixture(File(temp.root, "cloud-retention.db"))
        try {
            f.initialize(cloud = true)
            SettingsRepository(f.context).setCurrentCarId(1)
            val id = f.localId(1)
            val oldAt = System.currentTimeMillis() - 91L * 24 * 60 * 60 * 1_000
            val sample = TpmsPressureSample(id, oldAt, pressureFl = 2.8,
                provenance = TPMS_VERIFIED_COLLECTOR)
            f.db.tpmsPressureSampleDao().upsert(sample)
            assertTrue(worker(f, TpmsCustomAlertStateStore(HistoryMemoryPreferences())).doWork()
                is ListenableWorker.Result.Success)
            assertEquals(sample, f.db.tpmsPressureSampleDao().getAt(id, oldAt))
            assertTrue(f.calls.isEmpty())
        } finally { f.close() }
    }

    @Test fun unknownSelectedMappingSkipsNetworkAndPreservesOtherVehiclePending() = runBlocking(Dispatchers.IO) {
        val f=HistoryResourceFixture(File(temp.root,"cloud-unknown.db"))
        try {
            f.initialize(cloud=true)
            SettingsRepository(f.context).setCurrentCarId(999)
            val custom=TpmsCustomAlertStateStore(HistoryMemoryPreferences())
            val retained=mapOf(TirePosition.FL to TpmsCustomWheelState(TpmsCustomPressureState.LOW,
                pending=TpmsCustomPendingAlert(2.4,2.6)))
            custom.saveStates(f.localId(2),retained)
            f.calls.clear()
            assertTrue(worker(f,custom).doWork() is ListenableWorker.Result.Success)
            assertTrue(f.calls.isEmpty())
            assertNull(f.vehicleContexts.cachedContextForRemote(999))
            assertEquals(retained,custom.getStates(f.localId(2)))
        } finally { f.close() }
    }

    @Test fun repeatedStartupRegistrationKeepsTheSameSingleDelayedRequest() = runBlocking(Dispatchers.IO) {
        val f=HistoryResourceFixture(File(temp.root,"cloud-startup.db"))
        try {
            f.initialize(cloud=true)
            worker(f,TpmsCustomAlertStateStore(HistoryMemoryPreferences()))
            val manager=managers.last()
            fun active()=manager.getWorkInfosForUniqueWork(TpmsPressureWorker.WORK_NAME).get()
                .filterNot { it.state.isFinished }
            TpmsPressureWorker.schedulePeriodicWork(f.context)
            val first=active().single()
            TpmsPressureWorker.schedulePeriodicWork(f.context)
            assertEquals(first.id,active().single().id)
            val spec=requireNotNull(manager.workDatabase.workSpecDao().getWorkSpec(first.id.toString()))
            assertEquals(if(com.matelink.BuildConfig.DEBUG) 180_000L else 900_000L,spec.initialDelay)
            assertTrue(first.tags.contains(TpmsPressureWorker.TAG))
            assertTrue(f.calls.isEmpty())
        } finally { f.close() }
    }

    @Test fun actualCloudWorkerRecoversQualifiedCurrentPendingWithoutHttpAndRetainsOtherCarPending() = runBlocking(Dispatchers.IO) {
        val f=HistoryResourceFixture(File(temp.root,"cloud-pending.db"))
        try {
            f.initialize(cloud=true)
            SettingsRepository(f.context).setCurrentCarId(1)
            val id=f.localId(1)
            val custom=TpmsCustomAlertStateStore(HistoryMemoryPreferences())
            val rules=TpmsChangeRuleStore(HistoryMemoryPreferences())
            val history=TpmsHistoryRepository(f.db.tpmsPressureSampleDao(),f.vehicleContexts)
            val now=System.currentTimeMillis()
            val at=now-1000
            val details=TpmsDetails(pressureFl=2.4,pressureUnit="bar",pressureSource="telemetry_mqtt",
                pressureTimeBasis="collector_received_at",pressureObservedAtFl=java.time.Instant.ofEpochMilli(at).toString())
            val profile=TpmsAlertProfile(2.9,2.6,3.4,true)
            f.settings.saveTpmsAlertProfile(id,profile)
            admitTpmsObservation(id,TpmsPressureSample(id,at,pressureFl=2.4,provenance=TPMS_VERIFIED_COLLECTOR),
                TpmsChangeRule(),history,rules,executionNow=now)
            processSuccessfulTpmsStatus(id,"Synthetic",details,observedAt=at,profile=profile,
                saveObservation={},pruneOlderThan90Days={ _,_-> },detectTeslaStateChange={ _,_->null },
                updateTeslaState={ _,_-> },resetCustomState=custom::resetForProfile,
                claimCustomAlerts=custom::claimAlerts,commitCustomAlert=custom::commitClaim,releaseCustomAlert=custom::releaseClaim,
                notifyTesla={ _,_,_->error("no warning source") },
                notifyCustom={ _,_,_->throw NotificationDeliveryUnavailableException() },executionNow=now)
            assertNotNull(custom.getStates(id).getValue(TirePosition.FL).pending)
            val other=mapOf(TirePosition.FR to TpmsCustomWheelState(TpmsCustomPressureState.HIGH,
                pending=TpmsCustomPendingAlert(3.8,3.4)))
            custom.saveStates(f.localId(2),other)
            f.calls.clear()
            assertTrue(worker(f,custom,rules).doWork() is ListenableWorker.Result.Success)
            assertTrue("actual recovery posts no provider-facing request",f.calls.isEmpty())
            assertNull(custom.getStates(id).getValue(TirePosition.FL).pending)
            assertEquals(at,custom.getStates(id).getValue(TirePosition.FL).observedAt)
            assertEquals(other,custom.getStates(f.localId(2)))
            assertEquals(1,f.context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.size)
        } finally { f.close() }
    }
}
