package org.logister.android

import java.util.Collections
import java.util.LinkedHashMap
import org.json.JSONObject

/** Generic Logister event. Use LogisterClient convenience methods for common cases. */
public class LogisterEvent private constructor(builder: Builder) {
    public val eventType: String = builder.eventType
    public val message: String? = builder.message
    public val level: String? = builder.level
    public val fingerprint: String? = builder.fingerprint
    public val occurredAt: String? = builder.occurredAt
    public val context: Map<String, Any> = Collections.unmodifiableMap(LinkedHashMap(builder.context))
    public val attributes: Map<String, Any> = Collections.unmodifiableMap(LinkedHashMap(builder.attributes))

    @Throws(Exception::class)
    internal fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("event_type", eventType)
        putIfPresent(json, "message", message)
        putIfPresent(json, "level", level)
        putIfPresent(json, "fingerprint", fingerprint)
        putIfPresent(json, "occurred_at", occurredAt)

        for ((key, value) in attributes) {
            putIfPresent(json, key, value)
        }

        if (context.isNotEmpty()) {
            json.put("context", JSONObject(context))
        }

        return json
    }

    public class Builder internal constructor(
        internal val eventType: String,
        internal val message: String?
    ) {
        internal var level: String? = null
        internal var fingerprint: String? = null
        internal var occurredAt: String? = null
        internal val context: MutableMap<String, Any> = LinkedHashMap()
        internal val attributes: MutableMap<String, Any> = LinkedHashMap()

        init {
            require(eventType.isNotBlank()) { "eventType is required" }
        }

        public fun level(level: String?): Builder = apply {
            this.level = level
        }

        public fun fingerprint(fingerprint: String?): Builder = apply {
            this.fingerprint = fingerprint
        }

        public fun occurredAt(occurredAt: String?): Builder = apply {
            this.occurredAt = occurredAt
        }

        public fun context(key: String?, value: Any?): Builder = apply {
            if (!key.isNullOrEmpty() && value != null) {
                context[key] = value
            }
        }

        public fun context(context: Map<String, *>?): Builder = apply {
            context?.forEach { (key, value) ->
                context(key, value)
            }
        }

        public fun attribute(key: String?, value: Any?): Builder = apply {
            if (!key.isNullOrEmpty() && value != null) {
                attributes[key] = value
            }
        }

        public fun build(): LogisterEvent = LogisterEvent(this)
    }

    public companion object {
        @JvmStatic
        public fun builder(eventType: String, message: String?): Builder = Builder(eventType, message)

        @Throws(Exception::class)
        internal fun putIfPresent(json: JSONObject, key: String?, value: Any?) {
            if (!key.isNullOrEmpty() && value != null) {
                json.put(key, value)
            }
        }
    }
}

