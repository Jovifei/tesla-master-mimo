package com.matelink.data.local

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionScopeQualificationTest {
    @Test
    fun eachScopeDimensionSeparatesSameVehicleAndRemoteId() {
        val base = HistoryReadScope(HistoryConnectionSource.CLOUD, "server-a", "account-a", "https://api-a")
        val server = completionScopeKey(base, 42, 42)
        val otherServer = completionScopeKey(base.copy(serverIdentity = "server-b"), 42, 42)
        val account = completionScopeKey(base.copy(accountNamespace = "account-b"), 42, 42)
        val origin = completionScopeKey(base.copy(effectiveApiOrigin = "https://api-b"), 42, 42)
        assertNotEquals(server, otherServer)
        assertNotEquals(server, account)
        assertNotEquals(server, origin)
    }

    @Test
    fun nullableAccountNamespaceDoesNotMakeValidOriginsAmbiguous() {
        val withoutAccount = completionScopeKey(
            HistoryReadScope(HistoryConnectionSource.CLOUD, "server", null, "https://api"),
            1,
            1
        )
        val withAccount = completionScopeKey(
            HistoryReadScope(HistoryConnectionSource.CLOUD, "server", "account", "https://api"),
            1,
            1
        )
        assertNotEquals(withoutAccount, withAccount)
        assertTrue(withoutAccount.isNotEmpty())
    }
}
