package com.matelink.data.sync

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Supplemental wiring checks; behavioral journal/codec/delivery tests are separate. */
class CompletionRuntimeWiringTest {
    private fun source(path: String): String {
        val root = sequenceOf(File("src/main/java"), File("app/src/main/java"), File("android/app/src/main/java"))
            .first { File(it, path).exists() }
        return File(root, path).readText()
    }
    @Test fun foregroundAndSystemUseSameScopeReceiptAndDeliveryGuards() {
        val sync = source("com/matelink/data/sync/SyncRepository.kt")
        val prompt = source("com/matelink/ui/components/CompletedChargePrompt.kt")
        val store = source("com/matelink/data/local/CompletedChargeEventStore.kt")
        assertTrue(sync.contains("completionScopeKey(scope"));assertTrue(prompt.contains("completionScopeKey(scope"))
        assertTrue(sync.contains("resolvePendingCompletion(event"));assertTrue(prompt.contains("resolvePendingCompletion(event"))
        assertTrue(sync.contains("deliverPendingCompletions("));assertTrue(prompt.contains("modes.mode"))
        assertTrue(prompt.contains("event.matchesDrive(it)"));assertTrue(prompt.contains("event.matchesCharge(it)"))
        assertTrue(store.contains("CompletionJournalCodec.record("));assertTrue(store.contains("CompletionJournalCodec.consume("))
        assertFalse(store.contains("prefs.clear("));assertFalse(store.contains("prefs.remove("))
    }
    @Test fun newSystemTripUsesGenericInboxIntentWithoutNumericOrRouteExtras() {
        val manager = source("com/matelink/notification/TripNotificationManager.kt")
        val runtime = manager.substringAfter("override fun showCompletedDrive")
        assertTrue(runtime.contains("completedTripInboxIntent(context)"))
        assertFalse(runtime.contains("completedTripIntent("));assertFalse(runtime.contains("summary.startAddress"))
        val inbox = manager.substringAfter("internal fun completedTripInboxIntent").substringBefore("internal fun tripNotificationId")
        assertFalse(inbox.contains("putExtra"));assertFalse(inbox.contains("drive_detail"))
    }
    @Test fun backgroundWorkerPropagatesCancellationAtEveryCatchBoundary() {
        val worker = source("com/matelink/data/sync/DataSyncWorker.kt")
        val guarded = Regex("catch \\(e: CancellationException\\) \\{\\s*throw e\\s*} catch \\(e: Exception\\)")
        assertEquals(3, guarded.findAll(worker).count())
        assertEquals(3, Regex("catch \\(e: Exception\\)").findAll(worker).count())
    }
}
