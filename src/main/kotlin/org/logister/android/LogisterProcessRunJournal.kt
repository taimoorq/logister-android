package org.logister.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class LogisterProcessRun(
    val startedAtMillis: Long,
    val packageName: String,
    val versionName: String?,
    val versionCode: String?,
    val environment: String?,
    val release: String?,
    val processName: String?,
)

internal class LogisterProcessRunJournal(
    private val file: File,
    private val maxRuns: Int = 16,
) {
    @Synchronized
    fun buildFor(exitTimestampMillis: Long, processName: String?): LogisterProcessRun? {
        val runs = readRuns().sortedBy { it.startedAtMillis }
        return runs.lastOrNull {
            it.startedAtMillis <= exitTimestampMillis &&
                (processName.isNullOrBlank() || it.processName.isNullOrBlank() || it.processName == processName)
        }
    }

    @Synchronized
    fun record(run: LogisterProcessRun) {
        val runs = readRuns()
            .filterNot { it.startedAtMillis == run.startedAtMillis }
            .plus(run)
            .sortedBy { it.startedAtMillis }
            .takeLast(maxRuns)
        val array = JSONArray()
        runs.forEach { value ->
            array.put(
                JSONObject()
                    .put("started_at_ms", value.startedAtMillis)
                    .put("package_name", value.packageName)
                    .putOpt("version_name", value.versionName)
                    .putOpt("version_code", value.versionCode)
                    .putOpt("environment", value.environment)
                    .putOpt("release", value.release)
                    .putOpt("process_name", value.processName),
            )
        }
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(array.toString(), Charsets.UTF_8)
        if (!temporary.renameTo(file)) {
            file.writeText(array.toString(), Charsets.UTF_8)
            temporary.delete()
        }
    }

    @Synchronized
    fun clear(): Boolean = !file.exists() || file.delete()

    private fun readRuns(): List<LogisterProcessRun> {
        if (!file.isFile) return emptyList()
        return try {
            val array = JSONArray(file.readText(Charsets.UTF_8))
            buildList {
                for (index in 0 until array.length()) {
                    val value = array.optJSONObject(index) ?: continue
                    val startedAt = value.optLong("started_at_ms", 0L)
                    val packageName = value.optString("package_name", "")
                    if (startedAt <= 0L || packageName.isBlank()) continue
                    add(
                        LogisterProcessRun(
                            startedAtMillis = startedAt,
                            packageName = packageName,
                            versionName = value.optionalString("version_name"),
                            versionCode = value.optionalString("version_code"),
                            environment = value.optionalString("environment"),
                            release = value.optionalString("release"),
                            processName = value.optionalString("process_name"),
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

private fun JSONObject.optionalString(key: String): String? =
    optString(key, "").takeIf { it.isNotBlank() }
