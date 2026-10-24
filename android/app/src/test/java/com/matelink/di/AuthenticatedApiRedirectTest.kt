package com.matelink.di

import com.matelink.ui.screens.auth.TeslaAuthExchangeRequest
import com.matelink.ui.screens.auth.createTeslaAuthApi
import com.squareup.moshi.Moshi
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticatedApiRedirectTest {
    private class LoopbackServer(
        private val status: Int,
        private val location: String? = null,
        private val payload: String = ""
    ) : AutoCloseable {
        private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val baseUrl = "http://127.0.0.1:${listener.localPort}/"
        val url = "${baseUrl}history"
        val hits = AtomicInteger()
        @Volatile var authorization: String? = null
        @Volatile var requestBody: String? = null
        private val thread = Thread {
            try {
                listener.accept().use { socket ->
                    socket.soTimeout = 3_000
                    val reader = socket.getInputStream().bufferedReader()
                    var contentLength = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Authorization:", ignoreCase = true)) {
                            authorization = line.substringAfter(':').trim()
                        }
                        if (line.startsWith("Content-Length:", ignoreCase = true)) {
                            contentLength = line.substringAfter(':').trim().toInt()
                        }
                    }
                    requestBody = buildString { repeat(contentLength) { append(reader.read().toChar()) } }
                    hits.incrementAndGet()
                    val body = payload.toByteArray()
                    val headers = buildString {
                        append("HTTP/1.1 $status fixture\r\n")
                        if (location != null) append("Location: $location\r\n")
                        append("Content-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n")
                    }
                    socket.getOutputStream().apply { write(headers.toByteArray()); write(body); flush() }
                }
            } catch (_: java.net.SocketException) {
                // An unused redirect target stops waiting when the test closes it.
            }
        }.apply { isDaemon = true; start() }

        override fun close() {
            listener.close()
            thread.join(1_000)
        }
    }

    private fun authenticatedClient(): OkHttpClient {
        // Exercise the factory's real client policy without constructing unrelated DataStores.
        val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        val factory = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, TeslamateApiFactory::class.java) as TeslamateApiFactory
        val method = TeslamateApiFactory::class.java
            .getDeclaredMethod("createOkHttpClient", String::class.java, java.lang.Boolean.TYPE)
            .apply { isAccessible = true }
        return method.invoke(factory, "fixture-token", false) as OkHttpClient
    }

    @Test fun bearerApiNeverVisitsRedirectTarget() {
        for (status in listOf(301, 302, 303, 307, 308)) {
            LoopbackServer(200).use { target ->
                LoopbackServer(status, target.url).use { origin ->
                    val client = authenticatedClient().newBuilder()
                        .addInterceptor(Interceptor { chain ->
                            chain.proceed(chain.request().newBuilder().url(origin.url).build())
                        }).build()
                    val request = Request.Builder().url("https://unused.invalid/history").get().build()
                    client.newCall(request).execute().use { response ->
                        assertEquals("redirect=$status", status, response.code)
                    }
                    assertEquals("redirect=$status", 1, origin.hits.get())
                    assertEquals("Bearer fixture-token", origin.authorization)
                    assertEquals("redirect=$status", 0, target.hits.get())
                }
            }
        }
    }

    @Test fun exchangeTicketAndLogoutBearerNeverVisitRedirectTarget() = runBlocking {
        for (status in listOf(301, 302, 303, 307, 308)) {
            for (exchange in listOf(true, false)) {
                LoopbackServer(200, payload = """{"access_token":"x","refresh_token":"y","expires_in":3600,"user":{"id":"u"}}""").use { target ->
                    LoopbackServer(status, target.url).use { origin ->
                        val api = createTeslaAuthApi(Moshi.Builder().build(), origin.baseUrl)
                        val code = if (exchange) api.exchange(TeslaAuthExchangeRequest("fixture-ticket")).code()
                            else api.logout("Bearer fixture-access").code()
                        assertEquals("redirect=$status exchange=$exchange", status, code)
                        assertEquals(1, origin.hits.get())
                        if (exchange) {
                            assertNull(origin.authorization)
                            assertTrue(origin.requestBody?.contains("fixture-ticket") == true)
                        } else {
                            assertEquals("Bearer fixture-access", origin.authorization)
                        }
                        assertEquals("redirect=$status exchange=$exchange", 0, target.hits.get())
                    }
                }
            }
        }
    }
}
