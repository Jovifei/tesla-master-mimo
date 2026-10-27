package com.matelink.ui.screens.auth

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.viewModelScope
import com.matelink.BuildConfig
import com.matelink.data.api.TeslamateApi
import com.matelink.data.api.models.*
import com.matelink.data.local.*
import com.matelink.data.repository.*
import com.matelink.di.TeslamateApiFactory
import com.squareup.moshi.Moshi
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

/** Actual login VM -> repository -> API adapter -> durable settings; no HTTP or provider I/O. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class AppReleaseAuthSelectionTest {
    private class MemoryStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }
    private fun field(target: Any, name: String, value: Any) = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(target, value)
    private fun <T> allocate(type: Class<T>): T {
        val unsafeType = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeType.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return type.cast(unsafeType.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type))
    }
    private inner class Fixture {
        val app: Application = RuntimeEnvironment.getApplication()
        private val id = UUID.randomUUID().toString()
        val sessions = JourVoltSessionStore(app.getSharedPreferences("auth-selection-$id", Context.MODE_PRIVATE))
        val modes = ConnectionModeStore(MemoryStore())
        val selection = SettingsRepository(app)
        val onboard = TeslaOnboardingStateStore(app)
        val pairingReads = AtomicInteger()
        var cars = listOf(CarData(7, vehicleUid = "verified-seven"))
        var statusReply = CarStatusResponse(CarStatusData(CarStatus(odometer = 123.0),
            Units("mi", "psi", "F"), ApiDataMeta(source = "fleet_api", observedAt = "2026-10-26T01:02:03Z")))
        var beforeCars: () -> Unit = {}
        val api = Proxy.newProxyInstance(TeslamateApi::class.java.classLoader,
            arrayOf(TeslamateApi::class.java)) { _, method, _ ->
            when (method.name) {
                "getCars" -> { beforeCars(); Response.success(CarsResponse(CarsData(cars))) }
                "getCarStatus" -> Response.success(statusReply)
                "configureTelemetry" -> Response.success(TelemetryConfigureResponse(TelemetryConfigureResult("ready")))
                "getTelemetryPairing" -> {
                    pairingReads.incrementAndGet()
                    Response.success(TelemetryPairingResponse(TelemetryPairingStatus(status = "ready", configSynced = true)))
                }
                else -> error("Unexpected fixture API call: ${method.name}")
            }
        } as TeslamateApi
        val moshi = Moshi.Builder().build()
        private val secure = allocate(SecureSettingsDataStore::class.java).also {
            field(it, "securePrefs", app.getSharedPreferences("selection-secure-$id", Context.MODE_PRIVATE))
            field(it, "_apiTokenFlow", MutableStateFlow(""))
            field(it, "_httpBasicPasswordFlow", MutableStateFlow(""))
            field(it, "_amapKeyFlow", MutableStateFlow(""))
        }
        private val settings = SettingsDataStore(MemoryStore(), secure,
            app.getSharedPreferences("selection-language-$id", Context.MODE_PRIVATE))
        private val factory = TeslamateApiFactory(settings, sessions, JourVoltSessionRefresher(sessions), modes, moshi)
        val repository = TeslamateRepository(factory, settings, selection, modes)
        suspend fun login(account: String = "alice") {
            selection.setMockMode(false)
            sessions.save("fixture-access", "fixture-refresh", 3600, account)
            modes.set(ConnectionMode.TESLA_CLOUD)
            // Every valid factory key resolves to the fake; a normalization change cannot issue HTTP.
            field(factory, "apiCache", object : LinkedHashMap<Any, TeslamateApi>() {
                override fun get(key: Any): TeslamateApi = api
            })
        }
        fun vm(auth: TeslaAuthApi? = null): TeslaLoginViewModel = if (auth == null)
            TeslaLoginViewModel(moshi, sessions, modes, JourVoltConsentStore(app), repository, selection, onboard, app)
        else TeslaLoginViewModel(moshi, sessions, modes, JourVoltConsentStore(app), repository, selection, onboard, app, auth)
    }
    private suspend fun awaitReady(vm: TeslaLoginViewModel) {
        withTimeout(5000) { vm.postLoginOnboarding.first { it == TeslaLoginOnboardingState.Ready || it is TeslaLoginOnboardingState.Blocked } }
    }

    private class ExchangeAuthApi(
        private val onExchange: suspend (TeslaAuthExchangeRequest) -> Response<TeslaAuthSessionResponse>
    ) : TeslaAuthApi {
        override suspend fun startAuthorization(termsVersion: String, privacyVersion: String): Response<TeslaAuthStartResponse> =
            error("Unexpected authorization start")
        override suspend fun exchange(request: TeslaAuthExchangeRequest): Response<TeslaAuthSessionResponse> = onExchange(request)
        override suspend fun logout(authorization: String): Response<Unit> = error("Unexpected logout")
        override suspend fun deleteAccount(authorization: String): Response<TeslaAccountDeletionResponse> = error("Unexpected account deletion")
    }

    private fun callback(ticket: String) = Intent(Intent.ACTION_VIEW,
        Uri.parse("https://${BuildConfig.JOURVOLT_AUTH_HOST}/oauth/callback?ticket=$ticket"))

    private suspend fun awaitRequest(vm: TeslaLoginViewModel) {
        val job = vm.javaClass.getDeclaredField("requestJob").apply { isAccessible = true }.get(vm) as Job
        withTimeout(5000) { job.join() }
    }

    private suspend fun assertNewTicketFailureDoesNotOpenOldAccount(httpStatus: Int) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = Fixture()
        f.onboard.save(TeslaOnboardingSnapshot())
        f.selection.setCurrentCarId(7)
        f.login("alice")
        val priorLogin = f.sessions.loginIdentitySnapshot()
        val exchangeCalls = AtomicInteger()
        val ticket = "fresh-failed-$httpStatus"
        val auth = ExchangeAuthApi { request ->
            assertEquals(ticket, request.ticket)
            exchangeCalls.incrementAndGet()
            Response.error(httpStatus, "".toResponseBody())
        }
        val vm = f.vm(auth)
        try {
            withTimeout(5000) { vm.onboardingStateRestored.first { it } }
            vm.reauthorize { }
            assertTrue(vm.reauthorizing.value)
            vm.handleAuthorizationCallback(callback(ticket))
            awaitRequest(vm)

            assertEquals(1, exchangeCalls.get())
            assertEquals("A failed new ticket must preserve the existing login", priorLogin,
                f.sessions.loginIdentitySnapshot())
            assertFalse("HTTP $httpStatus for a new ticket must not navigate using Alice's session",
                vm.openDashboardAfterLogin.value)
            assertTrue("A rejected ticket must remain in the explicit authorization flow", vm.reauthorizing.value)
            assertTrue(vm.uiState.value is TeslaLoginUiState.Error)
            assertTrue(vm.revealLoginError.value)
            assertEquals(TeslaLoginOnboardingState.Idle, vm.postLoginOnboarding.value)
            assertEquals("A failed ticket must not start onboarding for the old account", 0, f.pairingReads.get())
            assertEquals(7, f.selection.currentCarId.first())
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun newTicket401CannotNavigateUsingAnExistingAliceSession() = runBlocking {
        assertNewTicketFailureDoesNotOpenOldAccount(401)
    }

    @Test fun newTicket403CannotNavigateUsingAnExistingAliceSession() = runBlocking {
        assertNewTicketFailureDoesNotOpenOldAccount(403)
    }

    @Test fun successfulNewTicketChangesAccountAndDuplicateCallbacksDoNotExchangeAgain() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = Fixture()
        f.onboard.save(TeslaOnboardingSnapshot())
        f.selection.setCurrentCarId(7)
        f.login("alice")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val exchangeCalls = AtomicInteger()
        val ticket = "fresh-success-bob"
        val auth = ExchangeAuthApi { request ->
            assertEquals(ticket, request.ticket)
            exchangeCalls.incrementAndGet()
            entered.complete(Unit)
            release.await()
            f.cars = listOf(CarData(22, vehicleUid = "bob-twenty-two"))
            Response.success(TeslaAuthSessionResponse("bob-access", "bob-refresh", 3600, TeslaAuthUser("bob")))
        }
        val vm = f.vm(auth)
        try {
            withTimeout(5000) { vm.onboardingStateRestored.first { it } }
            vm.reauthorize { }
            vm.handleAuthorizationCallback(callback(ticket))
            withTimeout(5000) { entered.await() }
            vm.handleAuthorizationCallback(callback(ticket))
            assertEquals("An in-flight duplicate must not exchange again", 1, exchangeCalls.get())
            assertEquals("alice", f.sessions.current()?.userId)
            assertFalse(vm.openDashboardAfterLogin.value)

            release.complete(Unit)
            awaitRequest(vm)
            assertEquals("bob", f.sessions.current()?.userId)
            assertEquals("bob-access", f.sessions.current()?.accessToken)
            assertEquals(22, f.selection.currentCarId.first())
            assertEquals(TeslaLoginOnboardingState.Ready, vm.postLoginOnboarding.value)
            assertFalse(vm.reauthorizing.value)
            assertTrue(vm.openDashboardAfterLogin.value)
            assertEquals(1, f.pairingReads.get())
            val acceptedLogin = f.sessions.loginIdentitySnapshot()

            vm.consumeDashboardAfterLogin()
            assertFalse(vm.openDashboardAfterLogin.value)
            vm.handleAuthorizationCallback(callback(ticket))
            awaitRequest(vm)
            assertEquals("A successfully handled ticket must not exchange again", 1, exchangeCalls.get())
            assertEquals(acceptedLogin, f.sessions.loginIdentitySnapshot())
            assertEquals(22, f.selection.currentCarId.first())
            assertEquals(TeslaLoginOnboardingState.Ready, vm.postLoginOnboarding.value)
            assertTrue(vm.openDashboardAfterLogin.value)
        } finally { release.complete(Unit); vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun nonOneFirstCarIsPersistedBeforeDashboardNavigationAndSurvivesRecreation() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = Fixture()
        f.onboard.save(TeslaOnboardingSnapshot()); f.selection.setCurrentCarId(1); f.login()
        val vm = f.vm()
        try {
            withTimeout(5000) { vm.onboardingStateRestored.first { it } }
            vm.retryTeslaOnboarding(); awaitReady(vm)
            assertTrue(vm.openDashboardAfterLogin.value)
            assertEquals("The selected /cars row must reach every main navigation consumer", 7,
                SettingsRepository(f.app).currentCarId.first())
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun accountChangeRepairsOldGlobalSelectionUsingOnlyCurrentAuthenticatedCars() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = Fixture()
        f.onboard.save(TeslaOnboardingSnapshot()); f.selection.setCurrentCarId(7); f.login("bob")
        f.cars = listOf(CarData(22, vehicleUid = "bob-twenty-two"))
        val vm = f.vm()
        try {
            withTimeout(5000) { vm.onboardingStateRestored.first { it } }
            vm.retryTeslaOnboarding(); awaitReady(vm)
            assertEquals(22, f.selection.currentCarId.first())
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun invalidOwnershipProofNeverWritesSelectionOrStartsPairing() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = Fixture()
        f.onboard.save(TeslaOnboardingSnapshot()); f.selection.setCurrentCarId(1); f.login()
        f.cars = listOf(CarData(7, vehicleUid = null))
        val vm = f.vm()
        try {
            withTimeout(5000) { vm.onboardingStateRestored.first { it } }
            vm.retryTeslaOnboarding(); awaitReady(vm)
            assertTrue("Missing current UID must fail closed", vm.postLoginOnboarding.value is TeslaLoginOnboardingState.Blocked)
            assertEquals(1, f.selection.currentCarId.first())
            assertEquals(0, f.pairingReads.get())
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    @Test fun actualLogoutViewModelCompletesAfterSameLoginRefreshButRejectsFreshSameUserLogin() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            for (freshLogin in listOf(false, true)) {
                val f = Fixture()
                f.onboard.save(TeslaOnboardingSnapshot()); f.login()
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val completed = CompletableDeferred<Unit>()
                val api = object : TeslaAuthApi {
                    override suspend fun startAuthorization(termsVersion: String, privacyVersion: String): Response<TeslaAuthStartResponse> = error("Unused")
                    override suspend fun exchange(request: TeslaAuthExchangeRequest): Response<TeslaAuthSessionResponse> = error("Unused")
                    override suspend fun deleteAccount(authorization: String): Response<TeslaAccountDeletionResponse> = error("Unused")
                    override suspend fun logout(authorization: String): Response<Unit> {
                        entered.complete(Unit); release.await(); return Response.success(Unit)
                    }
                }
                val vm = f.vm(api)
                try {
                    withTimeout(5000) { vm.onboardingStateRestored.first { it } }
                    vm.logout { completed.complete(Unit) }
                    withTimeout(5000) { entered.await() }
                    if (freshLogin) f.sessions.save("new-a", "new-r", 3600, "alice")
                    else {
                        val captured = f.sessions.identitySnapshot()
                        assertTrue(f.sessions.rotateIfCurrent(captured.first!!, captured.second, "a2", "r2", 3600))
                    }
                    release.complete(Unit)
                    val job = vm.javaClass.getDeclaredField("requestJob").apply { isAccessible = true }.get(vm) as Job
                    withTimeout(5000) { job.join() }
                    if (freshLogin) {
                        assertEquals("new-a", f.sessions.current()?.accessToken)
                        assertFalse(completed.isCompleted)
                    } else {
                        assertNull("Explicit logout must survive a same-login refresh", f.sessions.current())
                        assertTrue(completed.isCompleted)
                    }
                } finally { release.complete(Unit); vm.viewModelScope.cancel() }
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun legacyStatusRepositoryRetainsTheReceivedUnitsAndOptionalMetadata() = runBlocking {
        val f = Fixture()
        f.login()
        val result = f.repository.getCarStatus(7) as ApiResult.Success
        assertEquals(Units("mi", "psi", "F"), result.data.units)
        assertEquals(123.0, result.data.status.odometer!!, 0.0)
        assertEquals("fleet_api", result.metadata?.source)
        assertEquals("2026-10-26T01:02:03Z", result.metadata?.observedAt)
    }

    @Test fun lateCarListFromPriorLoginCannotPersistSelectionAfterSameUserLoginOrAccountAba() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            for (aba in listOf(false, true)) {
                val f = Fixture()
                f.onboard.save(TeslaOnboardingSnapshot()); f.selection.setCurrentCarId(1); f.login()
                f.beforeCars = {
                    if (aba) f.sessions.save("b", "br", 3600, "bob")
                    f.sessions.save("new-a", "new-r", 3600, "alice")
                }
                val vm = f.vm()
                try {
                    withTimeout(5000) { vm.onboardingStateRestored.first { it } }
                    vm.retryTeslaOnboarding()
                    val job = vm.javaClass.getDeclaredField("requestJob").apply { isAccessible = true }.get(vm) as Job
                    withTimeout(5000) { job.join() }
                    assertEquals(1, f.selection.currentCarId.first())
                    assertEquals(0, f.pairingReads.get())
                    assertFalse(vm.openDashboardAfterLogin.value)
                } finally { vm.viewModelScope.cancel() }
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun duplicateAuthenticatedCarIdsCannotBeSelectedOrPaired() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = Fixture()
        f.onboard.save(TeslaOnboardingSnapshot()); f.selection.setCurrentCarId(1); f.login()
        f.cars = listOf(CarData(7, vehicleUid = "first"), CarData(7, vehicleUid = "second"))
        val vm = f.vm()
        try {
            withTimeout(5000) { vm.onboardingStateRestored.first { it } }
            vm.retryTeslaOnboarding(); awaitReady(vm)
            assertTrue(vm.postLoginOnboarding.value is TeslaLoginOnboardingState.Blocked)
            assertEquals(1, f.selection.currentCarId.first())
            assertEquals(0, f.pairingReads.get())
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
}
