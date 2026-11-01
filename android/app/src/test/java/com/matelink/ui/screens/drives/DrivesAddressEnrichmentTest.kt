package com.matelink.ui.screens.drives

import com.matelink.data.api.models.DriveData
import com.matelink.data.repository.DriveAddressKey
import com.matelink.data.repository.DriveAddressTarget
import com.matelink.data.repository.enrichDriveAddresses
import com.matelink.data.repository.enrichDriveAddressesIncrementally
import com.matelink.data.repository.resetCompletedDriveAddressAttempts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class DrivesAddressEnrichmentTest {
    @Test
    fun missingListAddressesUseObservedRouteEndpoints() = kotlinx.coroutines.test.runTest {
        val drive = DriveData(
            driveId = 7,
            startLatitude = 31.2,
            startLongitude = 121.4,
            endLatitude = 31.3,
            endLongitude = 121.5
        )

        val enriched = enrichDriveAddresses(listOf(drive)) { latitude, longitude ->
            "${latitude},${longitude}"
        }.single()

        assertEquals("31.2,121.4", enriched.startAddress)
        assertEquals("31.3,121.5", enriched.endAddress)
    }

    @Test
    fun existingOrMissingEndpointsRemainTruthful() = kotlinx.coroutines.test.runTest {
        val drive = DriveData(
            driveId = 8,
            startAddress = "已有起点",
            startLatitude = null,
            startLongitude = null,
            endLatitude = null,
            endLongitude = null
        )

        var calls = 0
        val enriched = enrichDriveAddresses(listOf(drive)) { _, _ ->
            calls++
            "不应覆盖"
        }.single()

        assertEquals("已有起点", enriched.startAddress)
        assertEquals(null, enriched.endAddress)
        assertEquals(0, calls)
    }

    @Test
    fun invalidCoordinatesAreNotSentToGeocoder() = kotlinx.coroutines.test.runTest {
        var calls = 0
        val drive = DriveData(
            driveId = 9,
            startLatitude = 91.0,
            startLongitude = 181.0,
            endLatitude = 0.0,
            endLongitude = 0.0
        )

        val enriched = enrichDriveAddresses(listOf(drive)) { _, _ ->
            calls++
            "不应调用"
        }.single()

        assertEquals(null, enriched.startAddress)
        assertEquals(null, enriched.endAddress)
        assertEquals(0, calls)
    }

    @Test fun incrementalLookupIsBoundedAndDoesNotReplaceNewerOrForeignRows() = runBlocking {
        val rows = (1..12).map { id -> DriveData(id,
            startDate = "2026-10-30T01:00:00Z", endDate = "2026-10-30T01:01:00Z",
            source = "teslamate_archive", startLatitude = 30.0 + id / 100.0, startLongitude = 120.0) }
        val targets = rows.map { DriveAddressTarget(it,
            DriveAddressKey("identity-${it.driveId}", "receipt", true, it.startLatitude!!, it.startLongitude!!)) }
        val attempted = mutableMapOf<DriveAddressKey, Any>()
        var displayed = rows.map { if (it.driveId == 1) it.copy(startAddress = "新地址")
            else if (it.driveId == 2) it.copy(source = "telemetry_mqtt") else it }
        var lookups = 0
        var publications = 0
        suspend fun batch() = enrichDriveAddressesIncrementally(targets, attempted, ensureCurrent = {}, reverseGeocode = { _, _ ->
            lookups++
            "解析地址$lookups"
        }) { targets, address ->
            displayed = applyDriveAddressResult(displayed, targets, address)
            publications++
        }
        batch()
        assertEquals(12, lookups)
        assertEquals(12, publications)
        assertEquals("新地址", displayed.first { it.driveId == 1 }.startAddress)
        assertNull(displayed.first { it.driveId == 2 }.startAddress)
        assertEquals("解析地址3", displayed.first { it.driveId == 3 }.startAddress)
        assertEquals("解析地址12", displayed.last().startAddress)
        batch()
        assertEquals(12, lookups)
    }

    @Test fun failedLookupWaitsForExplicitRetryAndChangedReceiptIsEligible() = runBlocking {
        val drive = DriveData(1, startDate = "2026-10-30T01:00:00Z", endDate = "2026-10-30T01:01:00Z",
            source = "teslamate_archive", startLatitude = 30.1, startLongitude = 120.0)
        val target = DriveAddressTarget(drive, DriveAddressKey("identity", "old", true, 30.1, 120.0))
        val attempted = mutableMapOf<DriveAddressKey, Any>()
        var calls = 0
        suspend fun batch(item: DriveAddressTarget) = enrichDriveAddressesIncrementally(listOf(item), attempted,
            ensureCurrent = {}, reverseGeocode = { _, _ -> calls++; null }) { _, _ -> }
        batch(target)
        batch(target)
        assertEquals(1, calls)
        batch(target.copy(key = target.key.copy(receiptDigest = "new")))
        assertEquals(2, calls)
        resetCompletedDriveAddressAttempts(attempted)
        batch(target)
        assertEquals(3, calls)
    }

    @Test fun frozenSnapshotAutomaticallyAdvancesInBatchesAndStopsAtForty() = runBlocking {
        val targets = (1..45).map { id ->
            val drive = DriveData(id, startLatitude = 30.0 + id / 100.0, startLongitude = 120.0)
            DriveAddressTarget(drive, DriveAddressKey("identity-$id", "receipt", true, drive.startLatitude!!, 120.0))
        }
        val attempted = mutableMapOf<DriveAddressKey, Any>()
        val times = mutableListOf<Long>()
        suspend fun batch() = enrichDriveAddressesIncrementally(targets, attempted, ensureCurrent = {},
            reverseGeocode = { _, _ -> times += System.nanoTime(); "地点" }) { _, _ -> }
        batch()
        assertEquals(40, times.size)
        assertTrue(times[10] - times[9] >= TimeUnit.MILLISECONDS.toNanos(70))
        assertTrue(times[20] - times[19] >= TimeUnit.MILLISECONDS.toNanos(70))
        assertTrue(times[30] - times[29] >= TimeUnit.MILLISECONDS.toNanos(70))
        batch()
        assertEquals(45, times.size)
    }

    @Test fun cancellationBetweenBatchesDoesNotStartAnotherLookup() = runBlocking {
        val targets = (1..25).map { id ->
            val drive = DriveData(id, startLatitude = 30.0 + id / 100.0, startLongitude = 120.0)
            DriveAddressTarget(drive, DriveAddressKey("identity-$id", "receipt", true, drive.startLatitude!!, 120.0))
        }
        val attempted = mutableMapOf<DriveAddressKey, Any>()
        val calls = AtomicInteger()
        val reached = CompletableDeferred<Unit>()
        val job = launch {
            enrichDriveAddressesIncrementally(targets, attempted, ensureCurrent = {}, reverseGeocode = { _, _ ->
                if (calls.incrementAndGet() == 10) reached.complete(Unit)
                "地点"
            }) { _, _ -> }
        }
        withTimeout(10_000) { reached.await() }
        job.cancelAndJoin()
        assertEquals(10, calls.get())
        assertTrue(targets.take(9).all { it.key in attempted })
        assertTrue(targets.drop(10).none { it.key in attempted })
    }

    @Test fun identityChangeRejectsInFlightLocationAndItsAttempt() = runBlocking {
        val targets = (1..15).map { id ->
            val drive = DriveData(id, startLatitude = 30.0 + id / 100.0, startLongitude = 120.0)
            DriveAddressTarget(drive, DriveAddressKey("identity-$id", "receipt", true, drive.startLatitude!!, 120.0))
        }
        val attempted = mutableMapOf<DriveAddressKey, Any>()
        val current = AtomicBoolean(true)
        var calls = 0
        var published = 0
        try {
            enrichDriveAddressesIncrementally(targets, attempted,
                ensureCurrent = { if (!current.get()) throw CancellationException("account changed") },
                reverseGeocode = { _, _ -> calls++; if (calls == 11) current.set(false); "地点" }) { _, _ -> published++ }
            fail("identity change must stop the frozen snapshot")
        } catch (_: CancellationException) { }
        assertEquals(11, calls)
        assertEquals(10, published)
        assertFalse(attempted.containsKey(targets[10].key))
    }

    @Test fun blockedSdkCallsKeepTwoPermitsAcrossCancellationAndCanRetryAfterRelease() = runBlocking {
        fun target(id: Int): DriveAddressTarget {
            val drive = DriveData(id, startLatitude = 30.0 + id / 100.0, startLongitude = 120.0)
            return DriveAddressTarget(drive, DriveAddressKey("vehicle-$id", "receipt", true, drive.startLatitude!!, 120.0))
        }
        val releaseOld = CountDownLatch(1)
        val releaseNew = CountDownLatch(1)
        val enteredOld = CompletableDeferred<Unit>()
        val enteredNew = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val started = AtomicInteger()
        val currentCar = AtomicInteger(1)
        val oldPublished = AtomicInteger()
        val newPublished = AtomicInteger()
        val retryPublished = AtomicInteger()
        val oldAttempts = mutableMapOf<DriveAddressKey, Any>()
        val newAttempts = mutableMapOf<DriveAddressKey, Any>()
        val retryAttempts = mutableMapOf<DriveAddressKey, Any>()
        suspend fun block(entered: CompletableDeferred<Unit>, release: CountDownLatch): String = withContext(Dispatchers.IO) {
            val running = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, running) }
            started.incrementAndGet()
            entered.complete(Unit)
            try { release.await(); "地点" } finally { active.decrementAndGet() }
        }
        try {
            val old = launch {
                enrichDriveAddressesIncrementally(listOf(target(1)), oldAttempts,
                    ensureCurrent = { if (currentCar.get() != 1) throw CancellationException("vehicle changed") },
                    reverseGeocode = { _, _ -> block(enteredOld, releaseOld) }) { _, _ -> oldPublished.incrementAndGet() }
            }
            withTimeout(5_000) { enteredOld.await() }
            resetCompletedDriveAddressAttempts(oldAttempts)
            assertTrue(oldAttempts.containsKey(target(1).key))
            currentCar.set(2)
            withTimeout(1_000) { old.cancelAndJoin() }
            assertEquals(1, active.get())
            assertTrue(oldAttempts.isEmpty())

            val newer = launch {
                enrichDriveAddressesIncrementally(listOf(target(2)), newAttempts,
                    ensureCurrent = { if (currentCar.get() != 2) throw CancellationException("vehicle changed") },
                    reverseGeocode = { _, _ -> block(enteredNew, releaseNew) }) { _, _ -> newPublished.incrementAndGet() }
            }
            withTimeout(5_000) { enteredNew.await() }
            repeat(8) { switch ->
                val waiting = launch(start = CoroutineStart.UNDISPATCHED) {
                    enrichDriveAddressesIncrementally(listOf(target(100 + switch)), retryAttempts,
                        ensureCurrent = {}, reverseGeocode = { _, _ -> started.incrementAndGet(); "不应调用" }) { _, _ -> }
                }
                assertTrue(waiting.isActive)
                assertTrue(retryAttempts.isEmpty())
                withTimeout(1_000) { waiting.cancelAndJoin() }
            }
            assertEquals(2, started.get())
            assertEquals(2, peak.get())

            val waiting = launch(start = CoroutineStart.UNDISPATCHED) {
                enrichDriveAddressesIncrementally(listOf(target(3)), retryAttempts,
                    ensureCurrent = {}, reverseGeocode = { _, _ -> "可重试" }) { _, _ -> retryPublished.incrementAndGet() }
            }
            assertTrue(waiting.isActive)
            releaseNew.countDown()
            withTimeout(5_000) { newer.join() }
            assertEquals(1, newPublished.get())
            withTimeout(5_000) { waiting.join() }
            assertEquals(1, retryPublished.get())
            assertEquals(0, oldPublished.get())
        } finally {
            releaseOld.countDown()
            releaseNew.countDown()
            withTimeout(5_000) { while (active.get() != 0) delay(10) }
        }
    }
}
