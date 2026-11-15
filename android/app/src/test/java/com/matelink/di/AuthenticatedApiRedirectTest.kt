package com.matelink.di

import android.app.Application
import com.matelink.data.sync.HistoryResourceFixture
import com.matelink.ui.screens.auth.TeslaAuthExchangeRequest
import com.matelink.ui.screens.auth.createTeslaAuthApi
import com.squareup.moshi.Moshi
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class AuthenticatedApiRedirectTest {
    @get:Rule val temporary = TemporaryFolder()
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
        @Volatile var requestLine: String? = null
        private val thread = Thread {
            try {
                listener.accept().use { socket ->
                    socket.soTimeout = 3_000
                    val reader = socket.getInputStream().bufferedReader()
                    var contentLength = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("GET ")) requestLine = line
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

    @Test fun cloudDiagnosticRepositoryRejectsSelfHostedModeWithoutSendingAnyRequest() = runBlocking {
        val fixture = HistoryResourceFixture(File(temporary.root, "cloud-diagnostic-origin-fence.db"))
        fixture.initialize(cloud = false)
        try {
            val result = fixture.apiRepo.getCloudTelemetryDiagnostics(17, com.matelink.data.api.ArchiveEnrollmentRequestFence("fixture", 0) { true })
            assertTrue(result is com.matelink.data.repository.ApiResult.Error)
            assertEquals("source_changed", (result as com.matelink.data.repository.ApiResult.Error).details)
            assertTrue(fixture.calls.isEmpty())
        } finally { fixture.close() }
    }

    @Test fun officialDiagnosticsUsesOnlyAuthenticatedGetAndParsesUnknownFields() = runBlocking {
        val fixture = HistoryResourceFixture(File(temporary.root, "authenticated-diagnostics.db"))
        fixture.initialize(cloud = false)
        try {
            LoopbackServer(200, payload = """{"data":{"availability":"partial","requests":{"fleet_status":{"status":"ok","http_status":200}},"vehicle":{"paired":false,"streaming_enabled":null}}}""").use { origin ->
                val api = fixture.apiFactory.create(origin.baseUrl, apiTokenOverride = "fixture-token")
                val response = api.getTelemetryDiagnostics(17)
                assertEquals(200, response.code())
                assertEquals(false, response.body()?.data?.vehicle?.paired)
                assertNull(response.body()?.data?.vehicle?.streamingEnabled)
                assertNull(response.body()?.data?.configuration)
                assertEquals("GET /api/v1/cars/17/telemetry/diagnostics HTTP/1.1", origin.requestLine)
                assertEquals(1, origin.hits.get())
                assertEquals("Bearer fixture-token", origin.authorization)
            }
        } finally { fixture.close() }
    }

    @Test fun bearerApiNeverVisitsRedirectTarget() = runBlocking {
        val fixture = HistoryResourceFixture(File(temporary.root, "authenticated-api-redirect.db"))
        fixture.initialize(cloud = false)
        try {
            for (status in listOf(301, 302, 303, 307, 308)) {
                LoopbackServer(200, payload = """{"data":{"cars":[]}}""").use { target ->
                    LoopbackServer(status, target.url).use { origin ->
                        val api = fixture.apiFactory.create(origin.baseUrl, apiTokenOverride = "fixture-token")
                        assertEquals("redirect=$status", status, api.getCars().code())
                        assertEquals("redirect=$status", 1, origin.hits.get())
                        assertEquals("Bearer fixture-token", origin.authorization)
                        assertEquals("redirect=$status", 0, target.hits.get())
                    }
                }
            }
        } finally { fixture.close() }
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
