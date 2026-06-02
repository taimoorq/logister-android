package org.logister.android;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Optional fields that Logister understands on event payloads. */
public final class LogisterEventOptions {
    static final LogisterEventOptions EMPTY = new Builder().build();

    private final String level;
    private final String fingerprint;
    private final String occurredAt;
    private final String environment;
    private final String release;
    private final String traceId;
    private final String requestId;
    private final String sessionId;
    private final String userId;
    private final String transactionName;
    private final Double durationMs;
    private final Map<String, Object> context;

    private LogisterEventOptions(Builder builder) {
        this.level = builder.level;
        this.fingerprint = builder.fingerprint;
        this.occurredAt = builder.occurredAt;
        this.environment = builder.environment;
        this.release = builder.release;
        this.traceId = builder.traceId;
        this.requestId = builder.requestId;
        this.sessionId = builder.sessionId;
        this.userId = builder.userId;
        this.transactionName = builder.transactionName;
        this.durationMs = builder.durationMs;
        this.context = Collections.unmodifiableMap(new LinkedHashMap<>(builder.context));
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getLevel() {
        return level;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getOccurredAt() {
        return occurredAt;
    }

    public String getEnvironment() {
        return environment;
    }

    public String getRelease() {
        return release;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public String getTransactionName() {
        return transactionName;
    }

    public Double getDurationMs() {
        return durationMs;
    }

    public Map<String, Object> getContext() {
        return context;
    }

    public static final class Builder {
        private String level;
        private String fingerprint;
        private String occurredAt;
        private String environment;
        private String release;
        private String traceId;
        private String requestId;
        private String sessionId;
        private String userId;
        private String transactionName;
        private Double durationMs;
        private final Map<String, Object> context = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder level(String level) {
            this.level = level;
            return this;
        }

        public Builder fingerprint(String fingerprint) {
            this.fingerprint = fingerprint;
            return this;
        }

        public Builder occurredAt(String occurredAt) {
            this.occurredAt = occurredAt;
            return this;
        }

        public Builder environment(String environment) {
            this.environment = environment;
            return this;
        }

        public Builder release(String release) {
            this.release = release;
            return this;
        }

        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        public Builder requestId(String requestId) {
            this.requestId = requestId;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder transactionName(String transactionName) {
            this.transactionName = transactionName;
            return this;
        }

        public Builder durationMs(double durationMs) {
            this.durationMs = durationMs;
            return this;
        }

        public Builder context(String key, Object value) {
            if (key != null && !key.isEmpty() && value != null) {
                this.context.put(key, value);
            }
            return this;
        }

        public Builder context(Map<String, ?> context) {
            if (context != null) {
                for (Map.Entry<String, ?> entry : context.entrySet()) {
                    context(entry.getKey(), entry.getValue());
                }
            }
            return this;
        }

        public LogisterEventOptions build() {
            return new LogisterEventOptions(this);
        }
    }
}
