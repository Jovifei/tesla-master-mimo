package com.matelink.data.repository

import org.junit.Assert.*
import org.junit.Test

class HistoryDiscoveryPolicyTest {
    @Test fun onlyTypedTlsCausesAreEmittedByAllowlistedHistoryStages() {
        val tls = ApiResult.Error("Safe", kind = ApiErrorKind.NETWORK,
            safeFailure = SafeApiFailure.TLS, safeTlsCause = SafeTlsCause.PROTOCOL)
        val log = historyFailureDiagnostic("history_context", java.time.Instant.EPOCH, tls)
        assertEquals("stage=history_context requested_at=1970-01-01T00:00:00Z http=none category=tls tls_cause=protocol", log)
        val noTls = tls.copy(safeFailure = SafeApiFailure.DNS)
        assertEquals("stage=history_context requested_at=1970-01-01T00:00:00Z http=none category=dns",
            historyFailureDiagnostic("history_context", java.time.Instant.EPOCH, noTls))
    }

    @Test fun diagnosticCodesRemainDistinctWithoutSensitiveFields() {
        assertEquals("authentication", historyFailureCategory(401))
        assertEquals("authorization", historyFailureCategory(403))
        assertEquals("not_found", historyFailureCategory(404))
        assertEquals("server", historyFailureCategory(502))
        assertEquals("transport_or_decode", historyFailureCategory(null))
    }
}
