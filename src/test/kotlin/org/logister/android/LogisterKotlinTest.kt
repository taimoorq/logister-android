package org.logister.android

import java.util.concurrent.Executors
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogisterKotlinTest {
    @Test
    fun kotlinFacadeBuildsMetricEnvelope() {
        val transport = CapturingTransport()
        val client = logisterClient("test-token", "https://logister.example") {
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
        assertEquals("test-token", transport.apiKey)

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
        val client = logisterClient("test-token", "https://logister.example") {
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

    private class CapturingTransport : LogisterTransport {
        lateinit var endpoint: String
        lateinit var apiKey: String
        lateinit var envelope: JSONObject

        override fun send(
            endpoint: String,
            apiKey: String,
            envelope: JSONObject,
            connectTimeoutMs: Int,
            readTimeoutMs: Int
        ): LogisterResponse {
            this.endpoint = endpoint
            this.apiKey = apiKey
            this.envelope = envelope
            return LogisterResponse(201, """{"status":"accepted"}""")
        }
    }
}
