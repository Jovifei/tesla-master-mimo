package com.matelink.data.sync

import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.DriveData
import com.matelink.data.local.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** Actual Moshi journal codec, production publication and delivery functions; fake I/O only. */
class CompletionJournalFlowTest {
    private val cutoff = Instant.parse("2026-10-07T00:00:00Z").toEpochMilli()
    private val now = Instant.parse("2026-10-07T01:10:00Z").toEpochMilli()
    private val start = "2026-10-07T01:00:00.417Z"
    private val end = "2026-10-07T01:05:00.417Z"
    private val scope = HistoryReadScope(HistoryConnectionSource.CLOUD, "cloud", "fake-account", "https://fake.invalid/")
    private val key = completionScopeKey(scope, -7, 1)
    private fun charge() = ChargeData(12, startDate = start, endDate = end, source = "telemetry_mqtt",
        qualityState = "incomplete", qualityReason = "partial_or_missing_charge_counter")
    private fun initial() = CompletionJournalCodec.record(null, -7, 1, "charge", key, emptyList(), cutoff)
    private fun receipt() = charge().toSyncSummary(-7)!!.completionReceipt(scope.source, Instant.ofEpochMilli(now))!!
    private fun recorded() = CompletionJournalCodec.record(initial(), -7, 1, "charge", key, listOf(receipt()), now)

    @Test fun permissionDenialForegroundConsumeRestartThenSystemDeliveryAreIndependent() = runBlocking {
        var raw = recorded()
        val summary = charge().toSyncSummary(-7)!!
        var allowed = false
        var posts = 0
        suspend fun deliver() {
            deliverPendingCompletions(CompletionJournalCodec.decode(raw, now).events, { true },
                { event -> resolvePendingCompletion(event, -7, 1, key, { true }, { summary },
                    { it.canPublishCompletion(scope.source, Instant.ofEpochMilli(now)) && event.matchesCharge(it) }) },
                { _, _ -> if (allowed) { posts++; true } else false },
                { event -> raw = CompletionJournalCodec.consume(raw, event, true, now) })
        }
        deliver()
        val event = CompletionJournalCodec.decode(raw, now).events.single()
        assertFalse(event.systemConsumed);assertFalse(event.appConsumed);assertEquals(0, posts)
        raw = CompletionJournalCodec.consume(raw, event, false, now)
        val afterApp = CompletionJournalCodec.decode(raw, now).events.single()
        assertTrue(afterApp.appConsumed);assertFalse(afterApp.systemConsumed)
        // Reparse after a process restart; the next successful sync may return no new rows.
        raw = CompletionJournalCodec.record(raw, -7, 1, "charge", key, emptyList(), now)
        allowed = true
        deliver();deliver()
        assertEquals(1, posts);assertTrue(CompletionJournalCodec.decode(raw, now).events.isEmpty())
        raw = CompletionJournalCodec.record(raw, -7, 1, "charge", key, listOf(receipt()), now)
        assertTrue(CompletionJournalCodec.decode(raw, now).events.isEmpty())
        assertEquals(setOf(12), CompletionJournalCodec.decode(raw, now).seen)
        assertNull(charge().batteryInputKwh)
    }

    @Test fun suspendedScopeSwitchPreventsBothPromptAndSystemQualification() = runBlocking {
        val event = CompletionJournalCodec.decode(recorded(), now).events.single()
        var current = true
        var reads = 0
        val resolved = resolvePendingCompletion(event, -7, 1, key, { current },
            { reads++; current = false; charge().toSyncSummary(-7) }, { event.matchesCharge(it) })
        assertNull(resolved);assertEquals(1, reads)
        val other = completionScopeKey(scope.copy(effectiveApiOrigin = "https://other.invalid/"), -7, 1)
        assertNull(resolvePendingCompletion(event, -7, 1, other, { true }, { error("must not read another scope") }, { true }))
    }

    @Test fun realCancellationIsPropagatedWithoutAcknowledgingOrResettingJournal() = runBlocking {
        val raw = recorded()
        val event = CompletionJournalCodec.decode(raw, now).events.single()
        var caught = false
        try {
            resolvePendingCompletion(event, -7, 1, key, { true },
                { throw CancellationException("synthetic cancellation") }, { _: Unit -> true })
        } catch (_: CancellationException) { caught = true }
        assertTrue(caught);assertEquals(raw, CompletionJournalCodec.encode(CompletionJournalCodec.decode(raw, now)))
        assertFalse(CompletionJournalCodec.decode(raw, now).events.single().systemConsumed)
    }

    @Test fun sameIdOtherSourceOrStartCannotConsumeOrDeliverPendingEvent() = runBlocking {
        val raw = recorded()
        val event = CompletionJournalCodec.decode(raw, now).events.single()
        val wrong = charge().copy(source = "fleet_api", qualityState = "observed").toSyncSummary(-7)!!
        assertNull(resolvePendingCompletion(event, -7, 1, key, { true }, { wrong }, { event.matchesCharge(it) }))
        assertEquals(raw, CompletionJournalCodec.consume(raw, event.copy(source = "fleet_api"), true, now))
        assertEquals(raw, CompletionJournalCodec.consume(raw, event.copy(startDate = "2026-10-07T00:30:00Z"), false, now))
        assertEquals(raw, CompletionJournalCodec.consume(raw, event.copy(scopeKey = "wrong"), false, now))
    }

