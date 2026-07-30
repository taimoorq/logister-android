package org.logister.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

class LogisterDurableCaptureTest {
    @Test
    fun tokenFailureQueuesAndLaterFlushesEnvelope() {
        val tokenProvider = MutableTokenProvider()
        val transport = RecordingTransport()
        val client = client(tokenProvider, transport)
        val queue = queue()
        client.attachOfflineQueue(queue)

        val response = client.captureMessageAsync("queued event").get()
        assertTrue(response.isQueued)
        assertEquals(1, client.queuedEventCount())
        assertEquals(0, transport.envelopes.size)

        tokenProvider.token = LogisterToken("short-lived", futureEpochSeconds())
        assertEquals(1, client.flushQueuedEvents())
        assertEquals(0, client.queuedEventCount())
        assertEquals("queued event", transport.envelopes.single().getJSONObject("event").getString("message"))
    }

    @Test
    fun automaticCrashEnvelopeUsesSafePolicyBeforeAuthentication() {
        val store = InMemoryEnvelopeStore()
        val client = client(MutableTokenProvider(), RecordingTransport())
        client.attachOfflineQueue(
            LogisterOfflineQueue(
                store = store,
                maxEvents = 10,
                maxBytes = 64 * 1024,
                maxAgeDays = 7,
                nowMillis = { 1_000L },
            ),
        )
        val throwable = IllegalStateException(
            "bearer secret-value",
            IllegalArgumentException("private nested detail"),
        )

        client.captureUncaughtException(throwable)

        val stored = JSONArray(store.value).getJSONObject(0)
        val event = JSONObject(stored.getString("envelope")).getJSONObject("event")
        val context = event.getJSONObject("context")
        assertEquals(throwable.javaClass.name, event.getString("message"))
        assertFalse(event.toString().contains("secret-value"))
        assertFalse(event.toString().contains("private nested detail"))
        assertEquals("automatic", context.getJSONObject("error").getString("capture_source"))
        assertEquals(
            "type_and_stacktrace",
            context.getJSONObject("error").getString("data_policy"),
        )
        assertEquals("unhandled_exception", context.getJSONObject("error").getString("mechanism"))
        assertFalse(context.getJSONObject("error").getBoolean("handled"))
    }

    @Test
    fun accountCleanupRemovesSessionAndUserBoundEventsButRetainsAutomaticCrashes() {
        val client = client(MutableTokenProvider(), RecordingTransport())
        val queue = queue()
        client.attachOfflineQueue(queue)
        queue.enqueue(
            JSONObject().put(
                "event",
                JSONObject()
                    .put("message", "handled")
                    .put("session_id", "session-123"),
            ),
        )
        queue.enqueue(
            JSONObject().put(
                "event",
                JSONObject()
                    .put("message", "context session")
                    .put("context", JSONObject().put("session_id", "session-456")),
            ),
        )
        queue.enqueue(
            JSONObject().put(
                "event",
                JSONObject()
                    .put("message", "top-level user")
                    .put("user_id", "user-123"),
            ),
        )
        queue.enqueue(
            JSONObject().put(
                "event",
                JSONObject()
                    .put("message", "context user")
                    .put("context", JSONObject().put("user_id", 456)),
            ),
        )
        queue.enqueue(
            JSONObject().put(
                "event",
                JSONObject()
                    .put("message", "automatic")
                    .put("context", JSONObject().put("capture_source", "automatic")),
            ),
        )

        assertEquals(4, client.clearSessionBoundQueuedEvents())
        assertEquals(1, client.queuedEventCount())
    }

    private fun client(
        tokenProvider: LogisterTokenProvider,
        transport: LogisterTransport,
    ): LogisterClient = LogisterClient
        .builder(tokenProvider, "https://logister.example")
        .includeDeviceContext(false)
        .transport(transport)
        .executor(Executors.newSingleThreadExecutor())
        .build()

    private fun queue(): LogisterOfflineQueue = LogisterOfflineQueue(
        store = InMemoryEnvelopeStore(),
        maxEvents = 10,
        maxBytes = 64 * 1024,
        maxAgeDays = 7,
        nowMillis = { 1_000L },
    )

    private fun futureEpochSeconds(): Long = System.currentTimeMillis() / 1_000 + 300
}

private class MutableTokenProvider : LogisterTokenProvider {
    var token: LogisterToken? = null

    override fun fetchToken(): LogisterToken = token ?: throw IllegalStateException("signed out")
}

private class RecordingTransport : LogisterTransport {
    val envelopes = mutableListOf<JSONObject>()

    override fun send(
        endpoint: String,
        mobileIngestToken: String,
        envelope: JSONObject,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): LogisterResponse {
        envelopes += JSONObject(envelope.toString())
        return LogisterResponse(202)
    }
}
