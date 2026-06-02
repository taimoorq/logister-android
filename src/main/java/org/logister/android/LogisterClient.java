package org.logister.android;

import android.os.Build;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Main Android client for sending telemetry to Logister. */
public final class LogisterClient {
    private final String apiKey;
    private final String endpoint;
    private final String environment;
    private final String release;
    private final String service;
    private final String packageName;
    private final String appVersion;
    private final String buildNumber;
    private final String buildType;
    private final Map<String, Object> defaultContext;
    private final boolean includeDeviceContext;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final LogisterTransport transport;
    private final ExecutorService executor;

    private LogisterClient(Builder builder) {
        this.apiKey = require("apiKey", builder.apiKey);
        this.endpoint = require("endpoint", builder.endpoint);
        this.environment = builder.environment;
        this.release = builder.release;
        this.service = builder.service;
        this.packageName = builder.packageName;
        this.appVersion = builder.appVersion;
        this.buildNumber = builder.buildNumber;
        this.buildType = builder.buildType;
        this.defaultContext = new LinkedHashMap<>(builder.defaultContext);
        this.includeDeviceContext = builder.includeDeviceContext;
        this.connectTimeoutMs = builder.connectTimeoutMs;
        this.readTimeoutMs = builder.readTimeoutMs;
        this.transport = builder.transport;
        this.executor = builder.executor;
    }

    public static Builder builder(String apiKey, String baseUrl) {
        return new Builder(apiKey, endpointFromBaseUrl(baseUrl));
    }

    public static Builder endpointBuilder(String apiKey, String endpoint) {
        return new Builder(apiKey, endpoint);
    }

    public Future<LogisterResponse> captureAsync(LogisterEvent event) {
        return captureAsync(event, LogisterEventOptions.EMPTY);
    }

    public Future<LogisterResponse> captureAsync(LogisterEvent event, LogisterEventOptions options) {
        return executor.submit(() -> capture(event, options));
    }

    public LogisterResponse capture(LogisterEvent event) throws Exception {
        return capture(event, LogisterEventOptions.EMPTY);
    }

    public LogisterResponse capture(LogisterEvent event, LogisterEventOptions options) throws Exception {
        JSONObject envelope = new JSONObject();
        envelope.put("event", buildEventPayload(event, options == null ? LogisterEventOptions.EMPTY : options));
        return transport.send(endpoint, apiKey, envelope, connectTimeoutMs, readTimeoutMs);
    }

    public Future<LogisterResponse> captureExceptionAsync(Throwable throwable) {
        return captureExceptionAsync(throwable, LogisterEventOptions.EMPTY);
    }

    public Future<LogisterResponse> captureExceptionAsync(Throwable throwable, LogisterEventOptions options) {
        return captureAsync(exceptionEvent(throwable), options);
    }

    public Future<LogisterResponse> captureMessageAsync(String message) {
        return captureMessageAsync(message, LogisterEventOptions.EMPTY);
    }

    public Future<LogisterResponse> captureMessageAsync(String message, LogisterEventOptions options) {
        String level = options == null || options.getLevel() == null ? "info" : options.getLevel();
        return captureAsync(LogisterEvent.builder("log", message).level(level).build(), options);
    }

    public Future<LogisterResponse> captureMetricAsync(String name, double value) {
        return captureMetricAsync(name, value, null, LogisterEventOptions.EMPTY);
    }

    public Future<LogisterResponse> captureMetricAsync(String name, double value, String unit) {
        return captureMetricAsync(name, value, unit, LogisterEventOptions.EMPTY);
    }

    public Future<LogisterResponse> captureMetricAsync(String name, double value, String unit, LogisterEventOptions options) {
        LogisterEvent.Builder event = LogisterEvent.builder("metric", name)
                .context("value", value);
        if (unit != null) {
            event.context("unit", unit);
        }
        return captureAsync(event.build(), options);
    }

    public Future<LogisterResponse> captureTransactionAsync(String name, double durationMs) {
        return captureTransactionAsync(name, durationMs, LogisterEventOptions.EMPTY);
    }

    public Future<LogisterResponse> captureTransactionAsync(String name, double durationMs, LogisterEventOptions options) {
        LogisterEvent event = LogisterEvent.builder("transaction", name)
                .attribute("transaction_name", name)
                .attribute("duration_ms", durationMs)
                .build();
        return captureAsync(event, options);
    }

    public Future<LogisterResponse> captureSpanAsync(LogisterSpan span) {
        return captureSpanAsync(span, LogisterEventOptions.EMPTY);
    }

    public Future<LogisterResponse> captureSpanAsync(LogisterSpan span, LogisterEventOptions options) {
        return captureAsync(span.toEvent(), options);
    }

    public Future<LogisterResponse> checkInAsync(String slug, String status) {
        return checkInAsync(slug, status, LogisterEventOptions.EMPTY);
    }

    public Future<LogisterResponse> checkInAsync(String slug, String status, LogisterEventOptions options) {
        LogisterEvent event = LogisterEvent.builder("check_in", slug)
                .context("check_in_slug", slug)
                .context("check_in_status", status)
                .build();
        return captureAsync(event, options);
    }

