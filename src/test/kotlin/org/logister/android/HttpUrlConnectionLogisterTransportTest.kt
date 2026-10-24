package org.logister.android

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpUrlConnectionLogisterTransportTest {
    @Test
    fun userAgentUsesTheSharedSdkVersion() {
        val userAgent = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/ingest") { exchange ->
            userAgent.set(exchange.requestHeaders.getFirst("User-Agent"))
            exchange.requestBody.use { it.readBytes() }
            exchange.sendResponseHeaders(202, -1)
            exchange.close()
        }
        server.start()

        try {
            val response =
                HttpUrlConnectionLogisterTransport().send(
                    endpoint = "http://127.0.0.1:${server.address.port}/api/v1/ingest",
                    mobileIngestToken = "short-lived",
                    envelope = JSONObject().put("event", JSONObject()),
                    connectTimeoutMs = 2_000,
                    readTimeoutMs = 2_000,
                )

            assertEquals(202, response.statusCode)
            assertEquals("logister-android/$LOGISTER_ANDROID_SDK_VERSION", userAgent.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun parsesRetryAfterSecondsFromAThrottledResponse() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/ingest") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            exchange.responseHeaders.add("Retry-After", "12")
            exchange.sendResponseHeaders(429, -1)
            exchange.close()
        }
        server.start()

        try {
            val response = HttpUrlConnectionLogisterTransport().send(
                endpoint = "http://127.0.0.1:${server.address.port}/api/v1/ingest",
                mobileIngestToken = "short-lived",
                envelope = JSONObject().put("event", JSONObject()),
                connectTimeoutMs = 2_000,
                readTimeoutMs = 2_000,
            )
            assertEquals(429, response.statusCode)
            assertEquals(12_000L, response.retryAfterMillis)
            assertTrue(!response.isAccepted)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun doesNotFollowRedirectsWithTheBearerToken() {
        val redirectedAuthorization = AtomicReference<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/ingest") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${server.address.port}/elsewhere")
            exchange.sendResponseHeaders(307, -1)
            exchange.close()
        }
        server.createContext("/elsewhere") { exchange ->
            redirectedAuthorization.set(exchange.requestHeaders.getFirst("Authorization"))
            exchange.sendResponseHeaders(202, -1)
            exchange.close()
        }
        server.start()

        try {
            val response = HttpUrlConnectionLogisterTransport().send(
                endpoint = "http://127.0.0.1:${server.address.port}/api/v1/ingest",
                mobileIngestToken = "short-lived",
                envelope = JSONObject().put("event", JSONObject()),
                connectTimeoutMs = 2_000,
                readTimeoutMs = 2_000,
            )
            assertEquals(307, response.statusCode)
            assertNull(redirectedAuthorization.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun responseBodyReadsAreBounded() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/ingest") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val body = ByteArray(1_024 * 1_024) { 'a'.code.toByte() }
            exchange.sendResponseHeaders(500, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()

        try {
            val response = HttpUrlConnectionLogisterTransport().send(
                endpoint = "http://127.0.0.1:${server.address.port}/api/v1/ingest",
                mobileIngestToken = "short-lived",
                envelope = JSONObject().put("event", JSONObject()),
                connectTimeoutMs = 2_000,
                readTimeoutMs = 2_000,
            )
            assertEquals(500, response.statusCode)
            assertEquals(64 * 1_024, response.body.length)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun clientRequiresHttpsExceptForLoopbackEmulatorOrExplicitDebugOptIn() {
        listOf(
            "https://logister.example",
            "http://localhost:3000",
            "http://127.0.0.1:3000",
            "http://[::1]:3000",
            "http://10.0.2.2:3000",
        ).forEach { assertTrue(it, isPermittedEndpoint(it, allowInsecureHttp = false)) }
        listOf("http://logister.example", "http://192.168.1.10:3000", "ftp://logister.example", "not a url")
            .forEach { assertFalse(it, isPermittedEndpoint(it, allowInsecureHttp = false)) }
        assertTrue(isPermittedEndpoint("http://192.168.1.10:3000", allowInsecureHttp = true))

        val provider = LogisterTokenProvider { LogisterToken("synthetic", System.currentTimeMillis() / 1_000 + 300) }
        assertThrows(IllegalArgumentException::class.java) {
            LogisterClient.builder(provider, "http://logister.example").build()
        }
        LogisterClient.builder(provider, "http://logister.example").allowInsecureHttp(true).build()
    }
}