    @Test fun staleBeforeCutoffThenCorrectedReplayCannotEraseDurableSeenAfterRestart() {
        var raw = recorded()
        val event = CompletionJournalCodec.decode(raw, now).events.single()
        raw = CompletionJournalCodec.consume(raw, event, true, now)
        raw = CompletionJournalCodec.consume(raw, event, false, now)
        val stale = receipt().copy(startDate = "2026-10-01T00:00:00Z", endDate = "2026-10-01T00:05:00Z")
        raw = CompletionJournalCodec.record(raw, -7, 1, "charge", key, listOf(stale), now)
        raw = CompletionJournalCodec.encode(CompletionJournalCodec.decode(raw, now))
        raw = CompletionJournalCodec.record(raw, -7, 1, "charge", key, listOf(receipt()), now)
        assertTrue(CompletionJournalCodec.decode(raw, now).events.isEmpty())
        assertEquals(setOf(12), CompletionJournalCodec.decode(raw, now).seen)
    }

    @Test fun scopeKeySeparatesAccountOriginConnectionVehicleRemoteAndAmbiguousSeparators() {
        val keys = listOf(key,
            completionScopeKey(scope.copy(accountNamespace = "other"), -7, 1),
            completionScopeKey(scope.copy(effectiveApiOrigin = "https://other.invalid/"), -7, 1),
            completionScopeKey(scope.copy(serverIdentity = "other"), -7, 1),
            completionScopeKey(scope.copy(source = HistoryConnectionSource.SELF_HOSTED), -7, 1),
            completionScopeKey(scope, -8, 1), completionScopeKey(scope, -7, 2))
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.all { it.matches(Regex("[a-f0-9]{64}")) })
        assertNotEquals(completionScopeKey(scope.copy(serverIdentity = "a:b", accountNamespace = "c"), -7, 1),
            completionScopeKey(scope.copy(serverIdentity = "a", accountNamespace = "b:c"), -7, 1))
    }

    @Test fun malformedOrNewerJournalNeverResetsBaselineOrDeletesEvidence() {
        val raw = recorded()
        for (invalid in listOf("{}", "invalid", raw.replace("\"version\":2", "\"version\":99"))) {
            var rejected = false
            try { CompletionJournalCodec.record(invalid, -7, 1, "charge", key, emptyList(), now) }
            catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
        }
        assertEquals(raw, recorded())
    }

    @Test fun firstLargeArchiveKeepsSmallJournalAndDoesNotRememberBeforeCutoffIds() {
        val history = (1..20000).map { CompletionReceipt(it, "2026-10-01T00:00:00Z", "2026-10-01T00:05:00Z", "telemetry_mqtt") }
        val raw = CompletionJournalCodec.record(null, -7, 1, "drive", key, history, cutoff)
        val state = CompletionJournalCodec.decode(raw, now)
        assertEquals(20000, state.baseline);assertTrue(state.events.isEmpty());assertTrue(state.seen.isEmpty())
        assertTrue(raw.length < 500)
    }

    @Test fun receiptConflictNotSeenAndCorrectedLowerIdStillArrivesOnce() {
        val r = receipt()
        val conflict = CompletionJournalCodec.record(initial(), -7, 1, "charge", key,
            listOf(r, r.copy(source = "fleet_api")), now)
        assertTrue(CompletionJournalCodec.decode(conflict, now).seen.isEmpty())
        val corrected = CompletionJournalCodec.record(conflict, -7, 1, "charge", key, listOf(r), now)
        assertEquals(12, CompletionJournalCodec.decode(corrected, now).events.single().chargeId)
    }

    @Test fun repeatedOrOverlappingPageCannotEstablishBaselineButKeepsPersistedRows() = runBlocking {
        var writes = 0
        var callback = false
        val page = (1..50).map { DriveData(it, startDate = start, endDate = end,
            source = "telemetry_mqtt", qualityState = "observed").toSyncSummary(-7)!! }
        val ok = DriveSummarySyncRunner(
            { DriveSummaryPageResult.Success(page.map { it.driveId }, page) },
            { writes += it.size }, { callback = true }).sync()
        assertFalse(ok);assertFalse(callback);assertEquals(100, writes)
    }

    @Test fun boundedUniquePageWalkStopsWithoutFalseCompleteAndEmptyBaselineIsReal() = runBlocking {
        var calls = 0
        var callback = false
        val bounded = DriveSummarySyncRunner(
            { page -> calls++; DriveSummaryPageResult.Success(((page-1)*50+1..page*50).toList(), emptyList()) },
            { }, { callback = true }, maxPages = 3).sync()
        assertFalse(bounded);assertFalse(callback);assertEquals(3, calls)
        assertTrue(DriveSummarySyncRunner({ DriveSummaryPageResult.Success(emptyList(), emptyList()) },
            { error("no fabricated rows") }, { callback = true }).sync())
        assertTrue(callback)
        val guard = CompletionPageGuard()
        assertEquals(CompletionPageDecision.CONTINUE, guard.accept((1..50).toList()))
        assertEquals(CompletionPageDecision.INCOMPLETE, guard.accept(listOf(50)))
    }

    @Test fun pageCancellationCannotPublishFirstImportBaseline() = runBlocking {
        var callback = false
        var caught = false
        try { DriveSummarySyncRunner({ throw CancellationException("synthetic") }, { }, { callback = true }).sync() }
        catch (_: CancellationException) { caught = true }
        assertTrue(caught);assertFalse(callback)
    }
}
