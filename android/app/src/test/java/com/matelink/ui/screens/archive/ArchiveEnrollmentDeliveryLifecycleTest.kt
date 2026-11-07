package com.matelink.ui.screens.archive

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import com.matelink.data.api.models.ArchiveEnrollmentPreview
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Android lifecycle and production delivery observer; all backend responses are synthetic. */
class EnrollmentDeliveryTestActivity : ComponentActivity() {
    companion object { lateinit var model: ArchiveEnrollmentViewModel }
    private var observer: ArchiveEnrollmentLifecycleObserver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        install(archiveIntentForActivityCreate(intent, savedInstanceState != null))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        observer?.let(lifecycle::removeObserver)
        model.dismiss()
        install(intent)
    }

    private fun install(intent: Intent?) {
        observer = ArchiveEnrollmentLifecycleObserver(intent, model).also {
            lifecycle.addObserver(it)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) it.deliverOnce()
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ArchiveEnrollmentDeliveryLifecycleTest {
    private val first = "ab".repeat(32)
    private val second = "cd".repeat(32)
    private val now = Instant.parse("2026-10-29T04:00:00Z")
    private val identity = ArchiveEnrollmentIdentity("fake-account", "https://fake.invalid", 1, 1, 7, -17, "f".repeat(64))
    private val previews = mutableListOf<String>()
    private val decisions = mutableListOf<Boolean>()
    private lateinit var model: ArchiveEnrollmentViewModel

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        model = ArchiveEnrollmentViewModel(ArchiveEnrollmentDependencies(
            captureIdentity = { identity }, isIdentityCurrent = { it == identity },
            preview = { handle, _ ->
                previews += handle
                ArchiveEnrollmentPreview(handle, "pending", "123456", "2026-10-29T04:10:00Z",
                    7, "Synthetic vehicle", "TeslaMate", "f".repeat(16), "active")
            },
            decision = { _, approved, _, _ -> decisions += approved; error("unexpected decision") },
            now = { now }
        ))
        EnrollmentDeliveryTestActivity.model = model
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun link(handle: String) = Intent(Intent.ACTION_VIEW,
        Uri.parse("https://auth.teslalink.joviluma.com/archive/enroll?handle=$handle"))

    @Test fun cancelThenRecreateDoesNotReplayButExplicitNewIntentWorks() {
        val activity = Robolectric.buildActivity(EnrollmentDeliveryTestActivity::class.java, link(first)).setup()
        assertEquals(listOf(first), previews)
        assertEquals(ArchiveEnrollmentPhase.READY, model.uiState.value.phase)
        model.dismiss()
        activity.recreate()
        assertEquals(listOf(first), previews)
        assertEquals(ArchiveEnrollmentPhase.HIDDEN, model.uiState.value.phase)
        activity.newIntent(link(first))
        assertEquals(listOf(first, first), previews)
        assertEquals(ArchiveEnrollmentPhase.READY, model.uiState.value.phase)
        activity.newIntent(link(second))
        assertEquals(listOf(first, first, second), previews)
        assertEquals(ArchiveEnrollmentPhase.READY, model.uiState.value.phase)
        assertEquals(emptyList<Boolean>(), decisions)
        activity.pause().stop().destroy()
    }

    @Test fun backgroundThenRecreateNeverReopensOldDelivery() {
        val activity = Robolectric.buildActivity(EnrollmentDeliveryTestActivity::class.java, link(first)).setup()
        assertEquals(ArchiveEnrollmentPhase.READY, model.uiState.value.phase)
        activity.pause().stop()
        assertEquals(ArchiveEnrollmentPhase.HIDDEN, model.uiState.value.phase)
        activity.restart().start().resume().visible()
        assertEquals(listOf(first), previews)
        assertEquals(ArchiveEnrollmentPhase.HIDDEN, model.uiState.value.phase)
        activity.recreate()
        assertEquals(listOf(first), previews)
        assertEquals(ArchiveEnrollmentPhase.HIDDEN, model.uiState.value.phase)
        activity.newIntent(link(second))
        assertEquals(listOf(first, second), previews)
        assertEquals(emptyList<Boolean>(), decisions)
        activity.pause().stop().destroy()
    }
}
