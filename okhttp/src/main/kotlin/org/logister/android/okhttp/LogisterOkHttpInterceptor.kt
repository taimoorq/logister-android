package org.logister.android.okhttp

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.logister.android.LogisterClient
import org.logister.android.LogisterSpan
import org.logister.android.LogisterTraceContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

public class LogisterOkHttpException(cause: IOException, public val traceContext: LogisterTraceContext) : IOException(cause)

/** Opt-in request tracing. Install last so each network attempt can enforce the
 * origin allowlist after application interceptors have changed the request. */
public class LogisterOkHttpInterceptor(
    private val client: LogisterClient,
    allowedOrigins: Collection<String>,
    excludedUrls: Collection<String> = emptyList()
) {
    private val allowedOrigins = allowedOrigins.toList()
    private val excludedUrls = excludedUrls.toSet()
    private class CallContext(var trace: LogisterTraceContext? = null, var attempt: LogisterTraceContext? = null)

    /** Adds call-local context and a redirect-safe network interceptor. */
    public fun install(builder: OkHttpClient.Builder): OkHttpClient.Builder = builder
        .addInterceptor { chain ->
            val context = CallContext()
            val response = chain.proceed(chain.request().newBuilder().tag(CallContext::class.java, context).build())
            response.newBuilder().request(response.request.newBuilder().tag(LogisterTraceContext::class.java, context.attempt).build()).build()
        }
        .addNetworkInterceptor(::intercept)

    private fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val call = checkNotNull(original.tag(CallContext::class.java))
        val trace = call.trace?.child() ?: original.tag(LogisterTraceContext::class.java)?.child()
            ?: LogisterTraceContext.parse(original.header("traceparent"), original.header("x-request-id")) ?: LogisterTraceContext.create()
        val url = original.url.toString()
        val headers = if (excludedUrls.any { sameEndpoint(it, url) } || client.isTelemetryUrl(url)) emptyMap() else trace.headersFor(url, allowedOrigins)
        val builder = original.newBuilder().removeHeader("traceparent").removeHeader("tracestate").removeHeader("x-request-id")
            .tag(LogisterTraceContext::class.java, null)
        call.attempt = null
        if (headers.isEmpty()) return chain.proceed(builder.build())
        call.trace = trace
        call.attempt = trace
        headers.forEach { (key, value) -> builder.header(key, value) }
        val request = builder.tag(LogisterTraceContext::class.java, trace).build()
        val startedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val started = System.nanoTime()
        var failed = false
        try {
            val response = chain.proceed(request)
            failed = response.code >= 500
            // The application interceptor exposes the final attempt handle.
            return response
        } catch (error: IOException) {
            failed = true
            throw LogisterOkHttpException(error, trace)
        } finally {
            val span = LogisterSpan.builder(trace.traceId, "${request.method} HTTP request", (System.nanoTime() - started) / 1_000_000.0)
                .spanId(trace.spanId).parentSpanId(trace.parentSpanId).kind("http").status(if (failed) "error" else "ok")
                .startedAt(startedAt).context(trace.context).build()
            try { client.captureSpanAsync(span, trace.eventOptions()) } catch (_: Exception) { /* Preserve application behavior. */ }
        }
    }

    private fun sameEndpoint(first: String, second: String): Boolean = try {
        val a = java.net.URI(first)
        val b = java.net.URI(second)
        a.scheme == b.scheme && a.authority == b.authority && a.path == b.path
    } catch (_: Exception) { false }
}
