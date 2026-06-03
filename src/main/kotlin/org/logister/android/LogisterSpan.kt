package org.logister.android

import java.util.Collections
import java.util.LinkedHashMap
import java.util.UUID

/** Span payload for Logister performance traces. */
public class LogisterSpan private constructor(builder: Builder) {
    public val traceId: String = builder.traceId
    public val spanId: String = builder.spanId ?: UUID.randomUUID().toString()
    public val parentSpanId: String? = builder.parentSpanId
    public val name: String = builder.name
    public val kind: String? = builder.kind
    public val status: String? = builder.status
    public val durationMs: Double = builder.durationMs
    public val startedAt: String? = builder.startedAt
    public val endedAt: String? = builder.endedAt
    public val context: Map<String, Any> = Collections.unmodifiableMap(LinkedHashMap(builder.context))

    internal fun toEvent(): LogisterEvent {
        val builder = LogisterEvent.builder("span", name)
            .attribute("trace_id", traceId)
            .attribute("span_id", spanId)
            .attribute("name", name)
            .attribute("duration_ms", durationMs)
            .context(context)

        if (parentSpanId != null) {
            builder.attribute("parent_span_id", parentSpanId)
        }
        if (kind != null) {
            builder.attribute("kind", kind)
        }
        if (status != null) {
            builder.attribute("status", status)
        }
        if (startedAt != null) {
            builder.attribute("started_at", startedAt)
        }
        if (endedAt != null) {
            builder.attribute("ended_at", endedAt)
        }

        return builder.build()
    }

    public class Builder internal constructor(
        internal val traceId: String,
        internal val name: String,
        internal val durationMs: Double
    ) {
        internal var spanId: String? = null
        internal var parentSpanId: String? = null
        internal var kind: String? = "internal"
        internal var status: String? = null
        internal var startedAt: String? = null
        internal var endedAt: String? = null
        internal val context: MutableMap<String, Any> = LinkedHashMap()

        init {
            require(traceId.isNotBlank()) { "traceId is required" }
            require(name.isNotBlank()) { "name is required" }
        }

        public fun spanId(spanId: String?): Builder = apply {
            this.spanId = spanId
        }

        public fun parentSpanId(parentSpanId: String?): Builder = apply {
            this.parentSpanId = parentSpanId
        }

        public fun kind(kind: String?): Builder = apply {
            this.kind = kind
        }

        public fun status(status: String?): Builder = apply {
            this.status = status
        }

        public fun startedAt(startedAt: String?): Builder = apply {
            this.startedAt = startedAt
        }

        public fun endedAt(endedAt: String?): Builder = apply {
            this.endedAt = endedAt
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

        public fun build(): LogisterSpan = LogisterSpan(this)
    }

    public companion object {
        @JvmStatic
        public fun builder(traceId: String, name: String, durationMs: Double): Builder =
            Builder(traceId, name, durationMs)
    }
}

