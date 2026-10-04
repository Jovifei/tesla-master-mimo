package com.matelink.data.repository

import org.junit.Assert.*
import org.junit.Test

class HistoryDiscoveryPolicyTest {
    @Test fun diagnosticCodesRemainDistinctWithoutSensitiveFields() {
        assertEquals("authentication", historyFailureCategory(401))
        assertEquals("authorization", historyFailureCategory(403))
        assertEquals("not_found", historyFailureCategory(404))
        assertEquals("server", historyFailureCategory(502))
        assertEquals("transport_or_decode", historyFailureCategory(null))
    }
}
