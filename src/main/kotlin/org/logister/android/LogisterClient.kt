package org.logister.android

import android.app.Application
import android.os.Build
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

internal const val LOGISTER_ANDROID_SDK_VERSION: String = "0.6.0"

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
    private val capabilities: Set<String>,
    private val processName: String?,
    private val processPolicy: LogisterProcessPolicy,
    private val payloadPolicy: LogisterPayloadPolicy,
    private val beforeSend: LogisterBeforeSend?,
    private val clientReports: Set<String>,
    private val processEligible: Boolean,
    collectionEnabled: Boolean,
    breadcrumbCapacity: Int
) {
    public fun isTelemetryUrl(url: String): Boolean = url.substringBefore('?') == endpoint.substringBefore('?')

    @Volatile private var collectionEnabled: Boolean = collectionEnabled
    @Volatile private var lastDeliveryAt: String? = null
    @Volatile private var lastError: String? = null
    private val discardedBeforeQueue: AtomicInteger = AtomicInteger(0)
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
    ): Future<LogisterResponse> {
        if (!collectionEnabled) return completedResponse(LogisterResponse.collectionDisabled())
        val envelope = buildEnvelope(event, options ?: LogisterEventOptions.EMPTY)
            ?: return completedResponse(LogisterResponse.dropped("discarded by payload policy"))
        return executor.submit<LogisterResponse> { deliver(envelope) }
    }

    @Throws(Exception::class)
    public fun capture(event: LogisterEvent): LogisterResponse =
        capture(event, LogisterEventOptions.EMPTY)

    @Throws(Exception::class)
    public fun capture(event: LogisterEvent, options: LogisterEventOptions?): LogisterResponse {
        if (!collectionEnabled) return LogisterResponse.collectionDisabled()
        val envelope = buildEnvelope(event, options ?: LogisterEventOptions.EMPTY)
            ?: return LogisterResponse.dropped("discarded by payload policy")
        return deliver(envelope)
    }

    internal fun runAsync(block: () -> Unit) {
        executor.submit(block)
    }

    private fun completedResponse(response: LogisterResponse): Future<LogisterResponse> =
        java.util.concurrent.FutureTask<LogisterResponse> { response }.also { it.run() }

    public fun addBreadcrumb(breadcrumb: LogisterBreadcrumb) {
        if (!collectionEnabled) return
        breadcrumbBuffer.add(breadcrumb)
    }

    public fun queuedEventCount(): Int = offlineQueue?.size() ?: 0

    public fun discardedEventCount(): Int = discardedBeforeQueue.get() + (offlineQueue?.discardedCount() ?: 0)

    public fun flushQueuedEventsAsync(): Future<Int> = executor.submit<Int> { flushQueuedEvents() }

    @Throws(Exception::class)
    public fun flushQueuedEvents(): Int {
        if (!collectionEnabled) return 0
        val queue = offlineQueue ?: return 0
        return queue.flush { queuedEnvelope ->
            try {
                classifyForQueue(sendWithAuthentication(queuedEnvelope))
            } catch (error: Exception) {
                recordDeliveryError(error)
                LogisterQueueDeliveryDecision.Retry()
            }
        }
    }

    public fun clearQueuedEvents(): Boolean = offlineQueue?.clear() ?: true

    /**
     * Enables or disables collection at runtime. Disabling purges queued and in-memory telemetry by
     * default so consent withdrawal does not leave data waiting for a future launch.
     */
    @JvmOverloads
    public fun setCollectionEnabled(enabled: Boolean, purgeStoredDataOnDisable: Boolean = true) {
        collectionEnabled = enabled && processEligible
        if (!enabled && purgeStoredDataOnDisable) {
            cachedToken = null
            offlineQueue?.clear()
            breadcrumbBuffer.clear()
            androidIntegration?.clearCollectedState()
        } else if (enabled) {
            androidIntegration?.resetRuntimeState()
        }
    }

    public fun isCollectionEnabled(): Boolean = collectionEnabled

    public fun healthSnapshot(): LogisterClientHealth = LogisterClientHealth(
        collectionEnabled = collectionEnabled,
        capabilities = capabilities,
        queuedEventCount = queuedEventCount(),
        discardedEventCount = discardedEventCount(),
        lastDeliveryAt = lastDeliveryAt,
        lastError = lastError,
        processName = processName,
        processPolicy = processPolicy,
        crashHandlerInstalled = androidIntegration?.crashHandlerInstalled == true,
        clientReports = clientReports,
    )

    /** Detaches lifecycle and crash hooks. Queued envelopes remain available for a later client. */
    public fun close() {
        androidIntegration?.detach()
        androidIntegration = null
        cachedToken = null
    }

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
        if (!collectionEnabled) queue?.clear()
    }

    @Throws(Exception::class)
    private fun deliver(envelope: JSONObject): LogisterResponse {
        if (!collectionEnabled) return LogisterResponse.collectionDisabled()
        offlineQueue?.flush { queuedEnvelope ->
            try {
                classifyForQueue(sendWithAuthentication(queuedEnvelope))
            } catch (error: Exception) {
                recordDeliveryError(error)
                LogisterQueueDeliveryDecision.Retry()
            }
        }

        return try {
            val response = sendWithAuthentication(envelope)
            if (response.isAccepted) recordDeliverySuccess()
            if (response.isRetryable) {
                recordDeliveryError("HTTP ${response.statusCode}")
                if (offlineQueue?.enqueue(envelope, response.retryAfterMillis) == true) LogisterResponse.queued() else response
            } else {
                if (!response.isAccepted) {
                    discardedBeforeQueue.incrementAndGet()
                    recordDeliveryError("permanent HTTP ${response.statusCode}")
                }
                response
            }
        } catch (error: Exception) {
            recordDeliveryError(error)
            if (offlineQueue?.enqueue(envelope) == true) LogisterResponse.queued() else throw error
        }
    }

    private fun sendWithAuthentication(envelope: JSONObject): LogisterResponse {
        var token = mobileIngestToken()
        var response = transport.send(endpoint, token, envelope, connectTimeoutMs, readTimeoutMs)
        if (response.statusCode == 401) {
            cachedToken = null
            token = mobileIngestToken()
            response = transport.send(endpoint, token, envelope, connectTimeoutMs, readTimeoutMs)
        }
        return response
    }

    private fun classifyForQueue(response: LogisterResponse): LogisterQueueDeliveryDecision = when {
        response.isAccepted -> {
            recordDeliverySuccess()
            LogisterQueueDeliveryDecision.Accepted
        }
        response.isRetryable -> {
            recordDeliveryError("HTTP ${response.statusCode}")
            LogisterQueueDeliveryDecision.Retry(response.retryAfterMillis)
        }
        else -> {
            recordDeliveryError("permanent HTTP ${response.statusCode}")
            LogisterQueueDeliveryDecision.Discard
        }
    }

    private fun recordDeliverySuccess() {
        lastDeliveryAt = iso8601(System.currentTimeMillis())
        lastError = null
    }

    private fun recordDeliveryError(error: Throwable) {
        recordDeliveryError(error.javaClass.simpleName.ifBlank { "delivery_error" })
    }

    private fun recordDeliveryError(message: String) {
        lastError = message.take(160)
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

    internal fun captureUncaughtException(thread: Thread, throwable: Throwable) {
        if (!collectionEnabled) return
        val event = exceptionEvent(
            throwable = throwable,
            policy = automaticCrashExceptionDataPolicy,
            captureSource = CAPTURE_SOURCE_AUTOMATIC,
            threadName = boundedThreadName(thread.name),
        )
        val options = LogisterEventOptions.builder()
            .mechanism("unhandled_exception")
            .handled(false)
            .build()
        val envelope = buildEnvelope(event, options) ?: return
        if (offlineQueue?.tryEnqueue(envelope, CRASH_QUEUE_LOCK_TIMEOUT_MILLIS) == true) {
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

    internal fun persistHistoricalEvent(event: LogisterEvent): Boolean {
        if (!collectionEnabled) return false
        val envelope = buildEnvelope(event, LogisterEventOptions.EMPTY) ?: return false
        val queued = offlineQueue?.tryEnqueue(envelope, HISTORICAL_QUEUE_LOCK_TIMEOUT_MILLIS) == true
        if (queued) {
            executor.submit {
                try {
                    flushQueuedEvents()
                } catch (_: Exception) {
                    // The source checkpoint advances only after the envelope is durable.
                }
            }
        }
        return queued
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
        val historical = event.context[CAPTURE_SOURCE_CONTEXT_KEY] == CAPTURE_SOURCE_HISTORICAL
        if (historical) {
            listOf(
                "device_manufacturer", "device_model", "device_brand", "os_name", "os_version",
                "android_api_level", "device", "os", "session_id", "screen_name", "in_foreground",
                "installation_id_hash", "session", "installation",
            ).forEach(context::remove)
            if (event.context["build_provenance"] == "unknown") {
                listOf("app_version", "build_number", "build_type", "environment", "release").forEach(context::remove)
            }
        }

        if (!payload.has("uuid")) payload.put("uuid", UUID.randomUUID().toString())
        val capturedAt = firstPresent(options.occurredAt, event.occurredAt) ?: iso8601(System.currentTimeMillis())
        payload.put("occurred_at", capturedAt)

        LogisterEvent.putIfPresent(payload, "level", firstPresent(options.level, event.level))
        LogisterEvent.putIfPresent(payload, "fingerprint", firstPresent(options.fingerprint, event.fingerprint))
        val historicalEnvironment = event.context["environment"]?.toString()?.takeIf { it.isNotBlank() }
        LogisterEvent.putIfPresent(
            payload,
            "environment",
            if (historical) historicalEnvironment else firstPresent(options.environment, environment),
        )
        val historicalRelease = event.context["release"]?.toString()?.takeIf { it.isNotBlank() }
        LogisterEvent.putIfPresent(payload, "release", if (historical) historicalRelease else firstPresent(options.release, release))
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

        val runtime = if (historical) null else androidIntegration
        val sessionId = firstPresent(options.sessionId, runtime?.currentSessionId())
        val sessionStartedAt = if (options.sessionId != null) {
            options.sessionStartedAt
        } else {
            runtime?.currentSessionStartedAt()
        }
        val screenName = firstPresent(options.screenName, runtime?.currentScreen())
        val inForeground = options.inForeground ?: runtime?.inForeground
        val installationIdHash = runtime?.installationIdHash()
        putContext(context, "session_id", sessionId)
        putContext(context, "screen_name", screenName)
        putContext(context, "in_foreground", inForeground)
        putContext(context, "installation_id_hash", installationIdHash)

        if (!context.containsKey("app")) {
            val app = JSONObject()
            putJson(app, "package_name", packageName)
            putJson(app, "version_name", appVersion)
            putJson(app, "version_code", buildNumber)
            putJson(app, "build_type", buildType)
            putJson(app, "screen", screenName)
            putJson(app, "in_foreground", inForeground)
            if (app.length() > 0) context["app"] = app
        }

        val session = JSONObject()
        putJson(session, "id", sessionId)
        putJson(session, "started_at", sessionStartedAt)
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
            putJson(error, "fatal", handled == false)
            putJson(error, "user_perceived", if (mechanism in setOf("unhandled_exception", "anr", "native_crash")) inForeground else null)
            putJson(error, "capture_source", event.context[CAPTURE_SOURCE_CONTEXT_KEY])
            putJson(error, "data_policy", event.context[EXCEPTION_DATA_POLICY_CONTEXT_KEY])
            putJson(error, "thread_role", when (event.context[CAPTURE_SOURCE_CONTEXT_KEY]) {
                CAPTURE_SOURCE_AUTOMATIC -> "crashed"
                CAPTURE_SOURCE_MANUAL -> "reporting"
                else -> null
            })
            putJson(error, "thread_name", event.context[ERROR_THREAD_NAME_CONTEXT_KEY])
            context.remove(ERROR_THREAD_NAME_CONTEXT_KEY)
            if (error.length() > 0) context["error"] = error
        }

        val breadcrumbs = if (historical) emptyList() else breadcrumbBuffer.snapshot()
        if (breadcrumbs.isNotEmpty()) context["breadcrumbs"] = JSONArray(breadcrumbs)

        payload.put("context", JSONObject(context))
        payload.put("evidence", evidenceFor(event, context))
        return payload
    }

    private fun buildEnvelope(
        event: LogisterEvent,
        options: LogisterEventOptions,
    ): JSONObject? {
        val sanitized = sanitizeEnvelope(
            JSONObject().put("event", buildEventPayload(event, options)),
            payloadPolicy,
        ) ?: return discardBeforeQueue("payload budget exceeded")
        val processed = try {
            beforeSend?.process(JSONObject(sanitized.toString())) ?: sanitized.takeIf { beforeSend == null }
        } catch (error: Exception) {
            recordDeliveryError("beforeSend ${error.javaClass.simpleName}")
            null
        } ?: return discardBeforeQueue("beforeSend discarded event")
        val immutableEvent = sanitized.optJSONObject("event")
            ?: return discardBeforeQueue("payload policy removed event")
        val processedEvent = processed.optJSONObject("event")
            ?: return discardBeforeQueue("beforeSend removed event")
        processedEvent.put("uuid", immutableEvent.getString("uuid"))
        if (immutableEvent.has("occurred_at")) {
            processedEvent.put("occurred_at", immutableEvent.getString("occurred_at"))
        } else {
            processedEvent.remove("occurred_at")
        }
        processedEvent.put("evidence", JSONObject(immutableEvent.getJSONObject("evidence").toString()))
        if (processedEvent.optString("event_type").isBlank() || processedEvent.optString("message").isBlank()) {
            return discardBeforeQueue("beforeSend removed required event fields")
        }
        return sanitizeEnvelope(processed, payloadPolicy)
            ?: discardBeforeQueue("payload budget exceeded after beforeSend")
    }

    private fun discardBeforeQueue(reason: String): JSONObject? {
        discardedBeforeQueue.incrementAndGet()
        recordDeliveryError(reason)
        return null
    }

    private fun exceptionEvent(
        throwable: Throwable,
        policy: LogisterExceptionDataPolicy,
        captureSource: String,
        threadName: String? = null,
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
                .context(ERROR_THREAD_NAME_CONTEXT_KEY, threadName)
                .build()
        } catch (_: Exception) {
            LogisterEvent.builder("error", message)
                .level("error")
                .context("exception_class", throwable.javaClass.name)
                .context(CAPTURE_SOURCE_CONTEXT_KEY, captureSource)
                .context(EXCEPTION_DATA_POLICY_CONTEXT_KEY, policy.wireValue)
                .context(ERROR_THREAD_NAME_CONTEXT_KEY, threadName)
                .build()
        }
    }

    private fun boundedThreadName(value: String?): String? = value
        ?.filterNot(Char::isISOControl)
        ?.trim()
        ?.take(128)
        ?.takeIf { it.isNotBlank() }

    private fun baseContext(): MutableMap<String, Any> {
        val context: MutableMap<String, Any> = LinkedHashMap()
        context["platform"] = "android"
        context["telemetry_schema_version"] = 3
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
            context["device_abis"] = JSONArray(Build.SUPPORTED_ABIS)

            val device = JSONObject()
            putJson(device, "manufacturer", Build.MANUFACTURER)
            putJson(device, "model", Build.MODEL)
            putJson(device, "brand", Build.BRAND)
            device.put("abis", JSONArray(Build.SUPPORTED_ABIS))
            context["device"] = device

            val os = JSONObject()
            putJson(os, "name", "Android")
            putJson(os, "version", Build.VERSION.RELEASE)
            putJson(os, "api_level", Build.VERSION.SDK_INT)
            context["os"] = os
        }

        return context
    }

    private fun evidenceFor(
        event: LogisterEvent,
        context: Map<String, Any>,
    ): JSONObject {
        val error = context["error"] as? JSONObject
        val captureSource = error?.optString("capture_source")?.takeIf { it.isNotBlank() }
            ?: event.context[CAPTURE_SOURCE_CONTEXT_KEY]?.toString()
        val mechanism = error?.optString("mechanism")?.takeIf { it.isNotBlank() }
        val historical = captureSource == CAPTURE_SOURCE_HISTORICAL
        val diagnostic = event.context["diagnostic"] as? JSONObject
        val evidenceKind = when {
            historical -> diagnostic?.optString("evidence_kind")?.takeIf { it.isNotBlank() } ?: "termination_metadata"
            captureSource == CAPTURE_SOURCE_AUTOMATIC -> "crashed_stack"
            event.eventType == "error" -> "reported_stack"
            else -> "captured_event"
        }
        return JSONObject()
            .put("source", if (historical) "application_exit_info" else "sdk")
            .put("kind", mechanism ?: event.eventType)
            .put("capture_mode", captureSource ?: "manual_api")
            .put("evidence_kind", evidenceKind)
            .put("identity_scope", "occurrence")
            .put(
                "producer",
                JSONObject()
                    .put("sdk_name", "logister-android")
                    .put("sdk_version", LOGISTER_ANDROID_SDK_VERSION),
            )
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
        private var storageNamespace: String? = null
        private var exceptionDataPolicy: LogisterExceptionDataPolicy = LogisterExceptionDataPolicy.FULL
        private var automaticCrashExceptionDataPolicy: LogisterExceptionDataPolicy =
            LogisterExceptionDataPolicy.TYPE_AND_STACKTRACE
        private var collectionEnabled: Boolean = true
        private var processPolicy: LogisterProcessPolicy = LogisterProcessPolicy.MAIN_ONLY
        private val allowedProcesses: MutableSet<String> = linkedSetOf()
        private var payloadPolicy: LogisterPayloadPolicy = LogisterPayloadPolicy.DEFAULT
        private var beforeSend: LogisterBeforeSend? = null

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

        /** Sets the initial runtime collection state. Automatic capabilities remain configured. */
        public fun collectionEnabled(enabled: Boolean): Builder = apply {
            collectionEnabled = enabled
        }

        public fun processPolicy(processPolicy: LogisterProcessPolicy): Builder = apply {
            this.processPolicy = processPolicy
        }

        public fun allowedProcesses(vararg processNames: String): Builder = apply {
            allowedProcesses.clear()
            allowedProcesses.addAll(processNames.filter(String::isNotBlank))
        }

        public fun payloadPolicy(payloadPolicy: LogisterPayloadPolicy): Builder = apply {
            this.payloadPolicy = payloadPolicy
        }

        public fun beforeSend(beforeSend: LogisterBeforeSend?): Builder = apply {
            this.beforeSend = beforeSend
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

        /**
         * Separates durable delivery, installation, and exit state for multiple logical clients in
         * one app. Set the same stable value on every launch; do not put a token or user identifier
         * in this value.
         */
        public fun storageNamespace(storageNamespace: String): Builder = apply {
            require(storageNamespace.isNotBlank()) { "storageNamespace must not be blank" }
            require(storageNamespace.length <= 128) { "storageNamespace must be 128 characters or fewer" }
            this.storageNamespace = storageNamespace
        }

        public fun build(): LogisterClient {
            val needsApplication = sessionTrackingEnabled || installationTrackingEnabled || automaticCrashCaptureEnabled || applicationExitCaptureEnabled || offlineQueueEnabled
            require(!needsApplication || application != null) { "application is required for enabled Android integrations" }
            require(!(automaticCrashCaptureEnabled || applicationExitCaptureEnabled) || offlineQueueEnabled) {
                "automatic crash and application-exit capture require the durable offline queue"
            }

            val app = application
            val packageInfo = app?.let(::packageInfo)
            val effectivePackageName = packageName ?: app?.packageName
            val effectiveVersionName = appVersion ?: packageInfo?.versionName
            val effectiveVersionCode = buildNumber ?: packageInfo?.let(::versionCode)
            val effectiveRelease = release ?: listOfNotNull(effectivePackageName, effectiveVersionName, effectiveVersionCode)
                .takeIf { it.size == 3 }
                ?.let { "${it[0]}@${it[1]}+${it[2]}" }
            val effectiveProcessName = app?.let(::currentProcessName)
            val discardedLegacyState = app?.let(::discardUnscopedLegacyState) == true
            val processAllowed = when (processPolicy) {
                LogisterProcessPolicy.MAIN_ONLY -> app == null || effectiveProcessName == app.packageName
                LogisterProcessPolicy.CURRENT_PROCESS -> true
                LogisterProcessPolicy.ALLOWLIST -> effectiveProcessName != null && effectiveProcessName in allowedProcesses
            }
            val effectiveStorageKey = storageOwnerKey(
                endpoint = endpoint,
                packageName = effectivePackageName,
                service = service,
                explicitNamespace = listOfNotNull(
                    storageNamespace,
                    effectiveProcessName.takeIf { processPolicy != LogisterProcessPolicy.MAIN_ONLY },
                ).joinToString(":").ifBlank { null },
            )
            val effectiveDefaultContext = LinkedHashMap(defaultContext)
            putContext(effectiveDefaultContext, "process_name", effectiveProcessName)
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
            putJson(sdk, "collection_enabled", collectionEnabled && processAllowed)
            putJson(sdk, "process_policy", processPolicy.name.lowercase())
            effectiveDefaultContext["sdk"] = sdk

            val effectiveCapabilities = buildSet {
                add("manual_capture")
                if (sessionTrackingEnabled) add("session_tracking")
                if (installationTrackingEnabled) add("installation_tracking")
                if (breadcrumbCapacity > 0) add("breadcrumbs")
                if (offlineQueueEnabled) add("durable_queue")
                if (automaticCrashCaptureEnabled) add("automatic_crash_capture")
                if (applicationExitCaptureEnabled) add("application_exit_capture")
            }
            val clientReports = buildSet {
                if (discardedLegacyState) add("legacy_unscoped_state_discarded")
                if (!processAllowed) add("process_not_allowed")
            }

            val client = LogisterClient(
                tokenProvider = tokenProvider,
                endpoint = requireValue("endpoint", endpoint),
                environment = environment,
                release = effectiveRelease,
                repository = repository,
                commitSha = commitSha,
                branch = branch,
                service = service,
                packageName = effectivePackageName,
                appVersion = effectiveVersionName,
                buildNumber = effectiveVersionCode,
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
                capabilities = effectiveCapabilities,
                processName = effectiveProcessName,
                processPolicy = processPolicy,
                payloadPolicy = payloadPolicy,
                beforeSend = beforeSend,
                clientReports = clientReports,
                processEligible = processAllowed,
                collectionEnabled = collectionEnabled && processAllowed,
                breadcrumbCapacity = breadcrumbCapacity
            )
            if (app != null && processAllowed) {
                if (offlineQueueEnabled) {
                    client.attachOfflineQueue(
                        LogisterOfflineQueue(
                            context = app,
                            ownerKey = effectiveStorageKey,
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
                            applicationExitCaptureEnabled = applicationExitCaptureEnabled,
                            storageNamespace = effectiveStorageKey,
                            configuredPackageName = effectivePackageName,
                            configuredVersionName = effectiveVersionName,
                            configuredVersionCode = effectiveVersionCode,
                            configuredEnvironment = environment,
                            configuredRelease = effectiveRelease,
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
        private const val CAPTURE_SOURCE_HISTORICAL = "historical_exit"
        private const val ERROR_THREAD_NAME_CONTEXT_KEY = "error_thread_name"
        private const val CRASH_DELIVERY_TIMEOUT_SECONDS = 2L
        private const val CRASH_QUEUE_LOCK_TIMEOUT_MILLIS = 250L
        private const val HISTORICAL_QUEUE_LOCK_TIMEOUT_MILLIS = 2_000L

        @JvmStatic
        public fun builder(tokenProvider: LogisterTokenProvider, baseUrl: String): Builder =
            Builder(tokenProvider, endpointFromBaseUrl(baseUrl))

        @JvmStatic
        public fun endpointBuilder(tokenProvider: LogisterTokenProvider, endpoint: String): Builder =
            Builder(tokenProvider, endpoint)
    }
}

private fun iso8601(timestamp: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
    timeZone = java.util.TimeZone.getTimeZone("UTC")
}.format(java.util.Date(timestamp))

internal fun storageOwnerKey(
    endpoint: String,
    packageName: String?,
    service: String?,
    explicitNamespace: String?,
): String {
    val owner = listOf(endpoint, packageName.orEmpty(), service.orEmpty(), explicitNamespace.orEmpty())
        .joinToString("\u0000")
    return java.security.MessageDigest.getInstance("SHA-256")
    .digest(owner.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }
    .take(24)
}

@Suppress("DEPRECATION")
private fun packageInfo(application: Application): android.content.pm.PackageInfo? = try {
    application.packageManager.getPackageInfo(application.packageName, 0)
} catch (_: Exception) {
    null
}

@Suppress("DEPRECATION")
private fun versionCode(info: android.content.pm.PackageInfo): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toString() else info.versionCode.toString()

private fun currentProcessName(application: Application): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
    Application.getProcessName()
} else {
    try {
        java.io.File("/proc/self/cmdline").readText(Charsets.UTF_8).trimEnd('\u0000').ifBlank {
            application.packageName
        }
    } catch (_: Exception) {
        application.packageName
    }
}

private fun discardUnscopedLegacyState(application: Application): Boolean {
    val delivery = application.getSharedPreferences("logister_delivery", Application.MODE_PRIVATE)
    val identity = application.getSharedPreferences("logister_identity", Application.MODE_PRIVATE)
    val exit = application.getSharedPreferences("logister_exit_info", Application.MODE_PRIVATE)
    val hadState = delivery.contains("offline_envelopes") ||
        identity.contains("installation_pseudonym") ||
        identity.contains("installation_created_at") ||
        exit.contains("last_exit_timestamp")
    if (hadState) {
        delivery.edit().clear().commit()
        identity.edit().clear().commit()
        exit.edit().clear().commit()
    }
    return hadState
}

private val LogisterResponse.isRetryable: Boolean
    get() = statusCode in setOf(408, 425, 429) || statusCode >= 500

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
