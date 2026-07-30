package org.logister.android

import android.app.Application
import android.os.Build
import java.util.LinkedHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

internal const val LOGISTER_ANDROID_SDK_VERSION: String = "0.3.0"

/** Main Android client for sending telemetry to Logister. */
public class LogisterClient private constructor(
    private val tokenProvider: LogisterTokenProvider,
    private val endpoint: String,
    private val environment: String?,
    private val release: String?,
    private val repository: String?,
    private val commitSha: String?,
    private val branch: String?,
    private val service: String?,
    private val packageName: String?,
    private val appVersion: String?,
    private val buildNumber: String?,
    private val buildType: String?,
    private val defaultContext: Map<String, Any>,
    private val includeDeviceContext: Boolean,
    private val connectTimeoutMs: Int,
    private val readTimeoutMs: Int,
    private val tokenRefreshSkewSeconds: Long,
    private val transport: LogisterTransport,
    private val executor: ExecutorService,
    private val exceptionDataPolicy: LogisterExceptionDataPolicy,
    private val automaticCrashExceptionDataPolicy: LogisterExceptionDataPolicy,
    breadcrumbCapacity: Int
) {
    @Volatile
    private var cachedToken: LogisterToken? = null
    private val breadcrumbBuffer: LogisterBreadcrumbBuffer = LogisterBreadcrumbBuffer(breadcrumbCapacity)
    @Volatile private var androidIntegration: LogisterAndroidIntegration? = null
    @Volatile private var offlineQueue: LogisterOfflineQueue? = null

    public fun captureAsync(event: LogisterEvent): Future<LogisterResponse> =
        captureAsync(event, LogisterEventOptions.EMPTY)

    public fun captureAsync(
        event: LogisterEvent,
        options: LogisterEventOptions?
    ): Future<LogisterResponse> =
        executor.submit<LogisterResponse> { capture(event, options) }

    @Throws(Exception::class)
    public fun capture(event: LogisterEvent): LogisterResponse =
        capture(event, LogisterEventOptions.EMPTY)

    @Throws(Exception::class)
    public fun capture(event: LogisterEvent, options: LogisterEventOptions?): LogisterResponse {
        return deliver(buildEnvelope(event, options ?: LogisterEventOptions.EMPTY))
    }

    public fun addBreadcrumb(breadcrumb: LogisterBreadcrumb) {
        breadcrumbBuffer.add(breadcrumb)
    }

    public fun queuedEventCount(): Int = offlineQueue?.size() ?: 0

    public fun flushQueuedEventsAsync(): Future<Int> = executor.submit<Int> { flushQueuedEvents() }

    @Throws(Exception::class)
    public fun flushQueuedEvents(): Int {
        val queue = offlineQueue ?: return 0
        val token = mobileIngestToken()
        return queue.flush { queuedEnvelope ->
            try {
                transport.send(endpoint, token, queuedEnvelope, connectTimeoutMs, readTimeoutMs).isAccepted
            } catch (_: Exception) {
                false
            }
        }
    }

    public fun clearQueuedEvents(): Boolean = offlineQueue?.clear() ?: true

    /**
     * Removes queued events containing a session or user identifier while retaining anonymous
     * automatic crashes. Call this during logout or account replacement.
     */
    public fun clearSessionBoundQueuedEvents(): Int = offlineQueue?.removeIf(::isAccountBound) ?: 0

    internal fun attachAndroidIntegration(integration: LogisterAndroidIntegration?) {
        androidIntegration = integration
        integration?.attach(this)
    }

    internal fun attachOfflineQueue(queue: LogisterOfflineQueue?) {
        offlineQueue = queue
    }

    @Throws(Exception::class)
    private fun deliver(envelope: JSONObject): LogisterResponse {
        val token = try {
            mobileIngestToken()
        } catch (error: Exception) {
            if (offlineQueue?.enqueue(envelope) == true) return LogisterResponse.queued()
            throw error
        }
        offlineQueue?.flush { queuedEnvelope ->
            try {
                transport.send(endpoint, token, queuedEnvelope, connectTimeoutMs, readTimeoutMs).isAccepted
            } catch (_: Exception) {
                false
            }
        }

        return try {
            val response = transport.send(endpoint, token, envelope, connectTimeoutMs, readTimeoutMs)
            if (response.statusCode == 429 || response.statusCode >= 500) {
                if (offlineQueue?.enqueue(envelope) == true) LogisterResponse.queued() else response
            } else {
                response
            }
        } catch (error: Exception) {
            if (offlineQueue?.enqueue(envelope) == true) LogisterResponse.queued() else throw error
        }
    }

    @Throws(Exception::class)
    private fun mobileIngestToken(): String {
        val now = System.currentTimeMillis() / 1000
        val existing = cachedToken
        if (existing != null && !existing.shouldRefresh(now, tokenRefreshSkewSeconds)) {
            return existing.token
        }

        synchronized(this) {
            val refreshedNow = System.currentTimeMillis() / 1000
            val refreshedExisting = cachedToken
            if (refreshedExisting != null && !refreshedExisting.shouldRefresh(refreshedNow, tokenRefreshSkewSeconds)) {
                return refreshedExisting.token
            }

            val fresh = tokenProvider.fetchToken()
            val token = requireValue("mobileIngestToken", fresh.token)
            require(!fresh.isExpired(refreshedNow)) { "mobileIngestToken is expired" }
            cachedToken = fresh
            return token
        }
    }

    public fun captureExceptionAsync(throwable: Throwable): Future<LogisterResponse> =
        captureExceptionAsync(throwable, LogisterEventOptions.EMPTY)

    public fun captureExceptionAsync(
        throwable: Throwable,
        options: LogisterEventOptions?
    ): Future<LogisterResponse> =
        captureAsync(exceptionEvent(throwable, exceptionDataPolicy, CAPTURE_SOURCE_MANUAL), options)

    @Throws(Exception::class)
    public fun captureException(throwable: Throwable, options: LogisterEventOptions? = null): LogisterResponse =
        capture(
            exceptionEvent(throwable, exceptionDataPolicy, CAPTURE_SOURCE_MANUAL),
            options ?: LogisterEventOptions.EMPTY,
        )

    internal fun captureUncaughtException(throwable: Throwable) {
        val event = exceptionEvent(
            throwable = throwable,
            policy = automaticCrashExceptionDataPolicy,
            captureSource = CAPTURE_SOURCE_AUTOMATIC,
        )
        val options = LogisterEventOptions.builder()
            .mechanism("unhandled_exception")
            .handled(false)
            .build()
        val envelope = buildEnvelope(event, options)
        if (offlineQueue?.enqueue(envelope) == true) {
            executor.submit {
                try {
                    flushQueuedEvents()
                } catch (_: Exception) {
                    // The durable envelope remains queued for a later authenticated flush.
                }
            }
            return
        }

        try {
            captureAsync(event, options).get(CRASH_DELIVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: Exception) {
            // The existing uncaught-exception handler must always continue.
        }
    }

    public fun captureMessageAsync(message: String): Future<LogisterResponse> =
        captureMessageAsync(message, LogisterEventOptions.EMPTY)

    public fun captureMessageAsync(
        message: String,
        options: LogisterEventOptions?
    ): Future<LogisterResponse> {
        val level = options?.level ?: "info"
        return captureAsync(LogisterEvent.builder("log", message).level(level).build(), options)
    }

    public fun captureMetricAsync(name: String, value: Double): Future<LogisterResponse> =
        captureMetricAsync(name, value, null, LogisterEventOptions.EMPTY)

    public fun captureMetricAsync(
        name: String,
        value: Double,
        unit: String?
    ): Future<LogisterResponse> =
        captureMetricAsync(name, value, unit, LogisterEventOptions.EMPTY)

    public fun captureMetricAsync(
        name: String,
        value: Double,
        unit: String?,
        options: LogisterEventOptions?
    ): Future<LogisterResponse> {
        val event = LogisterEvent.builder("metric", name)
            .context("value", value)
        if (unit != null) {
            event.context("unit", unit)
        }
        return captureAsync(event.build(), options)
    }

    public fun captureTransactionAsync(name: String, durationMs: Double): Future<LogisterResponse> =
        captureTransactionAsync(name, durationMs, LogisterEventOptions.EMPTY)

    public fun captureTransactionAsync(
        name: String,
        durationMs: Double,
        options: LogisterEventOptions?
    ): Future<LogisterResponse> {
        val event = LogisterEvent.builder("transaction", name)
            .attribute("transaction_name", name)
            .attribute("duration_ms", durationMs)
            .build()
        return captureAsync(event, options)
    }

    public fun captureSpanAsync(span: LogisterSpan): Future<LogisterResponse> =
        captureSpanAsync(span, LogisterEventOptions.EMPTY)

    public fun captureSpanAsync(
        span: LogisterSpan,
        options: LogisterEventOptions?
    ): Future<LogisterResponse> =
        captureAsync(span.toEvent(), options)

    public fun checkInAsync(slug: String, status: String): Future<LogisterResponse> =
        checkInAsync(slug, status, LogisterEventOptions.EMPTY)

    public fun checkInAsync(
        slug: String,
        status: String,
        options: LogisterEventOptions?
    ): Future<LogisterResponse> {
        val event = LogisterEvent.builder("check_in", slug)
            .context("check_in_slug", slug)
            .context("check_in_status", status)
            .build()
        return captureAsync(event, options)
    }

    @Throws(Exception::class)
    private fun buildEventPayload(event: LogisterEvent, options: LogisterEventOptions): JSONObject {
        val payload = event.toJson()
        val context = baseContext()
        context.putAll(event.context)
        context.putAll(options.context)

        LogisterEvent.putIfPresent(payload, "level", firstPresent(options.level, event.level))
        LogisterEvent.putIfPresent(payload, "fingerprint", firstPresent(options.fingerprint, event.fingerprint))
        LogisterEvent.putIfPresent(payload, "occurred_at", firstPresent(options.occurredAt, event.occurredAt))
        LogisterEvent.putIfPresent(payload, "environment", firstPresent(options.environment, environment))
        LogisterEvent.putIfPresent(payload, "release", firstPresent(options.release, release))
        LogisterEvent.putIfPresent(payload, "trace_id", options.traceId)
        LogisterEvent.putIfPresent(payload, "request_id", options.requestId)
        LogisterEvent.putIfPresent(payload, "session_id", options.sessionId)
        LogisterEvent.putIfPresent(payload, "user_id", options.userId)
        LogisterEvent.putIfPresent(payload, "transaction_name", options.transactionName)
        LogisterEvent.putIfPresent(payload, "duration_ms", options.durationMs)

        putContext(context, "environment", firstPresent(options.environment, environment))
        putContext(context, "release", firstPresent(options.release, release))
        putContext(context, "trace_id", options.traceId)
        putContext(context, "request_id", options.requestId)
        putContext(context, "session_id", options.sessionId)
        putContext(context, "user_id", options.userId)
        putContext(context, "transaction_name", options.transactionName)
        putContext(context, "duration_ms", options.durationMs)

        val runtime = androidIntegration
        val sessionId = firstPresent(options.sessionId, runtime?.currentSessionId())
        val screenName = firstPresent(options.screenName, runtime?.currentScreen())
        val inForeground = options.inForeground ?: runtime?.inForeground
        val installationIdHash = runtime?.installationIdHash()
        putContext(context, "session_id", sessionId)
        putContext(context, "screen_name", screenName)
        putContext(context, "in_foreground", inForeground)
        putContext(context, "installation_id_hash", installationIdHash)

        val app = JSONObject()
        putJson(app, "package_name", packageName)
        putJson(app, "version_name", appVersion)
        putJson(app, "version_code", buildNumber)
        putJson(app, "build_type", buildType)
        putJson(app, "screen", screenName)
        putJson(app, "in_foreground", inForeground)
        if (app.length() > 0) context["app"] = app

        val session = JSONObject()
        putJson(session, "id", sessionId)
        if (session.length() > 0) context["session"] = session

        val installation = JSONObject()
        putJson(installation, "id_hash", installationIdHash)
        if (installation.length() > 0) context["installation"] = installation

        if (event.eventType == "error") {
            val mechanism = options.mechanism ?: if (event.context.containsKey("exception")) "handled_exception" else event.context["error_mechanism"]?.toString()
            val handled = options.handled ?: (mechanism == "handled_exception")
            val error = JSONObject()
            putJson(error, "mechanism", mechanism)
            putJson(error, "handled", handled)
            putJson(error, "user_perceived", if (mechanism in setOf("unhandled_exception", "anr", "native_crash")) inForeground else null)
            putJson(error, "capture_source", event.context[CAPTURE_SOURCE_CONTEXT_KEY])
            putJson(error, "data_policy", event.context[EXCEPTION_DATA_POLICY_CONTEXT_KEY])
            if (error.length() > 0) context["error"] = error
        }

        val breadcrumbs = breadcrumbBuffer.snapshot()
        if (breadcrumbs.isNotEmpty()) context["breadcrumbs"] = JSONArray(breadcrumbs)

        payload.put("context", JSONObject(context))
        return payload
    }

    private fun buildEnvelope(
        event: LogisterEvent,
        options: LogisterEventOptions,
    ): JSONObject = JSONObject().put("event", buildEventPayload(event, options))

    private fun exceptionEvent(
        throwable: Throwable,
        policy: LogisterExceptionDataPolicy,
        captureSource: String,
    ): LogisterEvent {
        var message = throwable.javaClass.name
        if (policy == LogisterExceptionDataPolicy.FULL && !throwable.message.isNullOrEmpty()) {
            message += ": ${throwable.message}"
        }

        return try {
            LogisterEvent.builder("error", message)
                .level("error")
                .context("exception", LogisterExceptionSerializer.serialize(throwable, policy))
                .context(CAPTURE_SOURCE_CONTEXT_KEY, captureSource)
                .context(EXCEPTION_DATA_POLICY_CONTEXT_KEY, policy.wireValue)
                .build()
        } catch (_: Exception) {
            LogisterEvent.builder("error", message)
                .level("error")
                .context("exception_class", throwable.javaClass.name)
                .context(CAPTURE_SOURCE_CONTEXT_KEY, captureSource)
                .context(EXCEPTION_DATA_POLICY_CONTEXT_KEY, policy.wireValue)
                .build()
        }
    }

    private fun baseContext(): MutableMap<String, Any> {
        val context: MutableMap<String, Any> = LinkedHashMap()
        context["platform"] = "android"
        context["telemetry_schema_version"] = 2
        putContext(context, "service", firstPresent(service, packageName))
        putContext(context, "package_name", packageName)
        putContext(context, "app_version", appVersion)
        putContext(context, "build_number", buildNumber)
        putContext(context, "build_type", buildType)
        putContext(context, "repository", repository)
        putContext(context, "commit_sha", commitSha)
        putContext(context, "branch", branch)
        context.putAll(defaultContext)

        if (includeDeviceContext) {
            putContext(context, "device_manufacturer", Build.MANUFACTURER)
            putContext(context, "device_model", Build.MODEL)
            putContext(context, "device_brand", Build.BRAND)
            putContext(context, "os_name", "Android")
            putContext(context, "os_version", Build.VERSION.RELEASE)
            context["android_api_level"] = Build.VERSION.SDK_INT

            val device = JSONObject()
            putJson(device, "manufacturer", Build.MANUFACTURER)
            putJson(device, "model", Build.MODEL)
            putJson(device, "brand", Build.BRAND)
            context["device"] = device

            val os = JSONObject()
            putJson(os, "name", "Android")
            putJson(os, "version", Build.VERSION.RELEASE)
            putJson(os, "api_level", Build.VERSION.SDK_INT)
            context["os"] = os
        }

        return context
    }

    private fun isAccountBound(envelope: JSONObject): Boolean {
        val event = envelope.optJSONObject("event") ?: return false
        if (event.hasAccountIdentifier()) return true
        val context = event.optJSONObject("context") ?: return false
        if (context.hasAccountIdentifier()) return true
        if (context.optJSONObject("session")?.has("id") == true) return true
        return context.optJSONObject("user")?.has("id") == true
    }

    public class Builder internal constructor(
        private val tokenProvider: LogisterTokenProvider,
        private val endpoint: String
    ) {
        private var environment: String? = null
        private var release: String? = null
        private var repository: String? = null
        private var commitSha: String? = null
        private var branch: String? = null
        private var service: String? = null
        private var packageName: String? = null
        private var appVersion: String? = null
        private var buildNumber: String? = null
        private var buildType: String? = null
        private val defaultContext: MutableMap<String, Any> = LinkedHashMap()
        private var includeDeviceContext: Boolean = true
        private var connectTimeoutMs: Int = 10_000
        private var readTimeoutMs: Int = 10_000
        private var tokenRefreshSkewSeconds: Long = 60
        private var transport: LogisterTransport = HttpUrlConnectionLogisterTransport()
        private var executor: ExecutorService = Executors.newSingleThreadExecutor()
        private var application: Application? = null
        private var sessionTrackingEnabled: Boolean = false
        private var installationTrackingEnabled: Boolean = false
        private var installationRotationDays: Int = 90
        private var automaticCrashCaptureEnabled: Boolean = false
        private var applicationExitCaptureEnabled: Boolean = false
        private var breadcrumbCapacity: Int = 0
        private var offlineQueueEnabled: Boolean = false
        private var offlineQueueMaxEvents: Int = 30
        private var offlineQueueMaxBytes: Int = 512 * 1024
        private var offlineQueueMaxAgeDays: Int = 7
        private var exceptionDataPolicy: LogisterExceptionDataPolicy = LogisterExceptionDataPolicy.FULL
        private var automaticCrashExceptionDataPolicy: LogisterExceptionDataPolicy =
            LogisterExceptionDataPolicy.TYPE_AND_STACKTRACE

        public fun environment(environment: String?): Builder = apply {
            this.environment = environment
        }

        public fun release(release: String?): Builder = apply {
            this.release = release
        }

        public fun repository(repository: String?): Builder = apply {
            this.repository = repository
        }

        public fun commitSha(commitSha: String?): Builder = apply {
            this.commitSha = commitSha
        }

        public fun branch(branch: String?): Builder = apply {
            this.branch = branch
        }

        public fun service(service: String?): Builder = apply {
            this.service = service
        }

        public fun packageName(packageName: String?): Builder = apply {
            this.packageName = packageName
        }

        public fun appVersion(appVersion: String?): Builder = apply {
            this.appVersion = appVersion
        }

        public fun buildNumber(buildNumber: String?): Builder = apply {
            this.buildNumber = buildNumber
        }

        public fun buildType(buildType: String?): Builder = apply {
            this.buildType = buildType
        }

        public fun defaultContext(key: String?, value: Any?): Builder = apply {
            if (!key.isNullOrEmpty() && value != null) {
                defaultContext[key] = value
            }
        }

        public fun defaultContext(context: Map<String, *>?): Builder = apply {
            context?.forEach { (key, value) ->
                defaultContext(key, value)
            }
        }

        public fun includeDeviceContext(includeDeviceContext: Boolean): Builder = apply {
            this.includeDeviceContext = includeDeviceContext
        }

        public fun timeoutMs(connectTimeoutMs: Int, readTimeoutMs: Int): Builder = apply {
            this.connectTimeoutMs = connectTimeoutMs
            this.readTimeoutMs = readTimeoutMs
        }

        public fun tokenRefreshSkewSeconds(tokenRefreshSkewSeconds: Long): Builder = apply {
            this.tokenRefreshSkewSeconds = tokenRefreshSkewSeconds
        }

        public fun transport(transport: LogisterTransport?): Builder = apply {
            if (transport != null) {
                this.transport = transport
            }
        }

        public fun executor(executor: ExecutorService?): Builder = apply {
            if (executor != null) {
                this.executor = executor
            }
        }

        /** Supplies the app process needed for lifecycle, identity, exit, and disk-queue features. */
        public fun application(application: Application?): Builder = apply {
            this.application = application
        }

        public fun sessionTracking(enabled: Boolean): Builder = apply {
            sessionTrackingEnabled = enabled
        }

        /** Enables a random, SHA-256 pseudonym that rotates on this device. No hardware ID is read. */
        public fun installationTracking(enabled: Boolean, rotationDays: Int = 90): Builder = apply {
            require(rotationDays in 1..365) { "rotationDays must be between 1 and 365" }
            installationTrackingEnabled = enabled
            installationRotationDays = rotationDays
        }

        @JvmOverloads
        public fun automaticCrashCapture(
            enabled: Boolean,
            exceptionDataPolicy: LogisterExceptionDataPolicy = LogisterExceptionDataPolicy.TYPE_AND_STACKTRACE,
        ): Builder = apply {
            automaticCrashCaptureEnabled = enabled
            automaticCrashExceptionDataPolicy = exceptionDataPolicy
        }

        /** Sets the policy used by manual captureException calls. */
        public fun exceptionDataPolicy(exceptionDataPolicy: LogisterExceptionDataPolicy): Builder = apply {
            this.exceptionDataPolicy = exceptionDataPolicy
        }

        public fun applicationExitCapture(enabled: Boolean): Builder = apply {
            applicationExitCaptureEnabled = enabled
        }

        public fun breadcrumbs(capacity: Int = 50): Builder = apply {
            require(capacity in 0..100) { "breadcrumb capacity must be between 0 and 100" }
            breadcrumbCapacity = capacity
        }

        /**
         * Enables the durable queue with its default seven-day retention period.
         *
         * Keep this three-argument/defaulted overload binary-compatible with 0.2.x Kotlin callers.
         */
        public fun offlineQueue(
            enabled: Boolean,
            maxEvents: Int = 30,
            maxBytes: Int = 512 * 1024,
        ): Builder = offlineQueue(enabled, maxEvents, maxBytes, maxAgeDays = 7)

        /** Enables the durable queue with an explicit retention period. */
        public fun offlineQueue(
            enabled: Boolean,
            maxEvents: Int,
            maxBytes: Int,
            maxAgeDays: Int,
        ): Builder = apply {
            require(maxEvents in 1..100) { "maxEvents must be between 1 and 100" }
            require(maxBytes in 16 * 1024..2 * 1024 * 1024) { "maxBytes must be between 16 KiB and 2 MiB" }
            require(maxAgeDays in 1..30) { "maxAgeDays must be between 1 and 30" }
            offlineQueueEnabled = enabled
            offlineQueueMaxEvents = maxEvents
            offlineQueueMaxBytes = maxBytes
            offlineQueueMaxAgeDays = maxAgeDays
        }

        public fun build(): LogisterClient {
            val needsApplication = sessionTrackingEnabled || installationTrackingEnabled || automaticCrashCaptureEnabled || applicationExitCaptureEnabled || offlineQueueEnabled
            require(!needsApplication || application != null) { "application is required for enabled Android integrations" }
            require(!(automaticCrashCaptureEnabled || applicationExitCaptureEnabled) || offlineQueueEnabled) {
                "automatic crash and application-exit capture require the durable offline queue"
            }

            val effectiveDefaultContext = LinkedHashMap(defaultContext)
            val sdk = JSONObject()
            putJson(sdk, "name", "logister-android")
            putJson(sdk, "version", LOGISTER_ANDROID_SDK_VERSION)
            putJson(sdk, "session_tracking", sessionTrackingEnabled)
            putJson(sdk, "installation_tracking", installationTrackingEnabled)
            putJson(sdk, "breadcrumbs_capacity", breadcrumbCapacity)
            putJson(sdk, "automatic_crash_capture", automaticCrashCaptureEnabled)
            putJson(sdk, "automatic_crash_data_policy", automaticCrashExceptionDataPolicy.wireValue)
            putJson(sdk, "manual_exception_data_policy", exceptionDataPolicy.wireValue)
            putJson(sdk, "application_exit_capture", applicationExitCaptureEnabled)
            putJson(sdk, "offline_queue", offlineQueueEnabled)
            putJson(sdk, "offline_queue_max_age_days", offlineQueueMaxAgeDays)
            effectiveDefaultContext["sdk"] = sdk

            val client = LogisterClient(
                tokenProvider = tokenProvider,
                endpoint = requireValue("endpoint", endpoint),
                environment = environment,
                release = release,
                repository = repository,
                commitSha = commitSha,
                branch = branch,
                service = service,
                packageName = packageName,
                appVersion = appVersion,
                buildNumber = buildNumber,
                buildType = buildType,
                defaultContext = effectiveDefaultContext,
                includeDeviceContext = includeDeviceContext,
                connectTimeoutMs = connectTimeoutMs,
                readTimeoutMs = readTimeoutMs,
                tokenRefreshSkewSeconds = tokenRefreshSkewSeconds,
                transport = transport,
                executor = executor,
                exceptionDataPolicy = exceptionDataPolicy,
                automaticCrashExceptionDataPolicy = automaticCrashExceptionDataPolicy,
                breadcrumbCapacity = breadcrumbCapacity
            )
            val app = application
            if (app != null) {
                if (offlineQueueEnabled) {
                    client.attachOfflineQueue(
                        LogisterOfflineQueue(
                            context = app,
                            maxEvents = offlineQueueMaxEvents,
                            maxBytes = offlineQueueMaxBytes,
                            maxAgeDays = offlineQueueMaxAgeDays,
                        ),
                    )
                }
                val runtimeIntegrationEnabled = sessionTrackingEnabled || installationTrackingEnabled || automaticCrashCaptureEnabled || applicationExitCaptureEnabled
                if (runtimeIntegrationEnabled) {
                    client.attachAndroidIntegration(
                        LogisterAndroidIntegration(
                            application = app,
                            sessionTrackingEnabled = sessionTrackingEnabled,
                            installationTrackingEnabled = installationTrackingEnabled,
                            installationRotationDays = installationRotationDays,
                            automaticCrashCaptureEnabled = automaticCrashCaptureEnabled,
                            applicationExitCaptureEnabled = applicationExitCaptureEnabled
                        )
                    )
                }
            }
            return client
        }
    }

    public companion object {
        private const val CAPTURE_SOURCE_CONTEXT_KEY = "capture_source"
        private const val EXCEPTION_DATA_POLICY_CONTEXT_KEY = "exception_data_policy"
        private const val CAPTURE_SOURCE_MANUAL = "manual"
        private const val CAPTURE_SOURCE_AUTOMATIC = "automatic"
        private const val CRASH_DELIVERY_TIMEOUT_SECONDS = 2L

        @JvmStatic
        public fun builder(tokenProvider: LogisterTokenProvider, baseUrl: String): Builder =
            Builder(tokenProvider, endpointFromBaseUrl(baseUrl))

        @JvmStatic
        public fun endpointBuilder(tokenProvider: LogisterTokenProvider, endpoint: String): Builder =
            Builder(tokenProvider, endpoint)
    }
}

private fun JSONObject.hasAccountIdentifier(): Boolean = has("session_id") || has("user_id")

private fun putContext(context: MutableMap<String, Any>, key: String, value: Any?) {
    if (value != null && !context.containsKey(key)) {
        context[key] = value
    }
}

private fun putJson(json: JSONObject, key: String, value: Any?) {
    if (value != null) json.put(key, value)
}

private fun firstPresent(first: String?, second: String?): String? {
    if (!first.isNullOrEmpty()) {
        return first
    }
    return if (second.isNullOrEmpty()) null else second
}

private fun requireValue(name: String, value: String?): String {
    require(!value.isNullOrBlank()) { "$name is required" }
    return value
}

private fun endpointFromBaseUrl(baseUrl: String): String {
    val normalized = requireValue("baseUrl", baseUrl).replace(Regex("/+$"), "")
    return "$normalized/api/v1/ingest_events"
}
