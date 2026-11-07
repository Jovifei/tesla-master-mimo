package com.matelink.data.local

import android.app.Application
import android.content.Context
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class JourVoltSessionConcurrencyTest {
    private class LoopbackServer(
        private val status: Int,
        private val location: String? = null,
        private val payload: String = ""
    ) : AutoCloseable {
        private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val url: String = "http://127.0.0.1:${listener.localPort}/capture"
        val hits = AtomicInteger()
        var requestBody: String? = null
            private set
        private val thread = Thread {
            try {
                listener.accept().use { socket ->
                    socket.soTimeout = 3_000
                    val reader = socket.getInputStream().bufferedReader()
                    var contentLength = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) {
                            contentLength = line.substringAfter(':').trim().toInt()
                        }
                    }
                    requestBody = buildString {
                        repeat(contentLength) { append(reader.read().toChar()) }
                    }
                    hits.incrementAndGet()
                    val body = payload.toByteArray()
                    val headers = buildString {
                        append("HTTP/1.1 $status fixture\r\n")
                        if (location != null) append("Location: $location\r\n")
                        append("Content-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n")
                    }
                    socket.getOutputStream().apply {
                        write(headers.toByteArray())
                        write(body)
                        flush()
                    }
                }
            } catch (_: java.net.SocketException) {
                // Closing an unused redirect target interrupts accept().
            }
        }.apply { isDaemon = true; start() }

        override fun close() {
            listener.close()
            thread.join(1_000)
        }
    }

    private fun sessions(): JourVoltSessionStore {
        val context = RuntimeEnvironment.getApplication()
        return JourVoltSessionStore(context.getSharedPreferences("refresh-${UUID.randomUUID()}", Context.MODE_PRIVATE))
    }

    private fun refresher(store: JourVoltSessionStore, interceptor: Interceptor): JourVoltSessionRefresher =
        JourVoltSessionRefresher(store).also { refresher ->
            val field = JourVoltSessionRefresher::class.java.getDeclaredField("client")
                .apply { isAccessible = true }
            val client = (field.get(refresher) as OkHttpClient).newBuilder()
                .addInterceptor(interceptor).build()
            field.set(refresher, client)
        }

    private fun response(chain: Interceptor.Chain, status: Int, body: String = "", location: String? = null): Response =
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("fixture")
            .body(body.toResponseBody("application/json".toMediaType()))
            .apply { if (location != null) header("Location", location) }
            .build()

    @Test fun oldRequestCannotRetryWithNewAccountsToken() = runBlocking {
        val store = sessions()
        store.save("access-a", "refresh-a", 3600, "account-a")
        val refresher = JourVoltSessionRefresher(store)
        store.save("access-b", "refresh-b", 3600, "account-b")

        assertNull(refresher.refreshIfCurrent("access-a"))
        assertEquals("account-b", store.current()?.userId)
    }

    @Test fun concurrentOldRequestsCanUseOnlyTheSameRefreshRotation() = runBlocking {
        val store = sessions()
        store.save("access-a", "refresh-a", 3600, "account-a")
        val requests = AtomicInteger()
        val refresher = refresher(store, Interceptor { chain ->
            requests.incrementAndGet()
            response(chain, 200, """{"access_token":"new-a","refresh_token":"new-refresh-a","expires_in":3600}""")
        })

        assertEquals("new-a", refresher.refreshIfCurrent("access-a"))
        assertEquals("new-a", refresher.refreshIfCurrent("access-a"))
        assertEquals(1, requests.get())
    }

    @Test fun lateSuccessfulRefreshCannotRestoreLoggedOutSession() {
        val store = sessions()
        store.save("access-a", "refresh-a", 3600, "account-a")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val refresher = refresher(store, Interceptor { chain ->
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            response(chain, 200, """{"access_token":"new-a","refresh_token":"new-refresh-a","expires_in":3600}""")
        })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<String?> { runBlocking { refresher.refreshIfCurrent("access-a") } }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            store.clear()
            release.countDown()
            assertNull(result.get(5, TimeUnit.SECONDS))
            assertNull(store.current())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun lateUnauthorizedRefreshCannotClearNewLogin() {
        val store = sessions()
        store.save("access-a", "refresh-a", 3600, "account-a")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val refresher = refresher(store, Interceptor { chain ->
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            response(chain, 401)
        })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<String?> { runBlocking { refresher.refreshIfCurrent("access-a") } }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            store.save("access-b", "refresh-b", 3600, "account-b")
            release.countDown()
            assertNull(result.get(5, TimeUnit.SECONDS))
            assertEquals("account-b", store.current()?.userId)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun cancelledRefreshCannotPublishTokensAfterBodyArrives() = runBlocking {
        val store = sessions()
        store.save("access-a", "refresh-a", 3600, "account-a")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val refresher = refresher(store, Interceptor { chain ->
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            response(chain, 200, """{"access_token":"new-a","refresh_token":"new-refresh-a","expires_in":3600}""")
        })
        val request = CoroutineScope(Dispatchers.IO).async { refresher.refreshIfCurrent("access-a") }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            request.cancel()
            release.countDown()
            request.join()
            assertEquals("access-a", store.current()?.accessToken)
        } finally {
            release.countDown()
            request.cancelAndJoin()
        }
    }

    @Test fun refreshNeverFollowsRedirectWithCredentialBody() = runBlocking {
        val store = sessions()
        store.save("access-a", "refresh-a", 3600, "account-a")
        for (status in listOf(301, 302, 303, 307, 308)) {
            LoopbackServer(200, payload = """{"access_token":"leaked","refresh_token":"leaked","expires_in":3600}""").use { second ->
                LoopbackServer(status, location = second.url).use { first ->
                val refresher = refresher(store, Interceptor { chain ->
                    chain.proceed(chain.request().newBuilder()
                        .url(first.url).build())
                })
                assertNull("redirect=$status", refresher.refreshIfCurrent("access-a"))
                assertEquals("redirect=$status", 1, first.hits.get())
                assertTrue("redirect=$status", first.requestBody?.contains("refresh-a") == true)
                assertEquals("redirect=$status", 0, second.hits.get())
                assertEquals("access-a", store.current()?.accessToken)
                }
            }
        }
    }
}
