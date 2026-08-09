package org.logister.android

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class LogisterOfflineQueue internal constructor(
    private val store: LogisterEnvelopeStore,
    private val maxEvents: Int,
    private val maxBytes: Int,
    maxAgeDays: Int,
    private val nowMillis: () -> Long = System::currentTimeMillis,
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
    private val lock = ReentrantLock()
    @Volatile private var discardedEvents: Int = 0

    fun enqueue(envelope: JSONObject, initialDelayMillis: Long? = null): Boolean = lock.withLock {
        enqueueUnlocked(envelope, initialDelayMillis)
    }

    fun tryEnqueue(envelope: JSONObject, timeoutMillis: Long): Boolean {
        if (!lock.tryLock(timeoutMillis, TimeUnit.MILLISECONDS)) return false
        return try {
            enqueueUnlocked(envelope, initialDelayMillis = null)
        } finally {
            lock.unlock()
        }
    }

    private fun enqueueUnlocked(envelope: JSONObject, initialDelayMillis: Long?): Boolean {
        val serialized = envelope.toString()
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

    fun flush(
        limit: Int = 10,
        sender: (JSONObject) -> LogisterQueueDeliveryDecision,
    ): Int = lock.withLock {
        val entries = activeEntries(countExpiredAsDiscarded = true).toMutableList()
        var delivered = 0
        while (entries.isNotEmpty() && delivered < limit) {
            if (entries.first().nextAttemptAtMillis > nowMillis()) break
            val envelope = try {
                JSONObject(entries.first().envelope)
            } catch (_: Exception) {
                entries.removeAt(0)
                discardedEvents += 1
                continue
            }
            when (val decision = sender(envelope)) {
                LogisterQueueDeliveryDecision.Accepted -> {
                    entries.removeAt(0)
                    delivered += 1
                }
                LogisterQueueDeliveryDecision.Discard -> {
                    entries.removeAt(0)
                    discardedEvents += 1
                }
                is LogisterQueueDeliveryDecision.Retry -> {
                    val current = entries.removeAt(0)
                    val attempt = current.attemptCount + 1
                    entries.add(
                        0,
                        current.copy(
                            attemptCount = attempt,
                            nextAttemptAtMillis = nowMillis() + (
                                decision.afterMillis ?: retryDelayMillis(attempt)
                            ).coerceIn(MIN_RETRY_MILLIS, MAX_RETRY_MILLIS),
                        ),
                    )
                    break
                }
            }
        }
        writeEntries(entries)
        delivered
    }

    fun size(): Int = lock.withLock {
        val entries = activeEntries(countExpiredAsDiscarded = true)
        writeEntries(entries)
        entries.size
    }

    fun clear(): Boolean = lock.withLock { store.clear() }

    fun discardedCount(): Int = discardedEvents

    fun removeIf(predicate: (JSONObject) -> Boolean): Int = lock.withLock {
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

    private fun retryDelayMillis(attempt: Int): Long {
        val exponent = (attempt - 1).coerceIn(0, 10)
        val base = MIN_RETRY_MILLIS * (1L shl exponent)
        return base.coerceAtMost(MAX_RETRY_MILLIS)
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
    // 0.3 used one Auto Backup-eligible global preference with no endpoint or project owner.
    // Its tenant cannot be proven, so upgrade discards it instead of risking cross-project delivery.
    context.getSharedPreferences("logister_delivery", Context.MODE_PRIVATE)
        .edit()
        .remove("offline_envelopes")
        .commit()
    return AtomicFileLogisterEnvelopeStore(
        File(context.noBackupFilesDir, "logister_delivery_$ownerKey.json"),
    )
}
