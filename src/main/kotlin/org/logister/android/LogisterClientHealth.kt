package org.logister.android

/** Non-sensitive local state for setup and support diagnostics. */
public data class LogisterClientHealth(
    val collectionEnabled: Boolean,
    val capabilities: Set<String>,
    val queuedEventCount: Int,
    val discardedEventCount: Int,
    val lastDeliveryAt: String?,
    val lastError: String?,
    val processName: String?,
    val processPolicy: LogisterProcessPolicy,
    val crashHandlerInstalled: Boolean,
    val clientReports: Set<String>,
)

/** Controls which Android app processes may initialize automatic Logister behavior. */
public enum class LogisterProcessPolicy {
    MAIN_ONLY,
    CURRENT_PROCESS,
    ALLOWLIST,
}
