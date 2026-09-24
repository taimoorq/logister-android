package org.logister.android

import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

public class LogisterHttpResult<T>(public val value: T, public val traceContext: LogisterTraceContext?)
public class LogisterHttpRequestException(cause: Exception, public val traceContext: LogisterTraceContext?) : Exception(cause)

/** Opt-in HTTP wrapper. Call off the main thread; consume the response in [execute]'s
 * block. Redirects are returned as 3xx, preventing propagation to a different origin.
 * Telemetry export uses the SDK's separate transport and cannot recurse here. */
public class LogisterHttpClient(
    private val client: LogisterClient,
    allowedOrigins: Collection<String>,
    excludedUrls: Collection<String> = emptyList()
) {
    private val allowedOrigins = allowedOrigins.toList()
    private val excludedUrls = excludedUrls.toSet()

    public fun <T> execute(
        url: String,
        operation: String = "HTTP request",
        parent: LogisterTraceContext? = null,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        block: (HttpURLConnection) -> T
    ): LogisterHttpResult<T> {
        val candidate = LogisterTraceContext.parse(headers.entries.firstOrNull { it.key.equals("traceparent", true) }?.value,
            headers.entries.firstOrNull { it.key.equals("x-request-id", true) }?.value) ?: parent?.child() ?: LogisterTraceContext.create()
        val propagated = if (excludedUrls.any { it.substringBefore('?').substringBefore('#') == url.substringBefore('?').substringBefore('#') } || client.isTelemetryUrl(url)) emptyMap() else candidate.headersFor(url, allowedOrigins)
        val trace = candidate.takeIf { propagated.isNotEmpty() }
        val startedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val started = System.nanoTime()
        var connection: HttpURLConnection? = null
        var failed = false
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            headers.filterKeys { trace != null || it.lowercase() !in setOf("traceparent", "tracestate", "x-request-id") }.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            propagated.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            val value = block(connection)
            failed = connection.responseCode >= 500
            return LogisterHttpResult(value, trace)
        } catch (error: Exception) {
            failed = true
            throw LogisterHttpRequestException(error, trace)
        } finally {
            connection?.disconnect()
            if (trace != null) {
                val span = LogisterSpan.builder(trace.traceId, operation.take(200), (System.nanoTime() - started) / 1_000_000.0)
                    .spanId(trace.spanId).parentSpanId(trace.parentSpanId).kind("http").status(if (failed) "error" else "ok")
                    .startedAt(startedAt).context(trace.context).build()
                try { client.captureSpanAsync(span, trace.eventOptions()) } catch (_: Exception) { /* Preserve the application's result. */ }
            }
        }
    }
}
