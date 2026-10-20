package com.matelink.data.repository

import android.app.Application
import androidx.room.withTransaction
import com.matelink.data.api.models.*
import com.matelink.data.local.ChargeCostOverrideStore
import com.matelink.data.local.HistoryIdentityUnavailableException
import com.matelink.data.local.dao.DriveSummaryDao
import com.matelink.data.local.dao.ChargeSummaryDao
import com.matelink.data.local.dao.AggregateDao
import com.matelink.data.local.entity.DriveSummary
import com.matelink.data.local.entity.DriveDetailAggregate
import com.matelink.data.local.entity.ChargeDetailAggregate
import com.matelink.data.sync.HistoryResourceFixture
import com.matelink.data.sync.SyncRepository
import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.model.YearFilter
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.util.TimeZone

/** Real Room/resolver/session mutations. No product network is allowed by the fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[28])
class StatsReadScopeAndYearTest {
    @get:Rule val temp=TemporaryFolder()

    private fun repository(f:HistoryResourceFixture,drives:DriveSummaryDao=f.db.driveSummaryDao(),
        aggregates:AggregateDao=f.db.aggregateDao(),
        charges:ChargeSummaryDao=f.db.chargeSummaryDao()):StatsRepository {
        val geo=SyncRepository::class.java.getDeclaredField("geocodingRepository").apply { isAccessible=true }
            .get(f.repo) as GeocodingRepository
        return StatsRepository(drives,charges,aggregates,f.syncManager,geo,
            f.vehicleContexts,ChargeCostOverrideStore(f.db.chargeCostOverrideDao(),f.settings),f.db,f.settings)
    }
    private fun drive(id:Int,car:Int,start:String,distance:Double,energy:Double,legacy:Boolean=false)=DriveData(
        id,startDate=start,endDate=Instant.parse(start).plusSeconds(60).toString(),
        odometerDetails=DriveOdometerDetails(distance=distance),energyConsumedNet=energy,
        source="teslamate_archive",qualityState=if(legacy) "incomplete" else "observed",
        qualityReason=if(legacy) "remote_quality_unavailable" else "api",lengthUnit="km"
    ).toSyncSummary(car)!!
    private fun charge(id:Int,car:Int,start:String,energy:Double?,cost:Double?,legacy:Boolean=false)=ChargeData(
        id,startDate=start,endDate=Instant.parse(start).plusSeconds(60).toString(),chargeEnergyAdded=energy,cost=cost,
        source="teslamate_archive",qualityState=if(legacy) "incomplete" else "observed",
        qualityReason=if(legacy) "remote_quality_unavailable" else "api",currencyCode="CNY",
        chargeType="ac"
    ).toSyncSummary(car)!!

    private class GateDao(private val real:DriveSummaryDao):DriveSummaryDao by real {
        val entered=CompletableDeferred<Unit>(); val resume=CompletableDeferred<Unit>()
        private var first=true
        val ids=mutableListOf<Int>()
        private suspend fun gate(car:Int) {
            ids+=car
            if(first) { first=false; entered.complete(Unit); resume.await() }
        }
        override suspend fun count(carId:Int):Int { val row=real.count(carId); gate(carId); return row }
        override suspend fun getAllChronological(carId:Int):List<DriveSummary> {
            val rows=real.getAllChronological(carId); gate(carId); return rows
        }
        override suspend fun getHistoryDrivesInRange(carId:Int,startDate:String,endDate:String):List<DriveSummary> {
            val rows=real.getHistoryDrivesInRange(carId,startDate,endDate); gate(carId); return rows
        }
        override suspend fun getYears(carId:Int):List<Int> { val rows=real.getYears(carId); gate(carId); return rows }
    }

    @Test fun accountSwitchDuringFirstRoomReadCannotReturnMixedQuickAndCoverage() = runBlocking(Dispatchers.IO) {
        val f=HistoryResourceFixture(File(temp.root,"stats-scope.db"))
        try {
            f.initialize(cloud=true); SettingsRepository(f.context).setMockMode(false)
            val a=f.localId(1)
            f.db.driveSummaryDao().upsert(drive(1,a,"2026-01-02T00:00:00Z",10.0,1.0))
            f.sessions.save("synthetic-access","synthetic-refresh",3600,"stats-account-b")
            val scopeB=f.vehicleContexts.captureReadScope()
            val b=f.vehicleContexts.resolveVerifiedHistoryCar(CarData(1,vehicleUid="stats-vehicle-b"),scopeB).localHistoryCarId
            f.db.driveSummaryDao().upsertAll(listOf(drive(1,b,"2026-01-02T00:00:00Z",100.0,10.0),
                drive(2,b,"2026-01-03T00:00:00Z",200.0,20.0)))
            f.sessions.save("synthetic-access","synthetic-refresh",3600,"synthetic-account")
            val gate=GateDao(f.db.driveSummaryDao())
            val stats=repository(f,gate)
            val pending=async { runCatching { stats.getStats(1,YearFilter.AllTime) } }
            withTimeout(10_000) { gate.entered.await() }
            f.sessions.save("synthetic-access","synthetic-refresh",3600,"stats-account-b")
            gate.resume.complete(Unit)
            assertTrue("source switch must reject the old request",pending.await().exceptionOrNull() is HistoryIdentityUnavailableException)
            assertTrue("old request cannot begin reads in another local namespace",gate.ids.all { it==a })
            val fresh=repository(f).getStats(1,YearFilter.AllTime)
            assertEquals(2,fresh.quickStats.totalDrives)
            assertEquals(300.0,fresh.quickStats.totalDistanceKm,0.001)
            assertEquals(30.0,requireNotNull(fresh.quickStats.totalEnergyConsumedKwh),0.001)
        } finally { f.close() }
    }

    @Test fun availableYearsAlsoRejectsAnAccountSwitchDuringItsRead() = runBlocking(Dispatchers.IO) {
        val f=HistoryResourceFixture(File(temp.root,"stats-years-scope.db"))
        try {
            f.initialize(cloud=true); SettingsRepository(f.context).setMockMode(false)
            val gate=GateDao(f.db.driveSummaryDao())
            val request=async { runCatching { repository(f,gate).getAvailableYears(1) } }
            withTimeout(10_000) { gate.entered.await() }
            f.sessions.save("synthetic-access","synthetic-refresh",3600,"other-account")
            gate.resume.complete(Unit)
            assertTrue(request.await().exceptionOrNull() is HistoryIdentityUnavailableException)
        } finally { f.close() }
    }

    @Test fun sameScopeRemoteIdRebindDuringRoomReadCannotPublishPreviousVehicle() = runBlocking(Dispatchers.IO) {
        val f=HistoryResourceFixture(File(temp.root,"stats-vehicle-rebind.db"))
        try {
            f.initialize(cloud=true); SettingsRepository(f.context).setMockMode(false)
            val original=f.localId(1)
            val gate=GateDao(f.db.driveSummaryDao())
            val result=async { runCatching { repository(f,gate).getStats(1,YearFilter.AllTime) } }
            withTimeout(10_000) { gate.entered.await() }
            val scope=f.vehicleContexts.captureReadScope()
            val replacement=f.vehicleContexts.resolveVerifiedHistoryCar(CarData(1,vehicleUid="replacement-stats-vehicle"),scope)
            assertNotEquals(original,replacement.localHistoryCarId)
            gate.resume.complete(Unit)
            assertTrue(result.await().exceptionOrNull() is HistoryIdentityUnavailableException)
            assertTrue(gate.ids.all { it==original })
        } finally { f.close() }
    }

    @Test fun switchDuringDeepAggregateReadCannotPublishEarlierQuickStats() = runBlocking(Dispatchers.IO) {
        val f=HistoryResourceFixture(File(temp.root,"stats-deep-scope.db"))
        try {
            f.initialize(cloud=true); SettingsRepository(f.context).setMockMode(false)
            val car=f.localId(1)
            f.db.driveSummaryDao().upsert(drive(1,car,"2026-06-01T12:00:00Z",10.0,1.0))
            val entered=CompletableDeferred<Unit>(); val resume=CompletableDeferred<Unit>()
            val real=f.db.aggregateDao()
            val aggregate=object:AggregateDao by real {
                override suspend fun getDriveAggregatesForStats(carId:Int,driveIds:List<Int>):List<DriveDetailAggregate> {
                    val rows=real.getDriveAggregatesForStats(carId,driveIds)
                    entered.complete(Unit); resume.await()
                    return rows
                }
            }
            val result=async { runCatching { repository(f,aggregates=aggregate).getStats(1,YearFilter.AllTime) } }
            withTimeout(10_000) { entered.await() }
            f.sessions.save("synthetic-access","synthetic-refresh",3600,"stats-other-account")
            resume.complete(Unit)
            assertTrue(result.await().exceptionOrNull() is HistoryIdentityUnavailableException)
        } finally { f.close() }
    }

    @Test fun deepYearEastOfUtcUsesTheQuickLocalCohort() = runBlocking(Dispatchers.IO) {
        assertDeepLocalYear("Asia/Shanghai","2025-12-31T16:30:00Z","2026-12-31T16:30:00Z")
    }

    @Test fun deepYearWestOfUtcUsesTheQuickLocalCohort() = runBlocking(Dispatchers.IO) {
        assertDeepLocalYear("America/Los_Angeles","2027-01-01T00:30:00Z","2026-01-01T00:30:00Z")
    }

    @Test fun deepYearMixedStoredOffsetsUseInstantsInsteadOfSqlStringYear() = runBlocking(Dispatchers.IO) {
        assertDeepLocalYear("Asia/Shanghai","2026-01-01T00:30:00+08:00","2026-12-31T20:30:00-04:00")
    }

    private suspend fun assertDeepLocalYear(zone:String,included:String,excluded:String) {
        val previousZone=TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        val f=HistoryResourceFixture(File(temp.root,"deep-${zone.substringAfterLast('/')}.db"))
        try {
            f.initialize(cloud=true); SettingsRepository(f.context).setMockMode(false)
            val id=f.localId(1)
            val middle="2026-06-01T12:00:00Z"
            f.db.driveSummaryDao().upsertAll(listOf(drive(1,id,included,10.0,2.0),
                drive(2,id,excluded,999.0,999.0),drive(3,id,middle,7.0,1.0,true)))
            f.db.chargeSummaryDao().upsertAll(listOf(charge(1,id,included,10.0,5.0),
                charge(2,id,excluded,999.0,999.0),charge(3,id,middle,4.0,2.0,true)))
            fun driveAggregate(record:Int,height:Int,temp:Double,country:String)=DriveDetailAggregate(
                driveId=record,carId=id,schemaVersion=1,computedAt=1,
                maxElevation=height,minElevation=height-50,startElevation=0,endElevation=height,
                elevationGain=height,elevationLoss=0,hasElevationData=true,
                maxInsideTemp=temp+10,minInsideTemp=temp-10,maxOutsideTemp=temp,minOutsideTemp=temp-5,
                maxPower=null,minPower=null,climateOnPositions=0,positionCount=2,startCountryCode=country)
            fun chargeAggregate(record:Int,power:Int,temp:Double,dc:Boolean)=ChargeDetailAggregate(
                chargeId=record,carId=id,schemaVersion=1,computedAt=1,isFastCharger=dc,
                fastChargerBrand=null,connectorType=null,maxChargerPower=power,maxChargerVoltage=null,
                maxChargerCurrent=null,chargerPhases=null,maxOutsideTemp=temp,minOutsideTemp=temp-5,chargePointCount=2)
            f.db.aggregateDao().upsertDriveAggregates(listOf(driveAggregate(1,100,10.0,"CN"),
                driveAggregate(2,999,99.0,"US"),driveAggregate(3,200,20.0,"JP")))
            f.db.aggregateDao().upsertChargeAggregates(listOf(chargeAggregate(1,11,10.0,false),
                chargeAggregate(2,999,99.0,true),chargeAggregate(3,22,20.0,false)))
            val stats=repository(f)
            val full=stats.getStats(1,YearFilter.Year(2026))
            assertEquals(2,full.quickStats.totalDrives)
            assertEquals(2,full.quickStats.totalCharges)
            val deep=requireNotNull(full.deepStats)
            assertEquals(200,deep.maxElevationM)
            assertEquals(3,deep.driveWithMaxElevation!!.driveId)
            assertEquals(middle,deep.driveWithMaxElevation!!.date)
            assertEquals(3,deep.driveWithMostClimbing!!.driveId)
            assertEquals(20.0,requireNotNull(deep.maxOutsideTempDrivingC),0.001)
            assertEquals(3,deep.hottestDrive!!.driveId)
            assertEquals(1,deep.coldestDrive!!.driveId)
            assertNull(deep.minElevationM); assertNull(deep.driveWithMinElevation)
            assertEquals(5.0,requireNotNull(deep.minOutsideTempDrivingC),0.001)
            assertEquals(30.0,requireNotNull(deep.maxCabinTempC),0.001)
            assertEquals(0.0,requireNotNull(deep.minCabinTempC),0.001)
            assertEquals(20.0,requireNotNull(deep.maxOutsideTempChargingC),0.001)
            assertEquals(3,deep.hottestCharge!!.chargeId)
            assertEquals(1,deep.coldestCharge!!.chargeId)
            assertEquals(5.0,requireNotNull(deep.minOutsideTempChargingC),0.001)
            assertEquals(22,deep.maxChargerPowerKw)
            assertEquals(3,deep.chargeWithMaxPower!!.chargeId)
            assertEquals(2,deep.acChargeCount); assertEquals(0,deep.dcChargeCount)
            assertEquals(14.0,deep.acChargeEnergyKwh,0.001); assertEquals(0.0,deep.dcChargeEnergyKwh,0.001)
            assertEquals(2,deep.driveDetailsProcessed); assertEquals(2,deep.chargeDetailsProcessed)
            assertEquals(2,deep.countriesVisitedCount)
            val direct=requireNotNull(stats.getDeepStats(1,YearFilter.Year(2026)))
            assertEquals(deep,direct)
            val all=requireNotNull(stats.getDeepStats(1,YearFilter.AllTime))
            assertEquals(999,all.maxElevationM)
            assertEquals(999,all.maxChargerPowerKw)
            assertEquals(3,all.driveDetailsProcessed)
        } finally { f.close(); TimeZone.setDefault(previousZone) }
    }

    @Test fun deepYearBatchesRealRoomIdsAndStopsImmediatelyWhenABatchChangesScope() = runBlocking(Dispatchers.IO) {
        val f=HistoryResourceFixture(File(temp.root,"deep-batches.db"))
        try {
            f.initialize(cloud=true); SettingsRepository(f.context).setMockMode(false)
            val car=f.localId(1)
            val at="2026-06-01T12:00:00Z"
            f.db.driveSummaryDao().upsertAll((1..401).map { drive(it,car,at,1.0,0.1) })
            f.db.aggregateDao().upsertDriveAggregates((1..401).map { record ->
                DriveDetailAggregate(record,car,1,1,record,null,null,null,null,null,true,
                    null,null,null,null,null,null,0,2)
            })
            val real=f.db.aggregateDao()
            val batches=mutableListOf<List<Int>>()
            var chargeReads=0
            var switchAfterRead=false
            val aggregate=object:AggregateDao by real {
                override suspend fun getDriveAggregatesForStats(carId:Int,driveIds:List<Int>):List<DriveDetailAggregate> {
                    assertEquals(car,carId)
                    batches+=driveIds.toList()
                    val rows=real.getDriveAggregatesForStats(carId,driveIds)
                    if(switchAfterRead) f.sessions.save("synthetic-access","synthetic-refresh",3600,"batch-other-account")
                    return rows
                }
                override suspend fun getChargeAggregatesForStats(carId:Int,chargeIds:List<Int>):List<ChargeDetailAggregate> {
                    chargeReads++
                    return real.getChargeAggregatesForStats(carId,chargeIds)
                }
                override suspend fun getDriveAggregate(carId:Int,driveId:Int):DriveDetailAggregate? =
                    error("Year statistics must not perform per-record aggregate reads")
            }
            var summaryReads=0
            val realDrives=f.db.driveSummaryDao()
            val drives=object:DriveSummaryDao by realDrives {
                override suspend fun getHistoryDrivesInRange(carId:Int,startDate:String,endDate:String):List<DriveSummary> {
                    summaryReads++
                    return realDrives.getHistoryDrivesInRange(carId,startDate,endDate)
                }
            }
            val result=repository(f,drives,aggregate).getStats(1,YearFilter.Year(2026))
            assertEquals(401,result.quickStats.totalDrives)
            assertEquals(401,result.deepStats!!.driveDetailsProcessed)
            assertEquals(401,result.deepStats!!.maxElevationM)
            assertEquals(listOf(400,1),batches.map { it.size })
            assertEquals((1..401).toSet(),batches.flatten().toSet())
            assertEquals(0,chargeReads)
            assertEquals(1,summaryReads) // Deep reuses the already selected Quick/coverage cohort.
            assertTrue(f.calls.isEmpty())
            batches.clear(); switchAfterRead=true
            val rejected=runCatching { repository(f,drives,aggregate).getStats(1,YearFilter.Year(2026)) }
            assertTrue(rejected.exceptionOrNull() is HistoryIdentityUnavailableException)
            assertEquals(1,batches.size)
        } finally { f.close() }
    }

    @Test fun annualCardsCoverageMonthlyAndDeepComeFromOneRoomRevision() = runBlocking(Dispatchers.IO) {
        val f=HistoryResourceFixture(File(temp.root,"stats-atomic-room.db"))
        try {
            f.initialize(cloud=true); SettingsRepository(f.context).setMockMode(false)
            f.settings.saveCurrency("CNY")
            val car=f.localId(1)
            val firstAt="2026-06-01T12:00:00Z"
            val secondAt="2026-06-02T12:00:00Z"
            val real=f.db.chargeSummaryDao()
            real.upsertAll(listOf(charge(1,car,firstAt,10.0,5.0),
                charge(2,car,secondAt,null,null)))
            fun aggregate(id:Int,power:Int)=ChargeDetailAggregate(id,car,1,1,false,
                null,null,power,null,null,null,null,null,2)
            f.db.aggregateDao().upsertChargeAggregate(aggregate(1,11))
            val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
            val gated=object:ChargeSummaryDao by real {
                override suspend fun getHistoryChargesInRange(carId:Int,startDate:String,endDate:String):List<com.matelink.data.local.entity.ChargeSummary> {
                    val rows=real.getHistoryChargesInRange(carId,startDate,endDate)
                    entered.complete(Unit)
                    release.await()
                    return rows
                }
            }
            val stats=repository(f,charges=gated)
            val pending=async { stats.getAnnualSnapshot(1,2026) }
            withTimeout(10_000) { entered.await() }
            val writerStarted=CompletableDeferred<Unit>(); val writerDone=CompletableDeferred<Unit>()
            val writer=async(Dispatchers.IO) {
                writerStarted.complete(Unit)
                f.db.withTransaction {
                    real.upsert(charge(2,car,secondAt,7.0,2.0))
                    f.db.aggregateDao().upsertChargeAggregate(aggregate(2,22))
                }
                writerDone.complete(Unit)
            }
            try {
                withTimeout(10_000) { writerStarted.await() }
                // Old separate DAO reads let the writer commit here and produce a hybrid report.
                assertNull("writer crossed an open stats snapshot",withTimeoutOrNull(400) { writerDone.await() })
            } finally { release.complete(Unit) }
            val before=withTimeout(10_000) { pending.await() }
            withTimeout(10_000) { writer.await() }
            val after=stats.getAnnualSnapshot(1,2026)
            assertEquals(10.0,before.stats.quickStats.totalEnergyAddedKwh,0.001)
            assertEquals(5.0,requireNotNull(before.stats.quickStats.totalCost),0.001)
            assertEquals(1,before.stats.analysisCoverage!!.chargeEnergySampleCount)
            assertEquals(10.0,requireNotNull(before.monthlyCharges.single().totalEnergy),0.001)
            assertEquals(11,before.stats.deepStats!!.maxChargerPowerKw)
            assertEquals(1,before.stats.deepStats!!.chargeDetailsProcessed)
            assertEquals(17.0,after.stats.quickStats.totalEnergyAddedKwh,0.001)
            assertEquals(7.0,requireNotNull(after.stats.quickStats.totalCost),0.001)
            assertEquals(2,after.stats.analysisCoverage!!.chargeEnergySampleCount)
            assertEquals(17.0,requireNotNull(after.monthlyCharges.single().totalEnergy),0.001)
            assertEquals(22,after.stats.deepStats!!.maxChargerPowerKw)
            assertEquals(2,after.stats.deepStats!!.chargeDetailsProcessed)
            assertTrue(f.calls.isEmpty())
        } finally { f.close() }
    }

    @Test fun explicitUnknownModeDoesNotBecomeAcFromFalseAggregateFlag() = runBlocking(Dispatchers.IO) {
        val f=HistoryResourceFixture(File(temp.root,"stats-unknown-ac.db"))
        try {
            f.initialize(cloud=true); SettingsRepository(f.context).setMockMode(false)
            val car=f.localId(1)
            val at="2026-06-01T12:00:00Z"
            val raw=ChargeData(1,startDate=at,endDate=Instant.parse(at).plusSeconds(60).toString(),
                chargeEnergyAdded=5.0,source="teslamate_archive",qualityState="observed",
                energyContract=EnergyContract(chargeMode="unknown",chargeModeEvidence="insufficient"))
            f.db.chargeSummaryDao().upsert(raw.toSyncSummary(car)!!)
            val noModeAt="2026-06-02T12:00:00Z"
            f.db.chargeSummaryDao().upsert(ChargeData(2,startDate=noModeAt,
                endDate=Instant.parse(noModeAt).plusSeconds(60).toString(),
                chargeEnergyAdded=4.0,source="teslamate_archive",qualityState="observed")
                .toSyncSummary(car)!!)
            f.db.aggregateDao().upsertChargeAggregate(ChargeDetailAggregate(1,car,1,1,false,
                null,null,56,null,null,null,null,null,2))
            f.db.aggregateDao().upsertChargeAggregate(ChargeDetailAggregate(2,car,1,1,false,
                null,null,22,null,null,null,null,null,2))
            val deep=requireNotNull(repository(f).getStats(1,YearFilter.Year(2026)).deepStats)
            assertEquals(2,deep.chargeDetailsProcessed)
            assertEquals(0,deep.acChargeCount)
            assertEquals(0,deep.dcChargeCount)
            assertEquals(0.0,deep.acChargeEnergyKwh,0.001)
        } finally { f.close() }
    }

    @Test fun qualifiedManualTotalDrivesCoverageAndMostExpensiveRecord() = runBlocking(Dispatchers.IO) {
        val f=HistoryResourceFixture(File(temp.root,"stats-manual-record.db"))
        try {
            f.initialize(cloud=true); SettingsRepository(f.context).setMockMode(false)
            f.settings.saveCurrency("CNY")
            val car=f.localId(1)
            val at="2026-06-01T12:00:00Z"
            val later="2026-06-02T12:00:00Z"
            f.db.chargeSummaryDao().upsertAll(listOf(charge(1,car,at,10.0,5.0),
                charge(2,car,later,10.0,20.0)))
            val raw=ChargeData(1,startDate=at,endDate=Instant.parse(at).plusSeconds(60).toString(),
                chargeEnergyAdded=10.0,cost=5.0,source="teslamate_archive",
                qualityState="observed",qualityReason="api",currencyCode="CNY")
            val store=ChargeCostOverrideStore(f.db.chargeCostOverrideDao(),f.settings)
            assertTrue(store.saveBound(car,raw,30.0,"CNY",0L))
            val result=repository(f).getStats(1,YearFilter.Year(2026))
            assertEquals(50.0,requireNotNull(result.quickStats.totalCost),0.001)
            assertEquals(2,result.analysisCoverage!!.chargeCostSampleCount)
            assertEquals(1,result.quickStats.mostExpensiveCharge!!.chargeId)
            assertEquals(30.0,result.quickStats.mostExpensiveCharge!!.cost!!,0.001)
            assertEquals(1,result.quickStats.mostExpensivePerKwhCharge!!.chargeId)
            assertEquals(30.0,result.quickStats.mostExpensivePerKwhCharge!!.cost!!,0.001)
            assertEquals(10.0,result.quickStats.mostExpensivePerKwhCharge!!.energyAdded,0.001)
        } finally { f.close() }
    }

    @Test fun legacyAmountMigratesBeforeSnapshotButCannotClaimCurrencyOrSession() = runBlocking(Dispatchers.IO) {
        val f=HistoryResourceFixture(File(temp.root,"stats-pending-legacy-cost.db"))
        try {
            f.initialize(cloud=true); SettingsRepository(f.context).setMockMode(false)
            f.settings.saveCurrency("CNY")
            val car=f.localId(1)
            f.db.chargeSummaryDao().upsert(charge(1,car,"2026-06-01T12:00:00Z",10.0,5.0))
            f.settings.saveChargeTotalOverride("$car:1",99.0)
            val result=repository(f).getStats(1,YearFilter.Year(2026))
            assertEquals(5.0,requireNotNull(result.quickStats.totalCost),0.001)
            assertEquals(5.0,result.quickStats.mostExpensiveCharge!!.cost!!,0.001)
            assertTrue(f.db.chargeCostOverrideDao().getAll().any {
                it.carId==car && it.chargeId==1 && it.sessionKey=="legacy" && it.manualTotalAmount==99.0
            })
        } finally { f.close() }
    }

    @Test fun providerPriceWithoutCurrencyRemainsUnknownAfterRoomRoundTrip() = runBlocking(Dispatchers.IO) {
        val f=HistoryResourceFixture(File(temp.root,"stats-unknown-currency.db"))
        try {
            f.initialize(cloud=true); SettingsRepository(f.context).setMockMode(false)
            f.settings.saveCurrency("CNY")
            val car=f.localId(1)
            val at="2026-06-01T12:00:00Z"
            f.db.chargeSummaryDao().upsert(ChargeData(1,startDate=at,
                endDate=Instant.parse(at).plusSeconds(60).toString(),chargeEnergyAdded=10.0,
                cost=9.0,source="teslamate_archive",qualityState="observed")
                .toSyncSummary(car)!!)
            val result=repository(f).getStats(1,YearFilter.Year(2026))
            assertEquals(10.0,result.quickStats.totalEnergyAddedKwh,0.001)
            assertNull(result.quickStats.totalCost)
            assertEquals(0,result.analysisCoverage!!.chargeCostSampleCount)
            assertNull(result.quickStats.mostExpensiveCharge)
        } finally { f.close() }
    }

    @Test fun localYearCountsCoverageTotalsAndMonthlyChartsUseOneCohortIncludingLegacyZeroAndRecovery() = runBlocking(Dispatchers.IO) {
        val previousZone=TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        val f=HistoryResourceFixture(File(temp.root,"stats-local-year.db"))
        try {
            f.initialize(cloud=true); SettingsRepository(f.context).setMockMode(false)
            f.settings.saveCurrency("CNY")
            val id=f.localId(1)
            f.db.driveSummaryDao().upsertAll(listOf(
                drive(1,id,"2025-12-31T16:30:00Z",10.0,2.0),drive(2,id,"2026-02-01T00:00:00Z",0.0,0.0),
                drive(3,id,"2026-03-01T00:00:00Z",5.0,-0.5),drive(4,id,"2026-12-31T16:30:00Z",999.0,999.0),
                drive(5,id,"2026-04-01T00:00:00Z",7.0,1.0,true)))
            f.db.chargeSummaryDao().upsertAll(listOf(
                charge(1,id,"2025-12-31T16:40:00Z",10.0,5.0),charge(2,id,"2026-02-01T00:00:00Z",0.0,0.0),
                charge(3,id,"2026-03-01T00:00:00Z",null,null),charge(4,id,"2026-12-31T16:40:00Z",999.0,999.0),
                charge(5,id,"2026-04-01T00:00:00Z",4.0,2.0,true)))
            val stats=repository(f)
            val result=stats.getStats(1,YearFilter.Year(2026))
            assertEquals(4,result.quickStats.totalDrives)
            assertEquals(4,result.quickStats.totalCharges)
            assertEquals(1,result.quickStats.longestDrive!!.driveId)
            assertEquals(1,result.quickStats.biggestCharge!!.chargeId)
            assertNull(result.quickStats.fastestDrive) // Missing speed is not an observed zero.
            assertEquals(4,result.quickStats.totalDrivingDays)
            assertEquals(4,result.analysisCoverage!!.driveRecordCount)
            assertEquals(4,result.analysisCoverage!!.chargeRecordCount)
            assertEquals(22.0,result.quickStats.totalDistanceKm,0.001)
            assertEquals(2.5,requireNotNull(result.quickStats.totalEnergyConsumedKwh),0.001)
            assertEquals(14.0,result.quickStats.totalEnergyAddedKwh,0.001)
            assertEquals(7.0,requireNotNull(result.quickStats.totalCost),0.001)
            val drives=stats.getMonthlyDriveAggregation(1,2026)
            val charges=stats.getMonthlyChargeAggregation(1,2026)
            assertEquals(result.quickStats.totalDrives,drives.sumOf { it.driveCount })
            assertEquals(result.quickStats.totalCharges,charges.sumOf { it.chargeCount })
            assertEquals(22.0,drives.mapNotNull { it.totalDistance }.sum(),0.001)
            assertEquals(2.5,drives.mapNotNull { it.totalEnergy }.sum(),0.001)
            assertEquals(14.0,charges.mapNotNull { it.totalEnergy }.sum(),0.001)
            assertEquals(listOf(2027,2026),stats.getAvailableYears(1))
        } finally { f.close(); TimeZone.setDefault(previousZone) }
    }
}
