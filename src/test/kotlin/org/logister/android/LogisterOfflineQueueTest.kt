package org.logister.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

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
            true
        })
        assertEquals(listOf("two", "three"), delivered)
        assertEquals(0, queue.size())
    }

    @Test
    fun queueExpiresOldEntriesAndReadsLegacyPayloads() {
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
        assertEquals(1, queue.size())

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

    private fun envelope(message: String): JSONObject =
        JSONObject().put("event", JSONObject().put("message", message))
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
