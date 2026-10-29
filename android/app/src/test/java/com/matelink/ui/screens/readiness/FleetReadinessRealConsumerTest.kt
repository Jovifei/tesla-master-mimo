package com.matelink.ui.screens.readiness

import android.app.Application
import com.matelink.data.api.models.*
import com.matelink.data.local.*
import com.matelink.data.local.dao.*
import com.matelink.data.sync.HistoryResourceFixture
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class FleetReadinessRealConsumerTest {
    @get:Rule val temporary = TemporaryFolder()
    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> error("Unexpected ${method.name}") } as T
    @Test fun realSessionChecksWireIdsAndRejectsRemappedAndChangedAccount() = runBlocking {
        val f = HistoryResourceFixture(File(temporary.root, "real-consumer.db")); f.initialize(cloud = true)
        try {
            val scope = f.vehicleContexts.captureReadScope()
            val car = CarData(7, vehicleUid = "provider-a")
            f.vehicleContexts.resolveVerifiedHistoryCar(car, scope)
            val diagnostics = ReadOnlyReadinessDiagnostics(f.vehicleContexts, unused<TpmsPressureSampleDao>(), unused<DriveSummaryDao>(), unused<ChargeSummaryDao>(), CompletedChargeEventStore(f.context), f.context)
            val session = diagnostics.capture(7)!!
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest("jourvolt.vehicle.uid.v1\u0000${scope.accountNamespace}\u0000provider-a".toByteArray()).joinToString("") { "%02x".format(it) }
            val readiness = DataReadiness(1, digest, emptyList())
            Assert.assertTrue(session.isCurrent())
            Assert.assertTrue(session.verifies(readiness, car))
            Assert.assertFalse(session.verifies(readiness, car.copy(vehicleUid = "provider-b")))
            Assert.assertFalse(session.verifies(readiness, car.copy(carId = 8)))
            f.vehicleContexts.resolveVerifiedHistoryCar(car.copy(vehicleUid = "provider-b"), scope)
            Assert.assertFalse(session.isCurrent())
            Assert.assertFalse(session.verifies(readiness, car))
            f.sessions.save("other-access", "other-refresh", 3600, "other-owner")
            Assert.assertFalse(session.isCurrent())
            Assert.assertFalse(session.requestFence!!.isCurrent())
        } finally { f.close() }
    }
}
