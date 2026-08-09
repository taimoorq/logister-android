package org.logister.android

import android.annotation.TargetApi
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Build
import android.os.Bundle
import android.os.Process
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import org.json.JSONObject

internal class LogisterAndroidIntegration(
    private val application: Application,
    private val sessionTrackingEnabled: Boolean,
    private val installationTrackingEnabled: Boolean,
    private val installationRotationDays: Int,
    private val automaticCrashCaptureEnabled: Boolean,
    private val applicationExitCaptureEnabled: Boolean,
    private val storageNamespace: String,
    private val configuredPackageName: String?,
    private val configuredVersionName: String?,
    private val configuredVersionCode: String?,
    private val configuredEnvironment: String? = null,
    private val configuredRelease: String? = null,
) : Application.ActivityLifecycleCallbacks {
    @Volatile private var startedActivities: Int = 0
    @Volatile private var currentScreen: String? = null
    @Volatile private var sessionId: String? = if (sessionTrackingEnabled) UUID.randomUUID().toString() else null
    @Volatile private var sessionStartedAtMillis: Long? = if (sessionTrackingEnabled) System.currentTimeMillis() else null
    @Volatile private var backgroundedAt: Long? = null
    private var client: LogisterClient? = null
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private var installedHandler: LogisterUncaughtExceptionHandler? = null
    @Volatile private var attached: Boolean = false
    private val processStartedAtMillis: Long = System.currentTimeMillis()
    private val processRunJournal = LogisterProcessRunJournal(
        java.io.File(application.noBackupFilesDir, "logister_process_runs_$storageNamespace.json"),
    )

    val inForeground: Boolean
        get() = startedActivities > 0
    val crashHandlerInstalled: Boolean
        get() = installedHandler != null

    fun currentSessionId(): String? = sessionId
    fun currentSessionStartedAt(): String? = sessionStartedAtMillis?.let(::iso8601)
    fun currentScreen(): String? = currentScreen

    fun clearCollectedState() {
        sessionId = null
        sessionStartedAtMillis = null
        currentScreen = null
        backgroundedAt = null
        application.getSharedPreferences("logister_identity_$storageNamespace", Application.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        application.getSharedPreferences("logister_exit_info_$storageNamespace", Application.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        processRunJournal.clear()
    }

    fun resetRuntimeState() {
        if (sessionTrackingEnabled && sessionId == null) {
            sessionId = UUID.randomUUID().toString()
            sessionStartedAtMillis = System.currentTimeMillis()
        }
    }

    fun installationIdHash(): String? {
        if (!installationTrackingEnabled) return null
        val preferences = application.getSharedPreferences("logister_identity_$storageNamespace", Application.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val createdAt = preferences.getLong("installation_created_at", 0L)
        val rotationMillis = TimeUnit.DAYS.toMillis(installationRotationDays.toLong())
        var value = preferences.getString("installation_pseudonym", null)
        if (value.isNullOrBlank() || createdAt <= 0L || now - createdAt >= rotationMillis) {
            value = sha256(UUID.randomUUID().toString())
            preferences.edit()
                .putString("installation_pseudonym", value)
                .putLong("installation_created_at", now)
                .apply()
        }
        return value
    }

    fun attach(client: LogisterClient) {
        if (attached) return
        attached = true
        this.client = client
        application.registerActivityLifecycleCallbacks(this)
        if (automaticCrashCaptureEnabled) installExceptionHandler()
        if (applicationExitCaptureEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            client.runAsync {
                try {
                    captureHistoricalExit()
                } finally {
                    recordCurrentRun()
                }
            }
        }
    }

    fun detach() {
        if (!attached) return
        attached = false
        application.unregisterActivityLifecycleCallbacks(this)
        val handler = installedHandler
        if (handler != null && Thread.getDefaultUncaughtExceptionHandler() === handler) {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
        installedHandler = null
        previousHandler = null
        client = null
    }

    override fun onActivityStarted(activity: Activity) {
        val wasBackgrounded = startedActivities == 0
        startedActivities += 1
        currentScreen = activity.javaClass.name
        if (sessionTrackingEnabled && wasBackgrounded) {
            val elapsed = backgroundedAt?.let { System.currentTimeMillis() - it }
            if (sessionId == null || (elapsed != null && elapsed >= SESSION_TIMEOUT_MILLIS)) {
                sessionId = UUID.randomUUID().toString()
                sessionStartedAtMillis = System.currentTimeMillis()
            }
        }
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        if (startedActivities == 0) backgroundedAt = System.currentTimeMillis()
    }

    override fun onActivityResumed(activity: Activity) { currentScreen = activity.javaClass.name }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    private fun installExceptionHandler() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        val delegate = previousHandler ?: Thread.UncaughtExceptionHandler { _, _ ->
            Process.killProcess(Process.myPid())
            exitProcess(FALLBACK_EXIT_CODE)
        }
        installedHandler = LogisterUncaughtExceptionHandler(
            capture = { thread, throwable -> client?.captureUncaughtException(thread, throwable) },
            delegate = delegate,
        )
        Thread.setDefaultUncaughtExceptionHandler(installedHandler)
    }

    @TargetApi(Build.VERSION_CODES.R)
    private fun captureHistoricalExit() {
        val preferences = application.getSharedPreferences("logister_exit_info_$storageNamespace", Application.MODE_PRIVATE)
        val lastCaptured = preferences.getLong("last_exit_timestamp", 0L)
        val manager = application.getSystemService(ActivityManager::class.java)
        val exits = manager.getHistoricalProcessExitReasons(application.packageName, 0, 8)
            .filter { it.timestamp > lastCaptured }
            .sortedBy { it.timestamp }
        var checkpoint = lastCaptured
        for (exit in exits) {
            val mechanism = mechanismFor(exit.reason)
            if (mechanism == null) {
                checkpoint = exit.timestamp
                continue
            }
            if (automaticCrashCaptureEnabled && exit.reason == ApplicationExitInfo.REASON_CRASH) {
                checkpoint = exit.timestamp
                continue
            }
            val priorRun = processRunJournal.buildFor(exit.timestamp, exit.processName)
            val externalId = historicalExternalId(exit)
            val sourcePackageName = priorRun?.packageName ?: application.packageName
            val anrTrace = if (exit.reason == ApplicationExitInfo.REASON_ANR) {
                try {
                    exit.traceInputStream
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
            val diagnostic = try {
                anrTrace?.use { trace ->
                    LogisterApplicationExitEvidence.build(
                        externalId = externalId,
                        mechanism = mechanism,
                        reason = exit.reason,
                        importance = exit.importance,
                        status = exit.status,
                        pssKb = exit.pss,
                        rssKb = exit.rss,
                        packageName = sourcePackageName,
                        anrTrace = trace,
                    )
                } ?: LogisterApplicationExitEvidence.build(
                    externalId = externalId,
                    mechanism = mechanism,
                    reason = exit.reason,
                    importance = exit.importance,
                    status = exit.status,
                    pssKb = exit.pss,
                    rssKb = exit.rss,
                    packageName = sourcePackageName,
                )
            } catch (_: Exception) {
                LogisterApplicationExitEvidence.build(
                    externalId = externalId,
                    mechanism = mechanism,
                    reason = exit.reason,
                    importance = exit.importance,
                    status = exit.status,
                    pssKb = exit.pss,
                    rssKb = exit.rss,
                    packageName = sourcePackageName,
                )
            }
            val app = JSONObject()
                .put("package_name", sourcePackageName)
                .putOpt("version_name", priorRun?.versionName)
                .putOpt("version_code", priorRun?.versionCode)
            val event = LogisterEvent.builder("error", "Android process exit: $mechanism")
                .level(if (mechanism == "low_memory_kill") "warning" else "error")
                .occurredAt(iso8601(exit.timestamp))
                .context("error_mechanism", mechanism)
                .context("handled", false)
                .context("capture_source", "historical_exit")
                .context("exception_data_policy", "metadata_only")
                .context("application_exit_reason", exit.reason)
                .context("application_exit_importance", exit.importance)
                .context("application_exit_status", exit.status)
                .context("process_name", exit.processName)
                .context("diagnostic_external_id", externalId)
                .context("diagnostic", diagnostic)
                .context("build_provenance", if (priorRun == null) "unknown" else "process_run_journal")
                .context("app", app)
                .context("package_name", sourcePackageName)
                .context("app_version", priorRun?.versionName)
                .context("build_number", priorRun?.versionCode)
                .context("environment", priorRun?.environment)
                .context("release", priorRun?.release)
                .attribute("uuid", UUID.nameUUIDFromBytes(externalId.toByteArray(Charsets.UTF_8)).toString())
                .build()
            if (client?.persistHistoricalEvent(event) == true) {
                checkpoint = exit.timestamp
            } else {
                break
            }
        }
        if (checkpoint > lastCaptured) {
            preferences.edit().putLong("last_exit_timestamp", checkpoint).commit()
        }
    }

    private fun recordCurrentRun() {
        processRunJournal.record(
            LogisterProcessRun(
                startedAtMillis = processStartedAtMillis,
                packageName = configuredPackageName ?: application.packageName,
                versionName = configuredVersionName,
                versionCode = configuredVersionCode,
                environment = configuredEnvironment,
                release = configuredRelease,
                processName = currentProcessName(),
            ),
        )
    }

    private fun mechanismFor(reason: Int): String? = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_CRASH -> "unhandled_exception"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native_crash"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory_kill"
        else -> null
    }

    @TargetApi(Build.VERSION_CODES.R)
    private fun historicalExternalId(exit: ApplicationExitInfo): String = listOf(
        application.packageName,
        exit.processName,
        exit.timestamp.toString(),
        exit.reason.toString(),
        exit.importance.toString(),
    ).joinToString(":")

    private fun currentProcessName(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Application.getProcessName()
    } else {
        application.packageName
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun iso8601(timestamp: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(timestamp))

    private companion object {
        val SESSION_TIMEOUT_MILLIS: Long = TimeUnit.MINUTES.toMillis(30)
        const val FALLBACK_EXIT_CODE: Int = 10
    }
}
