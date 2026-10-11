package com.matelink.data.local

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionScopeQualificationTest {
    @Test
    fun sameVehicleAndRemoteIdCannotShareCompletionNamespaceAcrossOrigins() {
        val first = completionScopeKey(
            HistoryReadScope(HistoryConnectionSource.CLOUD, "server-a", "account-a", "https://api-a"),
            42,
            42
        )
        val second = completionScopeKey(
            HistoryReadScope(HistoryConnectionSource.CLOUD, "server-b", "account-b", "https://api-b"),
            42,
            42
        )
        assertNotEquals(first, second)
    }

    @Test
    fun malformedScopeValuesDoNotCollapseToSameHashInput() {
        val withNull = completionScopeKey(
            HistoryReadScope(HistoryConnectionSource.CLOUD, null, "account", null),
            1,
            1
        )
        val withEmpty = completionScopeKey(
            HistoryReadScope(HistoryConnectionSource.CLOUD, "", "account", ""),
            1,
            1
        )
        assertNotEquals(withNull, withEmpty)
        assertTrue(withNull.isNotEmpty())
    }
}
