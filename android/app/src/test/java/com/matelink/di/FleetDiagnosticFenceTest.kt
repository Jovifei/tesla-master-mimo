package com.matelink.di

import android.app.Application
import com.matelink.data.api.ArchiveEnrollmentRequestFence
import com.matelink.data.sync.HistoryResourceFixture
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class FleetDiagnosticFenceTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun client(f: HistoryResourceFixture): OkHttpClient {
        val method = f.apiFactory.javaClass.getDeclaredMethod("createOkHttpClient", String::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType, Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        return method.invoke(f.apiFactory, "", true, true, f.modes.currentIdentityGeneration(), true) as OkHttpClient
    }
    private fun request(port: Int, fence: ArchiveEnrollmentRequestFence) = Request.Builder().url("http://127.0.0.1:$port/api/v1/cars/7/telemetry/diagnostics").tag(ArchiveEnrollmentRequestFence::class.java, fence).build()
    @Test fun accountSwapBeforeBearerSnapshotDoesNotSend() = runBlocking {
        val f = HistoryResourceFixture(File(temporary.root, "fence-swap.db")); f.initialize(cloud = true)
        try {
            val (session, generation) = f.sessions.identitySnapshot()
            val fence = ArchiveEnrollmentRequestFence(session!!.userId, generation) { f.sessions.save("other-access", "other-refresh", 3600, "other-owner"); true }
            ServerSocket(0).use { server ->
                server.soTimeout = 250
                try { client(f).newCall(request(server.localPort, fence)).execute().close(); Assert.fail("must reject") } catch (_: IOException) { }
                try { server.accept().close(); Assert.fail("sent request") } catch (_: SocketTimeoutException) { }
            }
        } finally { f.close() }
    }
    @Test fun unauthorizedAndDroppedConnectionNeverRefreshOrReplay() = runBlocking {
        val f = HistoryResourceFixture(File(temporary.root, "fence-retry.db")); f.initialize(cloud = true)
        try {
            val (session, generation) = f.sessions.identitySnapshot()
            val fence = ArchiveEnrollmentRequestFence(session!!.userId, generation) { true }
            val client = client(f)
            Assert.assertFalse(client.retryOnConnectionFailure); Assert.assertFalse(client.followRedirects)
            Assert.assertFalse(client.followSslRedirects)
            Assert.assertSame(okhttp3.Authenticator.NONE, client.authenticator)
            Assert.assertSame(okhttp3.Authenticator.NONE, client.proxyAuthenticator)
            for (status in listOf(401, 407, 408, 503, 0)) ServerSocket(0).use { server ->
                server.soTimeout = 1000
                val hits = AtomicInteger()
                val thread = Thread {
                    try {
                        server.accept().use { socket ->
                            hits.incrementAndGet()
                            if (status != 0) {
                                val reader = socket.getInputStream().bufferedReader()
                                while (reader.readLine()?.isNotEmpty() == true) { }
                                socket.getOutputStream().write("HTTP/1.1 $status fixture\r\nRetry-After: 0\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            }
                        }
                        try { server.accept().use { hits.incrementAndGet() } } catch (_: SocketTimeoutException) { }
                    } catch (_: IOException) { }
                }.apply { isDaemon = true; start() }
                try { client.newCall(request(server.localPort, fence)).execute().use { Assert.assertTrue(status != 0); Assert.assertEquals(status, it.code); Assert.assertEquals("0", it.header("Retry-After")) } }
                catch (e: IOException) { Assert.assertTrue(status == 0 || status == 407) }
                thread.join(2000); Assert.assertEquals(1, hits.get()); Assert.assertEquals(generation, f.sessions.identitySnapshot().second)
            }
        } finally { f.close() }
    }

    @Test fun enrollmentGetAndDecisionPostNeverReplayAfterScopeChanges() = runBlocking {
        val f = HistoryResourceFixture(File(temporary.root, "enrollment-retry.db")); f.initialize(cloud = true)
        try {
            val (session, generation) = f.sessions.identitySnapshot()
            for (post in listOf(false, true)) for (status in listOf(401, 503, 0)) ServerSocket(0).use { server ->
                server.soTimeout = 350
                val current = AtomicBoolean(true)
                val hits = AtomicInteger()
                val fence = ArchiveEnrollmentRequestFence(session!!.userId, generation) { current.get() }
                val thread = Thread {
                    try {
                        server.accept().use { socket ->
                            hits.incrementAndGet()
                            val reader = socket.getInputStream().bufferedReader()
                            while (reader.readLine()?.isNotEmpty() == true) { }
                            current.set(false)
                            if (status != 0) socket.getOutputStream().write(
                                "HTTP/1.1 $status fixture\r\nRetry-After: 0\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()
                            )
                        }
                        try { server.accept().use { hits.incrementAndGet() } } catch (_: SocketTimeoutException) { }
                    } catch (_: IOException) { }
                }.apply { isDaemon = true; start() }
                val url = "http://127.0.0.1:${server.localPort}/api/v1/archive/enrollments/" + "a".repeat(64) +
                    if (post) "/decision" else ""
                val builder = Request.Builder().url(url).tag(ArchiveEnrollmentRequestFence::class.java, fence)
                if (post) builder.post(ByteArray(0).toRequestBody())
                try { client(f).newCall(builder.build()).execute().use { Assert.assertEquals(status, it.code) } }
                catch (e: IOException) { Assert.assertEquals(0, status) }
                thread.join(1500)
                Assert.assertEquals(1, hits.get())
                Assert.assertEquals(generation, f.sessions.identitySnapshot().second)
            }
        } finally { f.close() }
    }
}
