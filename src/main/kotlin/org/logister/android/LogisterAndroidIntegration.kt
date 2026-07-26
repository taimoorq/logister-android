package org.logister.android

import android.annotation.TargetApi
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Build
import android.os.Bundle
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

internal class LogisterAndroidIntegration(
    private val application: Application,
    private val sessionTrackingEnabled: Boolean,
    private val installationTrackingEnabled: Boolean,
    private val installationRotationDays: Int,
    private val automaticCrashCaptureEnabled: Boolean,
    private val applicationExitCaptureEnabled: Boolean
) : Application.ActivityLifecycleCallbacks {
    @Volatile private var startedActivities: Int = 0
    @Volatile private var currentScreen: String? = null
    @Volatile private var sessionId: String? = if (sessionTrackingEnabled) UUID.randomUUID().toString() else null
    @Volatile private var backgroundedAt: Long? = null
    private var client: LogisterClient? = null
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    val inForeground: Boolean
        get() = startedActivities > 0

    fun currentSessionId(): String? = sessionId
    fun currentScreen(): String? = currentScreen

    fun installationIdHash(): String? {
        if (!installationTrackingEnabled) return null
        val preferences = application.getSharedPreferences("logister_identity", Application.MODE_PRIVATE)
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
        this.client = client
        application.registerActivityLifecycleCallbacks(this)
        if (automaticCrashCaptureEnabled) installExceptionHandler()
        if (applicationExitCaptureEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) captureHistoricalExit()
    }

    override fun onActivityStarted(activity: Activity) {
        val wasBackgrounded = startedActivities == 0
        startedActivities += 1
        currentScreen = activity.javaClass.name
        if (sessionTrackingEnabled && wasBackgrounded) {
            val elapsed = backgroundedAt?.let { System.currentTimeMillis() - it }
            if (sessionId == null || (elapsed != null && elapsed >= SESSION_TIMEOUT_MILLIS)) {
                sessionId = UUID.randomUUID().toString()
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
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                client?.captureExceptionAsync(
                    throwable,
                    LogisterEventOptions.builder()
                        .mechanism("unhandled_exception")
                        .handled(false)
                        .build()
                )?.get(2, TimeUnit.SECONDS)
            } catch (_: Exception) {
                // The previous handler must still run even when telemetry cannot be delivered.
            } finally {
                previousHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.R)
    private fun captureHistoricalExit() {
        val preferences = application.getSharedPreferences("logister_exit_info", Application.MODE_PRIVATE)
        val lastCaptured = preferences.getLong("last_exit_timestamp", 0L)
        val manager = application.getSystemService(ActivityManager::class.java)
        val exits = manager.getHistoricalProcessExitReasons(application.packageName, 0, 8)
            .filter { it.timestamp > lastCaptured }
            .sortedBy { it.timestamp }
        exits.forEach { exit ->
            val mechanism = mechanismFor(exit.reason) ?: return@forEach
            val description = exit.description ?: "Android process exit"
            val event = LogisterEvent.builder("error", description)
                .level(if (mechanism == "low_memory_kill") "warning" else "error")
                .occurredAt(iso8601(exit.timestamp))
                .context("error_mechanism", mechanism)
                .context("handled", false)
                .context("application_exit_reason", exit.reason)
                .context("application_exit_importance", exit.importance)
                .build()
            client?.captureAsync(event)
        }
        exits.maxOfOrNull { it.timestamp }?.let { timestamp ->
            preferences.edit().putLong("last_exit_timestamp", timestamp).apply()
        }
    }

    private fun mechanismFor(reason: Int): String? = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_CRASH -> "unhandled_exception"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native_crash"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory_kill"
        else -> null
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun iso8601(timestamp: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(timestamp))

    private companion object {
        val SESSION_TIMEOUT_MILLIS: Long = TimeUnit.MINUTES.toMillis(30)
    }
}
