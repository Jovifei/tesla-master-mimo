package com.matelink.data.local

import android.app.Application
import androidx.room.Room
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.core.DataStore
import com.matelink.data.api.models.TpmsDetails
import com.matelink.data.local.dao.TpmsPressureSampleDao
import com.matelink.data.local.entity.TPMS_VERIFIED_COLLECTOR
import com.matelink.data.local.entity.TpmsPressureSample
import com.matelink.data.repository.TpmsHistoryRepository
import com.matelink.data.repository.TpmsStateRepository
import com.matelink.data.sync.admitTpmsObservation
import com.matelink.data.sync.processSuccessfulTpmsStatus
import com.matelink.data.sync.drainTpmsPressureChanges
import com.matelink.data.sync.recoverTpmsAdmissionIntents
import com.matelink.domain.analytics.TpmsChangeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/** Real disk Room and Preferences DataStore rebuilt across simulated process interruption.
 * Runs in an isolated Robolectric Application, never the production application/device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class TpmsPersistentRecoveryTest {
    @get:Rule val temp = TemporaryFolder()
    private val carId = -71
    private val receiptAt = 1_791_690_000_000L
    private val profile = TpmsAlertProfile(2.9, 2.6, 3.4, enabled = true)
    private val pressure = TpmsDetails(pressureFl = 2.4,pressureUnit="bar",pressureSource="telemetry_mqtt",
        pressureTimeBasis="collector_received_at",pressureObservedAtFl=java.time.Instant.ofEpochMilli(receiptAt).toString())
    private val warning = TpmsDetails(warningFl = true, warningFr = false, warningRl = false, warningRr = false)
    private class ProcessDeath : Error("synthetic process termination after durable write")

    @Test fun sameReceiptRecoversCustomLeaseAfterRestartAndExecutionTimeAdvance() = runBlocking {
        var deliveries = 0
        var stores = Stores(temp.root)
        try { pass(stores, pressure, receiptAt) { throw ProcessDeath() }; fail("death expected") }
        catch (_: ProcessDeath) { }
        stores.close()
        stores = Stores(temp.root)
        try {
            pass(stores, pressure, receiptAt + 360_001L) { deliveries++ }
            assertEquals("fixed collector receipt must not freeze execution lease", 1, deliveries)
            pass(stores, pressure, receiptAt + 360_002L) { deliveries++ }
            assertEquals("committed receipt is not delivered twice", 1, deliveries)
        } finally { stores.close() }
    }

    @Test fun sameReceiptRecoversTeslaLeaseAfterRestartAndExecutionTimeAdvance() = runBlocking {
        var deliveries = 0
        var stores = Stores(temp.root)
        try { pass(stores, warning, receiptAt, teslaNotify = { throw ProcessDeath() }); fail("death expected") }
        catch (_: ProcessDeath) { }
        stores.close()
        stores = Stores(temp.root)
        try {
            pass(stores, warning, receiptAt + 360_001L, teslaNotify = { deliveries++ })
            assertEquals("Tesla lease uses execution clock independently of evidence time", 1, deliveries)
        } finally { stores.close() }
    }

    @Test fun durableCustomPendingDrainsAfterRestartWithoutFreshPressure() = runBlocking {
        var stores = Stores(temp.root)
        pass(stores, pressure, receiptAt) { throw IllegalStateException("notifications denied") }
        assertNotNull(stores.custom.getStates(carId).getValue(TirePosition.FL).pending)
        stores.close()
        stores = Stores(temp.root)
        var deliveries = 0
        try {
            pass(stores, null, receiptAt + 900_000L) { deliveries++ }
            assertEquals("permission recovery does not require a new Fleet receipt", 1, deliveries)
            assertNull(stores.custom.getStates(carId).getValue(TirePosition.FL).pending)
        } finally { stores.close() }
    }

    @Test fun admittedRoomReceiptRecoversChangeIntentAfterQueueWriteInterruption() = runBlocking {
        var database = openDatabase()
        var dao = database.tpmsPressureSampleDao()
        dao.upsert(TpmsPressureSample(carId, receiptAt - 1000, pressureFl = 2.8, provenance = TPMS_VERIFIED_COLLECTOR))
        val candidate = TpmsPressureSample(carId, receiptAt, pressureFl = 2.4, provenance = TPMS_VERIFIED_COLLECTOR)
        var stores = Stores(temp.root)
        val interruptedDao = InterruptAfterAdmissionDao(dao)
        try { admitTpmsObservation(carId, candidate, TpmsChangeRule(), TpmsHistoryRepository(interruptedDao), stores.rules); fail("death expected") }
        catch (_: ProcessDeath) { }
        assertEquals(candidate, dao.getAt(carId, receiptAt))
        stores.close()
        database.close()
        database = openDatabase()
        dao = database.tpmsPressureSampleDao()
        stores = Stores(temp.root)
        try {
            admitTpmsObservation(carId, candidate, TpmsChangeRule(), TpmsHistoryRepository(dao), stores.rules)
            val queued = stores.rules.pending(carId)
            assertEquals("admitted but unqueued receipt survives restart", 1, queued.size)
            assertEquals(receiptAt, queued.single().observedAt)
            stores.rules.acknowledge(carId, queued.single())
            admitTpmsObservation(carId, candidate, TpmsChangeRule(), TpmsHistoryRepository(dao), stores.rules)
            assertTrue("acknowledged event must not rearm on repeated poll", stores.rules.pending(carId).isEmpty())
        } finally { stores.close(); database.close() }
    }

    @Test fun unrelatedPartialFalseKeepsPendingWarningButItsOwnFalseClearsIt() = runBlocking {
        val stores=Stores(temp.root)
        try {
            val first=stores.tesla.claimPartialStateChange(carId,true,null,null,null,receiptAt,
                mapOf(TirePosition.FL to receiptAt)) as TpmsStateDataStore.TpmsStateDataStoreClaimResult.Claimed
            assertEquals(TpmsStateDataStore.TpmsStateDataStoreClaimResult.InFlight,
                stores.tesla.claimPartialStateChange(carId,null,false,null,null,receiptAt+1,
                    mapOf(TirePosition.FR to receiptAt+1)))
            assertEquals(TpmsStateDataStore.TpmsStateDataStoreClaimResult.NoTransition,
                stores.tesla.claimPartialStateChange(carId,null,null,null,null,receiptAt+2))
            val takeover=stores.tesla.claimPartialStateChange(carId,null,false,null,null,receiptAt+360_001,
                mapOf(TirePosition.FR to receiptAt+1)) as TpmsStateDataStore.TpmsStateDataStoreClaimResult.Claimed
            assertTrue(takeover.claim.nextState.warningFl)
            stores.tesla.commitStateChange(carId,first.claim,receiptAt+360_001)
            assertFalse("old token cannot commit",stores.tesla.getState(carId).warningFl)
            stores.tesla.commitStateChange(carId,takeover.claim,receiptAt+360_002)
            val cleared=stores.tesla.claimPartialStateChange(carId,false,null,null,null,receiptAt+360_003,
                mapOf(TirePosition.FL to receiptAt+360_003)) as TpmsStateDataStore.TpmsStateDataStoreClaimResult.Claimed
            assertFalse(cleared.claim.nextState.warningFl)
        } finally { stores.close() }
    }

    @Test fun freshUnrelatedWheelCannotResurrectExpiredPendingWarning() = runBlocking {
        var stores=Stores(temp.root)
        val original=TpmsDetails(warningFl=true,warningSource="telemetry_mqtt",warningTimeBasis="collector_received_at",
            warningObservedAtFl=java.time.Instant.ofEpochMilli(receiptAt).toString())
        TpmsStateRepository(stores.tesla).claimStateChangeForHistoryCarId(carId,original,receiptAt)
        stores.close(); stores=Stores(temp.root)
        try {
            val now=receiptAt+31L*24*60*60*1000
            assertEquals("FR receipt is not freshness proof for expired FL warning",
                com.matelink.data.repository.TpmsStateChangeClaimResult.NoTransition,
                TpmsStateRepository(stores.tesla).claimStateChangeForHistoryCarId(carId,TpmsDetails(
                    warningFr=false,warningSource="telemetry_mqtt",warningTimeBasis="collector_received_at",
                    warningObservedAtFr=java.time.Instant.ofEpochMilli(now).toString()),now))
            assertFalse(stores.tesla.getState(carId).hasAnyWarning)
        } finally { stores.close() }
    }

    @Test fun warningJournalReconstructionRetainsIndependentReceiptsAndCannotCrossIdentity() = runBlocking {
        var stores=Stores(temp.root)
        val fl=TpmsDetails(warningFl=true,warningSource="telemetry_mqtt",warningTimeBasis="collector_received_at",
            warningObservedAtFl=java.time.Instant.ofEpochMilli(receiptAt).toString())
        val fr=TpmsDetails(warningFr=false,warningSource="telemetry_mqtt",warningTimeBasis="collector_received_at",
            warningObservedAtFr=java.time.Instant.ofEpochMilli(receiptAt+1000).toString())
        stores.rules.rememberVerifiedWarning(carId,fl,receiptAt)
        stores.rules.rememberVerifiedWarning(carId,fr,receiptAt+1000)
        stores.close(); stores=Stores(temp.root)
        try {
            val restored=requireNotNull(stores.rules.verifiedWarningSnapshot(carId,receiptAt+2000))
            assertEquals(fl.warningObservedAtFl,restored.warningObservedAtFl)
            assertEquals(fr.warningObservedAtFr,restored.warningObservedAtFr)
            assertTrue(restored.warningFl==true)
            assertFalse(requireNotNull(restored.warningFr))
            assertNull(restored.warningRl)
            assertNull(stores.rules.verifiedWarningSnapshot(carId-1,receiptAt+2000))
            assertNull(stores.rules.verifiedWarningSnapshot(carId,receiptAt+31L*24*60*60*1000))
        } finally { stores.close() }
    }

    @Test fun executionClockRollbackTakesOverAndOldCustomTokenCannotCommitOrRelease() = runBlocking {
        val stores=Stores(temp.root)
        try {
            val observed=mapOf(TirePosition.FL to TpmsCustomWheelObservation(true,TpmsCustomPressureState.LOW,
                2.4,2.6,receiptAt,true))
            val old=stores.custom.claimAlerts(carId,"clock-profile",observed,false,receiptAt+1000).single()
            val next=stores.custom.claimAlerts(carId,"clock-profile",observed,false,receiptAt+500).single()
            assertNotEquals(old.token,next.token)
            stores.custom.commitClaim(carId,old); stores.custom.releaseClaim(carId,old)
            assertEquals(next.token,stores.custom.getStates(carId).getValue(TirePosition.FL).claimToken)
            stores.custom.commitClaim(carId,next)
            assertNull(stores.custom.getStates(carId).getValue(TirePosition.FL).pending)
        } finally { stores.close() }
    }

    @Test fun legacyUnqualifiedPendingSurvivesRestartWithoutIndependentDelivery() = runBlocking {
        var stores=Stores(temp.root)
        pass(stores,TpmsDetails(pressureFl=2.4),receiptAt) { throw IllegalStateException("denied") }
        stores.close(); stores=Stores(temp.root)
        var delivered=0
        try {
            pass(stores,null,receiptAt+900_000) { delivered++ }
            assertEquals(0,delivered)
            assertNotNull(stores.custom.getStates(carId).getValue(TirePosition.FL).pending)
        } finally { stores.close() }
    }

    @Test fun anotherHistoryIdentityCannotConsumeQualifiedCustomPending() = runBlocking {
        val stores=Stores(temp.root)
        pass(stores,pressure,receiptAt) { throw IllegalStateException("denied") }
        var delivered=0
        try {
            pass(stores,null,receiptAt+900_000,historyId=carId-1) { delivered++ }
            assertEquals(0,delivered)
            assertNotNull(stores.custom.getStates(carId).getValue(TirePosition.FL).pending)
        } finally { stores.close() }
    }

    @Test fun freshSameStateCannotBorrowItsTimestampForExpiredOriginalPending() = runBlocking {
        var stores=Stores(temp.root)
        val observed=TpmsCustomWheelObservation(true,TpmsCustomPressureState.LOW,2.4,2.6,receiptAt,true)
        val first=stores.custom.claimAlerts(carId,"expiry-profile",mapOf(TirePosition.FL to observed),false,receiptAt).single()
        stores.custom.releaseClaim(carId,first)
        stores.close(); stores=Stores(temp.root)
        try {
            val now=receiptAt+31L*24*60*60*1000
            val claims=stores.custom.claimAlerts(carId,"expiry-profile",mapOf(TirePosition.FL to observed.copy(observedAt=now)),false,now)
            assertTrue("only original pending receipt controls its eligibility",claims.isEmpty())
            assertEquals(receiptAt,stores.custom.getStates(carId).getValue(TirePosition.FL).observedAt)
        } finally { stores.close() }
    }

    @Test fun lateOrConflictingReceiptCannotRearmAfterQualifiedNormalObservation() = runBlocking {
        var stores=Stores(temp.root)
        val normal=TpmsCustomWheelObservation(true,null,2.9,null,receiptAt+10,true)
        stores.custom.claimAlerts(carId,"order-profile",mapOf(TirePosition.FL to normal),false,receiptAt+10)
        stores.close(); stores=Stores(temp.root)
        try {
            val low=TpmsCustomWheelObservation(true,TpmsCustomPressureState.LOW,2.4,2.6,receiptAt,true)
            assertTrue(stores.custom.claimAlerts(carId,"order-profile",mapOf(TirePosition.FL to low),false,receiptAt+20).isEmpty())
            assertTrue(stores.custom.claimAlerts(carId,"order-profile",mapOf(TirePosition.FL to low.copy(observedAt=receiptAt+10)),false,receiptAt+20).isEmpty())
            assertEquals(1,stores.custom.claimAlerts(carId,"order-profile",mapOf(TirePosition.FL to low.copy(observedAt=receiptAt+30)),false,receiptAt+30).size)
        } finally { stores.close() }
    }

    @Test fun cancellationBeforeFirstNotificationReleasesEntireDurableCustomClaimBatch() = runBlocking {
        val stores=Stores(temp.root)
        val two=pressure.copy(pressureFr=2.4,pressureObservedAtFr=pressure.pressureObservedAtFl)
        try {
            try {
                pass(stores,two,receiptAt,ensureCurrent={
                    if(stores.custom.getStates(carId).size==2) throw CancellationException("scope superseded before first notify")
                }) { fail("no notification before scope validation") }
                fail("cancel expected")
            } catch (_:CancellationException) { }
            val states=stores.custom.getStates(carId)
            assertEquals(2,states.size)
            assertTrue(states.values.all { it.pending!=null && it.claim==null && it.claimToken==null })
            var delivered=0
            pass(stores,two,receiptAt+1) { delivered++ }
            assertEquals("both pending wheels are immediately reclaimable",2,delivered)
        } finally { stores.close() }
    }

    @Test fun scopeChangeAfterDeliveryDoesNotCommitCustomClaim() = runBlocking {
        val stores=Stores(temp.root); var current=true
        try {
            try {
                pass(stores,pressure,receiptAt,ensureCurrent={ if(!current) throw CancellationException("scope switched") }) {
                    current=false
                }
                fail("scope cancellation expected")
            } catch (_:CancellationException) { }
            val state=stores.custom.getStates(carId).getValue(TirePosition.FL)
            assertNotNull(state.pending)
            assertNull(state.claim)
        } finally { stores.close() }
    }

    @Test fun concurrentProductionAdmissionsAndConsumersDeliverEachReceiptOnce() = runBlocking {
        val database=openDatabase(); val dao=database.tpmsPressureSampleDao(); val stores=Stores(temp.root)
        val history=TpmsHistoryRepository(dao)
        val sample=TpmsPressureSample(carId,receiptAt,pressureFl=2.4,provenance=TPMS_VERIFIED_COLLECTOR)
        dao.upsert(sample.copy(observedAt=receiptAt-1000,pressureFl=2.8))
        try {
            (1..4).map { async { admitTpmsObservation(carId,sample,TpmsChangeRule(),history,stores.rules,executionNow=receiptAt) } }.awaitAll()
            assertEquals(1,stores.rules.pending(carId).size)
            val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>(); var delivered=0
            val first=async { drainTpmsPressureChanges(carId,history,stores.rules,receiptAt) { delivered++; entered.complete(Unit); release.await() } }
            entered.await()
            val second=async { drainTpmsPressureChanges(carId,history,stores.rules,receiptAt) { delivered++ } }
            second.await(); release.complete(Unit); first.await()
            assertEquals(1,delivered)
            assertTrue(stores.rules.pending(carId).isEmpty())
            admitTpmsObservation(carId,sample.copy(pressureFr=2.8),TpmsChangeRule(),history,stores.rules,executionNow=receiptAt)
            assertTrue("late other wheel does not rearm acknowledged FL",stores.rules.pending(carId).isEmpty())
        } finally { stores.close(); database.close() }
    }

    @Test fun pressureChangeScopeCancellationKeepsEventAndReleasesLease() = runBlocking {
        val database=openDatabase(); val dao=database.tpmsPressureSampleDao(); val stores=Stores(temp.root)
        val history=TpmsHistoryRepository(dao); var current=true
        val sample=TpmsPressureSample(carId,receiptAt,pressureFl=2.4,provenance=TPMS_VERIFIED_COLLECTOR)
        dao.upsert(sample.copy(observedAt=receiptAt-1000,pressureFl=2.8))
        try {
            admitTpmsObservation(carId,sample,TpmsChangeRule(),history,stores.rules,executionNow=receiptAt)
            try {
                drainTpmsPressureChanges(carId,history,stores.rules,receiptAt,
                    { if(!current) throw CancellationException("scope switched") }) { current=false }
                fail("cancel expected")
            } catch (_:CancellationException) { }
            assertEquals(1,stores.rules.pending(carId).size)
            current=true; var retried=0
            drainTpmsPressureChanges(carId,history,stores.rules,receiptAt) { retried++ }
            assertEquals(1,retried)
            assertTrue(stores.rules.pending(carId).isEmpty())
        } finally { stores.close(); database.close() }
    }

    @Test fun unmatchedRoomReceiptKeepsIntentWithoutInventingQueueEvent() = runBlocking {
        val database=openDatabase(); val dao=database.tpmsPressureSampleDao(); val stores=Stores(temp.root)
        val sample=TpmsPressureSample(carId,receiptAt,pressureFl=2.4,provenance=TPMS_VERIFIED_COLLECTOR)
        try {
            val change=com.matelink.domain.analytics.TpmsPressureChange(TirePosition.FL,2.8,2.4,receiptAt)
            stores.rules.beginIntent(TpmsAdmissionIntent(sample,listOf(change),TpmsChangeRule()))
            dao.upsert(sample.copy(pressureFl=2.5))
            recoverTpmsAdmissionIntents(carId,TpmsHistoryRepository(dao),stores.rules,executionNow=receiptAt)
            assertTrue(stores.rules.pending(carId).isEmpty())
            assertEquals(1,stores.rules.recoveryIntents(carId).size)
            assertEquals(receiptAt,stores.rules.recoveryIntents(carId).single().sample.observedAt)
        } finally { stores.close(); database.close() }
    }

    @Test fun futureIntentAfterClockRollbackWaitsBeforeRoomAdmissionAndQueuePromotion() = runBlocking {
        var database=openDatabase(); var dao=database.tpmsPressureSampleDao(); var stores=Stores(temp.root)
        val future=receiptAt+1000
        val sample=TpmsPressureSample(carId,future,pressureFl=2.4,provenance=TPMS_VERIFIED_COLLECTOR)
        dao.upsert(sample.copy(observedAt=receiptAt-1000,pressureFl=2.8))
        try {
            try {
                admitTpmsObservation(carId,sample,TpmsChangeRule(),TpmsHistoryRepository(InterruptBeforeAdmissionDao(dao)),
                    stores.rules,executionNow=future)
                fail("death expected before Room write")
            } catch (_:ProcessDeath) { }
            database.close(); stores.close()
            database=openDatabase(); dao=database.tpmsPressureSampleDao(); stores=Stores(temp.root)
            recoverTpmsAdmissionIntents(carId,TpmsHistoryRepository(dao),stores.rules,executionNow=receiptAt)
            assertNull("future evidence cannot enter Room while wall clock rolled back",dao.getAt(carId,future))
            assertTrue(stores.rules.pending(carId).isEmpty())
            assertEquals(1,stores.rules.recoveryIntents(carId).size)
            recoverTpmsAdmissionIntents(carId,TpmsHistoryRepository(dao),stores.rules,executionNow=future)
            assertEquals(sample,dao.getAt(carId,future))
            assertEquals(future,stores.rules.pending(carId).single().observedAt)
        } finally { stores.close(); database.close() }
    }

    @Test fun disablingRuleWhileFinalScopeGuardSuspendsPreventsStalePressurePost() = runBlocking {
        val database=openDatabase(); val dao=database.tpmsPressureSampleDao(); val stores=Stores(temp.root)
        val history=TpmsHistoryRepository(dao)
        val sample=TpmsPressureSample(carId,receiptAt,pressureFl=2.4,provenance=TPMS_VERIFIED_COLLECTOR)
        dao.upsert(sample.copy(observedAt=receiptAt-1000,pressureFl=2.8))
        try {
            admitTpmsObservation(carId,sample,TpmsChangeRule(),history,stores.rules,executionNow=receiptAt)
            var disabled=false; var posted=0
            drainTpmsPressureChanges(carId,history,stores.rules,receiptAt,ensureCurrent={
                if(!disabled && stores.rulePreferences.data.first().asMap().keys.any { it.name.endsWith("token") }) {
                    disabled=true; stores.rules.save(carId,TpmsChangeRule(enabled=false))
                }
            }) { posted++ }
            assertTrue(disabled)
            assertEquals("removed event/token cannot be posted after the guard await",0,posted)
            assertTrue(stores.rules.pending(carId).isEmpty())
        } finally { stores.close(); database.close() }
    }

    @Test fun failedReleaseWritePreservesCancellationAndLeaseExpiryCanRecoverUnackedEvent() = runBlocking {
        val database=openDatabase(); val dao=database.tpmsPressureSampleDao(); val stores=Stores(temp.root)
        val history=TpmsHistoryRepository(dao)
        val sample=TpmsPressureSample(carId,receiptAt,pressureFl=2.4,provenance=TPMS_VERIFIED_COLLECTOR)
        dao.upsert(sample.copy(observedAt=receiptAt-1000,pressureFl=2.8))
        try {
            admitTpmsObservation(carId,sample,TpmsChangeRule(),history,stores.rules,executionNow=receiptAt)
            val cancelled=CancellationException("cancelled after durable claim")
            try {
                drainTpmsPressureChanges(carId,history,stores.rules,receiptAt) {
                    stores.failRuleWrites=true
                    throw cancelled
                }
                fail("cancellation expected")
            } catch(received:CancellationException) { assertSame(cancelled,received) }
            finally { stores.failRuleWrites=false }
            assertEquals(1,stores.rules.pending(carId).size)
            var delivered=0
            drainTpmsPressureChanges(carId,history,stores.rules,receiptAt+1) { delivered++ }
            assertEquals("failed release leaves original lease live",0,delivered)
            drainTpmsPressureChanges(carId,history,stores.rules,receiptAt+300_001) { delivered++ }
            assertEquals("expired token recovers the original unacked event",1,delivered)
            assertTrue(stores.rules.pending(carId).isEmpty())
        } finally { stores.close(); database.close() }
    }

    @Test fun ownedRecoveryStateStaysBoundedAcrossLongNaturalSequenceWithoutEvictingValidPending() = runBlocking {
        val database=openDatabase(); val history=TpmsHistoryRepository(database.tpmsPressureSampleDao()); val stores=Stores(temp.root)
        val day=24L*60*60*1000
        try {
            repeat(100) { i ->
                val at=receiptAt+i*day
                val sample=TpmsPressureSample(carId,at,pressureFl=if(i%2==0) 2.8 else 2.4,provenance=TPMS_VERIFIED_COLLECTOR)
                admitTpmsObservation(carId,sample,TpmsChangeRule(),history,stores.rules,executionNow=at)
            }
            val now=receiptAt+99*day
            val pending=stores.rules.pending(carId)
            assertEquals("all still-qualified daily transitions survive",31,pending.size)
            assertTrue(pending.all { validReceiptTime(it.observedAt,now) })
            val keys=stores.rulePreferences.data.first().asMap().keys
            assertTrue("fixed wheel watermark, latest receipt and 31 valid events",keys.size<=34)
            stores.rules.pruneExpiredRecovery(carId,now+31*day)
            assertTrue(stores.rules.pending(carId).isEmpty())
            assertEquals(1,stores.rulePreferences.data.first().asMap().size)
        } finally { stores.close(); database.close() }
    }

    private suspend fun pass(stores: Stores, details: TpmsDetails?, executionAt: Long,
        historyId:Int=carId,ensureCurrent:suspend ()->Unit={},
        teslaNotify: () -> Unit = {}, customNotify: () -> Unit = {}) {
        val repository = TpmsStateRepository(stores.tesla)
        processSuccessfulTpmsStatus(historyId, "Synthetic", details, observedAt = receiptAt,
            profile = profile, saveObservation = {}, pruneOlderThan90Days = { _, _ -> },
            detectTeslaStateChange = { _, _ -> error("must use durable repository") },
            updateTeslaState = { _, _ -> error("must use durable repository") },
            resetCustomState = stores.custom::resetForProfile,
            claimCustomAlerts = stores.custom::claimAlerts,
            commitCustomAlert = stores.custom::commitClaim, releaseCustomAlert = stores.custom::releaseClaim,
            notifyTesla = { _, _, _ -> teslaNotify() }, notifyCustom = { _, _, _ -> customNotify() },
            claimTeslaStateChange = repository::claimStateChangeForHistoryCarId,
            commitTeslaStateChange = repository::commitStateChangeForHistoryCarId,
            releaseTeslaStateChange = repository::releaseStateChangeForHistoryCarId,
            executionNow = executionAt,ensureCurrent=ensureCurrent,
            validateCustomClaim=stores.custom::isClaimCurrent,
            validateTeslaClaim=repository::isClaimCurrentForHistoryCarId)
    }

    private class Stores(private val root: File) {
        private val jobs = mutableListOf<kotlinx.coroutines.Job>()
        private fun open(name: String): DataStore<Preferences> {
            val job = SupervisorJob().also { jobs += it }
            return PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job),
                produceFile = { File(root, "$name.preferences_pb") })
        }
        val custom = TpmsCustomAlertStateStore(open("custom"))
        val tesla = TpmsStateDataStore(open("tesla"))
        val rulePreferences=open("rules")
        var failRuleWrites=false
        val rules = TpmsChangeRuleStore(object:DataStore<Preferences> {
            override val data=rulePreferences.data
            override suspend fun updateData(transform:suspend (Preferences)->Preferences):Preferences {
                if(failRuleWrites) throw IOException("synthetic preference storage write failure")
                return rulePreferences.updateData(transform)
            }
        })
        suspend fun close() { jobs.forEach { it.cancelAndJoin() } }
    }

    private fun openDatabase(): StatsDatabase = Room.databaseBuilder(
        RuntimeEnvironment.getApplication(), StatsDatabase::class.java,
        File(temp.root, "history.db").absolutePath
    ).allowMainThreadQueries().build()

    private class InterruptAfterAdmissionDao(private val real: TpmsPressureSampleDao) : TpmsPressureSampleDao by real {
        override suspend fun insertNovelCollectorObservation(sample: TpmsPressureSample): TpmsPressureSample? {
            real.insertNovelCollectorObservation(sample)
            throw ProcessDeath()
        }
    }

    private class InterruptBeforeAdmissionDao(private val real: TpmsPressureSampleDao) : TpmsPressureSampleDao by real {
        override suspend fun insertNovelCollectorObservation(sample: TpmsPressureSample): TpmsPressureSample? {
            throw ProcessDeath()
        }
    }
}
