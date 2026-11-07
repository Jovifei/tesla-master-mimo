package com.matelink.ui.screens.archive

import android.app.Application
import com.matelink.BuildConfig
import com.matelink.data.api.ArchiveEnrollmentRequestFence
import com.matelink.data.api.TeslamateApi
import com.matelink.data.api.models.CarData
import com.matelink.data.local.ConnectionMode
import com.matelink.data.repository.ArchiveEnrollmentRepository
import com.matelink.data.repository.SettingsRepository
import com.matelink.data.sync.HistoryResourceFixture
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ArchiveEnrollmentRepositoryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun confirmationUsesTheNonReplayingCloudClient() = runBlocking {
        val f = fixture("no-replay")
        try {
            val settings = SettingsRepository(f.context)
            settings.setCurrentCarId(1)
            val repo = ArchiveEnrollmentRepository(f.vehicleContexts, settings, f.apiFactory)
            val identity = requireNotNull(repo.captureIdentity())
            val method = repo.javaClass.getDeclaredMethod("client", ArchiveEnrollmentIdentity::class.java, Continuation::class.java)
                .apply { isAccessible = true }
            val service = suspendCoroutineUninterceptedOrReturn<TeslamateApi> { continuation ->
                method.invoke(repo, identity, continuation)
            }
            val cache = f.apiFactory.javaClass.getDeclaredField("apiCache").apply { isAccessible = true }
                .get(f.apiFactory) as Map<*, *>
            val key = requireNotNull(cache.entries.singleOrNull { it.value === service }?.key)
            val noRetry = key.javaClass.getDeclaredField("noRetry").apply { isAccessible = true }.getBoolean(key)
            assertTrue(noRetry)
        } finally { f.close() }
    }

    @Test fun cachedVerifiedSelectionDoesNotDiscoverOrConfigureVehicles() = runBlocking {
        val f = fixture("cached")
        try {
            val settings = SettingsRepository(f.context)
            settings.setCurrentCarId(1)
            val repo = ArchiveEnrollmentRepository(f.vehicleContexts, settings, f.apiFactory)
            f.calls.clear()
            val identity = requireNotNull(repo.captureIdentity())
            assertEquals(1, identity.vehicleId)
            assertEquals(f.localId(1), identity.localHistoryCarId)
            assertEquals(64, identity.vehicleIdentityDigest.length)
            assertTrue(repo.isCurrent(identity))
            assertTrue(f.calls.isEmpty())
            settings.setCurrentCarId(99)
            assertNull(repo.captureIdentity())
            assertFalse(repo.isCurrent(identity))
            assertTrue(f.calls.isEmpty())
        } finally { f.close() }
    }

    @Test fun sameNumericCarIdCannotSurviveUidRemapOrSameAccountLoginGenerationChange() = runBlocking {
        val f = fixture("remap")
        try {
            val settings = SettingsRepository(f.context)
            settings.setCurrentCarId(1)
            val repo = ArchiveEnrollmentRepository(f.vehicleContexts, settings, f.apiFactory)
            val original = requireNotNull(repo.captureIdentity())
            val scope = f.vehicleContexts.captureReadScope()
            f.vehicleContexts.resolveVerifiedHistoryCar(CarData(1, vehicleUid = "replacement-vehicle"), scope)
            assertFalse(repo.isCurrent(original))
            val remapped = requireNotNull(repo.captureIdentity())
            assertTrue(remapped.localHistoryCarId != original.localHistoryCarId)
            f.sessions.save("rotated-synthetic-access", "rotated-synthetic-refresh", 3600, remapped.accountId)
            assertFalse(repo.isCurrent(remapped))
            val loggedInAgain = requireNotNull(repo.captureIdentity())
            f.modes.set(ConnectionMode.SELF_HOSTED)
            assertNull(repo.captureIdentity())
            f.modes.set(ConnectionMode.TESLA_CLOUD)
            assertFalse(repo.isCurrent(loggedInAgain))
        } finally { f.close() }
    }

    @Test fun taggedRequestCannotBorrowAnotherAccountsBearerEvenIfAnEarlierGuardReturnedTrue() = runBlocking {
        val f = fixture("http-account")
        try {
            val (session, generation) = f.sessions.identitySnapshot()
            val fence = ArchiveEnrollmentRequestFence(requireNotNull(session).userId, generation) {
                // Simulate the switch after the first proof check, before the interceptor takes its token snapshot.
                f.sessions.save("other-synthetic-access", "other-synthetic-refresh", 3600, "another-owner")
                true
            }
            var sent = false
            val client = client(f, Interceptor { chain -> sent = true; response(chain) })
            try {
                client.newCall(request(fence)).execute().close()
                throw AssertionError("Changed account must fail before HTTP delivery")
            } catch (_: IOException) { assertFalse(sent) }
        } finally { f.close() }
    }

    @Test fun canceledTagNeverSendsAndTaggedUnauthorizedNeverRefreshesOrRetries() = runBlocking {
        val f = fixture("http-cancel")
        try {
            val (session, generation) = f.sessions.identitySnapshot()
            val owner = requireNotNull(session).userId
            var sent = false
            val client = client(f, Interceptor { chain -> sent = true; response(chain) })
            try {
                client.newCall(request(ArchiveEnrollmentRequestFence(owner, generation) { false })).execute().close()
                throw AssertionError("Canceled confirmation must not send")
            } catch (_: IOException) { assertFalse(sent) }
            val validRequest = request(ArchiveEnrollmentRequestFence(owner, generation) { true })
            val unauthorized = Response.Builder().request(validRequest).protocol(Protocol.HTTP_1_1)
                .code(401).message("Synthetic").body("{}".toResponseBody()).build()
            unauthorized.use { assertNull(client.authenticator.authenticate(null, it)) }
            assertFalse(sent)
            assertEquals(generation, f.sessions.identitySnapshot().second)
            val success = client.newCall(validRequest).execute()
            success.use { assertEquals(200, it.code) }
            assertTrue(sent)
        } finally { f.close() }
    }

    private suspend fun fixture(name: String): HistoryResourceFixture =
        HistoryResourceFixture(File(temp.root, "$name.db")).also { it.initialize(cloud = true) }

    /** Uses the real factory auth/mode interceptors; the final interceptor replaces only HTTP transport. */
    private fun client(f: HistoryResourceFixture, transport: Interceptor): OkHttpClient {
        val create = f.apiFactory.javaClass.getDeclaredMethod("createOkHttpClient", String::class.java,
            Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType, Long::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType)
        create.isAccessible = true
        val original = create.invoke(f.apiFactory, "", true, true, f.modes.currentIdentityGeneration(), false) as OkHttpClient
        assertFalse(original.followRedirects)
        return original.newBuilder().addInterceptor(transport).build()
    }
    private fun request(fence: ArchiveEnrollmentRequestFence) = Request.Builder()
        .url(BuildConfig.JOURVOLT_API_BASE_URL + "/api/v1/archive/enrollments/" + "a".repeat(64))
        .tag(ArchiveEnrollmentRequestFence::class.java, fence).build()
    private fun response(chain: Interceptor.Chain) = Response.Builder().request(chain.request())
        .protocol(Protocol.HTTP_1_1).code(200).message("Synthetic").body("{}".toResponseBody()).build()
}
