package com.matelink.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VehicleContextStoreAndroidTest {
    @Test
    fun committedAllocationsSurviveStoreRecreationAndRemainUniqueUnderConcurrency() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("vehicle_history_identity", 0).edit().clear().commit()
        val store = VehicleContextStore(context)
        val identities = (1..32).map { "self-hosted:https://example.test:car:$it" }

        val allocated = identities.mapIndexed { index, identity ->
            async(Dispatchers.Default) {
                store.getOrAllocate(identity, index + 1, HistoryConnectionSource.SELF_HOSTED, "https://example.test")
            }
        }.awaitAll()
        val reloadedStore = VehicleContextStore(context)

        assertEquals(identities.size, allocated.map { it.localHistoryCarId }.toSet().size)
        assertTrue(allocated.all { it.localHistoryCarId < 0 })
        allocated.forEach { vehicle ->
            assertEquals(vehicle.localHistoryCarId, reloadedStore.findLocalHistoryCarId(vehicle.stableIdentity))
        }
    }
}
