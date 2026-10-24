package org.logister.android

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class LogisterOfflineQueue internal constructor(
    private val store: LogisterEnvelopeStore,
    private val maxEvents: Int,
    private val maxBytes: Int,
    maxAgeDays: Int,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val randomFraction: () -> Double = { ThreadLocalRandom.current().nextDouble() },
) {
    constructor(
        context: Context,
        ownerKey: String,
        maxEvents: Int,
        maxBytes: Int,
        maxAgeDays: Int,
    ) : this(
        store = scopedEnvelopeStore(context, ownerKey),
        maxEvents = maxEvents,
        maxBytes = maxBytes,
        maxAgeDays = maxAgeDays,
    )

    private val maxAgeMillis = TimeUnit.DAYS.toMillis(maxAgeDays.toLong())
    // Guards each short read-modify-write of the store. It is never held across delivery, so an
    // enqueue (including the crash path) never waits on the network.
    private val storeLock = ReentrantLock()
    // Serializes flushes so one envelope is never sent by two flushers at once.
    private val flushLock = ReentrantLock()
    @Volatile private var discardedEvents: Int = 0

    fun enqueue(envelope: JSONObject, initialDelayMillis: Long? = null): Boolean {
        val serialized = envelope.toString()
        return storeLock.withLock { enqueueUnlocked(serialized, initialDelayMillis) }
    }

    fun tryEnqueue(envelope: JSONObject, timeoutMillis: Long): Boolean {
        val serialized = envelope.toString()
        if (!storeLock.tryLock(timeoutMillis, TimeUnit.MILLISECONDS)) return false
        return try {
            enqueueUnlocked(serialized, initialDelayMillis = null)
        } finally {
            storeLock.unlock()
        }
    }

    private fun enqueueUnlocked(serialized: String, initialDelayMillis: Long?): Boolean {
        if (serialized.byteSize() > maxBytes) return false

        val entries = activeEntries(countExpiredAsDiscarded = true).toMutableList()
        val entry = QueuedEnvelope(
            envelope = serialized,
            capturedAtMillis = nowMillis(),
            nextAttemptAtMillis = initialDelayMillis
                ?.coerceIn(MIN_RETRY_MILLIS, MAX_RETRY_MILLIS)
                ?.let { nowMillis() + it }
                ?: 0L,
        )
        entries.add(entry)
        while (entries.size > maxEvents || entries.totalBytes() > maxBytes) {
            if (entries.isEmpty()) return false
            entries.removeAt(0)
            discardedEvents += 1
        }
        if (entry !in entries) return false
        return writeEntries(entries)
    }

    /**
     * Delivers due envelopes oldest first. The store lock is taken only to read the head and to
     * record its outcome, so concurrent enqueues proceed while [sender] performs network I/O.
     * With [waitForActiveFlush] false, returns 0 at once when another flush is running; that flush
     * re-reads the head after each delivery and so also picks up newly queued envelopes.
     */
    fun flush(
        limit: Int = 10,
        waitForActiveFlush: Boolean = true,
        sender: (JSONObject) -> LogisterQueueDeliveryDecision,
    ): Int {
        if (waitForActiveFlush) flushLock.lock() else if (!flushLock.tryLock()) return 0
        try {
            var delivered = 0
            val attempted = mutableListOf<QueuedEnvelope>()
            while (delivered < limit) {
                val head = storeLock.withLock { prunedEntries().firstOrNull() } ?: break
                // A failed store write leaves the head in place; never resend it in this flush.
                if (head.nextAttemptAtMillis > nowMillis() || head in attempted) break
                attempted += head
                val envelope = try {
                    JSONObject(head.envelope)
                } catch (_: Exception) {
                    storeLock.withLock {
                        replaceEntry(head, replacement = null)
                        discardedEvents += 1
                    }
                    continue
                }
                val decision = sender(envelope)
                storeLock.withLock {
                    when (decision) {
                        LogisterQueueDeliveryDecision.Accepted -> {
                            replaceEntry(head, replacement = null)
                            delivered += 1
                        }
                        LogisterQueueDeliveryDecision.Discard -> {
                            if (replaceEntry(head, replacement = null)) discardedEvents += 1
                        }
                        is LogisterQueueDeliveryDecision.Retry -> {
                            val attempt = head.attemptCount + 1
                            replaceEntry(
                                head,
                                head.copy(
                                    attemptCount = attempt,
                                    nextAttemptAtMillis = nowMillis() + (
                                        decision.afterMillis ?: retryDelayMillis(attempt)
                                    ).coerceIn(MIN_RETRY_MILLIS, MAX_RETRY_MILLIS),
                                ),
                            )
                        }
                    }
                }
                if (decision is LogisterQueueDeliveryDecision.Retry) break
            }
            return delivered
        } finally {
            flushLock.unlock()
        }
    }

    /** Must hold [storeLock]. Returns false when the entry was evicted, removed, or cleared meanwhile. */
    private fun replaceEntry(original: QueuedEnvelope, replacement: QueuedEnvelope?): Boolean {
        val entries = activeEntries(countExpiredAsDiscarded = true).toMutableList()
        val index = entries.indexOf(original)
        if (index < 0) {
            writeEntries(entries)
            return false
        }
        if (replacement == null) entries.removeAt(index) else entries[index] = replacement
        writeEntries(entries)
        return true
    }

    /** Must hold [storeLock]. Persists the removal of expired or unreadable entries it counted. */
    private fun prunedEntries(): List<QueuedEnvelope> {
        val discardedBefore = discardedEvents
        val entries = activeEntries(countExpiredAsDiscarded = true)
        if (discardedEvents != discardedBefore) writeEntries(entries)
        return entries
    }

    fun size(): Int = storeLock.withLock {
        val entries = activeEntries(countExpiredAsDiscarded = true)
        writeEntries(entries)
        entries.size
    }

    fun clear(): Boolean = storeLock.withLock { store.clear() }

    fun discardedCount(): Int = discardedEvents

    fun removeIf(predicate: (JSONObject) -> Boolean): Int = storeLock.withLock {
        val entries = activeEntries(countExpiredAsDiscarded = true)
        val retained = entries.filterNot { entry ->
            try {
                predicate(JSONObject(entry.envelope))
            } catch (_: Exception) {
                true
            }
        }
        writeEntries(retained)
        entries.size - retained.size
    }

    private fun activeEntries(countExpiredAsDiscarded: Boolean = false): List<QueuedEnvelope> {
        val cutoff = nowMillis() - maxAgeMillis
        val raw = store.read() ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    when (val value = array.opt(index)) {
                        is JSONObject -> {
                            val envelope = value.optString(ENVELOPE_KEY, "")
                            val capturedAt = value.optLong(CAPTURED_AT_KEY, 0L)
                            if (envelope.isNotBlank() && capturedAt >= cutoff) {
                                add(
                                    QueuedEnvelope(
                                        envelope = envelope,
                                        capturedAtMillis = capturedAt,
                                        attemptCount = value.optInt(ATTEMPT_COUNT_KEY, 0).coerceAtLeast(0),
                                        nextAttemptAtMillis = value.optLong(NEXT_ATTEMPT_AT_KEY, 0L).coerceAtLeast(0L),
                                    ),
                                )
                            } else if (envelope.isNotBlank() && countExpiredAsDiscarded) {
                                discardedEvents += 1
                            }
                        }

                        is String -> if (value.isNotBlank() && countExpiredAsDiscarded) {
                            // An unowned legacy record must never be adopted into a scoped client.
                            discardedEvents += 1
                        }
                    }
                }
            }
        } catch (_: Exception) {
            store.quarantineCorruptData()
            discardedEvents += 1
            emptyList()
        }
    }

    private fun writeEntries(entries: List<QueuedEnvelope>): Boolean {
        if (entries.isEmpty()) return store.clear()
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put(ENVELOPE_KEY, entry.envelope)
                    .put(CAPTURED_AT_KEY, entry.capturedAtMillis)
                    .put(ATTEMPT_COUNT_KEY, entry.attemptCount)
                    .put(NEXT_ATTEMPT_AT_KEY, entry.nextAttemptAtMillis),
            )
        }
        return store.write(array.toString())
    }

    private fun List<QueuedEnvelope>.totalBytes(): Int = sumOf { it.envelope.byteSize() }

    private fun String.byteSize(): Int = toByteArray(Charsets.UTF_8).size

    private data class QueuedEnvelope(
        val envelope: String,
        val capturedAtMillis: Long,
        val attemptCount: Int = 0,
        val nextAttemptAtMillis: Long = 0L,
    )

    /** Exponential backoff with equal jitter: half the capped delay plus a random share of the rest. */
    private fun retryDelayMillis(attempt: Int): Long {
        val exponent = (attempt - 1).coerceIn(0, 10)
        val base = (MIN_RETRY_MILLIS * (1L shl exponent)).coerceAtMost(MAX_RETRY_MILLIS)
        val jitter = (base / 2 * randomFraction().coerceIn(0.0, 1.0)).toLong()
        return base / 2 + jitter
    }

    private companion object {
        const val ENVELOPE_KEY = "envelope"
        const val CAPTURED_AT_KEY = "captured_at_ms"
        const val ATTEMPT_COUNT_KEY = "attempt_count"
        const val NEXT_ATTEMPT_AT_KEY = "next_attempt_at_ms"
        const val MIN_RETRY_MILLIS: Long = 1_000
        const val MAX_RETRY_MILLIS: Long = 60 * 60 * 1_000
    }
}

