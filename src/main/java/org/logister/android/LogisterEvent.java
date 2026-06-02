package org.logister.android;

import org.json.JSONObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Generic Logister event. Use LogisterClient convenience methods for common cases. */
public final class LogisterEvent {
    private final String eventType;
    private final String message;
    private final String level;
    private final String fingerprint;
    private final String occurredAt;
    private final Map<String, Object> context;
    private final Map<String, Object> attributes;

    private LogisterEvent(Builder builder) {
        this.eventType = builder.eventType;
        this.message = builder.message;
        this.level = builder.level;
        this.fingerprint = builder.fingerprint;
        this.occurredAt = builder.occurredAt;
        this.context = Collections.unmodifiableMap(new LinkedHashMap<>(builder.context));
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(builder.attributes));
    }

    public static Builder builder(String eventType, String message) {
        return new Builder(eventType, message);
    }

    public String getEventType() {
        return eventType;
    }

    public String getMessage() {
        return message;
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

    public Map<String, Object> getContext() {
        return context;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    JSONObject toJson() throws Exception {
        JSONObject json = new JSONObject();
        json.put("event_type", eventType);
        putIfPresent(json, "message", message);
        putIfPresent(json, "level", level);
        putIfPresent(json, "fingerprint", fingerprint);
        putIfPresent(json, "occurred_at", occurredAt);

        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            putIfPresent(json, entry.getKey(), entry.getValue());
        }

        if (!context.isEmpty()) {
            json.put("context", new JSONObject(context));
        }

        return json;
    }

    static void putIfPresent(JSONObject json, String key, Object value) throws Exception {
        if (key != null && !key.isEmpty() && value != null) {
            json.put(key, value);
        }
    }

    public static final class Builder {
        private final String eventType;
        private final String message;
        private String level;
        private String fingerprint;
        private String occurredAt;
        private final Map<String, Object> context = new LinkedHashMap<>();
        private final Map<String, Object> attributes = new LinkedHashMap<>();

        private Builder(String eventType, String message) {
            if (eventType == null || eventType.trim().isEmpty()) {
                throw new IllegalArgumentException("eventType is required");
            }
            this.eventType = eventType;
            this.message = message;
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

        public Builder attribute(String key, Object value) {
            if (key != null && !key.isEmpty() && value != null) {
                this.attributes.put(key, value);
            }
            return this;
        }

        public LogisterEvent build() {
            return new LogisterEvent(this);
        }
    }
}
