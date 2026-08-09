package org.logister.android

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
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
        connection.requestMethod = "POST"
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.doOutput = true
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
        connection.disconnect()
        return LogisterResponse.withRetryAfter(statusCode, body, retryAfterMillis)
    }

    @Throws(Exception::class)
    private fun readBody(inputStream: InputStream?): String {
        if (inputStream == null) {
            return ""
        }

        val body = StringBuilder()
        BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).use { reader ->
            var line = reader.readLine()
            while (line != null) {
                body.append(line)
                line = reader.readLine()
            }
        }
        return body.toString()
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
    }
}