internal sealed interface LogisterQueueDeliveryDecision {
    data object Accepted : LogisterQueueDeliveryDecision
    data object Discard : LogisterQueueDeliveryDecision
    data class Retry(val afterMillis: Long? = null) : LogisterQueueDeliveryDecision
}

internal interface LogisterEnvelopeStore {
    fun read(): String?

    fun write(value: String): Boolean

    fun clear(): Boolean

    fun quarantineCorruptData(): Boolean = clear()
}

private class AtomicFileLogisterEnvelopeStore(
    private val file: File,
) : LogisterEnvelopeStore {
    override fun read(): String? = try {
        file.takeIf(File::isFile)?.readText(Charsets.UTF_8)
    } catch (_: Exception) {
        quarantineCorruptFile()
        null
    }

    override fun write(value: String): Boolean = try {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(value, Charsets.UTF_8)
        if (!temporary.renameTo(file)) {
            file.writeText(value, Charsets.UTF_8)
            temporary.delete()
        }
        true
    } catch (_: Exception) {
        false
    }

    override fun clear(): Boolean = !file.exists() || file.delete()

    override fun quarantineCorruptData(): Boolean {
        quarantineCorruptFile()
        return true
    }

    private fun quarantineCorruptFile() {
        if (!file.isFile) return
        val quarantine = File(file.parentFile, "${file.name}.corrupt")
        if (quarantine.exists()) quarantine.delete()
        file.renameTo(quarantine)
    }
}

private fun scopedEnvelopeStore(context: Context, ownerKey: String): LogisterEnvelopeStore {
    // 0.3's unowned global preference queue is discarded off the main thread by the client builder.
    return AtomicFileLogisterEnvelopeStore(
        File(context.noBackupFilesDir, "logister_delivery_$ownerKey.json"),
    )
}
