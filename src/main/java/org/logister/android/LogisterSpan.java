package org.logister.android;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Span payload for Logister performance traces. */
public final class LogisterSpan {
    private final String traceId;
    private final String spanId;
    private final String parentSpanId;
    private final String name;
    private final String kind;
    private final String status;
    private final double durationMs;
    private final String startedAt;
    private final String endedAt;
    private final Map<String, Object> context;

    private LogisterSpan(Builder builder) {
        this.traceId = builder.traceId;
        this.spanId = builder.spanId == null ? UUID.randomUUID().toString() : builder.spanId;
        this.parentSpanId = builder.parentSpanId;
        this.name = builder.name;
        this.kind = builder.kind;
        this.status = builder.status;
        this.durationMs = builder.durationMs;
        this.startedAt = builder.startedAt;
        this.endedAt = builder.endedAt;
        this.context = Collections.unmodifiableMap(new LinkedHashMap<>(builder.context));
    }

    public static Builder builder(String traceId, String name, double durationMs) {
        return new Builder(traceId, name, durationMs);
    }

    LogisterEvent toEvent() {
        LogisterEvent.Builder builder = LogisterEvent.builder("span", name)
                .attribute("trace_id", traceId)
                .attribute("span_id", spanId)
                .attribute("name", name)
                .attribute("duration_ms", durationMs)
                .context(context);

        if (parentSpanId != null) {
            builder.attribute("parent_span_id", parentSpanId);
        }
        if (kind != null) {
            builder.attribute("kind", kind);
        }
        if (status != null) {
            builder.attribute("status", status);
        }
        if (startedAt != null) {
            builder.attribute("started_at", startedAt);
        }
        if (endedAt != null) {
            builder.attribute("ended_at", endedAt);
        }

        return builder.build();
    }

    public static final class Builder {
        private final String traceId;
        private final String name;
        private final double durationMs;
        private String spanId;
        private String parentSpanId;
        private String kind = "internal";
        private String status;
        private String startedAt;
        private String endedAt;
        private final Map<String, Object> context = new LinkedHashMap<>();

        private Builder(String traceId, String name, double durationMs) {
            if (traceId == null || traceId.trim().isEmpty()) {
                throw new IllegalArgumentException("traceId is required");
            }
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("name is required");
            }
            this.traceId = traceId;
            this.name = name;
            this.durationMs = durationMs;
        }

        public Builder spanId(String spanId) {
            this.spanId = spanId;
            return this;
        }

        public Builder parentSpanId(String parentSpanId) {
            this.parentSpanId = parentSpanId;
            return this;
        }

        public Builder kind(String kind) {
            this.kind = kind;
            return this;
        }

        public Builder status(String status) {
            this.status = status;
            return this;
        }

        public Builder startedAt(String startedAt) {
            this.startedAt = startedAt;
            return this;
        }

        public Builder endedAt(String endedAt) {
            this.endedAt = endedAt;
            return this;
        }

        public Builder context(String key, Object value) {
            if (key != null && !key.isEmpty() && value != null) {
                context.put(key, value);
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

        public LogisterSpan build() {
            return new LogisterSpan(this);
        }
    }
}
