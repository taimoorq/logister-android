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
        val queuedEvent = JSONObject(
            JSONArray((queueStore(queue)).value).getJSONObject(0).getString("envelope"),
        ).getJSONObject("event")
        val queuedUuid = queuedEvent.getString("uuid")
        val queuedOccurredAt = queuedEvent.getString("occurred_at")

        tokenProvider.token = LogisterToken("short-lived", futureEpochSeconds())
        assertEquals(1, client.flushQueuedEvents())
        assertEquals(0, client.queuedEventCount())
        val deliveredEvent = transport.envelopes.single().getJSONObject("event")
        assertEquals("queued event", deliveredEvent.getString("message"))
        assertEquals(queuedUuid, deliveredEvent.getString("uuid"))
        assertEquals(queuedOccurredAt, deliveredEvent.getString("occurred_at"))
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

        client.captureUncaughtException(Thread("checkout-crash"), throwable)

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
        assertEquals("crashed", context.getJSONObject("error").getString("thread_role"))
        assertEquals("checkout-crash", context.getJSONObject("error").getString("thread_name"))
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

    @Test
    fun disablingCollectionPurgesQueuedDataAndDropsFutureCapture() {
        val tokenProvider = MutableTokenProvider()
        val transport = RecordingTransport()
        val client = client(tokenProvider, transport)
        client.attachOfflineQueue(queue())
        client.addBreadcrumb(LogisterBreadcrumb.builder("private trail").build())
        assertTrue(client.captureMessageAsync("queued before opt out").get().isQueued)
        assertEquals(1, client.queuedEventCount())

        client.setCollectionEnabled(false)

        assertFalse(client.isCollectionEnabled())
        assertEquals(0, client.queuedEventCount())
        val response = client.captureMessageAsync("must not send").get()
        assertFalse(response.isAccepted)
        assertFalse(response.isQueued)
        assertEquals("dropped: collection disabled", response.body)
        assertEquals(0, transport.envelopes.size)
    }

    @Test
    fun beforeSendCanDiscardWithoutAuthenticationOrPersistence() {
        val tokenProvider = MutableTokenProvider()
        val transport = RecordingTransport()
        val client = LogisterClient.builder(tokenProvider, "https://logister.example")
            .includeDeviceContext(false)
            .beforeSend(LogisterBeforeSend { null })
            .transport(transport)
            .executor(Executors.newSingleThreadExecutor())
            .build()
        client.attachOfflineQueue(queue())

        val response = client.captureMessageAsync("discard me").get()

        assertTrue(response.isDropped)
        assertEquals(1, client.healthSnapshot().discardedEventCount)
        assertEquals(0, client.queuedEventCount())
        assertEquals(0, transport.envelopes.size)
    }

    @Test
    fun permanentClientRejectionIsCountedAndNeverPoisonsTheQueue() {
        val client = LogisterClient.builder(
            LogisterTokenProvider { LogisterToken("short-lived", futureEpochSeconds()) },
            "https://logister.example",
        )
            .includeDeviceContext(false)
            .transport(LogisterTransport { _, _, _, _, _ -> LogisterResponse(422) })
            .executor(Executors.newSingleThreadExecutor())
            .build()
        client.attachOfflineQueue(queue())

        val response = client.captureMessageAsync("invalid").get()

        assertEquals(422, response.statusCode)
        assertEquals(0, client.queuedEventCount())
        assertEquals(1, client.healthSnapshot().discardedEventCount)
        assertEquals("permanent HTTP 422", client.healthSnapshot().lastError)
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

    private val queueStores = java.util.IdentityHashMap<LogisterOfflineQueue, InMemoryEnvelopeStore>()

    private fun queue(): LogisterOfflineQueue {
        val store = InMemoryEnvelopeStore()
        return LogisterOfflineQueue(
        store = store,
        maxEvents = 10,
        maxBytes = 64 * 1024,
        maxAgeDays = 7,
        nowMillis = { 1_000L },
        ).also { queueStores[it] = store }
    }

    private fun queueStore(queue: LogisterOfflineQueue): InMemoryEnvelopeStore =
        requireNotNull(queueStores[queue])

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
