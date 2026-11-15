package com.matelink.di

import android.app.Application
import com.matelink.data.local.ConnectionMode
import com.matelink.data.sync.HistoryResourceFixture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class NotificationPinnedApiTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun aSelfHostedNotificationClientCannotBeConstructedInCloudMode() = runBlocking {
        val f = HistoryResourceFixture(File(temporary.root, "pin-construction.db"))
        f.initialize(cloud = true)
        try {
            assertTrue(runCatching { f.apiFactory.create("https://synthetic.example", requiredCloudMode = false) }.isFailure)
        } finally { f.close() }
    }

    @Test fun aConstructedSelfHostedClientRejectsModeAbaBeforeAnyHttpAdmission() = runBlocking {
        val f = HistoryResourceFixture(File(temporary.root, "pin-aba.db"))
        f.initialize(cloud = false)
        val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val hits = AtomicInteger()
        val server = Thread {
            try {
                while (!socket.isClosed) socket.accept().use { connection ->
                    hits.incrementAndGet()
                    val input = connection.getInputStream().bufferedReader()
                    while (input.readLine()?.isNotEmpty() == true) Unit
                    val body = "{\"data\":{\"cars\":[]}}"
                    connection.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body").toByteArray())
                }
            } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        try {
            val url = "http://127.0.0.1:${socket.localPort}"
            val pinned = f.apiFactory.create(url, requiredCloudMode = false)
            assertTrue("The isolated loopback fixture must accept the original pinned request", pinned.getCars().isSuccessful)
            assertEquals(1, hits.get())
            hits.set(0)
            f.modes.set(ConnectionMode.TESLA_CLOUD)
            f.modes.set(ConnectionMode.SELF_HOSTED)
            assertTrue(runCatching { pinned.getCars() }.isFailure)
            assertEquals(0, hits.get())
        } finally { socket.close(); server.join(1000); f.close() }
    }
}
