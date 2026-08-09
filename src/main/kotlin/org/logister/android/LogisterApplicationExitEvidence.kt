package org.logister.android

import java.io.ByteArrayOutputStream
import java.io.InputStream
import org.json.JSONArray
import org.json.JSONObject

/** Builds bounded, structured evidence from Android's historical process-exit record. */
internal object LogisterApplicationExitEvidence {
    private const val MAX_TRACE_BYTES = 64 * 1024
    private const val MAX_TRACE_LINES = 800
    private const val MAX_THREADS = 16
    private const val MAX_FRAMES_PER_THREAD = 64
    private const val MAX_TOTAL_FRAMES = 256
    private const val MAX_THREAD_NAME_LENGTH = 128

    private val threadHeader = Regex("^\"([^\"]{1,$MAX_THREAD_NAME_LENGTH})\"(?:\\s+.*)?$")
    private val javaFrame = Regex("^\\s*at\\s+([^\\s(]+)\\(([^)]*)\\)\\s*$")

    fun build(
        externalId: String,
        mechanism: String,
        reason: Int,
        importance: Int,
        status: Int,
        pssKb: Long,
        rssKb: Long,
        packageName: String,
        anrTrace: InputStream? = null,
    ): JSONObject {
        val diagnostic = JSONObject()
            .put("external_id", externalId)
            .put("source", "application_exit_info")
            .put("kind", mechanism)
            .put("reason", reason)
            .put("importance", importance)
            .put("status", status)

        val measurements = JSONObject()
        sampledBytes(pssKb)?.let {
            measurements.put(
                "last_pss",
                measurement(it, "ApplicationExitInfo.pss"),
            )
        }
        sampledBytes(rssKb)?.let {
            measurements.put(
                "last_rss",
                measurement(it, "ApplicationExitInfo.rss"),
            )
        }
        if (measurements.length() > 0) diagnostic.put("measurements", measurements)

        if (mechanism == "anr" && anrTrace != null) {
            parseAnrTrace(anrTrace, packageName)?.let { diagnostic.put("thread_dump", it) }
        }

        diagnostic.put(
            "evidence_kind",
            if (diagnostic.has("thread_dump")) "sampled_thread_dump" else "termination_metadata",
        )
        return diagnostic
    }

    internal fun parseAnrTrace(input: InputStream, packageName: String): JSONObject? {
        val captured = readBounded(input)
        if (captured.bytes.isEmpty()) return null

        val threads = mutableListOf<MutableThread>()
        var current: MutableThread? = null
        var totalFrames = 0
        var lineLimitReached = false

        captured.bytes.toString(Charsets.UTF_8).lineSequence().forEachIndexed { index, line ->
            if (index >= MAX_TRACE_LINES) {
                lineLimitReached = true
                return@forEachIndexed
            }

            val header = threadHeader.matchEntire(line)
            if (header != null) {
                current = if (threads.size < MAX_THREADS) {
                    MutableThread(header.groupValues[1]).also(threads::add)
                } else {
                    null
                }
                return@forEachIndexed
            }

            val thread = current ?: return@forEachIndexed
            if (thread.frames.size >= MAX_FRAMES_PER_THREAD || totalFrames >= MAX_TOTAL_FRAMES) return@forEachIndexed
            val frame = parseFrame(line, packageName) ?: return@forEachIndexed
            thread.frames.add(frame)
            totalFrames += 1
        }

        val populated = threads.filter { it.frames.isNotEmpty() }
            .sortedWith(compareByDescending<MutableThread> { it.name == "main" })
        if (populated.isEmpty()) return null

        return JSONObject()
            .put("format", "android_anr_text_v1")
            .put("source_field", "ApplicationExitInfo.traceInputStream")
            .put("truncated", captured.truncated || lineLimitReached || totalFrames >= MAX_TOTAL_FRAMES)
            .put(
                "threads",
                JSONArray(populated.map { thread ->
                    JSONObject()
                        .put("name", thread.name)
                        .put("role", if (thread.name == "main") "main" else "sampled")
                        .put("attributed", thread.name == "main")
                        .put("frames", JSONArray(thread.frames))
                }),
            )
    }

    private fun parseFrame(line: String, packageName: String): JSONObject? {
        val match = javaFrame.matchEntire(line) ?: return null
        val qualifiedMethod = match.groupValues[1].take(512)
        val className = qualifiedMethod.substringBeforeLast('.', missingDelimiterValue = "").take(384)
        val methodName = qualifiedMethod.substringAfterLast('.').take(128)
        if (className.isBlank() || methodName.isBlank()) return null

        val rawLocation = match.groupValues[2].take(256)
        val lineMatch = Regex("^(.*):(\\d+)$").matchEntire(rawLocation)
        val fileName = when {
            lineMatch != null -> lineMatch.groupValues[1]
            rawLocation in setOf("Native Method", "Unknown Source") -> null
            else -> rawLocation.takeIf { it.isNotBlank() }
        }
        val lineNumber = lineMatch?.groupValues?.get(2)?.toIntOrNull()?.takeIf { it > 0 }

        return JSONObject()
            .put("class_name", className)
            .put("method_name", methodName)
            .putOpt("file", fileName)
            .putOpt("line_number", lineNumber)
            .put("in_app", packageName.isNotBlank() && className.startsWith(packageName))
    }

    private fun readBounded(input: InputStream): CapturedBytes {
        val output = ByteArrayOutputStream(MAX_TRACE_BYTES.coerceAtMost(8 * 1024))
        val buffer = ByteArray(4 * 1024)
        var truncated = false

        while (output.size() <= MAX_TRACE_BYTES) {
            val remaining = MAX_TRACE_BYTES + 1 - output.size()
            if (remaining <= 0) break
            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) break
            if (count == 0) continue
            output.write(buffer, 0, count)
        }

        var bytes = output.toByteArray()
        if (bytes.size > MAX_TRACE_BYTES) {
            bytes = bytes.copyOf(MAX_TRACE_BYTES)
            truncated = true
        }
        return CapturedBytes(bytes, truncated)
    }

    private fun sampledBytes(kilobytes: Long): Long? {
        if (kilobytes <= 0 || kilobytes > Long.MAX_VALUE / 1024L) return null
        return kilobytes * 1024L
    }

    private fun measurement(value: Long, sourceField: String): JSONObject = JSONObject()
        .put("value", value)
        .put("unit", "bytes")
        .put("source_field", sourceField)
        .put("precision", "last_system_sample")

    private data class MutableThread(
        val name: String,
        val frames: MutableList<JSONObject> = mutableListOf(),
    )

    private data class CapturedBytes(val bytes: ByteArray, val truncated: Boolean)
}
