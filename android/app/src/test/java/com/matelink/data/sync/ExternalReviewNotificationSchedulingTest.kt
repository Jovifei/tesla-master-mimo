package com.matelink.data.sync

import android.app.Application
import android.content.Intent
import androidx.work.WorkInfo
import androidx.work.impl.WorkManagerImpl
import com.matelink.MateLinkApplication
import com.matelink.receiver.BootReceiver
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication
import org.w3c.dom.Element

/** Actual application startup + persisted WorkInfo. Delayed workers are never run against a server.
 * Permission and provider checks below are wiring contracts, not device/Compose interaction proof. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class ExternalReviewNotificationSchedulingTest {
    private val managers = mutableListOf<WorkManagerImpl>()
    private val androidNamespace = "http://schemas.android.com/apk/res/android"

    @Before fun isolateWorkManagerSingleton() {
        // A test sandbox owns its WorkManager; production application initialization remains real.
        WorkManagerImpl.setDelegate(null)
        val field = WorkManagerImpl::class.java.getDeclaredField("sDefaultInstance").apply { isAccessible = true }
        (field.get(null) as? WorkManagerImpl)?.let {
            it.cancelAllWork().result.get()
            it.workDatabase.close()
        }
        field.set(null, null)
    }

    @After fun releaseWorkManagers() {
        managers.forEach { it.cancelAllWork().result.get() }
        WorkManagerImpl.setDelegate(null)
        managers.forEach { it.workDatabase.close() }
        WorkManagerImpl::class.java.getDeclaredField("sDefaultInstance")
            .apply { isAccessible = true }.set(null, null)
    }

    private fun startApplication(mainProcess: Boolean): Pair<MateLinkApplication, WorkManagerImpl> {
        val context: Application = RuntimeEnvironment.getApplication()
        val processName = context.packageName + if (mainProcess) "" else ":amap_verification"
        ShadowApplication.setProcessName(processName)
        val application = MateLinkApplication()
        shadowOf(application).callAttach(context)
        application.onCreate()
        val manager = WorkManagerImpl.getInstance(application)
        managers += manager
        return application to manager
    }

    private fun liveChargingWork(manager: WorkManagerImpl): List<WorkInfo> =
        manager.getWorkInfosForUniqueWork(ChargingNotificationWorker.WORK_NAME).get()
            .filterNot { it.state.isFinished }

    @Test fun actualMainProcessColdStartupSchedulesChargingWithoutWaitingForBoot() = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        val (_, manager) = startApplication(mainProcess = true)
        val active = liveChargingWork(manager)
        assertEquals("A fresh main-process launch must create the charging observation task", 1, active.size)
        assertTrue(manager.getWorkInfosForUniqueWork(ChargingNotificationWorker.PERIODIC_WORK_NAME)
            .get().any { !it.state.isFinished })
        val spec = requireNotNull(manager.workDatabase.workSpecDao().getWorkSpec(active.single().id.toString()))
        assertEquals(ChargingNotificationWorker::class.java.name, spec.workerClassName)
        assertTrue("Bootstrap registration must keep the existing delay, not make an immediate vehicle request",
            spec.initialDelay > 0)
    }

    @Test fun repeatedStartupRegistrationKeepsTheSamePendingChargingTask() = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        val (application, manager) = startApplication(mainProcess = true)
        val first = liveChargingWork(manager)
        assertEquals("Startup must first create the task before idempotence can be checked", 1, first.size)

        // Boot and process startup register the same monitoring responsibility.
        BootReceiver().onReceive(application, Intent(Intent.ACTION_BOOT_COMPLETED))

        val repeated = liveChargingWork(manager)
        assertEquals("Repeated registration must neither replace nor append a second pending task",
            first.map { it.id }, repeated.map { it.id })
        assertEquals(1, manager.getWorkInfosForUniqueWork(ChargingNotificationWorker.PERIODIC_WORK_NAME)
            .get().count { !it.state.isFinished })
    }

    @Test fun secondaryProcessStartupDoesNotRegisterChargingObservationWork() {
        val (_, manager) = startApplication(mainProcess = false)
        assertTrue(liveChargingWork(manager).isEmpty())
        assertTrue(manager.getWorkInfosForUniqueWork(ChargingNotificationWorker.PERIODIC_WORK_NAME).get().isEmpty())
    }

    private fun mainFile(relative: String): File =
        listOf(File("src/main", relative), File("android/app/src/main", relative))
            .firstOrNull(File::isFile) ?: error("Missing main source: $relative")

    @Test fun dismissingPermissionRationaleDoesNotPersistASystemPermissionRequest() {
        val source = mainFile("java/com/matelink/ui/navigation/NavGraph.kt").readText()
        val dialog = source.substringAfter("// One-time notification permission dialog", "")
            .substringBefore("// Handle deep-link")
        assertTrue("Exercise the actual notification rationale wiring", dialog.contains("onDismissRequest"))
        val dismiss = dialog.substringAfter("onDismissRequest = {", "").substringBefore("},")
        assertTrue("Dismiss must still defer this dialog in memory", dismiss.isNotBlank())
        assertFalse("Dismissing rationale is not a POST_NOTIFICATIONS request result",
            dismiss.contains("markNotificationPermissionAsked") || dismiss.contains("saveNotificationPermissionAsked"))
        assertFalse(dismiss.contains("permissionLauncher.launch"))
        assertTrue("The explicit grant action must still launch the actual platform permission request",
            dialog.contains("permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)"))
    }

    private fun xml(relative: String): Element = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(mainFile(relative)).documentElement

    @Test fun widgetProviderRoutesToItsExistingConfigurationActivityWithoutLayoutChanges() {
        val provider = xml("res/xml/car_widget_info.xml")
        val configure = provider.getAttributeNS(androidNamespace, "configure")
        assertTrue("Adding a widget must open its existing vehicle configuration Activity", configure.isNotBlank())
        fun resolved(name: String) = if (name.startsWith(".")) "com.matelink$name" else name
        assertEquals("com.matelink.widget.CarWidgetConfigActivity", resolved(configure))
        val manifest = xml("AndroidManifest.xml")
        val activities = manifest.getElementsByTagName("activity")
        assertTrue((0 until activities.length).map { activities.item(it) as Element }.any {
            resolved(it.getAttributeNS(androidNamespace, "name")) == resolved(configure)
        })
        assertEquals("110dp", provider.getAttributeNS(androidNamespace, "minWidth"))
        assertEquals("110dp", provider.getAttributeNS(androidNamespace, "minHeight"))
        assertEquals("horizontal|vertical", provider.getAttributeNS(androidNamespace, "resizeMode"))
        assertEquals("@layout/widget_loading", provider.getAttributeNS(androidNamespace, "initialLayout"))
        assertEquals("@layout/widget_loading", provider.getAttributeNS(androidNamespace, "previewLayout"))
    }
}
