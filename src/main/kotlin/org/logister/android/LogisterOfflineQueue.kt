package org.logister.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal class LogisterOfflineQueue internal constructor(
    private val store: LogisterEnvelopeStore,
    private val maxEvents: Int,
    private val maxBytes: Int,
    maxAgeDays: Int,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    constructor(
        context: Context,
        maxEvents: Int,
        maxBytes: Int,
        maxAgeDays: Int,
    ) : this(
        store = SharedPreferencesLogisterEnvelopeStore(context),
        maxEvents = maxEvents,
        maxBytes = maxBytes,
        maxAgeDays = maxAgeDays,
    )

    private val maxAgeMillis = TimeUnit.DAYS.toMillis(maxAgeDays.toLong())

    @Synchronized
    fun enqueue(envelope: JSONObject): Boolean {
        val serialized = envelope.toString()
        if (serialized.byteSize() > maxBytes) return false

        val entries = activeEntries().toMutableList()
        val entry = QueuedEnvelope(serialized, nowMillis())
        entries.add(entry)
        while (entries.size > maxEvents || entries.totalBytes() > maxBytes) {
            if (entries.isEmpty()) return false
            entries.removeAt(0)
        }
        if (entry !in entries) return false
        return writeEntries(entries)
    }

    @Synchronized
    fun flush(
        limit: Int = 10,
        sender: (JSONObject) -> Boolean,
    ): Int {
        val entries = activeEntries().toMutableList()
        var delivered = 0
        while (entries.isNotEmpty() && delivered < limit) {
            val envelope = try {
                JSONObject(entries.first().envelope)
            } catch (_: Exception) {
                entries.removeAt(0)
                continue
            }
            if (!sender(envelope)) break
            entries.removeAt(0)
            delivered += 1
        }
        writeEntries(entries)
        return delivered
    }

    @Synchronized
    fun size(): Int {
        val entries = activeEntries()
        writeEntries(entries)
        return entries.size
    }

    @Synchronized
    fun clear(): Boolean = store.clear()

    @Synchronized
    fun removeIf(predicate: (JSONObject) -> Boolean): Int {
        val entries = activeEntries()
        val retained = entries.filterNot { entry ->
            try {
                predicate(JSONObject(entry.envelope))
            } catch (_: Exception) {
                true
            }
        }
        writeEntries(retained)
        return entries.size - retained.size
    }

    private fun activeEntries(): List<QueuedEnvelope> {
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
                                add(QueuedEnvelope(envelope, capturedAt))
                            }
                        }

                        is String -> if (value.isNotBlank()) {
                            // Retain entries written by 0.2.x and age them from the migration read.
                            add(QueuedEnvelope(value, nowMillis()))
                        }
                    }
                }
            }
        } catch (_: Exception) {
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
                    .put(CAPTURED_AT_KEY, entry.capturedAtMillis),
            )
        }
        return store.write(array.toString())
    }

    private fun List<QueuedEnvelope>.totalBytes(): Int = sumOf { it.envelope.byteSize() }

    private fun String.byteSize(): Int = toByteArray(Charsets.UTF_8).size

    private data class QueuedEnvelope(
        val envelope: String,
        val capturedAtMillis: Long,
    )

    private companion object {
        const val ENVELOPE_KEY = "envelope"
        const val CAPTURED_AT_KEY = "captured_at_ms"
    }
}

internal interface LogisterEnvelopeStore {
    fun read(): String?

    fun write(value: String): Boolean

    fun clear(): Boolean
}

private class SharedPreferencesLogisterEnvelopeStore(
    context: Context,
) : LogisterEnvelopeStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun read(): String? = preferences.getString(KEY, null)

    override fun write(value: String): Boolean = preferences.edit().putString(KEY, value).commit()

    override fun clear(): Boolean = preferences.edit().remove(KEY).commit()

    private companion object {
        const val PREFERENCES_NAME = "logister_delivery"
        const val KEY = "offline_envelopes"
    }
}
