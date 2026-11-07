package com.matelink.ui.screens.archive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.lang.reflect.InvocationTargetException
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ArchiveEnrollmentViewModelTest {
    private val pkg = "com.matelink.ui.screens.archive."
    private val dto = "com.matelink.data.api.models.ArchiveEnrollmentPreview"
    private val handle = "ab".repeat(32)
    private val start = Instant.parse("2026-10-29T04:00:00Z")

    private fun type(name: String): Class<*> = try { Class.forName(name) }
        catch (_: ClassNotFoundException) { throw AssertionError("Missing enrollment production boundary: $name") }
    private fun create(name: String, vararg args: Any?): Any =
        type(name).declaredConstructors.single { ctor -> !ctor.isSynthetic && ctor.parameterCount == args.size &&
            ctor.parameterTypes.zip(args).all { (parameter, value) -> value == null || parameter.isInstance(value) ||
                (parameter == Int::class.javaPrimitiveType && value is Int) ||
                (parameter == Long::class.javaPrimitiveType && value is Long) ||
                (parameter == Boolean::class.javaPrimitiveType && value is Boolean) } }
            .also { it.isAccessible = true }.newInstance(*args)
    private fun call(target: Any, name: String, vararg args: Any?): Any? = try {
        target.javaClass.methods.single { it.name == name && it.parameterCount == args.size }.invoke(target, *args)
    } catch (e: InvocationTargetException) { throw e.targetException }
    private fun value(target: Any, name: String): Any? = call(target, "get${name.replaceFirstChar { it.uppercaseChar() }}")

    private inner class Fixture {
        var identity: Any? = identity()
        var now = start
        var previewCar = 7
        var previewStatus = "pending"
        var binding = "active"
        var expires = "2026-10-29T04:10:00Z"
        var code = "123456"
        var fingerprint = "f".repeat(16)
        var vehicleName = "Current vehicle"
        var previewGate: CompletableDeferred<Unit>? = null
        var decisionGate: CompletableDeferred<Unit>? = null
        var previewFailure: Throwable? = null
        var decisionFailure: Throwable? = null
        var previewCalls = 0
        val decisions = mutableListOf<Triple<String, Boolean, String>>()
        val vm: Any

        fun identity(account: String = "owner-a", origin: String = "https://api.joviluma.com",
                     generation: Long = 1, modeGeneration: Long = 1, car: Int = 7,
                     localId: Int = -17, proof: String = "f".repeat(64)): Any = create(pkg + "ArchiveEnrollmentIdentity",
            account, origin, generation, modeGeneration, car, localId, proof)
        fun preview(status: String = previewStatus): Any = create(dto, handle, status, code, expires,
            previewCar, vehicleName, "TeslaMate", fingerprint, binding)

        init {
            val capture: suspend () -> Any? = { identity }
            val current: suspend (Any) -> Boolean = { it == identity }
            val getPreview: suspend (String, Any) -> Any = { _, _ ->
                previewCalls++
                withContext(NonCancellable) { previewGate?.await() }
                previewFailure?.let { throw it }
                preview()
            }
            val decide: suspend (String, Boolean, String, Any) -> Any = { h, approve, code, _ ->
                decisions += Triple(h, approve, code)
                withContext(NonCancellable) { decisionGate?.await() }
                decisionFailure?.let { throw it }
                preview(if (approve) "approved" else "rejected")
            }
            val clock: () -> Instant = { now }
            val deps = create(pkg + "ArchiveEnrollmentDependencies", capture, current, getPreview, decide, clock)
            vm = create(pkg + "ArchiveEnrollmentViewModel", deps)
        }
        fun command(name: String, vararg args: Any?) { call(vm, name, *args) }
        fun state(): Any = (call(vm, "getUiState") as StateFlow<*>).value!!
        fun phase(): String = value(state(), "phase").toString()
        fun error(): String = value(state(), "error").toString()
        fun close() { (vm as ViewModel).viewModelScope.cancel() }
    }

    private fun http(code: Int) = HttpException(Response.error<Any>(code, "{}".toResponseBody()))

    @Test fun openingPreviewsWithoutAnyAutomaticDecision() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = Fixture()
        try {
            f.command("open", handle); advanceUntilIdle()
            assertEquals("READY", f.phase()); assertEquals(1, f.previewCalls)
            assertTrue(f.decisions.isEmpty())
            assertEquals("123456", value(value(f.state(), "preview")!!, "verificationCode"))
        } finally { f.close(); Dispatchers.resetMain() }
    }

    @Test fun onlyExplicitApproveSubmitsMatchingCodeOnce() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val f = Fixture()
        try {
            f.command("open", handle); advanceUntilIdle()
            f.command("approve"); f.command("approve"); advanceUntilIdle()
            assertEquals(listOf(Triple(handle, true, "123456")), f.decisions)
            assertEquals("APPROVED", f.phase())
        } finally { f.close(); Dispatchers.resetMain() }
    }

    @Test fun onlyExplicitRejectSubmitsRejection() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val f = Fixture()
        try {
            f.command("open", handle); advanceUntilIdle(); f.command("reject"); advanceUntilIdle()
            assertEquals(listOf(Triple(handle, false, "123456")), f.decisions)
            assertEquals("REJECTED", f.phase())
        } finally { f.close(); Dispatchers.resetMain() }
    }

    @Test fun originalExpiredButUnrevokedBindingCanBeExplicitlyRenewed() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val f = Fixture()
        try {
            f.binding = "expired"; f.command("open", handle); advanceUntilIdle()
            assertEquals("READY", f.phase()); assertTrue(f.decisions.isEmpty())
            f.command("approve"); advanceUntilIdle(); assertEquals("APPROVED", f.phase())
        } finally { f.close(); Dispatchers.resetMain() }
    }

    @Test fun concurrentDisplayNameAndBindingExpiryChangeDoNotAlterTheApprovedScope() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val f = Fixture()
        try {
            f.command("open", handle); advanceUntilIdle()
            f.vehicleName = "Renamed vehicle"; f.binding = "expired"
            f.command("approve"); advanceUntilIdle()
            assertEquals("APPROVED", f.phase())
        } finally { f.close(); Dispatchers.resetMain() }
    }

    @Test fun changedDecisionFingerprintCannotBeReportedAsAnApproval() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val f = Fixture()
        try {
            f.command("open", handle); advanceUntilIdle(); f.fingerprint = "b".repeat(16)
            f.command("approve"); advanceUntilIdle()
            assertEquals("ERROR", f.phase()); assertEquals("FORBIDDEN", f.error())
        } finally { f.close(); Dispatchers.resetMain() }
    }

    @Test fun comparisonCodePreservesLeadingZeroAndRejectsNonCanonicalForms() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val f = Fixture()
        try {
            f.code = "001234"; f.command("open", handle); advanceUntilIdle()
            f.command("approve"); advanceUntilIdle()
            assertEquals(listOf(Triple(handle, true, "001234")), f.decisions)
            f.code = "12345678"; f.command("open", handle); advanceUntilIdle()
            assertEquals("FORBIDDEN", f.error())
        } finally { f.close(); Dispatchers.resetMain() }
    }

    @Test fun unavailableCachedIdentityNeverMakesAnOwnerRequest() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val f = Fixture()
        try {
            f.identity = null; f.command("open", handle); advanceUntilIdle(); f.command("approve"); advanceUntilIdle()
            assertEquals(0, f.previewCalls); assertTrue(f.decisions.isEmpty())
            assertEquals("IDENTITY_UNAVAILABLE", f.error())
        } finally { f.close(); Dispatchers.resetMain() }
    }

    @Test fun wrongSelectedCarCannotBeApproved() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val f = Fixture()
        try {
            f.previewCar = 8; f.command("open", handle); advanceUntilIdle(); f.command("approve"); advanceUntilIdle()
            assertEquals("FORBIDDEN", f.error()); assertTrue(f.decisions.isEmpty())
        } finally { f.close(); Dispatchers.resetMain() }
    }

    @Test fun revokedBindingAndNonPendingRequestNeverBecomeReady() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val f = Fixture()
        try {
            f.binding = "revoked"; f.command("open", handle); advanceUntilIdle(); f.command("approve"); advanceUntilIdle()
            assertEquals("FORBIDDEN", f.error()); assertTrue(f.decisions.isEmpty())
            f.binding = "active"; f.previewStatus = "approved"
            f.command("open", handle); advanceUntilIdle(); f.command("approve"); advanceUntilIdle()
            assertEquals("FORBIDDEN", f.error()); assertTrue(f.decisions.isEmpty())
        } finally { f.close(); Dispatchers.resetMain() }
    }

    @Test fun localExpiryBeforeClickBlocksBothDecisions() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val f = Fixture()
        try {
            f.command("open", handle); advanceUntilIdle(); f.now = start.plusSeconds(600)
            f.command("approve"); f.command("reject"); advanceUntilIdle()
            assertEquals("EXPIRED", f.error()); assertTrue(f.decisions.isEmpty())
        } finally { f.close(); Dispatchers.resetMain() }
    }

    @Test fun serverExpiryAndForbiddenAreFixedErrorsWithoutRawServerMessage() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val f = Fixture()
        try {
            f.previewFailure = http(410); f.command("open", handle); advanceUntilIdle()
            assertEquals("EXPIRED", f.error())
            f.previewFailure = http(403); f.command("open", handle); advanceUntilIdle()
            assertEquals("FORBIDDEN", f.error()); assertTrue(f.decisions.isEmpty())
        } finally { f.close(); Dispatchers.resetMain() }
    }

    @Test fun decisionFailureDoesNotRenderApprovalOrPermitAnAutomaticRetry() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val f = Fixture()
        try {
            f.command("open", handle); advanceUntilIdle(); f.decisionFailure = http(410)
            f.command("approve"); advanceUntilIdle()
            assertEquals("ERROR", f.phase()); assertEquals("EXPIRED", f.error())
            assertEquals(1, f.decisions.size)
        } finally { f.close(); Dispatchers.resetMain() }
    }

    @Test fun everyIdentityDimensionIsRecheckedImmediatelyBeforeDecision() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val f = Fixture()
        try {
            val changed = listOf(f.identity(account = "owner-b"), f.identity(origin = "https://elsewhere.example"),
                f.identity(generation = 2), f.identity(modeGeneration = 2), f.identity(car = 8),
                f.identity(localId = -18), f.identity(proof = "b".repeat(64)))
            changed.forEach {
                f.identity = f.identity(); f.command("open", handle); advanceUntilIdle()
                assertEquals("READY", f.phase()); f.identity = it
                f.command("approve"); advanceUntilIdle()
                assertEquals("IDENTITY_UNAVAILABLE", f.error()); assertTrue(f.decisions.isEmpty())
            }
        } finally { f.close(); Dispatchers.resetMain() }
    }

    @Test fun latePreviewAfterDismissBackgroundOrSwitchCannotReopenDialog() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val f = Fixture()
        try {
            listOf("dismiss", "onBackground", "onContextChanged").forEach { cancel ->
                f.previewGate = CompletableDeferred(); f.command("open", handle); runCurrent()
                f.command(cancel); f.previewGate!!.complete(Unit); advanceUntilIdle()
                assertEquals("HIDDEN", f.phase()); f.command("approve"); advanceUntilIdle()
                assertTrue(f.decisions.isEmpty()); assertNull(value(f.state(), "preview"))
            }
        } finally { f.close(); Dispatchers.resetMain() }
    }

    @Test fun lateDecisionAfterBackgroundCannotDisplaySuccessOrReapprove() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val f = Fixture()
        try {
            f.command("open", handle); advanceUntilIdle(); f.decisionGate = CompletableDeferred()
            f.command("approve"); runCurrent(); assertEquals(1, f.decisions.size)
            f.command("onBackground"); f.decisionGate!!.complete(Unit); advanceUntilIdle()
            assertEquals("HIDDEN", f.phase()); f.command("approve"); advanceUntilIdle()
            assertEquals(1, f.decisions.size)
        } finally { f.close(); Dispatchers.resetMain() }
    }

    @Test fun latePreviewAndDecisionRejectChangedSessionEvenWithoutAUiContextCallback() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val f = Fixture()
        try {
            val previewGate = CompletableDeferred<Unit>(); f.previewGate = previewGate
            f.command("open", handle); runCurrent(); f.identity = f.identity(generation = 2)
            previewGate.complete(Unit); advanceUntilIdle()
            assertEquals("IDENTITY_UNAVAILABLE", f.error()); assertTrue(f.decisions.isEmpty())
            f.previewGate = null; f.command("open", handle); advanceUntilIdle()
            val decisionGate = CompletableDeferred<Unit>(); f.decisionGate = decisionGate
            f.command("approve"); runCurrent(); f.identity = f.identity(generation = 3)
            decisionGate.complete(Unit); advanceUntilIdle()
            assertEquals("ERROR", f.phase()); assertEquals("IDENTITY_UNAVAILABLE", f.error())
        } finally { f.close(); Dispatchers.resetMain() }
    }

    @Test fun replacingDialogInvalidatesAnOlderPreviewEvenWhenNetworkIgnoresCancellation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val f = Fixture()
        try {
            val gate = CompletableDeferred<Unit>(); f.previewGate = gate
            f.command("open", handle); runCurrent(); f.command("dismiss")
            f.previewGate = null; f.command("open", handle); advanceUntilIdle()
            assertEquals("READY", f.phase()); gate.complete(Unit); advanceUntilIdle()
            assertEquals("READY", f.phase()); assertTrue(f.decisions.isEmpty())
        } finally { f.close(); Dispatchers.resetMain() }
    }
}
