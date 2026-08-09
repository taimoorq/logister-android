package org.logister.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class LogisterOfflineQueueTest {
    @Test
    fun queueIsBoundedAndFlushesOldestFirst() {
        val store = InMemoryEnvelopeStore()
        val queue = LogisterOfflineQueue(
            store = store,
            maxEvents = 2,
            maxBytes = 16 * 1024,
            maxAgeDays = 7,
            nowMillis = { 1_000L },
        )

        assertTrue(queue.enqueue(envelope("one")))
        assertTrue(queue.enqueue(envelope("two")))
        assertTrue(queue.enqueue(envelope("three")))
        assertEquals(2, queue.size())

        val delivered = mutableListOf<String>()
        assertEquals(2, queue.flush { queued ->
            delivered += queued.getJSONObject("event").getString("message")
            LogisterQueueDeliveryDecision.Accepted
        })
        assertEquals(listOf("two", "three"), delivered)
        assertEquals(0, queue.size())
    }

    @Test
    fun queueExpiresOldEntriesAndDiscardsUnownedLegacyPayloads() {
        var now = TimeUnit.DAYS.toMillis(20)
        val store = InMemoryEnvelopeStore(
            JSONArray().put(
                JSONObject()
                    .put("envelope", envelope("expired").toString())
                    .put("captured_at_ms", TimeUnit.DAYS.toMillis(1)),
            ).toString(),
        )
        val queue = LogisterOfflineQueue(
            store = store,
            maxEvents = 10,
            maxBytes = 16 * 1024,
            maxAgeDays = 7,
            nowMillis = { now },
        )

        assertEquals(0, queue.size())
        store.value = JSONArray().put(envelope("legacy").toString()).toString()
        assertEquals(0, queue.size())
        assertEquals(2, queue.discardedCount())

        now += TimeUnit.DAYS.toMillis(8)
        assertEquals(0, queue.size())
    }

    @Test
    fun oversizedEnvelopeIsRejectedWithoutDiscardingExistingEntries() {
        val store = InMemoryEnvelopeStore()
        val queue = LogisterOfflineQueue(
            store = store,
            maxEvents = 10,
            maxBytes = 64,
            maxAgeDays = 7,
            nowMillis = { 1_000L },
        )

        assertFalse(queue.enqueue(envelope("x".repeat(256))))
        assertEquals(0, queue.size())
    }

    @Test
    fun crashPathEnqueueStopsWaitingWhenAnotherWriterOwnsTheQueueLock() {
        val store = BlockingEnvelopeStore()
        val queue = LogisterOfflineQueue(
            store = store,
            maxEvents = 10,
            maxBytes = 16 * 1024,
            maxAgeDays = 7,
            nowMillis = { 1_000L },
        )
        val executor = Executors.newSingleThreadExecutor()
        val first = executor.submit<Boolean> { queue.enqueue(envelope("first")) }
        assertTrue(store.writeStarted.await(1, TimeUnit.SECONDS))

        val started = System.nanoTime()
        assertFalse(queue.tryEnqueue(envelope("crash"), 25))
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue("enqueue waited $elapsedMillis ms", elapsedMillis < 500)

        store.releaseWrite.countDown()
        assertTrue(first.get(1, TimeUnit.SECONDS))
        executor.shutdownNow()
    }

    @Test
    fun storageOwnerSeparatesEndpointsPackagesAndExplicitLogicalClients() {
        val baseline = storageOwnerKey("https://logister.example/api", "com.acme.one", null, null)
        assertTrue(baseline != storageOwnerKey("https://other.example/api", "com.acme.one", null, null))
        assertTrue(baseline != storageOwnerKey("https://logister.example/api", "com.acme.two", null, null))
        assertTrue(baseline != storageOwnerKey("https://logister.example/api", "com.acme.one", null, "secondary"))
    }

    @Test
    fun permanentFailureDoesNotBlockTheNextEnvelope() {
        val queue = LogisterOfflineQueue(
            store = InMemoryEnvelopeStore(),
            maxEvents = 10,
            maxBytes = 16 * 1024,
            maxAgeDays = 7,
            nowMillis = { 1_000L },
        )
        queue.enqueue(envelope("poison"))
        queue.enqueue(envelope("valid"))

        assertEquals(1, queue.flush { queued ->
            if (queued.getJSONObject("event").getString("message") == "poison") {
                LogisterQueueDeliveryDecision.Discard
            } else {
                LogisterQueueDeliveryDecision.Accepted
            }
        })
        assertEquals(0, queue.size())
        assertEquals(1, queue.discardedCount())
    }

    @Test
    fun retryAfterDefersTheHeadEnvelopeWithoutChangingItsIdentity() {
        var now = 1_000L
        val queue = LogisterOfflineQueue(
            store = InMemoryEnvelopeStore(),
            maxEvents = 10,
            maxBytes = 16 * 1024,
            maxAgeDays = 7,
            nowMillis = { now },
        )
        queue.enqueue(envelope("retry"))
        var attempts = 0

        assertEquals(0, queue.flush { _ ->
            attempts += 1
            LogisterQueueDeliveryDecision.Retry(afterMillis = 5_000)
        })
        assertEquals(0, queue.flush { _ ->
            attempts += 1
            LogisterQueueDeliveryDecision.Accepted
        })
        assertEquals(1, attempts)

        now += 5_000
        assertEquals(1, queue.flush { _ ->
            attempts += 1
            LogisterQueueDeliveryDecision.Accepted
        })
        assertEquals(2, attempts)
    }

    private fun envelope(message: String): JSONObject =
        JSONObject().put("event", JSONObject().put("message", message))
}

private class BlockingEnvelopeStore : LogisterEnvelopeStore {
    val writeStarted = CountDownLatch(1)
    val releaseWrite = CountDownLatch(1)
    private var value: String? = null

    override fun read(): String? = value

    override fun write(value: String): Boolean {
        writeStarted.countDown()
        check(releaseWrite.await(2, TimeUnit.SECONDS))
        this.value = value
        return true
    }

    override fun clear(): Boolean {
        value = null
        return true
    }
}

internal class InMemoryEnvelopeStore(
    var value: String? = null,
) : LogisterEnvelopeStore {
    override fun read(): String? = value

    override fun write(value: String): Boolean {
        this.value = value
        return true
    }

    override fun clear(): Boolean {
        value = null
        return true
    }
}
