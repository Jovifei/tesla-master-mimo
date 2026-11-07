package com.matelink.data.local

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class AppReleaseLogoutIdentityTest {
    private fun preferences() = RuntimeEnvironment.getApplication().getSharedPreferences(
        "logout-identity-${UUID.randomUUID()}", Context.MODE_PRIVATE)

    @Test fun explicitLogoutClearsSameLoginAfterCredentialRotationAndKeepsHistoryGenerationSemantics() {
        val store = JourVoltSessionStore(preferences())
        store.save("a", "r", 3600, "alice")
        val logout = store.loginIdentitySnapshot()
        val beforeHistory = store.identitySnapshot()
        assertTrue(store.rotateIfCurrent(beforeHistory.first!!, beforeHistory.second, "a2", "r2", 3600))
        assertTrue("Existing history generation must still advance on refresh",
            store.identitySnapshot().second > beforeHistory.second)
        assertTrue("Logout belongs to the login, rather than its old token", store.clearIfLoginCurrent(logout.first, logout.second))
        assertNull(store.current())
    }

    @Test fun freshLoginForSameUserAndAccountAbaCannotBeClearedByOldLogout() {
        for (aba in listOf(false, true)) {
            val store = JourVoltSessionStore(preferences())
            store.save("a", "r", 3600, "alice")
            val old = store.loginIdentitySnapshot()
            if (aba) store.save("bob", "bob-r", 3600, "bob")
            store.save("new-a", "new-r", 3600, "alice")
            assertFalse(store.clearIfLoginCurrent(old.first, old.second))
            assertEquals("new-a", store.current()?.accessToken)
        }
    }

    @Test fun durableLoginIdentityCannotRepeatAfterRecreationWithSameUser() {
        val prefs = preferences()
        var store = JourVoltSessionStore(prefs)
        store.save("a", "r", 3600, "alice")
        val old = store.loginIdentitySnapshot()
        store = JourVoltSessionStore(prefs)
        store.save("new-a", "new-r", 3600, "alice")
        assertEquals("History process generation may restart after reconstruction", old.second, store.identitySnapshot().second)
        assertTrue("Login identity must remain monotonic across recreation", store.loginIdentitySnapshot().second > old.second)
        assertFalse(store.clearIfLoginCurrent(old.first, old.second))
        assertEquals("alice", store.current()?.userId)
        val refreshed = store.identitySnapshot()
        assertTrue(store.rotateIfCurrent(refreshed.first!!, refreshed.second, "a2", "r2", 3600))
        assertTrue(store.clearIfLoginCurrent(store.loginIdentitySnapshot().first, store.loginIdentitySnapshot().second))
    }
}
