package org.logister.android

import java.net.URI
import java.security.SecureRandom
import java.util.Collections
import java.util.UUID

/** Immutable identity for one request attempt. No process-wide last-request state. */
public class LogisterTraceContext private constructor(
    public val traceId: String,
    public val spanId: String,
    public val parentSpanId: String?,
    public val requestId: String,
    public val flags: String
) {
    public val traceparent: String get() = "00-$traceId-$spanId-$flags"
    public val context: Map<String, String> get() = Collections.unmodifiableMap(
        mutableMapOf("trace_id" to traceId, "span_id" to spanId, "request_id" to requestId).apply {
            parentSpanId?.let { put("parent_span_id", it) }
        }
    )
    public fun eventOptions(): LogisterEventOptions = LogisterEventOptions.builder()
        .traceId(traceId).requestId(requestId).context(context).build()

    public fun child(): LogisterTraceContext = LogisterTraceContext(traceId, randomHex(8), spanId, requestId, flags)

    /** Re-evaluate at each redirect hop, or disable redirects in the HTTP client. */
    public fun headersFor(url: String, allowedOrigins: Collection<String>): Map<String, String> {
        val destination = origin(url) ?: return emptyMap()
        if (allowedOrigins.none { origin(it) == destination }) return emptyMap()
        return mapOf("traceparent" to traceparent, "x-request-id" to requestId)
    }

    public companion object {
        private val random = SecureRandom()
        private val pattern = Regex("00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})")
        @JvmStatic public fun create(): LogisterTraceContext =
            LogisterTraceContext(randomHex(16), randomHex(8), null, UUID.randomUUID().toString(), "01")

        /** Adopt an existing outbound header without replacing another tracer's span. */
        @JvmStatic @JvmOverloads public fun parse(traceparent: String?, requestId: String? = null): LogisterTraceContext? {
            val parts = pattern.matchEntire(traceparent ?: "")?.groupValues ?: return null
            if (parts[1].all { it == '0' } || parts[2].all { it == '0' }) return null
            val request = requestId?.takeIf { Regex("[A-Za-z0-9._:-]{1,200}").matches(it) } ?: UUID.randomUUID().toString()
            return LogisterTraceContext(parts[1], parts[2], null, request, parts[3])
        }

        internal fun origin(value: String): String? = try {
            val uri = URI(value)
            val scheme = uri.scheme?.lowercase()
            val host = uri.host?.lowercase()
            if (scheme !in listOf("http", "https") || host == null || uri.rawUserInfo != null) null
            else "$scheme://$host:${if (uri.port != -1) uri.port else if (scheme == "https") 443 else 80}"
        } catch (_: Exception) { null }

        private fun randomHex(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
