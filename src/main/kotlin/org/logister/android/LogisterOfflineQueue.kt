package org.logister.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal class LogisterOfflineQueue(
    context: Context,
    private val maxEvents: Int,
    private val maxBytes: Int
) {
    private val preferences = context.getSharedPreferences("logister_delivery", Context.MODE_PRIVATE)

    @Synchronized
    fun enqueue(envelope: JSONObject): Boolean {
        val entries = readEntries().toMutableList()
        entries.add(envelope.toString())
        while (entries.size > maxEvents || entries.sumOf { it.toByteArray(Charsets.UTF_8).size } > maxBytes) {
            if (entries.isEmpty()) return false
            entries.removeAt(0)
        }
        writeEntries(entries)
        return true
    }

    @Synchronized
    fun flush(limit: Int = 10, sender: (JSONObject) -> Boolean): Int {
        val entries = readEntries().toMutableList()
        var delivered = 0
        while (entries.isNotEmpty() && delivered < limit) {
            val envelope = try {
                JSONObject(entries.first())
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
    fun size(): Int = readEntries().size

    private fun readEntries(): List<String> {
        val raw = preferences.getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index -> array.optString(index, null) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeEntries(entries: List<String>) {
        val array = JSONArray()
        entries.forEach(array::put)
        preferences.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "offline_envelopes"
    }
}
