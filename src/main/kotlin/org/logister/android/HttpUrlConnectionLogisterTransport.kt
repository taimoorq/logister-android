package org.logister.android

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.json.JSONObject

/** Default transport backed by HttpURLConnection to avoid extra runtime dependencies. */
public class HttpUrlConnectionLogisterTransport : LogisterTransport {
    @Throws(Exception::class)
    override fun send(
        endpoint: String,
        mobileIngestToken: String,
        envelope: JSONObject,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): LogisterResponse {
        val payload = envelope.toString().toByteArray(StandardCharsets.UTF_8)
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            // A redirect would resend the bearer token to a host the app never configured.
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(payload.size)
            connection.setRequestProperty("Authorization", "Bearer $mobileIngestToken")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "logister-android/$LOGISTER_ANDROID_SDK_VERSION")

            connection.outputStream.use { outputStream ->
                outputStream.write(payload)
            }

            val statusCode = connection.responseCode
            val retryAfterMillis = parseRetryAfter(connection.getHeaderField("Retry-After"))
            val responseStream = if (statusCode >= 400) connection.errorStream else connection.inputStream
            val body = readBody(responseStream)
            return LogisterResponse.withRetryAfter(statusCode, body, retryAfterMillis)
        } finally {
            connection.disconnect()
        }
    }

    /** Reads at most [MAX_RESPONSE_BODY_BYTES]; the SDK only needs short status bodies. */
    @Throws(Exception::class)
    private fun readBody(inputStream: InputStream?): String {
        if (inputStream == null) {
            return ""
        }

        val body = ByteArrayOutputStream()
        inputStream.use { stream ->
            val buffer = ByteArray(8 * 1_024)
            while (body.size() < MAX_RESPONSE_BODY_BYTES) {
                val read = stream.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_BODY_BYTES - body.size()))
                if (read < 0) break
                body.write(buffer, 0, read)
            }
        }
        return String(body.toByteArray(), StandardCharsets.UTF_8)
    }

    private fun parseRetryAfter(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        value.trim().toLongOrNull()?.let { seconds ->
            return (seconds.coerceAtLeast(0) * 1_000).coerceAtMost(MAX_RETRY_AFTER_MILLIS)
        }
        val parsed = try {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
                isLenient = false
            }.parse(value)?.time
        } catch (_: Exception) {
            null
        } ?: return null
        return (parsed - System.currentTimeMillis()).coerceIn(0, MAX_RETRY_AFTER_MILLIS)
    }

    private companion object {
        const val MAX_RETRY_AFTER_MILLIS: Long = 24 * 60 * 60 * 1_000
        const val MAX_RESPONSE_BODY_BYTES: Int = 64 * 1_024
    }
}
