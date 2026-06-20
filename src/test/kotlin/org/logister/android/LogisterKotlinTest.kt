package org.logister.android

import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LogisterKotlinTest {
    @Test
    fun kotlinFacadeBuildsMetricEnvelope() {
        val transport = CapturingTransport()
        val tokenProvider = SequenceTokenProvider(LogisterToken("mobile-token-1", futureEpochSeconds()))
        val client = logisterClient(
            baseUrl = "https://logister.example",
            tokenProvider = tokenProvider
        ) {
            includeDeviceContext(false)
            environment("production")
            release("1.0.0+42")
            repository("acme/android")
            commitSha("abc1234")
            branch("main")
            service("com.example.app")
            transport(transport)
            executor(Executors.newSingleThreadExecutor())
        }

        val response = client.captureMetricAsync("cart.item_count", 3, "count") {
            sessionId("session-123")
            context("screen_name", "Checkout")
        }.get()

        assertTrue(response.isAccepted)
        assertEquals("https://logister.example/api/v1/ingest_events", transport.endpoint)
        assertEquals("mobile-token-1", transport.mobileIngestToken)
        assertEquals(1, tokenProvider.fetchCount)

        val event = transport.envelope.getJSONObject("event")
        val context = event.getJSONObject("context")

        assertEquals("metric", event.getString("event_type"))
        assertEquals("cart.item_count", event.getString("message"))
        assertEquals("production", event.getString("environment"))
        assertEquals("1.0.0+42", event.getString("release"))
        assertEquals("android", context.getString("platform"))
        assertEquals("com.example.app", context.getString("service"))
        assertEquals("acme/android", context.getString("repository"))
        assertEquals("abc1234", context.getString("commit_sha"))
        assertEquals("main", context.getString("branch"))
        assertEquals("session-123", context.getString("session_id"))
        assertEquals("Checkout", context.getString("screen_name"))
        assertEquals(3.0, context.getDouble("value"), 0.001)
        assertEquals("count", context.getString("unit"))
    }

    @Test
    fun kotlinFacadeBuildsSpanEnvelope() {
        val transport = CapturingTransport()
        val client = logisterClient(
            baseUrl = "https://logister.example",
            tokenProvider = SequenceTokenProvider(LogisterToken("mobile-token-1", futureEpochSeconds()))
        ) {
            includeDeviceContext(false)
            transport(transport)
            executor(Executors.newSingleThreadExecutor())
        }

        client.captureSpanAsync(
            logisterSpan("trace-123", "GET /checkout", 42.5) {
                spanId("span-456")
                parentSpanId("span-root")
                kind("http")
                status("ok")
                context("screen_name", "Checkout")
            }
        ).get()

        val event = transport.envelope.getJSONObject("event")
        val context = event.getJSONObject("context")

        assertEquals("span", event.getString("event_type"))
        assertEquals("trace-123", event.getString("trace_id"))
        assertEquals("span-456", event.getString("span_id"))
        assertEquals("span-root", event.getString("parent_span_id"))
        assertEquals("GET /checkout", event.getString("name"))
        assertEquals("http", event.getString("kind"))
        assertEquals(42.5, event.getDouble("duration_ms"), 0.001)
        assertEquals("android", context.getString("platform"))
        assertEquals("Checkout", context.getString("screen_name"))
    }

    @Test
    fun clientCachesTokenUntilRefreshWindow() {
        val transport = CapturingTransport()
        val tokenProvider = SequenceTokenProvider(LogisterToken("mobile-token-1", futureEpochSeconds(300)))
        val client = logisterClient(
            baseUrl = "https://logister.example",
            tokenProvider = tokenProvider
        ) {
            includeDeviceContext(false)
            transport(transport)
            executor(Executors.newSingleThreadExecutor())
        }

        client.captureMessageAsync("one").get()
        client.captureMessageAsync("two").get()

        assertEquals(1, tokenProvider.fetchCount)
        assertEquals(listOf("mobile-token-1", "mobile-token-1"), transport.mobileIngestTokens)
    }

    @Test
    fun clientRefreshesCachedTokenInsideRefreshWindow() {
        val transport = CapturingTransport()
        val tokenProvider = SequenceTokenProvider(
            LogisterToken("mobile-token-1", futureEpochSeconds(30)),
            LogisterToken("mobile-token-2", futureEpochSeconds(300))
        )
        val client = logisterClient(
            baseUrl = "https://logister.example",
            tokenProvider = tokenProvider
        ) {
            includeDeviceContext(false)
            transport(transport)
            executor(Executors.newSingleThreadExecutor())
        }

        client.captureMessageAsync("one").get()
        client.captureMessageAsync("two").get()

        assertEquals(2, tokenProvider.fetchCount)
        assertEquals(listOf("mobile-token-1", "mobile-token-2"), transport.mobileIngestTokens)
    }

    @Test
    fun providerFailureDoesNotSendRequest() {
        val transport = CapturingTransport()
        val client = logisterClient(
            baseUrl = "https://logister.example",
            tokenProvider = LogisterTokenProvider { throw IllegalStateException("token issuer unavailable") }
        ) {
            includeDeviceContext(false)
            transport(transport)
            executor(Executors.newSingleThreadExecutor())
        }

        try {
            client.captureMessageAsync("one").get()
            fail("Expected token provider failure")
        } catch (exception: ExecutionException) {
            assertTrue(exception.cause is IllegalStateException)
        }

        assertEquals(0, transport.sendCount)
    }

    @Test
    fun blankTokenDoesNotSendRequest() {
        val transport = CapturingTransport()
        val client = logisterClient(
            baseUrl = "https://logister.example",
            tokenProvider = SequenceTokenProvider(LogisterToken("", futureEpochSeconds()))
        ) {
            includeDeviceContext(false)
            transport(transport)
            executor(Executors.newSingleThreadExecutor())
        }

        try {
            client.captureMessageAsync("one").get()
            fail("Expected blank token failure")
        } catch (exception: ExecutionException) {
            assertTrue(exception.cause is IllegalArgumentException)
        }

        assertEquals(0, transport.sendCount)
    }

    private class CapturingTransport : LogisterTransport {
        lateinit var endpoint: String
        lateinit var mobileIngestToken: String
        lateinit var envelope: JSONObject
        val mobileIngestTokens = mutableListOf<String>()
        var sendCount = 0

        override fun send(
            endpoint: String,
            mobileIngestToken: String,
            envelope: JSONObject,
            connectTimeoutMs: Int,
            readTimeoutMs: Int
        ): LogisterResponse {
            sendCount += 1
            this.endpoint = endpoint
            this.mobileIngestToken = mobileIngestToken
            this.mobileIngestTokens.add(mobileIngestToken)
            this.envelope = envelope
            return LogisterResponse(201, """{"status":"accepted"}""")
        }
    }

    private class SequenceTokenProvider(
        vararg tokens: LogisterToken
    ) : LogisterTokenProvider {
        private val tokens = ArrayDeque(tokens.toList())
        var fetchCount = 0

        override fun fetchToken(): LogisterToken {
            fetchCount += 1
            return if (tokens.size > 1) tokens.removeFirst() else tokens.first()
        }
    }

    private fun futureEpochSeconds(seconds: Long = 300): Long =
        System.currentTimeMillis() / 1000 + seconds
}