    private JSONObject buildEventPayload(LogisterEvent event, LogisterEventOptions options) throws Exception {
        JSONObject payload = event.toJson();
        Map<String, Object> context = baseContext();
        context.putAll(event.getContext());
        context.putAll(options.getContext());

        putIfPresent(payload, "level", firstPresent(options.getLevel(), event.getLevel()));
        putIfPresent(payload, "fingerprint", firstPresent(options.getFingerprint(), event.getFingerprint()));
        putIfPresent(payload, "occurred_at", firstPresent(options.getOccurredAt(), event.getOccurredAt()));
        putIfPresent(payload, "environment", firstPresent(options.getEnvironment(), environment));
        putIfPresent(payload, "release", firstPresent(options.getRelease(), release));
        putIfPresent(payload, "trace_id", options.getTraceId());
        putIfPresent(payload, "request_id", options.getRequestId());
        putIfPresent(payload, "session_id", options.getSessionId());
        putIfPresent(payload, "user_id", options.getUserId());
        putIfPresent(payload, "transaction_name", options.getTransactionName());
        putIfPresent(payload, "duration_ms", options.getDurationMs());

        putContext(context, "environment", firstPresent(options.getEnvironment(), environment));
        putContext(context, "release", firstPresent(options.getRelease(), release));
        putContext(context, "trace_id", options.getTraceId());
        putContext(context, "request_id", options.getRequestId());
        putContext(context, "session_id", options.getSessionId());
        putContext(context, "user_id", options.getUserId());
        putContext(context, "transaction_name", options.getTransactionName());
        putContext(context, "duration_ms", options.getDurationMs());

        payload.put("context", new JSONObject(context));
        return payload;
    }

    private LogisterEvent exceptionEvent(Throwable throwable) {
        String message = throwable.getClass().getName();
        if (throwable.getMessage() != null && !throwable.getMessage().isEmpty()) {
            message += ": " + throwable.getMessage();
        }

        try {
            return LogisterEvent.builder("error", message)
                    .level("error")
                    .context("exception", LogisterExceptionSerializer.serialize(throwable))
                    .build();
        } catch (Exception exception) {
            return LogisterEvent.builder("error", message)
                    .level("error")
                    .context("exception_class", throwable.getClass().getName())
                    .build();
        }
    }

    private Map<String, Object> baseContext() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("platform", "android");
        putContext(context, "service", firstPresent(service, packageName));
        putContext(context, "package_name", packageName);
        putContext(context, "app_version", appVersion);
        putContext(context, "build_number", buildNumber);
        putContext(context, "build_type", buildType);
        context.putAll(defaultContext);

        if (includeDeviceContext) {
            putContext(context, "device_manufacturer", Build.MANUFACTURER);
            putContext(context, "device_model", Build.MODEL);
            putContext(context, "device_brand", Build.BRAND);
            putContext(context, "os_name", "Android");
            putContext(context, "os_version", Build.VERSION.RELEASE);
            context.put("android_api_level", Build.VERSION.SDK_INT);
        }

        return context;
    }

    private static void putIfPresent(JSONObject json, String key, Object value) throws Exception {
        if (value != null) {
            json.put(key, value);
        }
    }

    private static void putContext(Map<String, Object> context, String key, Object value) {
        if (value != null && !context.containsKey(key)) {
            context.put(key, value);
        }
    }

    private static String firstPresent(String first, String second) {
        if (first != null && !first.isEmpty()) {
            return first;
        }
        return second == null || second.isEmpty() ? null : second;
    }

    private static String require(String name, String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static String endpointFromBaseUrl(String baseUrl) {
        String normalized = require("baseUrl", baseUrl).replaceAll("/+$", "");
        return normalized + "/api/v1/ingest_events";
    }

    public static final class Builder {
        private final String apiKey;
        private final String endpoint;
        private String environment;
        private String release;
        private String service;
        private String packageName;
        private String appVersion;
        private String buildNumber;
        private String buildType;
        private final Map<String, Object> defaultContext = new LinkedHashMap<>();
        private boolean includeDeviceContext = true;
        private int connectTimeoutMs = 10_000;
        private int readTimeoutMs = 10_000;
        private LogisterTransport transport = new HttpUrlConnectionLogisterTransport();
        private ExecutorService executor = Executors.newSingleThreadExecutor();

        private Builder(String apiKey, String endpoint) {
            this.apiKey = apiKey;
            this.endpoint = endpoint;
        }

        public Builder environment(String environment) {
            this.environment = environment;
            return this;
        }

        public Builder release(String release) {
            this.release = release;
            return this;
        }

        public Builder service(String service) {
            this.service = service;
            return this;
        }

        public Builder packageName(String packageName) {
            this.packageName = packageName;
            return this;
        }

        public Builder appVersion(String appVersion) {
            this.appVersion = appVersion;
            return this;
        }

        public Builder buildNumber(String buildNumber) {
            this.buildNumber = buildNumber;
            return this;
        }

        public Builder buildType(String buildType) {
            this.buildType = buildType;
            return this;
        }

        public Builder defaultContext(String key, Object value) {
            if (key != null && !key.isEmpty() && value != null) {
                this.defaultContext.put(key, value);
            }
            return this;
        }

        public Builder defaultContext(Map<String, ?> context) {
            if (context != null) {
                for (Map.Entry<String, ?> entry : context.entrySet()) {
                    defaultContext(entry.getKey(), entry.getValue());
                }
            }
            return this;
        }

        public Builder includeDeviceContext(boolean includeDeviceContext) {
            this.includeDeviceContext = includeDeviceContext;
            return this;
        }

        public Builder timeoutMs(int connectTimeoutMs, int readTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
            this.readTimeoutMs = readTimeoutMs;
            return this;
        }

        public Builder transport(LogisterTransport transport) {
            if (transport != null) {
                this.transport = transport;
            }
            return this;
        }

        public Builder executor(ExecutorService executor) {
            if (executor != null) {
                this.executor = executor;
            }
            return this;
        }

        public LogisterClient build() {
            return new LogisterClient(this);
        }
    }
}
