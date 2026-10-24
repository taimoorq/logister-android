package org.logister.android

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogisterCaptureSafetyTest {
    @Test
    fun largeContextFullExceptionAndBreadcrumbsAreAcceptedWithoutThrowingOnTheCaller() {
        val transport = RecordingSafetyTransport()
        val client = client(transport) {
            exceptionDataPolicy(LogisterExceptionDataPolicy.FULL).breadcrumbs(100)
        }
        repeat(100) { index ->
            client.addBreadcrumb(
                LogisterBreadcrumb.builder("Opened screen $index")
                    .category("navigation")
                    .data("screen", "Screen$index")
                    .data("position", index)
                    .build(),
            )
        }
        val options = LogisterEventOptions.builder()
        repeat(1_200) { index -> options.context("custom_%04d".format(index), "value-$index") }

        val response = client.captureExceptionAsync(causeChain(depth = 6), options.build())
            .get(10, TimeUnit.SECONDS)

        assertTrue(response.body, response.isAccepted)
        val event = transport.envelopes.single().getJSONObject("event")
        assertTrue(event.getString("uuid").isNotBlank())
        assertEquals("error", event.getString("event_type"))
        assertTrue(event.getString("message").startsWith("java.lang.IllegalStateException"))
        assertEquals("reported_stack", event.getJSONObject("evidence").getString("evidence_kind"))
        val context = event.getJSONObject("context")
        assertTrue(context.getBoolean("_truncated"))
        assertEquals(100, context.getJSONArray("breadcrumbs").length())
        var exception = context.getJSONObject("exception")
        var causes = 0
        while (exception.has("cause")) {
            exception = exception.getJSONObject("cause")
            causes += 1
        }
        assertEquals(5, causes)
    }

    @Test
    fun envelopeBuildFailureIsReportedAsDroppedNotThrown() {
        val transport = RecordingSafetyTransport()
        val client = client(transport)

        // org.json rejects non-finite numbers in event attributes; this used to throw on the caller.
        val asyncResponse = client.captureTransactionAsync("checkout", Double.NaN).get(5, TimeUnit.SECONDS)
        val metricResponse = client.captureMetricAsync("latency", Double.NaN).get(5, TimeUnit.SECONDS)
        val syncResponse = client.capture(
            LogisterEvent.builder("transaction", "checkout").attribute("duration_ms", Double.POSITIVE_INFINITY).build(),
        )

        assertTrue(asyncResponse.body, asyncResponse.isDropped)
        assertTrue(syncResponse.body, syncResponse.isDropped)
        assertTrue(metricResponse.body, metricResponse.isDropped)
        assertEquals(0, transport.envelopes.size)
        assertEquals(3, client.discardedEventCount())
    }

    @Test
    fun throwingBeforeSendAndShutDownExecutorNeverEscapeCapture() {
        val executor = Executors.newSingleThreadExecutor()
        val client = LogisterClient.builder(token(), "https://logister.example")
            .includeDeviceContext(false)
            .transport(RecordingSafetyTransport())
            .beforeSend(LogisterBeforeSend { throw StackOverflowError("hook recursion") })
            .executor(executor)
            .build()

        assertTrue(client.captureMessageAsync("hook").get(5, TimeUnit.SECONDS).isDropped)
        executor.shutdownNow()
        assertTrue(client.captureMessageAsync("after shutdown").get(5, TimeUnit.SECONDS).isDropped)
        assertEquals(0, client.flushQueuedEventsAsync().get(5, TimeUnit.SECONDS))
    }

    @Test
    fun crashEnvelopeIsPersistedWhileASlowDeliveryHoldsTheQueue() {
        val sendStarted = CountDownLatch(1)
        val releaseSend = CountDownLatch(1)
        val sends = AtomicInteger()
        val transport = LogisterTransport { _, _, _, _, _ ->
            sends.incrementAndGet()
            sendStarted.countDown()
            check(releaseSend.await(10, TimeUnit.SECONDS))
            LogisterResponse(202)
        }
        val store = InMemoryEnvelopeStore()
        val client = client(transport)
        val queue = LogisterOfflineQueue(store = store, maxEvents = 10, maxBytes = 256 * 1024, maxAgeDays = 7)
        client.attachOfflineQueue(queue)
        queue.enqueue(JSONObject().put("event", JSONObject().put("message", "queued before the crash")))

        val flush = client.flushQueuedEventsAsync()
        assertTrue(sendStarted.await(5, TimeUnit.SECONDS))

        val started = System.nanoTime()
        client.captureUncaughtException(Thread("main"), IllegalStateException("boom"))
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        val messages = storedMessages(store)
        assertTrue("crash capture waited $elapsedMillis ms", elapsedMillis < 1_000)
        assertTrue(messages.toString(), "java.lang.IllegalStateException" in messages)

        releaseSend.countDown()
        flush.get(10, TimeUnit.SECONDS)
        assertEquals(0, client.queuedEventCount())
        assertEquals(2, sends.get())
    }

    private fun storedMessages(store: InMemoryEnvelopeStore): List<String> {
        val entries = JSONArray(store.value ?: "[]")
        return (0 until entries.length()).map { index ->
            JSONObject(entries.getJSONObject(index).getString("envelope")).getJSONObject("event").getString("message")
        }
    }

    /** A realistic chain: each cause has its own top frames and shares the rest with its wrapper. */
    private fun causeChain(depth: Int): Throwable {
        val common = Array(90) { StackTraceElement("com.acme.checkout.Common", "frame$it", "Common.kt", it + 1) }
        fun unique(name: String) = Array(10) { StackTraceElement("com.acme.checkout.$name", "invoke", "$name.kt", it + 1) }
        var throwable: Throwable = IllegalArgumentException("root cause").apply { stackTrace = unique("Root") + common }
        repeat(depth - 1) { level ->
            throwable = IllegalStateException("level $level", throwable).apply { stackTrace = unique("Level$level") + common }
        }
        return throwable
    }

    private fun token(): LogisterTokenProvider = LogisterTokenProvider {
        LogisterToken("synthetic-mobile-token", System.currentTimeMillis() / 1_000 + 300)
    }

    private fun client(
        transport: LogisterTransport,
        configure: LogisterClient.Builder.() -> LogisterClient.Builder = { this },
    ): LogisterClient = LogisterClient.builder(token(), "https://logister.example")
        .includeDeviceContext(false)
        .transport(transport)
        .executor(Executors.newSingleThreadExecutor())
        .configure()
        .build()
}

private class RecordingSafetyTransport : LogisterTransport {
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
