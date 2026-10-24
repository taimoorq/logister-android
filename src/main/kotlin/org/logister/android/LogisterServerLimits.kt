package org.logister.android

import org.json.JSONArray
import org.json.JSONObject

/**
 * Keeps an event within the limits the Logister ingest API enforces per event, using the server's
 * counting rule: every object key anywhere in the event counts toward the key limit, and the byte
 * estimate adds each key's and each scalar value's UTF-8 size plus 4 (null counts as 4 + 4).
 * The server rejects an event over any limit with 413, which the SDK treats as permanent.
 *
 * Content is kept in priority order: required event fields, other event fields, the SDK's own
 * small context entries, the top exception without its causes, ANR diagnostics, the newest
 * breadcrumbs, the exception's causes, then caller context (scalars, then small objects before
 * large ones). Anything dropped is reported through [onTruncated].
 */
internal class LogisterServerLimits(
    maxKeys: Int,
    maxBytes: Long,
    private val onTruncated: () -> Unit,
) {
    private var remainingKeys: Int = maxKeys
    private var remainingBytes: Long = maxBytes

    fun fitEvent(event: JSONObject): JSONObject {
        // Room for the `_truncated` marker the caller may add afterwards.
        reserve(keys = 1, bytes = keyBytes(TRUNCATED_FIELD) + scalarBytes(true))

        val output = JSONObject()
        val required = REQUIRED_EVENT_FIELDS.filter(event::has)
        for (key in required) {
            var value = event.opt(key)
            if (key == "message" && value is String) value = truncateUtf8(value, MAX_MESSAGE_BYTES)
            output.put(key, value)
            val cost = cost(value, depth = 1)
            reserve(keys = 1 + cost.keys, bytes = keyBytes(key) + cost.bytes)
        }
        val context = event.optJSONObject("context")
        if (context != null) reserve(keys = 1, bytes = keyBytes("context"))

        val otherFields = event.keys().asSequence().filter { it != "context" && it !in required }.toList()
        insertAll(output, event, otherFields, depth = 1)

        if (context != null) output.put("context", fitContext(context))
        return output
    }

    private fun fitContext(source: JSONObject): JSONObject {
        val output = JSONObject()
        val keys = source.keys().asSequence().toList()
        val sdk = SDK_CONTEXT_ORDER.filter(keys::contains)
        val caller = keys.filterNot(SDK_CONTEXT_ORDER::contains)

        insertAll(output, source, sdk.filterNot(LARGE_SDK_CONTEXT_KEYS::contains), depth = 2)

        val exception = source.opt(EXCEPTION_KEY)
        val cause = (exception as? JSONObject)?.opt(CAUSE_KEY)
        if (exception != null) {
            val topLevel = if (exception is JSONObject && cause != null) exception.without(CAUSE_KEY) else exception
            insert(output, EXCEPTION_KEY, topLevel, depth = 2)
        }
        source.opt(DIAGNOSTIC_KEY)?.let { insert(output, DIAGNOSTIC_KEY, it, depth = 2) }
        source.opt(BREADCRUMBS_KEY)?.let { insert(output, BREADCRUMBS_KEY, it, depth = 2, newestFirst = true) }
        if (cause != null) {
            val fittedException = output.optJSONObject(EXCEPTION_KEY)
            if (fittedException != null) insert(fittedException, CAUSE_KEY, cause, depth = 3) else onTruncated()
        }

        insertAll(output, source, caller, depth = 2)
        return output
    }

    /**
     * Inserts scalars first, then containers from smallest to largest, with causes last.
     * [depth] is the server depth of the inserted values (event fields are at 1).
     */
    private fun insertAll(output: JSONObject, source: JSONObject, keys: List<String>, depth: Int) {
        val (scalars, containers) = keys.partition { !isContainer(source.opt(it)) }
        scalars.sorted().forEach { insert(output, it, source.opt(it), depth) }
        containers
            .map { it to cost(source.opt(it), depth).keys }
            .sortedWith(compareBy<Pair<String, Int>> { it.first == CAUSE_KEY }.thenBy { it.second }.thenBy { it.first })
            .forEach { (key, _) -> insert(output, key, source.opt(key), depth) }
    }

    /** Adds [key] with its whole value when it fits, a trimmed container when not, or nothing. */
    private fun insert(output: JSONObject, key: String, value: Any?, depth: Int, newestFirst: Boolean = false) {
        if (depth > MAX_DEPTH) {
            onTruncated()
            return
        }
        val entryBytes = keyBytes(key)
        val whole = cost(value, depth)
        if (whole.valid && fits(1 + whole.keys, entryBytes + whole.bytes)) {
            reserve(1 + whole.keys, entryBytes + whole.bytes)
            output.put(key, value ?: JSONObject.NULL)
            return
        }
        onTruncated()
        if (!isContainer(value) || !fits(1, entryBytes)) return
        reserve(1, entryBytes)
        val trimmed = trim(value, depth, newestFirst)
        if (trimmed == null) {
            release(1, entryBytes)
        } else {
            output.put(key, trimmed)
        }
    }

    /** Returns a non-empty part of [value] that fits the remaining budget, or null. */
    private fun trim(value: Any?, depth: Int, newestFirst: Boolean): Any? = when (value) {
        is JSONObject -> {
            val output = JSONObject()
            insertAll(output, value, value.keys().asSequence().toList(), depth + 1)
            output.takeIf { it.length() > 0 }
        }
        is JSONArray -> {
            // Arrays keep whole elements only: a prefix, or for breadcrumbs the newest suffix.
            val indices = (0 until value.length()).let { if (newestFirst) it.reversed() else it.toList() }
            val kept = ArrayList<Any?>()
            for (index in indices) {
                if (kept.size >= MAX_ARRAY_LENGTH) break
                val element = value.opt(index)
                val elementCost = cost(element, depth + 1)
                if (!elementCost.valid || !fits(elementCost.keys, elementCost.bytes)) break
                reserve(elementCost.keys, elementCost.bytes)
                kept += element
            }
            if (newestFirst) kept.reverse()
            if (kept.isEmpty()) null else JSONArray().also { array -> kept.forEach(array::put) }
        }
        else -> null
    }

    private fun cost(value: Any?, depth: Int): Cost {
        if (depth > MAX_DEPTH) return Cost.INVALID
        return when (value) {
            is JSONObject -> {
                var keys = value.length()
                var bytes = 0L
                for (key in value.keys()) {
                    val child = cost(value.opt(key), depth + 1)
                    if (!child.valid) return Cost.INVALID
                    keys += child.keys
                    bytes += keyBytes(key) + child.bytes
                }
                Cost(keys, bytes)
            }
            is JSONArray -> {
                if (value.length() > MAX_ARRAY_LENGTH) return Cost.INVALID
                var keys = 0
                var bytes = 0L
                for (index in 0 until value.length()) {
                    val child = cost(value.opt(index), depth + 1)
                    if (!child.valid) return Cost.INVALID
                    keys += child.keys
                    bytes += child.bytes
                }
                Cost(keys, bytes)
            }
            is String -> if (utf8Size(value) > MAX_STRING_BYTES) Cost.INVALID else Cost(0, scalarBytes(value))
            else -> Cost(0, scalarBytes(value))
        }
    }

    private fun fits(keys: Int, bytes: Long): Boolean = keys <= remainingKeys && bytes <= remainingBytes

    private fun reserve(keys: Int, bytes: Long) {
        remainingKeys -= keys
        remainingBytes -= bytes
    }

    private fun release(keys: Int, bytes: Long) {
        remainingKeys += keys
        remainingBytes += bytes
    }

    private class Cost(val keys: Int, val bytes: Long, val valid: Boolean = true) {
        companion object {
            val INVALID = Cost(0, 0, valid = false)
        }
    }

    companion object {
        const val MAX_KEYS: Int = 512
        const val MAX_BYTES: Long = 1_000_000
        private const val MAX_DEPTH = 16
        private const val MAX_ARRAY_LENGTH = 1_000
        private const val MAX_STRING_BYTES = 256 * 1_024
        private const val MAX_MESSAGE_BYTES = 64 * 1_024
        private const val TRUNCATED_FIELD = "_truncated"
        private const val EXCEPTION_KEY = "exception"
        private const val CAUSE_KEY = "cause"
        private const val DIAGNOSTIC_KEY = "diagnostic"
        private const val BREADCRUMBS_KEY = "breadcrumbs"

        private val REQUIRED_EVENT_FIELDS = listOf(
            "uuid", "event_type", "message", "level", "occurred_at", "fingerprint", "environment",
            "release", "trace_id", "span_id", "parent_span_id", "request_id", "session_id", "user_id",
            "transaction_name", "duration_ms", "evidence",
        )

        private val LARGE_SDK_CONTEXT_KEYS = setOf(EXCEPTION_KEY, DIAGNOSTIC_KEY, BREADCRUMBS_KEY)

        /** The SDK's own context entries; everything else is caller context. */
        private val SDK_CONTEXT_ORDER = listOf(
            "platform", "telemetry_schema_version", "error", "exception_class", "capture_source",
            "exception_data_policy", "error_mechanism", "handled", "diagnostic_external_id",
            "build_provenance", "app", "session", "installation", "sdk", "device", "os", "service",
            "package_name", "app_version", "build_number", "build_type", "environment", "release",
            "trace_id", "span_id", "parent_span_id", "request_id", "session_id", "user_id",
            "transaction_name", "duration_ms", "screen_name", "in_foreground", "installation_id_hash",
            "process_name", "device_manufacturer", "device_model", "device_brand", "os_name",
            "os_version", "android_api_level", "device_abis", "application_exit_reason",
            "application_exit_importance", "application_exit_status", EXCEPTION_KEY, DIAGNOSTIC_KEY,
            BREADCRUMBS_KEY,
        )

        private fun isContainer(value: Any?): Boolean = value is JSONObject || value is JSONArray

        private fun keyBytes(key: String): Long = utf8Size(key) + 4L

        private fun scalarBytes(value: Any?): Long = when (value) {
            null, JSONObject.NULL -> 8L
            is String -> utf8Size(value) + 4L
            else -> utf8Size(value.toString()) + 4L
        }

        private fun utf8Size(value: String): Int = value.toByteArray(Charsets.UTF_8).size

        private fun truncateUtf8(value: String, maxBytes: Int): String {
            if (utf8Size(value) <= maxBytes) return value
            val budget = maxBytes - utf8Size("…")
            var bytes = 0
            var end = 0
            while (end < value.length) {
                val codePoint = value.codePointAt(end)
                val size = when {
                    codePoint < 0x80 -> 1
                    codePoint < 0x800 -> 2
                    codePoint < 0x10000 -> 3
                    else -> 4
                }
                if (bytes + size > budget) break
                bytes += size
                end += Character.charCount(codePoint)
            }
            return value.substring(0, end) + "…"
        }

        private fun JSONObject.without(key: String): JSONObject {
            val copy = JSONObject()
            for (name in keys()) if (name != key) copy.put(name, opt(name))
            return copy
        }
    }
}
